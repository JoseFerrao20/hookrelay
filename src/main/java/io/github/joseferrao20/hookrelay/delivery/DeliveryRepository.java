package io.github.joseferrao20.hookrelay.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

  List<Delivery> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);

  @Query(
      """
      select d from Delivery d
      where (:eventId is null or d.eventId = :eventId)
        and (:endpointId is null or d.endpointId = :endpointId)
        and (:status is null or d.status = :status)
      """)
  Page<Delivery> search(
      @Param("eventId") UUID eventId,
      @Param("endpointId") UUID endpointId,
      @Param("status") DeliveryStatus status,
      Pageable pageable);
}
