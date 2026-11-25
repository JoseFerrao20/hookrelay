package io.github.joseferrao20.hookrelay.endpoint;

import io.github.joseferrao20.hookrelay.common.SecretCipher;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Encrypts endpoint secrets stored in plaintext by E1-E3. Idempotent and safe with several
 * instances: every write is conditional on the value that was read.
 */
@Component
public class SecretBackfill implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SecretBackfill.class);

  private record Row(UUID id, String secret, String previousSecret) {}

  private final JdbcTemplate jdbc;
  private final SecretCipher cipher;

  public SecretBackfill(JdbcTemplate jdbc, SecretCipher cipher) {
    this.jdbc = jdbc;
    this.cipher = cipher;
  }

  @Override
  public void run(ApplicationArguments args) {
    int rows = encryptPlaintextSecrets();
    if (rows > 0) {
      log.info("Encrypted {} plaintext endpoint secret value(s)", rows);
    }
  }

  /** Returns the number of columns encrypted. */
  public int encryptPlaintextSecrets() {
    List<Row> pending =
        jdbc.query(
            """
            SELECT id, secret, previous_secret FROM endpoints
            WHERE secret NOT LIKE 'v1:%'
               OR (previous_secret IS NOT NULL AND previous_secret NOT LIKE 'v1:%')
            """,
            (rs, i) ->
                new Row(
                    rs.getObject("id", UUID.class),
                    rs.getString("secret"),
                    rs.getString("previous_secret")));
    int count = 0;
    for (Row row : pending) {
      if (!SecretCipher.isEncrypted(row.secret())) {
        count +=
            jdbc.update(
                "UPDATE endpoints SET secret = ? WHERE id = ? AND secret = ?",
                cipher.encrypt(row.id(), row.secret()),
                row.id(),
                row.secret());
      }
      if (row.previousSecret() != null && !SecretCipher.isEncrypted(row.previousSecret())) {
        count +=
            jdbc.update(
                "UPDATE endpoints SET previous_secret = ? WHERE id = ? AND previous_secret = ?",
                cipher.encrypt(row.id(), row.previousSecret()),
                row.id(),
                row.previousSecret());
      }
    }
    return count;
  }
}
