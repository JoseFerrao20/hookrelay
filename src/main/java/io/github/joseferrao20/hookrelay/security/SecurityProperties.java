package io.github.joseferrao20.hookrelay.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings. There are no production defaults for the API keys and the encryption key: the
 * application refuses to start without them.
 */
@Validated
@ConfigurationProperties("hookrelay.security")
public record SecurityProperties(
    @NotEmpty
        List<@NotBlank @Size(min = 32, message = "must be at least 32 characters") String> apiKeys,
    @NotBlank String secretEncryptionKey,
    @DefaultValue Ssrf ssrf) {

  public record Ssrf(
      @DefaultValue("false") boolean allowPrivateNetworks,
      @DefaultValue List<String> allowedHosts) {}
}
