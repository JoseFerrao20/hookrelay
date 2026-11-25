package io.github.joseferrao20.hookrelay.common;

import io.github.joseferrao20.hookrelay.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption of endpoint secrets at rest.
 *
 * <p>Stored form: {@code v1:<base64(nonce12 || ciphertext || tag16)>}. The nonce is random per
 * value and the endpoint id is bound as additional authenticated data, so a ciphertext copied to
 * another row does not decrypt.
 */
@Component
public class SecretCipher {

  public static final String PREFIX = "v1:";

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_BYTES = 32;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKeySpec key;

  @Autowired
  public SecretCipher(SecurityProperties props) {
    this(props.secretEncryptionKey());
  }

  /** {@code base64Key} must be the Base64 of exactly 32 bytes. */
  public SecretCipher(String base64Key) {
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "hookrelay.security.secret-encryption-key is not valid Base64; generate one with"
              + " 'openssl rand -base64 32'",
          e);
    }
    if (raw.length != KEY_BYTES) {
      Arrays.fill(raw, (byte) 0);
      throw new IllegalStateException(
          "hookrelay.security.secret-encryption-key must be the Base64 of exactly 32 bytes"
              + " (AES-256); generate one with 'openssl rand -base64 32'");
    }
    this.key = new SecretKeySpec(raw, "AES");
    Arrays.fill(raw, (byte) 0);
  }

  public static boolean isEncrypted(String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }

  public String encrypt(UUID endpointId, String plaintext) {
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(endpointId));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[NONCE_BYTES + ciphertext.length];
      System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
      System.arraycopy(ciphertext, 0, out, NONCE_BYTES, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not encrypt secret", e);
    }
  }

  /**
   * @throws IllegalArgumentException if the value is not in the stored format, was tampered with,
   *     or belongs to another endpoint. The message never contains the value.
   */
  public String decrypt(UUID endpointId, String stored) {
    if (!isEncrypted(stored)) {
      throw new IllegalArgumentException("Secret is not in the encrypted v1 format");
    }
    try {
      byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      if (in.length < NONCE_BYTES + TAG_BITS / 8) {
        throw new IllegalArgumentException("Encrypted secret is too short");
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
      cipher.updateAAD(aad(endpointId));
      byte[] plain = cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Could not decrypt secret (corrupt or wrong key/id)");
    }
  }

  private static byte[] aad(UUID endpointId) {
    return endpointId.toString().getBytes(StandardCharsets.UTF_8);
  }
}
