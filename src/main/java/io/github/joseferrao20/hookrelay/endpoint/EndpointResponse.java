package io.github.joseferrao20.hookrelay.endpoint;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EndpointResponse(
    UUID id,
    String url,
    String description,
    List<String> eventTypes,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt) {

  static EndpointResponse from(Endpoint e) {
    return new EndpointResponse(
        e.getId(),
        e.getUrl(),
        e.getDescription(),
        e.getEventTypes().stream().sorted().toList(),
        e.isEnabled(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
