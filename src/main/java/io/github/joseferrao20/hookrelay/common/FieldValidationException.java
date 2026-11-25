package io.github.joseferrao20.hookrelay.common;

/** A request field failed a check that bean validation cannot express; answered with 400. */
public class FieldValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  public FieldValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String getField() {
    return field;
  }
}
