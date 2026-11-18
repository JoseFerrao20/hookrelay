package io.github.joseferrao20.hookrelay.event;

import java.time.Instant;
import java.util.UUID;

public record EventSummaryResponse(
    UUID id, String eventType, String idempotencyKey, Instant createdAt) {

  static EventSummaryResponse from(Event e) {
    return new EventSummaryResponse(
        e.getId(), e.getEventType(), e.getIdempotencyKey(), e.getCreatedAt());
  }
}
