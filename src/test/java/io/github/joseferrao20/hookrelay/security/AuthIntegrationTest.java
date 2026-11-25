package io.github.joseferrao20.hookrelay.security;

import static io.github.joseferrao20.hookrelay.TestAuthConfiguration.API_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.joseferrao20.hookrelay.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "hookrelay.dispatch.enabled=false")
class AuthIntegrationTest {

  @Autowired TestRestTemplate rest;

  private ResponseEntity<String> get(String path, String authorization) {
    HttpHeaders headers = new HttpHeaders();
    if (authorization != null) {
      headers.set(HttpHeaders.AUTHORIZATION, authorization);
    }
    return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private void assertUnauthorized(ResponseEntity<String> r, String leakedKey) {
    assertThat(r.getStatusCode().value()).isEqualTo(401);
    assertThat(r.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
    assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    assertThat(r.getBody())
        .contains("\"title\":\"Unauthorized\"")
        .contains("\"status\":401")
        .contains("\"detail\":\"Missing or invalid API key\"");
    if (leakedKey != null) {
      assertThat(r.getBody()).doesNotContain(leakedKey);
    }
  }

  @Test
  void missingKeyIs401() {
    assertUnauthorized(get("/api/v1/endpoints", null), null);
  }

  @Test
  void wrongKeyOrSchemeIs401WithoutEchoingTheKey() {
    String wrong = "wrong-api-key-0123456789-0123456789-xyz";
    assertUnauthorized(get("/api/v1/endpoints", "Bearer " + wrong), wrong);
    assertUnauthorized(get("/api/v1/endpoints", "Bearer " + API_KEY + "x"), API_KEY);
    assertUnauthorized(get("/api/v1/endpoints", "Bearer "), null);
    assertUnauthorized(get("/api/v1/endpoints", "Basic " + API_KEY), API_KEY);
    assertUnauthorized(get("/api/v1/endpoints", API_KEY), API_KEY);
  }

  @Test
  void everyApiPathNeedsAKey() {
    for (String path :
        new String[] {
          "/api/v1/events", "/api/v1/deliveries", "/actuator", "/actuator/env", "/something"
        }) {
      assertThat(get(path, null).getStatusCode().value()).as(path).isEqualTo(401);
    }
  }

  @Test
  void validKeyIs200() {
    ResponseEntity<String> r = get("/api/v1/endpoints", "Bearer " + API_KEY);
    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(get("/api/v1/endpoints", "bearer " + API_KEY).getStatusCode().value())
        .isEqualTo(200);
  }

  @Test
  void healthInfoAndApiDocsArePublic() {
    assertThat(get("/actuator/health", null).getStatusCode().value()).isEqualTo(200);
    assertThat(get("/actuator/health/liveness", null).getStatusCode().value()).isEqualTo(200);
    assertThat(get("/actuator/info", null).getStatusCode().value()).isEqualTo(200);
    ResponseEntity<String> docs = get("/v3/api-docs", null);
    assertThat(docs.getStatusCode().value()).isEqualTo(200);
    assertThat(docs.getBody()).contains("\"bearer\"");
  }
}
