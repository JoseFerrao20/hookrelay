package io.github.joseferrao20.hookrelay.common;

/** Validation patterns shared by the endpoint and event DTOs. */
public final class EventTypes {

  public static final String PATTERN = "^[A-Za-z0-9._-]{1,100}$";
  public static final String PATTERN_OR_WILDCARD = "^(\\*|[A-Za-z0-9._-]{1,100})$";

  private EventTypes() {}
}
