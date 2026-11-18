package io.github.joseferrao20.hookrelay.dispatch;

/** Outcome of one HTTP attempt. {@code responseStatus} is null on I/O errors and timeouts. */
public record SendResult(Integer responseStatus, String error, long durationMs) {

  public boolean succeeded() {
    return responseStatus != null && responseStatus >= 200 && responseStatus < 300;
  }
}
