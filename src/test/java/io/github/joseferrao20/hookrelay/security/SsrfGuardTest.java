package io.github.joseferrao20.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.joseferrao20.hookrelay.common.FieldValidationException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SsrfGuardTest {

  private static final Map<String, String> DNS =
      Map.of(
          "public.example", "93.184.216.34",
          "internal.example", "10.1.2.3",
          "metadata.example", "169.254.169.254",
          "loop.example", "127.0.0.1");

  private static InetAddress[] lookup(String host) throws UnknownHostException {
    String ip = DNS.get(host);
    if (ip == null) {
      // literals resolve to themselves, like InetAddress.getAllByName
      return InetAddress.getAllByName(
          host.matches("[0-9.:\\[\\]a-f]+") ? host : "unresolvable.invalid");
    }
    return new InetAddress[] {InetAddress.getByName(ip)};
  }

  private final SsrfGuard strict = new SsrfGuard(false, Set.of(), SsrfGuardTest::lookup);

  @Test
  void rejectsLiteralIpsAndLocalhostNamesOnSave() {
    for (String url :
        new String[] {
          "http://127.0.0.1/x",
          "http://169.254.169.254/latest",
          "http://[::1]/x",
          "http://[::ffff:10.0.0.1]/x",
          "http://localhost/x",
          "http://LOCALHOST:8080/x",
          "http://localhost./x",
          "http://app.localhost/x",
          "http://10.0.0.5:9000/x",
          "http://192.168.1.1/x",
          "http://100.64.1.1/x",
          "http://2130706433/x",
        }) {
      assertThatThrownBy(() -> strict.validateForSave(url))
          .as(url)
          .isInstanceOfSatisfying(
              FieldValidationException.class,
              e -> {
                assertThat(e.getField()).isEqualTo("url");
                assertThat(e.getMessage())
                    .isEqualTo("must not target a private, loopback or link-local address");
              });
    }
  }

  @Test
  void rejectsNamesThatResolveToBlockedAddressesOnSave() {
    assertThatThrownBy(() -> strict.validateForSave("https://internal.example/hook"))
        .isInstanceOf(FieldValidationException.class);
    assertThatThrownBy(() -> strict.validateForSave("https://metadata.example/hook"))
        .isInstanceOf(FieldValidationException.class);
  }

  @Test
  void acceptsPublicAndUnresolvableOnSave() {
    assertThatCode(() -> strict.validateForSave("https://public.example/hook"))
        .doesNotThrowAnyException();
    assertThatCode(() -> strict.validateForSave("https://not-yet-created.invalid/hook"))
        .doesNotThrowAnyException();
    assertThatCode(() -> strict.validateForSave("http://93.184.216.34/hook"))
        .doesNotThrowAnyException();
  }

  @Test
  void connectTimeResolutionNamesHostAndAddress() throws Exception {
    assertThatThrownBy(() -> strict.resolveChecked("loop.example"))
        .isInstanceOf(BlockedDestinationException.class)
        .hasMessage("Blocked destination: loop.example resolves to 127.0.0.1");
    assertThatThrownBy(() -> strict.resolveChecked("127.0.0.1"))
        .isInstanceOf(BlockedDestinationException.class)
        .hasMessage("Blocked destination: 127.0.0.1 resolves to 127.0.0.1");
    assertThatThrownBy(() -> strict.resolveChecked("localhost"))
        .isInstanceOf(BlockedDestinationException.class);
    assertThat(strict.resolveChecked("public.example")).hasSize(1);
  }

  @Test
  void aSingleBlockedAddressAmongSeveralBlocksTheHost() {
    SsrfGuard guard =
        new SsrfGuard(
            false,
            Set.of(),
            host ->
                new InetAddress[] {
                  InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")
                });
    assertThatThrownBy(() -> guard.resolveChecked("mixed.example"))
        .isInstanceOf(BlockedDestinationException.class)
        .hasMessageContaining("10.0.0.1");
  }

  @Test
  void allowedHostsBypassCaseInsensitively() throws Exception {
    SsrfGuard guard =
        new SsrfGuard(false, Set.of("Webhook-Sink", "loop.example"), SsrfGuardTest::lookup);
    assertThatCode(() -> guard.validateForSave("http://loop.example/x")).doesNotThrowAnyException();
    assertThatCode(() -> guard.validateForSave("http://LOOP.EXAMPLE/x")).doesNotThrowAnyException();
    assertThat(guard.resolveChecked("loop.example")).hasSize(1);
    assertThat(guard.isExempt("webhook-sink")).isTrue();
    // only exact names are exempt
    assertThat(guard.isExempt("evil.webhook-sink")).isFalse();
    assertThatThrownBy(() -> guard.validateForSave("http://internal.example/x"))
        .isInstanceOf(FieldValidationException.class);
  }

  @Test
  void allowPrivateNetworksDisablesTheCheck() throws Exception {
    SsrfGuard open = new SsrfGuard(true, Set.of(), SsrfGuardTest::lookup);
    assertThatCode(() -> open.validateForSave("http://127.0.0.1/x")).doesNotThrowAnyException();
    assertThat(open.resolveChecked("127.0.0.1")).hasSize(1);
  }
}
