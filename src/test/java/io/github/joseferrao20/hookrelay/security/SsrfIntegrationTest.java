package io.github.joseferrao20.hookrelay.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.joseferrao20.hookrelay.TestAuthConfiguration;
import io.github.joseferrao20.hookrelay.TestcontainersConfiguration;
import io.github.joseferrao20.hookrelay.common.SecretCipher;
import io.github.joseferrao20.hookrelay.delivery.Delivery;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttemptRepository;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.endpoint.Endpoint;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import io.github.joseferrao20.hookrelay.event.Event;
import io.github.joseferrao20.hookrelay.event.EventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
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

/** SSRF protection with private networks NOT allowed (the shared test setup allows them). */
@Import({TestcontainersConfiguration.class, TestAuthConfiguration.class})
@SpringBootTest(
    properties = {
      "hookrelay.security.ssrf.allow-private-networks=false",
      "hookrelay.security.ssrf.allowed-hosts=localhost",
      "hookrelay.dispatch.enabled=true",
      "hookrelay.dispatch.poll-interval=200ms",
      "hookrelay.dispatch.connect-timeout=1s",
      "hookrelay.dispatch.request-timeout=2s",
    })
@AutoConfigureMockMvc
class SsrfIntegrationTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.wireMockConfig().dynamicPort())
          .build();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired DeliveryRepository deliveries;
  @Autowired DeliveryAttemptRepository attempts;
  @Autowired EventRepository events;
  @Autowired EndpointRepository endpoints;
  @Autowired SecretCipher cipher;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

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

  private String createBody(String url) {
    return "{\"url\":\"" + url + "\",\"eventTypes\":[\"a.b\"]}";
  }

  @Test
  void createRejectsBlockedUrlsOnFieldUrl() throws Exception {
    for (String url :
        new String[] {
          "http://127.0.0.1/x",
          "http://169.254.169.254/latest",
          "http://[::1]/x",
          "http://10.0.0.1/x",
          "http://192.168.0.10:8080/x",
          "http://foo.localhost/x",
        }) {
      mvc.perform(
              post("/api/v1/endpoints")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(createBody(url)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors[0].field").value("url"))
          .andExpect(
              jsonPath("$.errors[0].message")
                  .value("must not target a private, loopback or link-local address"));
    }
    assertThat(endpoints.count()).isZero();
  }

  @Test
  void patchRejectsBlockedUrlAndKeepsTheOldOne() throws Exception {
    // "localhost" is in allowed-hosts, so a localhost URL is accepted
    String created =
        mvc.perform(
                post("/api/v1/endpoints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("http://localhost:" + wm.getPort() + "/hook")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(created).get("id").asText();

    mvc.perform(
            patch("/api/v1/endpoints/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"http://169.254.169.254/latest\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("url"));
    assertThat(endpoints.findById(UUID.fromString(id)).orElseThrow().getUrl())
        .isEqualTo("http://localhost:" + wm.getPort() + "/hook");
  }

  private Delivery deliveryTo(String url) throws Exception {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    Endpoint endpoint =
        endpoints.save(
            new Endpoint(id, url, null, cipher.encrypt(id, "whsec_x"), true, Set.of("*"), now));
    Event event = events.save(new Event("a.b", json.readTree("{\"x\":1}"), null, now));
    return deliveries.save(new Delivery(event.getId(), endpoint.getId(), now.minusSeconds(1)));
  }

  private Map<String, Object> row(Delivery d) {
    return jdbc.queryForMap("SELECT * FROM deliveries WHERE id = ?", d.getId());
  }

  @Test
  void literalIpEndpointInsertedBehindOurBackGoesDeadWithoutAnyRequest() throws Exception {
    // WireMock listens on 127.0.0.1; the row bypasses save-time validation
    Delivery d = deliveryTo("http://127.0.0.1:" + wm.getPort() + "/hook");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(row(d).get("status")).isEqualTo("DEAD"));

    Map<String, Object> r = row(d);
    assertThat(r.get("last_error"))
        .isEqualTo("Blocked destination: 127.0.0.1 resolves to 127.0.0.1");
    assertThat(r.get("next_attempt_at")).isNull();
    assertThat(r.get("attempt_count")).isEqualTo(1);
    var attempt = attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId()).get(0);
    assertThat(attempt.getOutcome()).isEqualTo("FAILED");
    assertThat(attempt.getResponseStatus()).isNull();
    assertThat(attempt.getError())
        .isEqualTo("Blocked destination: 127.0.0.1 resolves to 127.0.0.1");
    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void hostnameResolvingToLoopbackIsBlockedAtConnectTime() throws Exception {
    // 127.0.0.1.nip.io-style names need the internet; "localhost" is allowed here, so use the
    // IPv6 loopback literal for a second literal form
    Delivery d = deliveryTo("http://[::1]:" + wm.getPort() + "/hook");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(row(d).get("status")).isEqualTo("DEAD"));

    assertThat((String) row(d).get("last_error")).startsWith("Blocked destination: [::1]");
    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void allowedHostsEntryBypassesTheCheckAtSendTime() throws Exception {
    Delivery d = deliveryTo("http://localhost:" + wm.getPort() + "/hook");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(row(d).get("status")).isEqualTo("SUCCEEDED"));

    assertThat(wm.getAllServeEvents()).hasSize(1);
  }
}
