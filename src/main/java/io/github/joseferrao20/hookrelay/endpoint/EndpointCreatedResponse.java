package io.github.joseferrao20.hookrelay.endpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EndpointCreatedResponse(
    UUID id,
    String url,
    String description,
    List<String> eventTypes,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt,
    String secret) {

  static EndpointCreatedResponse from(Endpoint e, String plaintextSecret) {
    return new EndpointCreatedResponse(
        e.getId(),
        e.getUrl(),
        e.getDescription(),
        e.getEventTypes().stream().sorted().toList(),
        e.isEnabled(),
        e.getCreatedAt(),
        e.getUpdatedAt(),
        plaintextSecret);
  }
}
