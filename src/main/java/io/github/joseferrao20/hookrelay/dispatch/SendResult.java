package io.github.joseferrao20.hookrelay.dispatch;

import java.time.Duration;

/**
 * Outcome of one HTTP attempt. {@code responseStatus} is null on I/O errors and timeouts; {@code
 * retryAfter} is the parsed {@code Retry-After} of a 429/503 response, if any; {@code
 * blockedReason} is set when the destination was refused by SSRF protection (never retried).
 */
public record SendResult(
    Integer responseStatus,
    String error,
    long durationMs,
    Duration retryAfter,
    String blockedReason) {

  public SendResult(Integer responseStatus, String error, long durationMs) {
    this(responseStatus, error, durationMs, null, null);
  }

  public SendResult(Integer responseStatus, String error, long durationMs, Duration retryAfter) {
    this(responseStatus, error, durationMs, retryAfter, null);
  }

  public static SendResult blocked(String reason, long durationMs) {
    return new SendResult(null, reason, durationMs, null, reason);
  }

  public boolean succeeded() {
    return responseStatus != null && responseStatus >= 200 && responseStatus < 300;
  }
}
