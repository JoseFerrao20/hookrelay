package io.github.joseferrao20.hookrelay.security;

import io.github.joseferrao20.hookrelay.common.FieldValidationException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSRF protection. {@link #validateForSave} is the early, user-friendly check; {@link
 * #resolveChecked} is the real guard, used by the HTTP client's DNS resolver so the connection can
 * only ever use addresses that were checked (this also defeats DNS rebinding).
 */
@Component
public class SsrfGuard {

  public static final String URL_MESSAGE =
      "must not target a private, loopback or link-local address";

  /** Resolves a host name to addresses; replaceable in tests. */
  @FunctionalInterface
  public interface Lookup {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final boolean allowPrivateNetworks;
  private final Set<String> allowedHosts;
  private final Lookup lookup;

  @Autowired
  public SsrfGuard(SecurityProperties props) {
    this(
        props.ssrf().allowPrivateNetworks(),
        props.ssrf().allowedHosts() == null ? Set.of() : Set.copyOf(props.ssrf().allowedHosts()),
        InetAddress::getAllByName);
  }

  public SsrfGuard(boolean allowPrivateNetworks, Set<String> allowedHosts, Lookup lookup) {
    this.allowPrivateNetworks = allowPrivateNetworks;
    this.allowedHosts =
        allowedHosts.stream().map(SsrfGuard::normalize).collect(Collectors.toUnmodifiableSet());
    this.lookup = lookup;
  }

  /** Lowercase, without a trailing dot or IPv6 brackets. */
  static String normalize(String host) {
    String h = host.trim().toLowerCase(Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]")) {
      h = h.substring(1, h.length() - 1);
    }
    while (h.endsWith(".")) {
      h = h.substring(0, h.length() - 1);
    }
    return h;
  }

  /** True when the host is exempt from the check (allowed host or private networks allowed). */
  public boolean isExempt(String host) {
    return allowPrivateNetworks || allowedHosts.contains(normalize(host));
  }

  static boolean isLocalhostName(String host) {
    return host.equals("localhost") || host.endsWith(".localhost");
  }

  /**
   * Resolves {@code host} and refuses (via {@link BlockedDestinationException}) if any address is
   * blocked. Exempt hosts are resolved without the check.
   */
  public InetAddress[] resolveChecked(String host) throws UnknownHostException {
    String h = normalize(host);
    if (isExempt(h)) {
      return lookup.resolve(host);
    }
    if (isLocalhostName(h)) {
      throw new BlockedDestinationException(host, "127.0.0.1");
    }
    InetAddress[] addresses = lookup.resolve(host);
    for (InetAddress a : addresses) {
      if (AddressClassifier.isBlocked(a)) {
        throw new BlockedDestinationException(host, a.getHostAddress());
      }
    }
    return addresses;
  }

  /**
   * Save-time check for a URL. Literal IPs and {@code localhost} names in blocked ranges and host
   * names that resolve to a blocked address are rejected; host names that do not resolve (yet) are
   * accepted, the connect-time check is the real guard.
   *
   * @throws FieldValidationException on field {@code url}
   */
  public void validateForSave(String url) {
    String host;
    try {
      host = URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return; // malformed URLs are rejected by bean validation
    }
    if (host == null || host.isBlank() || isExempt(host)) {
      return;
    }
    try {
      resolveChecked(host);
    } catch (BlockedDestinationException e) {
      throw new FieldValidationException("url", URL_MESSAGE);
    } catch (UnknownHostException e) {
      // not resolvable (yet): accepted
    }
  }
}
