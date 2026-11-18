package io.github.joseferrao20.hookrelay.endpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EndpointRepository extends JpaRepository<Endpoint, UUID> {

  Optional<Endpoint> findByIdAndDeletedAtIsNull(UUID id);

  Page<Endpoint> findByDeletedAtIsNull(Pageable pageable);

  @Query(
      """
      select distinct e from Endpoint e join e.eventTypes t
      where e.enabled = true and e.deletedAt is null and (t = :eventType or t = '*')
      """)
  List<Endpoint> findSubscribers(@Param("eventType") String eventType);
}
