package io.github.joseferrao20.hookrelay.security;

import java.net.UnknownHostException;

/**
 * Thrown by the connect-time resolver when a host resolves to a blocked address. It is an {@link
 * UnknownHostException} so HTTP clients treat it as a failed lookup and never connect.
 */
public class BlockedDestinationException extends UnknownHostException {

  private static final long serialVersionUID = 1L;

  public BlockedDestinationException(String host, String address) {
    super("Blocked destination: " + host + " resolves to " + address);
  }
}
