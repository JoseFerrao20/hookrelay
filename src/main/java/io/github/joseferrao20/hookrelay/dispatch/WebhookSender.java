package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.joseferrao20.hookrelay.security.BlockedDestinationException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookSender {

  private static final int MAX_ERROR_LENGTH = 2000;

  private final RestClient client;
  private final Clock clock;
  private final String userAgent;

  public WebhookSender(RestClient webhookRestClient, Clock clock) {
    this.client = webhookRestClient;
    this.clock = clock;
    String version = WebhookSender.class.getPackage().getImplementationVersion();
    this.userAgent = "hookrelay/" + (version != null ? version : "0.0.1-SNAPSHOT");
  }

  /**
   * Sends one webhook. {@code signingSecrets} are the plaintext secrets that sign this delivery,
   * the current one first; the signature covers the exact body bytes that go on the wire.
   */
  public SendResult send(
      UUID deliveryId,
      UUID eventId,
      String eventType,
      int attempt,
      String url,
      JsonNode payload,
      List<String> signingSecrets) {
    long start = System.nanoTime();
    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
    long timestamp = clock.instant().getEpochSecond();
    try {
      HttpOutcome outcome =
          client
              .post()
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .header("User-Agent", userAgent)
              .header("Hookrelay-Delivery-Id", deliveryId.toString())
              .header("Hookrelay-Event-Id", eventId.toString())
              .header("Hookrelay-Event-Type", eventType)
              .header("Hookrelay-Attempt", Integer.toString(attempt))
              .header("Hookrelay-Timestamp", Long.toString(timestamp))
              .header(
                  "Hookrelay-Signature",
                  WebhookSignature.header(signingSecrets, deliveryId.toString(), timestamp, body))
              .body(body)
              .exchange(
                  (request, response) -> {
                    try (var in = response.getBody()) {
                      in.transferTo(OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                      // the body is discarded anyway; the status line is what matters
                    }
                    int code = response.getStatusCode().value();
                    Duration retryAfter =
                        code == 429 || code == 503
                            ? parseRetryAfter(
                                response.getHeaders().getFirst("Retry-After"), clock.instant())
                            : null;
                    return new HttpOutcome(code, retryAfter);
                  });
      long ms = elapsedMs(start);
      int status = outcome.status();
      boolean ok = status >= 200 && status < 300;
      return new SendResult(status, ok ? null : "HTTP " + status, ms, outcome.retryAfter());
    } catch (Exception e) {
      BlockedDestinationException blocked = findBlocked(e);
      if (blocked != null) {
        return SendResult.blocked(blocked.getMessage(), elapsedMs(start));
      }
      String message = describe(e);
      if (message.length() > MAX_ERROR_LENGTH) {
        message = message.substring(0, MAX_ERROR_LENGTH);
      }
      return new SendResult(null, message, elapsedMs(start));
    }
  }

  private static BlockedDestinationException findBlocked(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof BlockedDestinationException b) {
        return b;
      }
    }
    return null;
  }

  private record HttpOutcome(int status, Duration retryAfter) {}

  /**
   * Parses a {@code Retry-After} value (delta-seconds or HTTP-date). Returns null when missing or
   * invalid; a date in the past yields zero.
   */
  static Duration parseRetryAfter(String value, Instant now) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    if (v.isEmpty()) {
      return null;
    }
    if (v.chars().allMatch(c -> c >= '0' && c <= '9')) {
      try {
        return Duration.ofSeconds(Long.parseLong(v));
      } catch (NumberFormatException e) {
        return null;
      }
    }
    try {
      Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Builds "{@code <ExceptionSimpleName>: <message>}" from the first cause below Spring's wrapper,
   * taking the message from the first cause in the chain that has one. When no cause has a message
   * (e.g. an unresolvable host), the nested exception names are used instead of "null".
   */
  static String describe(Exception e) {
    Throwable top = e.getCause() != null ? e.getCause() : e;
    StringBuilder chain = new StringBuilder();
    for (Throwable t = top; t != null; t = t.getCause()) {
      if (t.getMessage() != null && !t.getMessage().isBlank()) {
        return top.getClass().getSimpleName() + ": " + t.getMessage();
      }
      if (t != top) {
        chain.append(chain.isEmpty() ? "" : " > ").append(t.getClass().getSimpleName());
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return chain.isEmpty()
        ? top.getClass().getSimpleName()
        : top.getClass().getSimpleName() + ": " + chain;
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
