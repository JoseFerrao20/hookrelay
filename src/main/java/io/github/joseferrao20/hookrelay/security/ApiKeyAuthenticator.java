package io.github.joseferrao20.hookrelay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Checks API keys against SHA-256 digests; the plaintext keys are not kept in memory. */
public class ApiKeyAuthenticator {

  private final List<byte[]> digests;

  public ApiKeyAuthenticator(List<String> apiKeys) {
    this.digests = apiKeys.stream().map(ApiKeyAuthenticator::sha256).toList();
  }

  /** Constant-time per comparison; every configured key is always compared. */
  public boolean isValid(String candidate) {
    if (candidate == null || candidate.isEmpty()) {
      return false;
    }
    byte[] candidateDigest = sha256(candidate);
    boolean match = false;
    for (byte[] digest : digests) {
      match |= MessageDigest.isEqual(digest, candidateDigest);
    }
    return match;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
