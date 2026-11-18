package io.github.joseferrao20.hookrelay.event;

/** Outcome of an event submission: {@code created} is false for an idempotent replay. */
public record EventCreationResult(EventResponse event, boolean created) {}
