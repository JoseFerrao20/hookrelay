package io.github.joseferrao20.hookrelay.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  private static final String SECRET = "whsec_testsecret";
  private static final String OLD_SECRET = "whsec_oldsecret";
  private static final String DELIVERY_ID = "dlv_123";
  private static final long TS = 1_700_000_000L;
  private static final byte[] BODY =
      "{\"orderId\":42,\"ok\":true}".getBytes(StandardCharsets.UTF_8);
  // Independently computed: HMAC-SHA256 over "dlv_123.1700000000.<body>", Base64.
  private static final String KNOWN_SIGNATURE = "AcELZupL3ZS41qLaKcdcrWB5j7PjXqxyZgpMn0OMHNg=";
  private static final Instant NOW = Instant.ofEpochSecond(TS + 10);
  private static final Duration TOLERANCE = WebhookSignature.DEFAULT_TOLERANCE;

  @Test
  void knownAnswerVector() {
    assertThat(WebhookSignature.sign(SECRET, DELIVERY_ID, TS, BODY)).isEqualTo(KNOWN_SIGNATURE);
    assertThat(WebhookSignature.header(List.of(SECRET), DELIVERY_ID, TS, BODY))
        .isEqualTo("v1=" + KNOWN_SIGNATURE);
  }

  @Test
  void verifiesWithOneSignature() {
    String header = "v1=" + KNOWN_SIGNATURE;
    assertThat(
            WebhookSignature.verify(List.of(SECRET), header, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isTrue();
  }

  @Test
  void verifiesWithTwoSignaturesAndEitherSecret() {
    String header = WebhookSignature.header(List.of(SECRET, OLD_SECRET), DELIVERY_ID, TS, BODY);
    assertThat(header).matches("v1=\\S+ v1=\\S+");
    assertThat(
            WebhookSignature.verify(List.of(SECRET), header, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isTrue();
    assertThat(
            WebhookSignature.verify(
                List.of(OLD_SECRET), header, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isTrue();
    assertThat(
            WebhookSignature.verify(
                List.of("whsec_other"), header, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isFalse();
  }

  @Test
  void rejectsTamperedBodyIdTimestampAndGarbage() {
    String header = "v1=" + KNOWN_SIGNATURE;
    byte[] tampered = "{\"orderId\":43,\"ok\":true}".getBytes(StandardCharsets.UTF_8);
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), header, DELIVERY_ID, TS, tampered, NOW, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(List.of(SECRET), header, "dlv_999", TS, BODY, NOW, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), header, DELIVERY_ID, TS + 1, BODY, NOW, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), "v1=%%%", DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), "v2=" + KNOWN_SIGNATURE, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(List.of(SECRET), null, DELIVERY_ID, TS, BODY, NOW, TOLERANCE))
        .isFalse();
  }

  @Test
  void rejectsTimestampOutsideToleranceInBothDirections() {
    String header = "v1=" + KNOWN_SIGNATURE;
    Instant tooLate = Instant.ofEpochSecond(TS + 301);
    Instant tooEarly = Instant.ofEpochSecond(TS - 301);
    Instant edge = Instant.ofEpochSecond(TS + 300);
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), header, DELIVERY_ID, TS, BODY, tooLate, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), header, DELIVERY_ID, TS, BODY, tooEarly, TOLERANCE))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                List.of(SECRET), header, DELIVERY_ID, TS, BODY, edge, TOLERANCE))
        .isTrue();
  }
}
