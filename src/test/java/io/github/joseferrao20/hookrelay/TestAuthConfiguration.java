package io.github.joseferrao20.hookrelay;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Makes every {@code MockMvc} request carry the test API key, so tests stay free of auth noise.
 * Tests that exercise authentication itself use {@code TestRestTemplate} instead.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAuthConfiguration {

  /** Same value as {@code hookrelay.security.api-keys} in {@code config/application.yml}. */
  public static final String API_KEY = "test-api-key-0123456789-0123456789-abcdef";

  public static final String BEARER = "Bearer " + API_KEY;

  @Bean
  MockMvcBuilderCustomizer apiKeyCustomizer() {
    return builder ->
        builder.defaultRequest(
            MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, BEARER));
  }
}
