package io.github.joseferrao20.hookrelay.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressClassifierTest {

  private static boolean blocked(String literal) throws Exception {
    return AddressClassifier.isBlocked(InetAddress.getByName(literal));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "127.0.0.1",
        "127.255.255.254",
        "::1",
        "0.0.0.0",
        "0.1.2.3",
        "::",
        "10.0.0.1",
        "10.255.255.255",
        "172.16.0.1",
        "172.31.255.255",
        "192.168.0.1",
        "192.168.255.255",
        "fc00::1",
        "fd12:3456:789a::1",
        "169.254.169.254",
        "169.254.0.1",
        "fe80::1",
        "100.64.0.1",
        "100.127.255.255",
        "224.0.0.1",
        "239.255.255.255",
        "ff02::1",
        "255.255.255.255",
        "::ffff:127.0.0.1",
        "::ffff:10.1.2.3",
        "::ffff:169.254.169.254",
        "::ffff:192.168.1.1",
        "::127.0.0.1",
        "::10.0.0.1",
        "64:ff9b::7f00:1",
        "2002:7f00:1::1",
      })
  void blocksInternalAddresses(String literal) throws Exception {
    assertThat(blocked(literal)).as(literal).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "172.15.255.255",
        "172.32.0.1",
        "100.63.255.255",
        "100.128.0.1",
        "169.253.1.1",
        "192.169.0.1",
        "11.0.0.1",
        "223.255.255.255",
        "2606:4700:4700::1111",
        "2001:4860:4860::8888",
        "::ffff:8.8.8.8",
      })
  void allowsPublicAddresses(String literal) throws Exception {
    assertThat(blocked(literal)).as(literal).isFalse();
  }
}
