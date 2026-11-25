package io.github.joseferrao20.hookrelay.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private final SecretCipher cipher = new SecretCipher(KEY);

  @Test
  void roundTrip() {
    UUID id = UUID.randomUUID();
    String stored = cipher.encrypt(id, "whsec_abc");
    assertThat(stored).startsWith("v1:").doesNotContain("whsec_abc");
    assertThat(cipher.decrypt(id, stored)).isEqualTo("whsec_abc");
  }

  @Test
  void nonceIsRandomPerValue() {
    UUID id = UUID.randomUUID();
    assertThat(cipher.encrypt(id, "same")).isNotEqualTo(cipher.encrypt(id, "same"));
  }

  @Test
  void storedLayoutIsNonceCiphertextTag() {
    String stored = cipher.encrypt(UUID.randomUUID(), "abcd");
    byte[] raw = Base64.getDecoder().decode(stored.substring(3));
    assertThat(raw).hasSize(12 + 4 + 16);
  }

  @Test
  void wrongAadFails() {
    String stored = cipher.encrypt(UUID.randomUUID(), "whsec_abc");
    assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), stored))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tamperedCiphertextFails() {
    UUID id = UUID.randomUUID();
    byte[] raw = Base64.getDecoder().decode(cipher.encrypt(id, "whsec_abc").substring(3));
    raw[raw.length / 2] ^= 0x01;
    String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
    assertThatThrownBy(() -> cipher.decrypt(id, tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("whsec_abc");
  }

  @Test
  void wrongKeyAndMalformedValuesFail() {
    UUID id = UUID.randomUUID();
    String stored = cipher.encrypt(id, "whsec_abc");
    SecretCipher other =
        new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]).replace('A', 'B'));
    assertThatThrownBy(() -> other.decrypt(id, stored))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cipher.decrypt(id, "plaintext"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cipher.decrypt(id, "v1:AAAA"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> cipher.decrypt(id, "v1:***"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void badKeyIsRejectedAtStartup() {
    assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
    assertThatThrownBy(() -> new SecretCipher("not base64 !!"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Base64");
    assertThatThrownBy(() -> new SecretCipher((String) null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new SecretCipher("")).isInstanceOf(IllegalStateException.class);
  }
}
