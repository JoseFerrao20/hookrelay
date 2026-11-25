package io.github.joseferrao20.hookrelay.endpoint;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Optional body of the rotation call; {@code graceSeconds} defaults to 86400 (24 h). */
public record RotateSecretRequest(
    @Min(0) @Max(RotateSecretRequest.MAX_GRACE_SECONDS) Integer graceSeconds) {

  public static final int MAX_GRACE_SECONDS = 604_800;
  public static final int DEFAULT_GRACE_SECONDS = 86_400;

  public int graceSecondsOrDefault() {
    return graceSeconds == null ? DEFAULT_GRACE_SECONDS : graceSeconds;
  }
}
