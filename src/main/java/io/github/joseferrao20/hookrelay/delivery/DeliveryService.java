package io.github.joseferrao20.hookrelay.delivery;

import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import io.github.joseferrao20.hookrelay.common.Pagination;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {

  private final DeliveryRepository repository;

  public DeliveryService(DeliveryRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public PageResponse<DeliveryResponse> list(
      UUID eventId, UUID endpointId, DeliveryStatus status, int page, int size) {
    Specification<Delivery> spec = (root, query, cb) -> cb.conjunction();
    if (eventId != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("eventId"), eventId));
    }
    if (endpointId != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("endpointId"), endpointId));
    }
    if (status != null) {
      spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    }
    return PageResponse.of(
        repository.findAll(spec, Pagination.newestFirst(page, size)), DeliveryResponse::from);
  }

  @Transactional(readOnly = true)
  public DeliveryResponse get(UUID id) {
    return repository
        .findById(id)
        .map(DeliveryResponse::from)
        .orElseThrow(() -> new NotFoundException("Delivery " + id + " not found"));
  }
}
