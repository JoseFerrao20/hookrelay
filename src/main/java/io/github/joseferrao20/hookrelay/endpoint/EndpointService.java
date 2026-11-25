package io.github.joseferrao20.hookrelay.endpoint;

import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import io.github.joseferrao20.hookrelay.common.Pagination;
import io.github.joseferrao20.hookrelay.common.SecretCipher;
import io.github.joseferrao20.hookrelay.security.SsrfGuard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
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
  private final SecretCipher cipher;
  private final SsrfGuard ssrfGuard;

  public EndpointService(
      EndpointRepository repository, Clock clock, SecretCipher cipher, SsrfGuard ssrfGuard) {
    this.repository = repository;
    this.clock = clock;
    this.cipher = cipher;
    this.ssrfGuard = ssrfGuard;
  }

  @Transactional
  public EndpointCreatedResponse create(CreateEndpointRequest request) {
    ssrfGuard.validateForSave(request.url());
    UUID id = UUID.randomUUID();
    String secret = generateSecret();
    Endpoint endpoint =
        new Endpoint(
            id,
            request.url(),
            request.description(),
            cipher.encrypt(id, secret),
            request.enabled() == null || request.enabled(),
            new HashSet<>(request.eventTypes()),
            clock.instant());
    return EndpointCreatedResponse.from(repository.save(endpoint), secret);
  }

  /**
   * Replaces the secret. The old one keeps signing next to the new one until {@code graceSeconds}
   * have passed; with zero it stops signing immediately.
   */
  @Transactional
  public RotateSecretResponse rotateSecret(UUID id, int graceSeconds) {
    Endpoint endpoint = find(id);
    Instant now = clock.instant();
    String newSecret = generateSecret();
    String previous = null;
    Instant previousExpiresAt = null;
    if (graceSeconds > 0) {
      previous = endpoint.getSecret(); // already ciphertext bound to this endpoint id
      previousExpiresAt = now.plusSeconds(graceSeconds);
    }
    endpoint.rotateSecret(cipher.encrypt(id, newSecret), previous, previousExpiresAt);
    endpoint.touch(now);
    return new RotateSecretResponse(newSecret, previousExpiresAt);
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
      ssrfGuard.validateForSave(request.url());
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
