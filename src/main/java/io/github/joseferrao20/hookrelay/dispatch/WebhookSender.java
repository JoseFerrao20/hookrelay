package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
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

  public SendResult send(
      UUID deliveryId, UUID eventId, String eventType, int attempt, String url, JsonNode payload) {
    long start = System.nanoTime();
    try {
      int status =
          client
              .post()
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .header("User-Agent", userAgent)
              .header("Hookrelay-Delivery-Id", deliveryId.toString())
              .header("Hookrelay-Event-Id", eventId.toString())
              .header("Hookrelay-Event-Type", eventType)
              .header("Hookrelay-Attempt", Integer.toString(attempt))
              .header("Hookrelay-Timestamp", Long.toString(clock.instant().getEpochSecond()))
              .body(payload.toString())
              .exchange(
                  (request, response) -> {
                    try (var body = response.getBody()) {
                      body.transferTo(OutputStream.nullOutputStream());
                    } catch (IOException ignored) {
                      // the body is discarded anyway; the status line is what matters
                    }
                    return response.getStatusCode().value();
                  });
      long ms = elapsedMs(start);
      boolean ok = status >= 200 && status < 300;
      return new SendResult(status, ok ? null : "HTTP " + status, ms);
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
      if (message.length() > MAX_ERROR_LENGTH) {
        message = message.substring(0, MAX_ERROR_LENGTH);
      }
      return new SendResult(null, message, elapsedMs(start));
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
