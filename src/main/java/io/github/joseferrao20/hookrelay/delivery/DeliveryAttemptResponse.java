package io.github.joseferrao20.hookrelay.delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
    UUID id,
    int attemptNumber,
    String outcome,
    Instant startedAt,
    long durationMs,
    Integer responseStatus,
    String error) {

  static DeliveryAttemptResponse from(DeliveryAttempt a) {
    return new DeliveryAttemptResponse(
        a.getId(),
        a.getAttemptNumber(),
        a.getOutcome(),
        a.getStartedAt(),
        a.getDurationMs(),
        a.getResponseStatus(),
        a.getError());
  }
}
