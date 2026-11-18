package io.github.joseferrao20.hookrelay.common;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The annotated {@link JsonNode} must be a JSON object. {@code null} is considered valid. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JsonObject.Validator.class)
public @interface JsonObject {

  String message() default "must be a JSON object";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Validates that a {@link JsonNode} is an object node. */
  class Validator implements ConstraintValidator<JsonObject, JsonNode> {
    @Override
    public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
      return value == null || value.isObject();
    }
  }
}
