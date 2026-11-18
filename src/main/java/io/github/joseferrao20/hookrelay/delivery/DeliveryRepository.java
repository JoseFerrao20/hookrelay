package io.github.joseferrao20.hookrelay.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository
    extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {

  List<Delivery> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);

  /**
   * Locks up to {@code limit} due deliveries (SKIP LOCKED), leases them and bumps their version, in
   * one statement. Returns rows of {@code [id, newVersion]}.
   */
  @Query(
      value =
          """
          UPDATE deliveries SET locked_until = :lockedUntil, version = version + 1,
                 updated_at = :now
           WHERE id IN (
                 SELECT id FROM deliveries
                  WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now
                    AND (locked_until IS NULL OR locked_until < :now)
                  ORDER BY next_attempt_at
                  LIMIT :limit
                    FOR UPDATE SKIP LOCKED)
          RETURNING id, version
          """,
      nativeQuery = true)
  List<Object[]> leaseDue(
      @Param("now") Instant now,
      @Param("lockedUntil") Instant lockedUntil,
      @Param("limit") int limit);

  /** Claims a delivery for one attempt. Returns {@code [attemptCount, newVersion]} or empty. */
  @Query(
      value =
          """
          UPDATE deliveries SET locked_until = :lockedUntil, attempt_count = attempt_count + 1,
                 version = version + 1, updated_at = :now
           WHERE id = :id AND version = :version AND status IN ('PENDING', 'FAILED')
          RETURNING attempt_count, version
          """,
      nativeQuery = true)
  List<Object[]> claim(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("now") Instant now,
      @Param("lockedUntil") Instant lockedUntil);

  /** Gives back a lease taken by {@link #leaseDue} when the message could not be published. */
  @Modifying
  @Query(
      value = "UPDATE deliveries SET locked_until = NULL WHERE id = :id AND version = :version",
      nativeQuery = true)
  int releaseLease(@Param("id") UUID id, @Param("version") long version);

  /** Records the outcome of a claimed attempt; 0 rows when the claim was superseded. */
  @Modifying
  @Query(
      value =
          """
          UPDATE deliveries SET status = :status, last_attempt_at = :startedAt,
                 last_response_status = CAST(:code AS integer), last_error = CAST(:error AS varchar), locked_until = NULL,
                 next_attempt_at = CAST(:nextAttemptAt AS timestamptz), version = version + 1,
                 updated_at = :now
           WHERE id = :id AND version = :claimedVersion
          """,
      nativeQuery = true)
  int recordResult(
      @Param("id") UUID id,
      @Param("claimedVersion") long claimedVersion,
      @Param("status") String status,
      @Param("startedAt") Instant startedAt,
      @Param("code") Integer code,
      @Param("error") String error,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("now") Instant now);
}
