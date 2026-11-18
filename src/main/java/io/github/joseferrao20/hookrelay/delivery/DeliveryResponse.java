package io.github.joseferrao20.hookrelay.delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID id,
    UUID eventId,
    UUID endpointId,
    DeliveryStatus status,
    int attemptCount,
    Instant nextAttemptAt,
    Instant lastAttemptAt,
    Integer lastResponseStatus,
    String lastError,
    Instant createdAt,
    Instant updatedAt) {

  static DeliveryResponse from(Delivery d) {
    return new DeliveryResponse(
        d.getId(),
        d.getEventId(),
        d.getEndpointId(),
        d.getStatus(),
        d.getAttemptCount(),
        d.getNextAttemptAt(),
        d.getLastAttemptAt(),
        d.getLastResponseStatus(),
        d.getLastError(),
        d.getCreatedAt(),
        d.getUpdatedAt());
  }
}
