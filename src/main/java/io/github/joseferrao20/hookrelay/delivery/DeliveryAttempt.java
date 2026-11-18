package io.github.joseferrao20.hookrelay.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

  @Id private UUID id;

  @Column(name = "delivery_id", nullable = false)
  private UUID deliveryId;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Column(nullable = false, length = 20)
  private String outcome;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  @Column(name = "response_status")
  private Integer responseStatus;

  @Column(length = 2000)
  private String error;

  protected DeliveryAttempt() {}

  public DeliveryAttempt(
      UUID deliveryId,
      int attemptNumber,
      DeliveryStatus outcome,
      Instant startedAt,
      long durationMs,
      Integer responseStatus,
      String error) {
    this.id = UUID.randomUUID();
    this.deliveryId = deliveryId;
    this.attemptNumber = attemptNumber;
    this.outcome = outcome.name();
    this.startedAt = startedAt;
    this.durationMs = durationMs;
    this.responseStatus = responseStatus;
    this.error = error;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDeliveryId() {
    return deliveryId;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public String getOutcome() {
    return outcome;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public Integer getResponseStatus() {
    return responseStatus;
  }

  public String getError() {
    return error;
  }
}
