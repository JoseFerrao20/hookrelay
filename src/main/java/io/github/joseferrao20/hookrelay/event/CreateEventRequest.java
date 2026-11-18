package io.github.joseferrao20.hookrelay.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.joseferrao20.hookrelay.common.EventTypes;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateEventRequest(
    @NotNull @Pattern(regexp = EventTypes.PATTERN) String eventType, @NotNull JsonNode payload) {

  @JsonIgnore
  @AssertTrue(message = "must be a JSON object")
  public boolean isPayloadObject() {
    return payload == null || payload.isObject();
  }
}
