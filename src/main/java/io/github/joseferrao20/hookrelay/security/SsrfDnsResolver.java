package io.github.joseferrao20.hookrelay.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * DNS resolver for the webhook HTTP client. The client connects only to the addresses returned
 * here, so a name cannot resolve to something different between the check and the connection.
 */
public class SsrfDnsResolver implements DnsResolver {

  private final SsrfGuard guard;

  public SsrfDnsResolver(SsrfGuard guard) {
    this.guard = guard;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    return guard.resolveChecked(host);
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    return host;
  }
}
