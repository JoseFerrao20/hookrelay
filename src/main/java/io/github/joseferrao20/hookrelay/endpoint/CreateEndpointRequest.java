package io.github.joseferrao20.hookrelay.endpoint;

import io.github.joseferrao20.hookrelay.common.EventTypes;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.hibernate.validator.constraints.URL;

public record CreateEndpointRequest(
    @NotNull @Size(max = 2048) @URL(regexp = "^https?://.+") String url,
    @Size(max = 500) String description,
    @NotEmpty @Size(max = 50)
        List<@NotNull @Pattern(regexp = EventTypes.PATTERN_OR_WILDCARD) String> eventTypes,
    Boolean enabled) {}
