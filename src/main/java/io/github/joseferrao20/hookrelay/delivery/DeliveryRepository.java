package io.github.joseferrao20.hookrelay.delivery;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DeliveryRepository
    extends JpaRepository<Delivery, UUID>, JpaSpecificationExecutor<Delivery> {

  List<Delivery> findByEventIdOrderByCreatedAtAscIdAsc(UUID eventId);
}
