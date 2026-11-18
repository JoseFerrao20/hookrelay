package io.github.joseferrao20.hookrelay.endpoint;

import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import io.github.joseferrao20.hookrelay.common.Pagination;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EndpointService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final EndpointRepository repository;
  private final Clock clock;

  public EndpointService(EndpointRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional
  public EndpointCreatedResponse create(CreateEndpointRequest request) {
    Endpoint endpoint =
        new Endpoint(
            request.url(),
            request.description(),
            generateSecret(),
            request.enabled() == null || request.enabled(),
            new HashSet<>(request.eventTypes()),
            clock.instant());
    return EndpointCreatedResponse.from(repository.save(endpoint));
  }

  @Transactional(readOnly = true)
  public PageResponse<EndpointResponse> list(int page, int size) {
    return PageResponse.of(
        repository.findByDeletedAtIsNull(Pagination.newestFirst(page, size)),
        EndpointResponse::from);
  }

  @Transactional(readOnly = true)
  public EndpointResponse get(UUID id) {
    return EndpointResponse.from(find(id));
  }

  @Transactional
  public EndpointResponse update(UUID id, UpdateEndpointRequest request) {
    Endpoint endpoint = find(id);
    if (request.url() != null) {
      endpoint.setUrl(request.url());
    }
    if (request.description() != null) {
      endpoint.setDescription(request.description());
    }
    if (request.eventTypes() != null) {
      endpoint.replaceEventTypes(new HashSet<>(request.eventTypes()));
    }
    if (request.enabled() != null) {
      endpoint.setEnabled(request.enabled());
    }
    endpoint.touch(clock.instant());
    return EndpointResponse.from(endpoint);
  }

  @Transactional
  public void delete(UUID id) {
    find(id).markDeleted(clock.instant());
  }

  private Endpoint find(UUID id) {
    return repository
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new NotFoundException("Endpoint " + id + " not found"));
  }

  private static String generateSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
