package io.github.joseferrao20.hookrelay.event;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, UUID> {

  Optional<Event> findByIdempotencyKey(String idempotencyKey);

  Page<Event> findByEventType(String eventType, Pageable pageable);
}
