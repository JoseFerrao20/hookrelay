package io.github.joseferrao20.hookrelay.security;

import java.net.InetAddress;

/**
 * Decides whether an IP address is a destination hookrelay must never connect to: loopback,
 * unspecified, private, link-local (including cloud metadata), CGNAT, multicast and broadcast, plus
 * IPv6 forms that embed such IPv4 addresses (mapped, compatible, NAT64, 6to4).
 */
public final class AddressClassifier {

  private AddressClassifier() {}

  public static boolean isBlocked(InetAddress address) {
    byte[] b = address.getAddress();
    return b.length == 4 ? blockedV4(b[0], b[1]) : blockedV6(b);
  }

  private static boolean blockedV4(byte first, byte second) {
    int a = first & 0xff;
    int b = second & 0xff;
    return a == 0 // 0.0.0.0/8 unspecified ("this network")
        || a == 10 // 10/8
        || a == 127 // loopback
        || (a == 169 && b == 254) // link-local, cloud metadata
        || (a == 172 && b >= 16 && b <= 31) // 172.16/12
        || (a == 192 && b == 168) // 192.168/16
        || (a == 100 && b >= 64 && b <= 127) // CGNAT 100.64/10
        || a >= 224; // multicast 224/4, reserved 240/4, broadcast
  }

  private static boolean blockedV6(byte[] b) {
    // ::, ::1 and IPv4-compatible (::a.b.c.d) and IPv4-mapped (::ffff:a.b.c.d): judge the IPv4 part
    if (allZero(b, 0, 10)
        && ((b[10] == 0 && b[11] == 0) || (b[10] == (byte) 0xff && b[11] == (byte) 0xff))) {
      return blockedV4(b[12], b[13]);
    }
    // NAT64 64:ff9b::/96
    if (b[0] == 0x00
        && b[1] == 0x64
        && b[2] == (byte) 0xff
        && b[3] == (byte) 0x9b
        && allZero(b, 4, 12)) {
      return blockedV4(b[12], b[13]);
    }
    // 6to4 2002::/16 embeds the IPv4 address in bytes 2..5
    if (b[0] == 0x20 && b[1] == 0x02) {
      return blockedV4(b[2], b[3]);
    }
    int b0 = b[0] & 0xff;
    int b1 = b[1] & 0xff;
    return (b0 & 0xfe) == 0xfc // fc00::/7 unique local
        || (b0 == 0xfe && (b1 & 0xc0) == 0x80) // fe80::/10 link-local
        || (b0 == 0xfe && (b1 & 0xc0) == 0xc0) // fec0::/10 site-local (deprecated)
        || b0 == 0xff; // ff00::/8 multicast
  }

  private static boolean allZero(byte[] b, int from, int to) {
    for (int i = from; i < to; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }
}
