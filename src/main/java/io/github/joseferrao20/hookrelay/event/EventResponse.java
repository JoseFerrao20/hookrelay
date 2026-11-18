package io.github.joseferrao20.hookrelay.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventResponse(
    UUID id,
    String eventType,
    JsonNode payload,
    String idempotencyKey,
    Instant createdAt,
    List<EventDeliveryResponse> deliveries) {}
