package io.github.joseferrao20.hookrelay.event;

import io.github.joseferrao20.hookrelay.delivery.DeliveryStatus;
import java.util.UUID;

public record EventDeliveryResponse(UUID id, UUID endpointId, DeliveryStatus status) {}
