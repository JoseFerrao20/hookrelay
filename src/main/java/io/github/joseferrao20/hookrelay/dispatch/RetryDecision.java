package io.github.joseferrao20.hookrelay.dispatch;

import io.github.joseferrao20.hookrelay.delivery.DeliveryStatus;
import java.time.Instant;

/** What to store on the delivery after an attempt. {@code nextAttemptAt} is set only for FAILED. */
public record RetryDecision(DeliveryStatus status, Instant nextAttemptAt, String lastError) {}
