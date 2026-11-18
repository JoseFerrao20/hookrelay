package io.github.joseferrao20.hookrelay.event;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.joseferrao20.hookrelay.common.EventTypes;
import io.github.joseferrao20.hookrelay.common.JsonObject;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateEventRequest(
    @NotNull @Pattern(regexp = EventTypes.PATTERN) String eventType,
    @NotNull @JsonObject JsonNode payload) {}
