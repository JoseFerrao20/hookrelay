package io.github.joseferrao20.hookrelay.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures: {@code Base64(HMAC-SHA256(secret, "<deliveryId>.<timestamp>.<body>"))}, sent
 * as {@code Hookrelay-Signature: v1=<sig>} (several space-separated entries during a secret
 * rotation). The key is the UTF-8 bytes of the whole secret string, {@code whsec_} prefix included,
 * and the body is the exact bytes that were sent.
 */
public final class WebhookSignature {

  public static final String VERSION = "v1";
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  private static final String ALGORITHM = "HmacSHA256";

  private WebhookSignature() {}

  /** Returns the Base64 (standard, padded) signature, without the {@code v1=} prefix. */
  public static String sign(String secret, String deliveryId, long timestamp, byte[] body) {
    return Base64.getEncoder().encodeToString(hmac(secret, deliveryId, timestamp, body));
  }

  /** Builds the header value: one {@code v1=<sig>} entry per secret, joined by a space. */
  public static String header(
      Collection<String> secrets, String deliveryId, long timestamp, byte[] body) {
    StringBuilder header = new StringBuilder();
    for (String secret : secrets) {
      if (!header.isEmpty()) {
        header.append(' ');
      }
      header.append(VERSION).append('=').append(sign(secret, deliveryId, timestamp, body));
    }
    return header.toString();
  }

  /**
   * Verifies a {@code Hookrelay-Signature} header. True when the timestamp is within {@code
   * tolerance} of {@code now} and at least one {@code v1=} entry matches the signature made with at
   * least one of {@code secrets}. Signatures are compared in constant time.
   */
  public static boolean verify(
      Collection<String> secrets,
      String header,
      String deliveryId,
      long timestamp,
      byte[] body,
      Instant now,
      Duration tolerance) {
    if (header == null || header.isBlank() || secrets.isEmpty()) {
      return false;
    }
    long skew = Math.abs(now.getEpochSecond() - timestamp);
    if (skew > tolerance.toSeconds()) {
      return false;
    }
    byte[][] expected = new byte[secrets.size()][];
    int i = 0;
    for (String secret : secrets) {
      expected[i++] = hmac(secret, deliveryId, timestamp, body);
    }
    boolean match = false;
    for (String entry : header.trim().split("\\s+")) {
      if (!entry.startsWith(VERSION + "=")) {
        continue;
      }
      byte[] candidate;
      try {
        candidate = Base64.getDecoder().decode(entry.substring(VERSION.length() + 1));
      } catch (IllegalArgumentException e) {
        continue;
      }
      for (byte[] e : expected) {
        match |= MessageDigest.isEqual(e, candidate);
      }
    }
    return match;
  }

  private static byte[] hmac(String secret, String deliveryId, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      mac.update(deliveryId.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      mac.update(body);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }
}
