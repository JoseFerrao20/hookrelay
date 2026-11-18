package io.github.joseferrao20.hookrelay.dispatch;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("hookrelay.retry")
public record RetryProperties(
    @DefaultValue("10") @Min(1) @Max(100) int maxAttempts,
    @DefaultValue("30s") @NotNull Duration initialDelay,
    @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
    @DefaultValue("6h") @NotNull Duration maxDelay,
    @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax("1.0") double jitter) {

  @AssertTrue(message = "initial-delay must be greater than zero")
  boolean isInitialDelayPositive() {
    return initialDelay == null || (!initialDelay.isNegative() && !initialDelay.isZero());
  }

  @AssertTrue(message = "max-delay must be greater than or equal to initial-delay")
  boolean isMaxDelayAtLeastInitialDelay() {
    return initialDelay == null || maxDelay == null || maxDelay.compareTo(initialDelay) >= 0;
  }
}
