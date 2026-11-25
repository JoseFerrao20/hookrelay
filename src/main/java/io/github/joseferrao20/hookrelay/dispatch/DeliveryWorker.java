package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.joseferrao20.hookrelay.common.SecretCipher;
import io.github.joseferrao20.hookrelay.delivery.Delivery;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttempt;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttemptRepository;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.delivery.DeliveryStatus;
import io.github.joseferrao20.hookrelay.endpoint.Endpoint;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import io.github.joseferrao20.hookrelay.event.Event;
import io.github.joseferrao20.hookrelay.event.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumes dispatch messages: claim by version, send over HTTP, record the outcome. */
@Component
public class DeliveryWorker {

  private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

  private final DeliveryRepository deliveries;
  private final DeliveryAttemptRepository attempts;
  private final EventRepository events;
  private final EndpointRepository endpoints;
  private final WebhookSender sender;
  private final DispatchProperties props;
  private final RetryPolicy retryPolicy;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final SecretCipher cipher;

  public DeliveryWorker(
      DeliveryRepository deliveries,
      DeliveryAttemptRepository attempts,
      EventRepository events,
      EndpointRepository endpoints,
      WebhookSender sender,
      DispatchProperties props,
      RetryPolicy retryPolicy,
      Clock clock,
      TransactionTemplate tx,
      SecretCipher cipher) {
    this.deliveries = deliveries;
    this.attempts = attempts;
    this.events = events;
    this.endpoints = endpoints;
    this.sender = sender;
    this.props = props;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
    this.tx = tx;
    this.cipher = cipher;
  }

  /** Everything needed to send, loaded in the claim transaction. */
  private record Claim(
      int attemptNumber,
      long version,
      UUID eventId,
      String eventType,
      JsonNode payload,
      String url,
      List<String> signingSecrets,
      String blockedReason) {}

  @RabbitListener(
      queues = DispatchConfig.QUEUE,
      concurrency = "${hookrelay.dispatch.consumers:4}",
      autoStartup = "${hookrelay.dispatch.enabled:true}")
  public void onMessage(DeliveryMessage message) {
    Claim claim = tx.execute(status -> claim(message));
    if (claim == null) {
      log.debug(
          "Stale or duplicate message for delivery {} version {}",
          message.deliveryId(),
          message.version());
      return;
    }

    Instant startedAt = clock.instant();
    SendResult result;
    if (claim.blockedReason() != null) {
      result = new SendResult(null, claim.blockedReason(), 0);
    } else {
      result =
          sender.send(
              message.deliveryId(),
              claim.eventId(),
              claim.eventType(),
              claim.attemptNumber(),
              claim.url(),
              claim.payload(),
              claim.signingSecrets());
    }
    record(message.deliveryId(), claim, startedAt, result);
  }

  private Claim claim(DeliveryMessage message) {
    Instant now = clock.instant();
    List<Object[]> rows =
        deliveries.claim(message.deliveryId(), message.version(), now, now.plus(props.lease()));
    if (rows.isEmpty()) {
      return null;
    }
    int attemptNumber = ((Number) rows.get(0)[0]).intValue();
    long version = ((Number) rows.get(0)[1]).longValue();
    Delivery delivery = deliveries.findById(message.deliveryId()).orElseThrow();
    Event event = events.findById(delivery.getEventId()).orElseThrow();
    Endpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElseThrow();
    String blocked = null;
    if (endpoint.getDeletedAt() != null) {
      blocked = "Endpoint deleted";
    } else if (!endpoint.isEnabled()) {
      blocked = "Endpoint disabled";
    }
    List<String> secrets = blocked == null ? signingSecrets(endpoint, now) : List.of();
    return new Claim(
        attemptNumber,
        version,
        event.getId(),
        event.getEventType(),
        event.getPayload(),
        endpoint.getUrl(),
        secrets,
        blocked);
  }

  /** Current secret first, then the previous one while its grace period lasts (plaintext). */
  private List<String> signingSecrets(Endpoint endpoint, Instant now) {
    List<String> secrets = new ArrayList<>(2);
    secrets.add(cipher.decrypt(endpoint.getId(), endpoint.getSecret()));
    if (endpoint.getPreviousSecret() != null
        && endpoint.getPreviousSecretExpiresAt() != null
        && endpoint.getPreviousSecretExpiresAt().isAfter(now)) {
      secrets.add(cipher.decrypt(endpoint.getId(), endpoint.getPreviousSecret()));
    }
    return secrets;
  }

  private void record(UUID deliveryId, Claim claim, Instant startedAt, SendResult result) {
    DeliveryStatus outcome = result.succeeded() ? DeliveryStatus.SUCCEEDED : DeliveryStatus.FAILED;
    String blockedReason =
        claim.blockedReason() != null ? claim.blockedReason() : result.blockedReason();
    RetryDecision decision =
        blockedReason != null
            ? RetryPolicy.dead(blockedReason)
            : retryPolicy.decide(claim.attemptNumber(), result, clock.instant());
    tx.executeWithoutResult(
        s -> {
          attempts.save(
              new DeliveryAttempt(
                  deliveryId,
                  claim.attemptNumber(),
                  outcome,
                  startedAt,
                  result.durationMs(),
                  result.responseStatus(),
                  result.error()));
          int updated =
              deliveries.recordResult(
                  deliveryId,
                  claim.version(),
                  decision.status().name(),
                  startedAt,
                  result.responseStatus(),
                  decision.lastError(),
                  decision.nextAttemptAt(),
                  clock.instant());
          if (updated == 0) {
            log.warn(
                "Lease for delivery {} expired before the result was recorded; attempt kept",
                deliveryId);
          }
        });
  }
}
