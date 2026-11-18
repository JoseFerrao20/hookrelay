package io.github.joseferrao20.hookrelay.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.joseferrao20.hookrelay.delivery.DeliveryStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static RetryProperties props(int max, double jitter) {
    return new RetryProperties(max, Duration.ofSeconds(30), 2.0, Duration.ofHours(6), jitter);
  }

  private static SendResult fail(Integer status, Duration retryAfter) {
    return new SendResult(
        status, status == null ? "IOException: x" : "HTTP " + status, 5, retryAfter);
  }

  @Test
  void nominalScheduleWithoutJitter() {
    RetryPolicy p = new RetryPolicy(props(10, 0), () -> 0.5);
    long[] expectedSeconds = {30, 60, 120, 240, 480, 960, 1920, 3840, 7680};
    for (int n = 1; n <= 9; n++) {
      assertThat(p.delay(n)).isEqualTo(Duration.ofSeconds(expectedSeconds[n - 1]));
    }
  }

  @Test
  void delayIsCappedAtMaxDelay() {
    RetryPolicy p = new RetryPolicy(props(100, 0), () -> 0.5);
    assertThat(p.delay(12)).isEqualTo(Duration.ofHours(6));
    assertThat(p.delay(100)).isEqualTo(Duration.ofHours(6));
  }

  @Test
  void jitterStaysWithinBounds() {
    RetryPolicy p = new RetryPolicy(props(10, 0.2), new Random(42)::nextDouble);
    boolean varied = false;
    Duration first = p.delay(3);
    for (int i = 0; i < 5000; i++) {
      Duration d = p.delay(3);
      assertThat(d).isBetween(Duration.ofSeconds(96), Duration.ofSeconds(144));
      varied |= !d.equals(first);
    }
    assertThat(varied).isTrue();
    RetryPolicy capped = new RetryPolicy(props(100, 1.0), new Random(7)::nextDouble);
    for (int i = 0; i < 2000; i++) {
      assertThat(capped.delay(50)).isBetween(Duration.ZERO, Duration.ofHours(6));
    }
  }

  @Test
  void retryAfterRaisesDelayFor429And503() {
    RetryPolicy p = new RetryPolicy(props(10, 0), () -> 0.5);
    assertThat(p.delay(1, 429, Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(120));
    assertThat(p.delay(1, 503, Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(120));
    assertThat(p.delay(3, 429, Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(120));
    assertThat(p.delay(1, 429, Duration.ofDays(3))).isEqualTo(Duration.ofHours(6));
    assertThat(p.delay(1, 500, Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(30));
    assertThat(p.delay(1, 429, null)).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void retryAfterParsing() {
    assertThat(WebhookSender.parseRetryAfter("7", NOW)).isEqualTo(Duration.ofSeconds(7));
    assertThat(WebhookSender.parseRetryAfter(" 0 ", NOW)).isEqualTo(Duration.ZERO);
    assertThat(WebhookSender.parseRetryAfter("Thu, 01 Jan 2026 00:01:30 GMT", NOW))
        .isEqualTo(Duration.ofSeconds(90));
    assertThat(WebhookSender.parseRetryAfter("Wed, 31 Dec 2025 23:00:00 GMT", NOW))
        .isEqualTo(Duration.ZERO);
    assertThat(WebhookSender.parseRetryAfter(null, NOW)).isNull();
    assertThat(WebhookSender.parseRetryAfter("", NOW)).isNull();
    assertThat(WebhookSender.parseRetryAfter("soon", NOW)).isNull();
    assertThat(WebhookSender.parseRetryAfter("-5", NOW)).isNull();
    assertThat(WebhookSender.parseRetryAfter("1.5", NOW)).isNull();
    assertThat(WebhookSender.parseRetryAfter("99999999999999999999999", NOW)).isNull();
  }

  @Test
  void successIsTerminalSuccess() {
    RetryPolicy p = new RetryPolicy(props(3, 0), () -> 0.5);
    RetryDecision d = p.decide(1, new SendResult(204, null, 3), NOW);
    assertThat(d).isEqualTo(new RetryDecision(DeliveryStatus.SUCCEEDED, null, null));
  }

  @Test
  void failureSchedulesRetryWhileAttemptsLeft() {
    RetryPolicy p = new RetryPolicy(props(3, 0), () -> 0.5);
    RetryDecision d1 = p.decide(1, fail(500, null), NOW);
    assertThat(d1.status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(d1.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(d1.lastError()).isEqualTo("HTTP 500");
    RetryDecision d2 = p.decide(2, fail(null, null), NOW);
    assertThat(d2.status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(d2.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void clientErrorsAndRedirectsAreRetried() {
    RetryPolicy p = new RetryPolicy(props(3, 0), () -> 0.5);
    for (int code : new int[] {301, 400, 401, 404}) {
      assertThat(p.decide(1, fail(code, null), NOW).status()).isEqualTo(DeliveryStatus.FAILED);
    }
  }

  @Test
  void exhaustedAttemptsGoDead() {
    RetryPolicy p = new RetryPolicy(props(3, 0), () -> 0.5);
    RetryDecision d = p.decide(3, fail(500, null), NOW);
    assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD);
    assertThat(d.nextAttemptAt()).isNull();
    assertThat(d.lastError()).isEqualTo("HTTP 500 (gave up after 3 attempts)");
  }

  @Test
  void goneIsDeadImmediately() {
    RetryPolicy p = new RetryPolicy(props(10, 0), () -> 0.5);
    RetryDecision d = p.decide(1, fail(410, null), NOW);
    assertThat(d).isEqualTo(new RetryDecision(DeliveryStatus.DEAD, null, "HTTP 410 (gone)"));
  }

  @Test
  void maxAttemptsOneMeansNoRetries() {
    RetryPolicy p = new RetryPolicy(props(1, 0), () -> 0.5);
    RetryDecision d = p.decide(1, fail(500, null), NOW);
    assertThat(d.status()).isEqualTo(DeliveryStatus.DEAD);
    assertThat(d.lastError()).isEqualTo("HTTP 500 (gave up after 1 attempts)");
  }
}
