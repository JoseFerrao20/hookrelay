package io.github.joseferrao20.hookrelay.dispatch;

import io.github.joseferrao20.hookrelay.delivery.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Pure retry/backoff logic: turns the result of attempt N into the next delivery state. */
@Component
public class RetryPolicy {

  private final RetryProperties props;
  private final DoubleSupplier random;

  @Autowired
  public RetryPolicy(RetryProperties props) {
    this(props, () -> ThreadLocalRandom.current().nextDouble());
  }

  /** {@code random} returns values in [0, 1). */
  public RetryPolicy(RetryProperties props, DoubleSupplier random) {
    this.props = props;
    this.random = random;
  }

  /** Backoff after failed attempt {@code attempt} (1-based), with jitter, within [0, max-delay]. */
  public Duration delay(int attempt) {
    double maxMs = props.maxDelay().toMillis();
    double baseMs =
        Math.min(
            maxMs, props.initialDelay().toMillis() * Math.pow(props.multiplier(), attempt - 1));
    double factor = 1 + (2 * random.getAsDouble() - 1) * props.jitter();
    double ms = Math.max(0, Math.min(maxMs, baseMs * factor));
    return Duration.ofMillis(Math.round(ms));
  }

  /** Like {@link #delay(int)} but honours a {@code Retry-After} for 429 and 503. */
  public Duration delay(int attempt, Integer responseStatus, Duration retryAfter) {
    Duration d = delay(attempt);
    if (retryAfter != null
        && responseStatus != null
        && (responseStatus == 429 || responseStatus == 503)) {
      d = d.compareTo(retryAfter) >= 0 ? d : retryAfter;
      if (d.compareTo(props.maxDelay()) > 0) {
        d = props.maxDelay();
      }
    }
    return d;
  }

  public RetryDecision decide(int attempt, SendResult result, Instant now) {
    if (result.succeeded()) {
      return new RetryDecision(DeliveryStatus.SUCCEEDED, null, null);
    }
    if (result.responseStatus() != null && result.responseStatus() == 410) {
      return dead("HTTP 410 (gone)");
    }
    if (attempt >= props.maxAttempts()) {
      return dead(result.error() + " (gave up after " + attempt + " attempts)");
    }
    Duration wait = delay(attempt, result.responseStatus(), result.retryAfter());
    return new RetryDecision(DeliveryStatus.FAILED, now.plus(wait), result.error());
  }

  /** Terminal decision for failures that need no HTTP attempt (e.g. endpoint deleted). */
  public static RetryDecision dead(String reason) {
    return new RetryDecision(DeliveryStatus.DEAD, null, reason);
  }
}
