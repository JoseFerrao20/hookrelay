package io.github.joseferrao20.hookrelay.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.joseferrao20.hookrelay.TestAuthConfiguration;
import io.github.joseferrao20.hookrelay.TestcontainersConfiguration;
import io.github.joseferrao20.hookrelay.common.SecretCipher;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttemptRepository;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import io.github.joseferrao20.hookrelay.endpoint.SecretBackfill;
import io.github.joseferrao20.hookrelay.event.EventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import({TestcontainersConfiguration.class, TestAuthConfiguration.class})
@SpringBootTest(
    properties = {
      "hookrelay.dispatch.enabled=true",
      "hookrelay.dispatch.poll-interval=200ms",
      "hookrelay.dispatch.connect-timeout=1s",
      "hookrelay.dispatch.request-timeout=2s",
    })
@AutoConfigureMockMvc
class SigningIntegrationTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.wireMockConfig().dynamicPort())
          .build();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired DeliveryRepository deliveries;
  @Autowired DeliveryAttemptRepository attempts;
  @Autowired EventRepository events;
  @Autowired EndpointRepository endpoints;
  @Autowired SecretCipher cipher;
  @Autowired SecretBackfill backfill;

  @BeforeEach
  void clean() {
    attempts.deleteAll();
    deliveries.deleteAll();
    events.deleteAll();
    endpoints.deleteAll();
    wm.resetAll();
    wm.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/hook"))
            .willReturn(aResponse().withStatus(200)));
  }

  private JsonNode call(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r)
      throws Exception {
    String body =
        mvc.perform(r)
            .andExpect(status().is2xxSuccessful())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.isBlank() ? null : json.readTree(body);
  }

  private JsonNode createEndpoint() throws Exception {
    return call(
        post("/api/v1/endpoints")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + wm.baseUrl() + "/hook\",\"eventTypes\":[\"order.created\"]}"));
  }

  private void postEvent() throws Exception {
    call(
        post("/api/v1/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventType\":\"order.created\",\"payload\":{\"orderId\":42,\"n\":1.50}}"));
  }

  private LoggedRequest awaitRequestNumber(int n) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(wm.getAllServeEvents()).hasSize(n));
    return wm.getAllServeEvents().stream()
        .map(e -> e.getRequest())
        .sorted(java.util.Comparator.comparing(LoggedRequest::getLoggedDate))
        .toList()
        .get(n - 1);
  }

  private static boolean verifies(LoggedRequest req, String secret) {
    return WebhookSignature.verify(
        List.of(secret),
        req.getHeader("Hookrelay-Signature"),
        req.getHeader("Hookrelay-Delivery-Id"),
        Long.parseLong(req.getHeader("Hookrelay-Timestamp")),
        req.getBody(),
        Instant.now(),
        WebhookSignature.DEFAULT_TOLERANCE);
  }

  private static int signatureCount(LoggedRequest req) {
    return req.getHeader("Hookrelay-Signature").trim().split("\\s+").length;
  }

  @Test
  void webhookIsSignedOverTheExactBodyBytes() throws Exception {
    JsonNode created = createEndpoint();
    String secret = created.get("secret").asText();
    postEvent();

    LoggedRequest req = awaitRequestNumber(1);

    assertThat(signatureCount(req)).isEqualTo(1);
    assertThat(req.getHeader("Hookrelay-Signature")).startsWith("v1=");
    assertThat(verifies(req, secret)).isTrue();
    assertThat(verifies(req, "whsec_wrong")).isFalse();
    // the body that was signed is what the receiver got, byte for byte
    assertThat(json.readTree(req.getBody())).isEqualTo(json.readTree("{\"orderId\":42,\"n\":1.5}"));
    assertThat(req.getBody()).isEqualTo(req.getBodyAsString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rotationSignsWithBothSecretsDuringGrace() throws Exception {
    JsonNode created = createEndpoint();
    String id = created.get("id").asText();
    String oldSecret = created.get("secret").asText();

    JsonNode rotated = call(post("/api/v1/endpoints/" + id + "/secret/rotate"));
    String newSecret = rotated.get("secret").asText();
    assertThat(newSecret).startsWith("whsec_").isNotEqualTo(oldSecret);
    Instant expires = Instant.parse(rotated.get("previousSecretExpiresAt").asText());
    assertThat(expires)
        .isBetween(
            Instant.now().plus(Duration.ofHours(23)), Instant.now().plus(Duration.ofHours(25)));

    postEvent();
    LoggedRequest req = awaitRequestNumber(1);

    assertThat(signatureCount(req)).isEqualTo(2);
    assertThat(req.getHeader("Hookrelay-Signature")).matches("v1=\\S+ v1=\\S+");
    assertThat(verifies(req, newSecret)).isTrue();
    assertThat(verifies(req, oldSecret)).isTrue();
    // current secret first
    String first = req.getHeader("Hookrelay-Signature").split(" ")[0];
    assertThat(
            WebhookSignature.sign(
                newSecret,
                req.getHeader("Hookrelay-Delivery-Id"),
                Long.parseLong(req.getHeader("Hookrelay-Timestamp")),
                req.getBody()))
        .isEqualTo(first.substring(3));
  }

  @Test
  void rotationWithZeroGraceSignsWithNewSecretOnly() throws Exception {
    JsonNode created = createEndpoint();
    String id = created.get("id").asText();
    String oldSecret = created.get("secret").asText();

    JsonNode rotated =
        call(
            post("/api/v1/endpoints/" + id + "/secret/rotate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"graceSeconds\":0}"));
    assertThat(rotated.get("previousSecretExpiresAt").isNull()).isTrue();
    String newSecret = rotated.get("secret").asText();

    postEvent();
    LoggedRequest req = awaitRequestNumber(1);

    assertThat(signatureCount(req)).isEqualTo(1);
    assertThat(verifies(req, newSecret)).isTrue();
    assertThat(verifies(req, oldSecret)).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT previous_secret FROM endpoints WHERE id = ?::uuid", String.class, id))
        .isNull();
  }

  @Test
  void expiredPreviousSecretIsIgnored() throws Exception {
    JsonNode created = createEndpoint();
    String id = created.get("id").asText();
    String oldSecret = created.get("secret").asText();
    String newSecret =
        call(post("/api/v1/endpoints/" + id + "/secret/rotate")).get("secret").asText();
    jdbc.update(
        "UPDATE endpoints SET previous_secret_expires_at = now() - interval '1 minute'"
            + " WHERE id = ?::uuid",
        id);

    postEvent();
    LoggedRequest req = awaitRequestNumber(1);

    assertThat(signatureCount(req)).isEqualTo(1);
    assertThat(verifies(req, newSecret)).isTrue();
    assertThat(verifies(req, oldSecret)).isFalse();
  }

  @Test
  void rotatingAgainReplacesThePreviousSecretWithTheCurrentOne() throws Exception {
    JsonNode created = createEndpoint();
    String id = created.get("id").asText();
    String first = created.get("secret").asText();
    String second = call(post("/api/v1/endpoints/" + id + "/secret/rotate")).get("secret").asText();
    String third = call(post("/api/v1/endpoints/" + id + "/secret/rotate")).get("secret").asText();

    postEvent();
    LoggedRequest req = awaitRequestNumber(1);

    assertThat(signatureCount(req)).isEqualTo(2);
    assertThat(verifies(req, third)).isTrue();
    assertThat(verifies(req, second)).isTrue();
    assertThat(verifies(req, first)).isFalse();
  }

  @Test
  void rotationValidatesGraceAndUnknownOrDeletedEndpointsAre404() throws Exception {
    mvc.perform(post("/api/v1/endpoints/" + UUID.randomUUID() + "/secret/rotate"))
        .andExpect(status().isNotFound());

    String id = createEndpoint().get("id").asText();
    for (String bad : new String[] {"{\"graceSeconds\":-1}", "{\"graceSeconds\":604801}"}) {
      mvc.perform(
              post("/api/v1/endpoints/" + id + "/secret/rotate")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(bad))
          .andExpect(status().isBadRequest());
    }
    call(
        post("/api/v1/endpoints/" + id + "/secret/rotate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"graceSeconds\":604800}"));

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/endpoints/" + id))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/endpoints/" + id + "/secret/rotate"))
        .andExpect(status().isNotFound());
  }

  @Test
  void secretsAreEncryptedAtRestAndNeverReturnedAgain() throws Exception {
    JsonNode created = createEndpoint();
    String id = created.get("id").asText();
    String secret = created.get("secret").asText();

    String stored =
        jdbc.queryForObject("SELECT secret FROM endpoints WHERE id = ?::uuid", String.class, id);
    assertThat(stored).startsWith("v1:").doesNotContain(secret);
    assertThat(cipher.decrypt(UUID.fromString(id), stored)).isEqualTo(secret);

    String rotatedSecret =
        call(post("/api/v1/endpoints/" + id + "/secret/rotate")).get("secret").asText();
    String row =
        jdbc.queryForList("SELECT secret || '|' || previous_secret FROM endpoints", String.class)
            .get(0);
    assertThat(row).doesNotContain(secret).doesNotContain(rotatedSecret).contains("v1:");

    String get =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/endpoints/" + id))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(get).doesNotContain("secret").doesNotContain(rotatedSecret);
  }

  @Test
  void backfillEncryptsPlaintextRowsAndIsIdempotent() throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO endpoints (id, url, secret, previous_secret, previous_secret_expires_at,"
            + " enabled, created_at, updated_at) VALUES (?, ?, 'whsec_legacy', 'whsec_legacyprev',"
            + " now() + interval '1 hour', true, now(), now())",
        id,
        wm.baseUrl() + "/hook");
    jdbc.update("INSERT INTO endpoint_event_types (endpoint_id, event_type) VALUES (?, '*')", id);

    assertThat(backfill.encryptPlaintextSecrets()).isEqualTo(2);

    var row = jdbc.queryForMap("SELECT secret, previous_secret FROM endpoints WHERE id = ?", id);
    assertThat((String) row.get("secret")).startsWith("v1:");
    assertThat(cipher.decrypt(id, (String) row.get("secret"))).isEqualTo("whsec_legacy");
    assertThat(cipher.decrypt(id, (String) row.get("previous_secret")))
        .isEqualTo("whsec_legacyprev");
    assertThat(backfill.encryptPlaintextSecrets()).isZero();

    // and the legacy endpoint signs with its old secret once it is encrypted
    postEvent();
    LoggedRequest req = awaitRequestNumber(1);
    assertThat(signatureCount(req)).isEqualTo(2);
    assertThat(verifies(req, "whsec_legacy")).isTrue();
    assertThat(verifies(req, "whsec_legacyprev")).isTrue();
  }
}
