package io.github.joseferrao20.hookrelay.dispatch;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("hookrelay.dispatch")
public record DispatchProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("1s") @NotNull Duration pollInterval,
    @DefaultValue("100") @Min(1) int batchSize,
    @DefaultValue("60s") @NotNull Duration lease,
    @DefaultValue("5s") @NotNull Duration connectTimeout,
    @DefaultValue("10s") @NotNull Duration requestTimeout,
    @DefaultValue("4") @Min(1) int consumers) {}
