package io.github.joseferrao20.hookrelay.dispatch;

import java.util.UUID;

/** Work item published by the poller: which delivery, at which version it was leased. */
public record DeliveryMessage(UUID deliveryId, long version) {}
