package io.github.joseferrao20.hookrelay.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.joseferrao20.hookrelay.TestcontainersConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "hookrelay.dispatch.enabled=true",
      "hookrelay.dispatch.poll-interval=200ms",
      "hookrelay.dispatch.connect-timeout=1s",
      "hookrelay.dispatch.request-timeout=1s",
      "hookrelay.dispatch.lease=30s"
    })
@AutoConfigureMockMvc
class DispatchIntegrationTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                  .dynamicPort())
          .build();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired RabbitTemplate rabbit;
  @Autowired DeliveryRepository deliveries;
  @Autowired DeliveryAttemptRepository attempts;
  @Autowired EventRepository events;
  @Autowired EndpointRepository endpoints;

  @BeforeEach
  void clean() {
    attempts.deleteAll();
    deliveries.deleteAll();
    events.deleteAll();
    endpoints.deleteAll();
    wm.resetAll();
  }

  private Delivery newDelivery(boolean endpointEnabled, String path) throws Exception {
    Instant now = Instant.now();
    Endpoint endpoint =
        endpoints.save(
            new Endpoint(wm.baseUrl() + path, null, "secret", endpointEnabled, Set.of("*"), now));
    Event event =
        events.save(
            new Event("order.created", json.readTree("{\"orderId\":42,\"ok\":true}"), null, now));
    return deliveries.save(new Delivery(event.getId(), endpoint.getId(), now.minusSeconds(1)));
  }

  private Map<String, Object> row(Delivery d) {
    return jdbc.queryForMap("SELECT * FROM deliveries WHERE id = ?", d.getId());
  }

  private void awaitStatus(Delivery d, String status) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(row(d).get("status")).isEqualTo(status));
  }

  @Test
  void successfulDeliveryIsSentWithHeadersAndRecorded() throws Exception {
    wm.stubFor(post(urlEqualTo("/ok")).willReturn(aResponse().withStatus(204)));
    Delivery d = newDelivery(true, "/ok");

    awaitStatus(d, "SUCCEEDED");

    Map<String, Object> r = row(d);
    assertThat(r.get("attempt_count")).isEqualTo(1);
    assertThat(r.get("last_response_status")).isEqualTo(204);
    assertThat(r.get("last_error")).isNull();
    assertThat(r.get("locked_until")).isNull();
    assertThat(r.get("next_attempt_at")).isNull();
    assertThat(attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId())).hasSize(1);

    wm.verify(
        1,
        postRequestedFor(urlEqualTo("/ok"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("User-Agent", matching("hookrelay/.+"))
            .withHeader("Hookrelay-Delivery-Id", equalTo(d.getId().toString()))
            .withHeader("Hookrelay-Event-Id", equalTo(d.getEventId().toString()))
            .withHeader("Hookrelay-Event-Type", equalTo("order.created"))
            .withHeader("Hookrelay-Attempt", equalTo("1"))
            .withHeader("Hookrelay-Timestamp", matching("\\d{10}"))
            .withRequestBody(equalToJson("{\"orderId\":42,\"ok\":true}")));

    mvc.perform(get("/api/v1/deliveries/" + d.getId() + "/attempts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].attemptNumber").value(1))
        .andExpect(jsonPath("$[0].outcome").value("SUCCEEDED"))
        .andExpect(jsonPath("$[0].responseStatus").value(204));
  }

  @Test
  void serverErrorEndsFailed() throws Exception {
    wm.stubFor(post(urlEqualTo("/err")).willReturn(aResponse().withStatus(500).withBody("boom")));
    Delivery d = newDelivery(true, "/err");

    awaitStatus(d, "FAILED");

    Map<String, Object> r = row(d);
    assertThat(r.get("last_response_status")).isEqualTo(500);
    assertThat(r.get("last_error")).isEqualTo("HTTP 500");
    assertThat(r.get("next_attempt_at")).isNull();
    var attempt = attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId()).get(0);
    assertThat(attempt.getOutcome()).isEqualTo("FAILED");
    assertThat(attempt.getResponseStatus()).isEqualTo(500);
  }

  @Test
  void timeoutEndsFailedWithoutResponseStatus() throws Exception {
    wm.stubFor(
        post(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(4000)));
    Delivery d = newDelivery(true, "/slow");

    awaitStatus(d, "FAILED");

    Map<String, Object> r = row(d);
    assertThat(r.get("last_response_status")).isNull();
    assertThat((String) r.get("last_error")).isNotBlank();
    var attempt = attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId()).get(0);
    assertThat(attempt.getResponseStatus()).isNull();
    assertThat(attempt.getError()).isNotBlank();
  }

  @Test
  void redirectIsNotFollowed() throws Exception {
    wm.stubFor(
        post(urlEqualTo("/moved"))
            .willReturn(
                aResponse().withStatus(302).withHeader("Location", wm.baseUrl() + "/other")));
    wm.stubFor(post(urlEqualTo("/other")).willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery(true, "/moved");

    awaitStatus(d, "FAILED");

    assertThat(row(d).get("last_error")).isEqualTo("HTTP 302");
    wm.verify(0, postRequestedFor(urlEqualTo("/other")));
  }

  @Test
  void disabledEndpointFailsWithoutSending() throws Exception {
    wm.stubFor(post(urlEqualTo("/off")).willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery(false, "/off");

    awaitStatus(d, "FAILED");

    assertThat(row(d).get("last_error")).isEqualTo("Endpoint disabled");
    var attempt = attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId()).get(0);
    assertThat(attempt.getResponseStatus()).isNull();
    assertThat(attempt.getDurationMs()).isZero();
    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void staleAndDuplicateMessagesAreIgnored() throws Exception {
    wm.stubFor(post(urlEqualTo("/stale")).willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery(true, "/stale");
    // Not due, so the poller leaves it alone; only our hand-made messages reach the worker.
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?",
        d.getId());
    long version = ((Number) row(d).get("version")).longValue();

    rabbit.convertAndSend(
        DispatchConfig.EXCHANGE,
        DispatchConfig.ROUTING_KEY,
        new DeliveryMessage(d.getId(), version + 5));
    awaitQueueDrained();
    Thread.sleep(500);

    Map<String, Object> r = row(d);
    assertThat(r.get("attempt_count")).isEqualTo(0);
    assertThat(((Number) r.get("version")).longValue()).isEqualTo(version);
    assertThat(r.get("status")).isEqualTo("PENDING");
    assertThat(wm.getAllServeEvents()).isEmpty();

    // The matching version claims exactly once; a duplicate of the same message does nothing.
    DeliveryMessage good = new DeliveryMessage(d.getId(), version);
    rabbit.convertAndSend(DispatchConfig.EXCHANGE, DispatchConfig.ROUTING_KEY, good);
    awaitStatus(d, "SUCCEEDED");
    rabbit.convertAndSend(DispatchConfig.EXCHANGE, DispatchConfig.ROUTING_KEY, good);
    awaitQueueDrained();
    Thread.sleep(500);
    wm.verify(1, postRequestedFor(urlEqualTo("/stale")));
    assertThat(row(d).get("attempt_count")).isEqualTo(1);
  }

  @Test
  void pollerPublishesDueDeliveriesOnly() throws Exception {
    wm.stubFor(post(urlEqualTo("/due")).willReturn(aResponse().withStatus(200)));
    wm.stubFor(post(urlEqualTo("/locked")).willReturn(aResponse().withStatus(200)));
    wm.stubFor(post(urlEqualTo("/future")).willReturn(aResponse().withStatus(200)));

    Delivery locked = newDelivery(true, "/locked");
    jdbc.update(
        "UPDATE deliveries SET locked_until = now() + interval '1 hour' WHERE id = ?",
        locked.getId());
    Delivery future = newDelivery(true, "/future");
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?",
        future.getId());
    Delivery due = newDelivery(true, "/due");

    awaitStatus(due, "SUCCEEDED");
    Thread.sleep(1500);

    for (Delivery other : new Delivery[] {locked, future}) {
      Map<String, Object> r = row(other);
      assertThat(r.get("status")).isEqualTo("PENDING");
      assertThat(r.get("attempt_count")).isEqualTo(0);
    }
    wm.verify(1, postRequestedFor(urlEqualTo("/due")));
    wm.verify(0, postRequestedFor(urlEqualTo("/locked")));
    wm.verify(0, postRequestedFor(urlEqualTo("/future")));
  }

  @Test
  void attemptsOfUnknownDeliveryReturn404() throws Exception {
    mvc.perform(get("/api/v1/deliveries/" + java.util.UUID.randomUUID() + "/attempts"))
        .andExpect(status().isNotFound());
  }

  private void awaitQueueDrained() {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> rabbit.execute(ch -> ch.messageCount(DispatchConfig.QUEUE)) == 0);
  }
}
