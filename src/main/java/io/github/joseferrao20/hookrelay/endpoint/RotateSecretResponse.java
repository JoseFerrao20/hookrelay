package io.github.joseferrao20.hookrelay.endpoint;

import java.time.Instant;

/** The new secret is shown only here. {@code previousSecretExpiresAt} is null without grace. */
public record RotateSecretResponse(String secret, Instant previousSecretExpiresAt) {}
