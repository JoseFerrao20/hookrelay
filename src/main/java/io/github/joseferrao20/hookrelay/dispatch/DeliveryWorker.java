package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
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
  private final Clock clock;
  private final TransactionTemplate tx;

  public DeliveryWorker(
      DeliveryRepository deliveries,
      DeliveryAttemptRepository attempts,
      EventRepository events,
      EndpointRepository endpoints,
      WebhookSender sender,
      DispatchProperties props,
      Clock clock,
      TransactionTemplate tx) {
    this.deliveries = deliveries;
    this.attempts = attempts;
    this.events = events;
    this.endpoints = endpoints;
    this.sender = sender;
    this.props = props;
    this.clock = clock;
    this.tx = tx;
  }

  /** Everything needed to send, loaded in the claim transaction. */
  private record Claim(
      int attemptNumber,
      long version,
      UUID eventId,
      String eventType,
      JsonNode payload,
      String url,
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
              claim.payload());
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
    return new Claim(
        attemptNumber,
        version,
        event.getId(),
        event.getEventType(),
        event.getPayload(),
        endpoint.getUrl(),
        blocked);
  }

  private void record(UUID deliveryId, Claim claim, Instant startedAt, SendResult result) {
    DeliveryStatus status = result.succeeded() ? DeliveryStatus.SUCCEEDED : DeliveryStatus.FAILED;
    tx.executeWithoutResult(
        s -> {
          attempts.save(
              new DeliveryAttempt(
                  deliveryId,
                  claim.attemptNumber(),
                  status,
                  startedAt,
                  result.durationMs(),
                  result.responseStatus(),
                  result.error()));
          int updated =
              deliveries.recordResult(
                  deliveryId,
                  claim.version(),
                  status.name(),
                  startedAt,
                  result.responseStatus(),
                  result.error(),
                  clock.instant());
          if (updated == 0) {
            log.warn(
                "Lease for delivery {} expired before the result was recorded; attempt kept",
                deliveryId);
          }
        });
  }
}
