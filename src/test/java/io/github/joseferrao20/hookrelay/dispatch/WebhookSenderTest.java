package io.github.joseferrao20.hookrelay.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class WebhookSenderTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance()
          .options(WireMockConfiguration.wireMockConfig().dynamicPort())
          .build();

  private final ObjectMapper mapper = new ObjectMapper();

  private WebhookSender sender() {
    DispatchProperties props =
        new DispatchProperties(
            true,
            Duration.ofSeconds(1),
            10,
            Duration.ofSeconds(30),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            1);
    return new WebhookSender(new DispatchConfig().webhookRestClient(props), Clock.systemUTC());
  }

  private SendResult send(String path) throws Exception {
    return sender()
        .send(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a.b",
            1,
            wm.baseUrl() + path,
            mapper.readTree("{\"x\":1}"));
  }

  @Test
  void twoHundredIsSuccess() throws Exception {
    wm.stubFor(post(urlEqualTo("/a")).willReturn(aResponse().withStatus(201).withBody("hi")));
    SendResult r = send("/a");
    assertThat(r.succeeded()).isTrue();
    assertThat(r.responseStatus()).isEqualTo(201);
    assertThat(r.error()).isNull();
  }

  @Test
  void non2xxIsFailureWithHttpError() throws Exception {
    wm.stubFor(post(urlEqualTo("/b")).willReturn(aResponse().withStatus(404)));
    SendResult r = send("/b");
    assertThat(r.succeeded()).isFalse();
    assertThat(r.error()).isEqualTo("HTTP 404");
  }

  @Test
  void timeoutHasNoStatus() throws Exception {
    wm.stubFor(post(urlEqualTo("/c")).willReturn(aResponse().withFixedDelay(3000)));
    SendResult r = send("/c");
    assertThat(r.succeeded()).isFalse();
    assertThat(r.responseStatus()).isNull();
    assertThat(r.error()).contains(":");
  }

  @Test
  void connectionRefusedHasNoStatus() throws Exception {
    SendResult r =
        sender()
            .send(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a.b",
                1,
                "http://127.0.0.1:1/x",
                mapper.readTree("{}"));
    assertThat(r.responseStatus()).isNull();
    assertThat(r.error()).isNotBlank();
  }

  @Test
  void unknownHostErrorIsDescriptive() throws Exception {
    SendResult r =
        sender()
            .send(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a.b",
                1,
                "http://does-not-exist.invalid/x",
                mapper.readTree("{}"));
    assertThat(r.responseStatus()).isNull();
    assertThat(r.error()).doesNotContain("null").contains("UnresolvedAddressException");
  }
}
