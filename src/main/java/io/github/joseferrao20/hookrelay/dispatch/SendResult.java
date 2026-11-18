package io.github.joseferrao20.hookrelay.dispatch;

import java.time.Duration;

/**
 * Outcome of one HTTP attempt. {@code responseStatus} is null on I/O errors and timeouts; {@code
 * retryAfter} is the parsed {@code Retry-After} of a 429/503 response, if any.
 */
public record SendResult(
    Integer responseStatus, String error, long durationMs, Duration retryAfter) {

  public SendResult(Integer responseStatus, String error, long durationMs) {
    this(responseStatus, error, durationMs, null);
  }

  public boolean succeeded() {
    return responseStatus != null && responseStatus >= 200 && responseStatus < 300;
  }
}
