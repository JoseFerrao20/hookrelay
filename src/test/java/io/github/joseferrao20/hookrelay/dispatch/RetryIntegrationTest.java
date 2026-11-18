package io.github.joseferrao20.hookrelay.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.joseferrao20.hookrelay.TestcontainersConfiguration;
import io.github.joseferrao20.hookrelay.delivery.Delivery;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttempt;
import io.github.joseferrao20.hookrelay.delivery.DeliveryAttemptRepository;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.endpoint.Endpoint;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import io.github.joseferrao20.hookrelay.event.Event;
import io.github.joseferrao20.hookrelay.event.EventRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "hookrelay.dispatch.enabled=true",
      "hookrelay.dispatch.poll-interval=200ms",
      "hookrelay.dispatch.connect-timeout=1s",
      "hookrelay.dispatch.request-timeout=1s",
      "hookrelay.dispatch.lease=30s",
      "hookrelay.retry.max-attempts=3",
      "hookrelay.retry.initial-delay=200ms",
      "hookrelay.retry.max-delay=1s",
      "hookrelay.retry.jitter=0"
    })
class RetryIntegrationTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(
              com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig()
                  .dynamicPort())
          .build();

  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired RabbitTemplate rabbit;
  @Autowired AmqpAdmin admin;
  @Autowired DeliveryRepository deliveries;
  @Autowired DeliveryAttemptRepository attempts;
  @Autowired EventRepository events;
  @Autowired EndpointRepository endpoints;
  @Autowired SmartInitializingSingleton legacyQueueCleanup;

  @BeforeEach
  void clean() {
    attempts.deleteAll();
    deliveries.deleteAll();
    events.deleteAll();
    endpoints.deleteAll();
    wm.resetAll();
  }

  private Delivery newDelivery(String path) throws Exception {
    Instant now = Instant.now();
    Endpoint endpoint =
        endpoints.save(new Endpoint(wm.baseUrl() + path, null, "secret", true, Set.of("*"), now));
    Event event = events.save(new Event("order.created", json.readTree("{\"a\":1}"), null, now));
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

  private List<DeliveryAttempt> attemptsOf(Delivery d) {
    return attempts.findByDeliveryIdOrderByAttemptNumberAsc(d.getId());
  }

  @Test
  void failsTwiceThenSucceeds() throws Exception {
    String url = "/flaky";
    wm.stubFor(
        post(urlEqualTo(url))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("one"));
    wm.stubFor(
        post(urlEqualTo(url))
            .inScenario("flaky")
            .whenScenarioStateIs("one")
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("two"));
    wm.stubFor(
        post(urlEqualTo(url))
            .inScenario("flaky")
            .whenScenarioStateIs("two")
            .willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery(url);

    awaitStatus(d, "SUCCEEDED");

    Map<String, Object> r = row(d);
    assertThat(r.get("attempt_count")).isEqualTo(3);
    assertThat(r.get("next_attempt_at")).isNull();
    assertThat(r.get("last_error")).isNull();
    assertThat(attemptsOf(d))
        .extracting(DeliveryAttempt::getOutcome)
        .containsExactly("FAILED", "FAILED", "SUCCEEDED");
  }

  @Test
  void alwaysFailingEndsDeadAfterMaxAttempts() throws Exception {
    wm.stubFor(post(urlEqualTo("/down")).willReturn(aResponse().withStatus(500)));
    Delivery d = newDelivery("/down");

    awaitStatus(d, "DEAD");

    Map<String, Object> r = row(d);
    assertThat(r.get("attempt_count")).isEqualTo(3);
    assertThat(r.get("next_attempt_at")).isNull();
    assertThat((String) r.get("last_error")).isEqualTo("HTTP 500 (gave up after 3 attempts)");
    assertThat(attemptsOf(d))
        .extracting(DeliveryAttempt::getOutcome)
        .containsExactly("FAILED", "FAILED", "FAILED");
    Thread.sleep(1500);
    wm.verify(3, postRequestedFor(urlEqualTo("/down")));
  }

  @Test
  void goneIsDeadAfterOneAttempt() throws Exception {
    wm.stubFor(post(urlEqualTo("/gone")).willReturn(aResponse().withStatus(410)));
    Delivery d = newDelivery("/gone");

    awaitStatus(d, "DEAD");

    assertThat(row(d).get("attempt_count")).isEqualTo(1);
    assertThat(row(d).get("last_error")).isEqualTo("HTTP 410 (gone)");
    assertThat(row(d).get("next_attempt_at")).isNull();
    assertThat(attemptsOf(d)).hasSize(1);
  }

  @Test
  void retryAfterDelaysTheNextAttempt() throws Exception {
    wm.stubFor(
        post(urlEqualTo("/limited"))
            .inScenario("rl")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
            .willSetStateTo("open"));
    wm.stubFor(
        post(urlEqualTo("/limited"))
            .inScenario("rl")
            .whenScenarioStateIs("open")
            .willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery("/limited");

    awaitStatus(d, "SUCCEEDED");

    List<DeliveryAttempt> list = attemptsOf(d);
    assertThat(list).hasSize(2);
    Duration gap = Duration.between(list.get(0).getStartedAt(), list.get(1).getStartedAt());
    assertThat(gap).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void failedRowWaitsUntilItsNextAttemptAt() throws Exception {
    wm.stubFor(post(urlEqualTo("/later")).willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery("/later");
    jdbc.update(
        "UPDATE deliveries SET status = 'FAILED', attempt_count = 1,"
            + " next_attempt_at = now() + interval '1 hour' WHERE id = ?",
        d.getId());

    Thread.sleep(1500);
    assertThat(row(d).get("status")).isEqualTo("FAILED");
    assertThat(row(d).get("next_attempt_at")).isNotNull();
    assertThat(row(d).get("attempt_count")).isEqualTo(1);
    assertThat(wm.getAllServeEvents()).isEmpty();

    jdbc.update(
        "UPDATE deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
        d.getId());
    awaitStatus(d, "SUCCEEDED");
    assertThat(row(d).get("attempt_count")).isEqualTo(2);
  }

  @Test
  void deletedEndpointIsDead() throws Exception {
    wm.stubFor(post(urlEqualTo("/deleted")).willReturn(aResponse().withStatus(200)));
    Delivery d = newDelivery("/deleted");
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at = now() + interval '1 hour' WHERE id = ?",
        d.getId());
    jdbc.update("UPDATE endpoints SET deleted_at = now() WHERE id = ?", d.getEndpointId());
    jdbc.update(
        "UPDATE deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
        d.getId());

    awaitStatus(d, "DEAD");

    assertThat(row(d).get("last_error")).isEqualTo("Endpoint deleted");
    assertThat(wm.getAllServeEvents()).isEmpty();
  }

  @Test
  void unconvertibleMessageLandsInDeadLetterQueue() {
    admin.purgeQueue(DispatchConfig.DLQ, true);
    MessageProperties props = new MessageProperties();
    props.setContentType("application/json");
    rabbit.send(
        DispatchConfig.EXCHANGE,
        DispatchConfig.ROUTING_KEY,
        new Message("this is not json".getBytes(StandardCharsets.UTF_8), props));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              var info = admin.getQueueInfo(DispatchConfig.DLQ);
              assertThat(info).isNotNull();
              assertThat(info.getMessageCount()).isEqualTo(1);
            });
    assertThat(admin.getQueueInfo(DispatchConfig.QUEUE).getMessageCount()).isZero();
  }

  @Test
  void legacyQueueIsDeletedByCleanup() {
    Queue legacy = new Queue(DispatchConfig.LEGACY_QUEUE, true);
    admin.declareQueue(legacy);
    admin.declareBinding(
        BindingBuilder.bind(legacy)
            .to(new DirectExchange(DispatchConfig.EXCHANGE))
            .with(DispatchConfig.ROUTING_KEY));
    assertThat(admin.getQueueInfo(DispatchConfig.LEGACY_QUEUE)).isNotNull();

    legacyQueueCleanup.afterSingletonsInstantiated();

    assertThat(admin.getQueueInfo(DispatchConfig.LEGACY_QUEUE)).isNull();
    assertThat(admin.getQueueInfo(DispatchConfig.QUEUE)).isNotNull();
    // Running it again is harmless.
    legacyQueueCleanup.afterSingletonsInstantiated();
  }

  @Test
  void v3MigrationTurnsLegacyTerminalFailedRowsIntoDead() throws Exception {
    Delivery terminal = newDelivery("/m1");
    Delivery scheduled = newDelivery("/m2");
    jdbc.update(
        "UPDATE deliveries SET status = 'FAILED', next_attempt_at = NULL, locked_until = now()"
            + " + interval '1 hour' WHERE id = ?",
        terminal.getId());
    jdbc.update(
        "UPDATE deliveries SET status = 'FAILED', locked_until = now() + interval '1 hour'"
            + " WHERE id = ?",
        scheduled.getId());

    String sql =
        Files.readString(Path.of("src/main/resources/db/migration/V3__retries.sql"))
            .lines()
            .filter(l -> !l.trim().startsWith("--"))
            .reduce("", (a, b) -> a + b + "\n");
    String update = sql.substring(0, sql.indexOf(';'));
    jdbc.update(update);

    assertThat(row(terminal).get("status")).isEqualTo("DEAD");
    assertThat(row(scheduled).get("status")).isEqualTo("FAILED");
  }
}
