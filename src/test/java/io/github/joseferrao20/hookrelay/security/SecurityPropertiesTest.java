package io.github.joseferrao20.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SecurityPropertiesTest {

  @Configuration
  @EnableConfigurationProperties(SecurityProperties.class)
  static class Config {}

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String API_KEY = "a".repeat(32);

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Config.class);

  @Test
  void validConfigurationBindsCommaSeparatedKeys() {
    runner
        .withPropertyValues(
            "hookrelay.security.api-keys=" + API_KEY + "," + "b".repeat(40),
            "hookrelay.security.secret-encryption-key=" + KEY,
            "hookrelay.security.ssrf.allowed-hosts=webhook-sink,other")
        .run(
            ctx -> {
              SecurityProperties p = ctx.getBean(SecurityProperties.class);
              assertThat(p.apiKeys()).hasSize(2);
              assertThat(p.ssrf().allowPrivateNetworks()).isFalse();
              assertThat(p.ssrf().allowedHosts()).containsExactly("webhook-sink", "other");
            });
  }

  @Test
  void ssrfDefaultsAreStrict() {
    runner
        .withPropertyValues(
            "hookrelay.security.api-keys=" + API_KEY,
            "hookrelay.security.secret-encryption-key=" + KEY)
        .run(
            ctx -> {
              SecurityProperties p = ctx.getBean(SecurityProperties.class);
              assertThat(p.ssrf().allowPrivateNetworks()).isFalse();
              assertThat(p.ssrf().allowedHosts()).isEmpty();
            });
  }

  @Test
  void missingApiKeysFailStartup() {
    runner
        .withPropertyValues("hookrelay.security.secret-encryption-key=" + KEY)
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void shortApiKeyFailsStartup() {
    runner
        .withPropertyValues(
            "hookrelay.security.api-keys=too-short-secret",
            "hookrelay.security.secret-encryption-key=" + KEY)
        .run(
            ctx -> {
              assertThat(ctx).hasFailed();
              assertThat(ctx).getFailure().hasStackTraceContaining("at least 32");
            });
  }

  @Test
  void missingEncryptionKeyFailsStartup() {
    runner
        .withPropertyValues("hookrelay.security.api-keys=" + API_KEY)
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
