package io.github.joseferrao20.hookrelay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "hookrelay.dispatch.enabled=false")
class HookrelayApplicationTests {

  @Autowired TestRestTemplate rest;

  @Test
  void contextLoadsWithSchemaValidation() {}

  @Test
  void healthIsUp() {
    ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }
}
