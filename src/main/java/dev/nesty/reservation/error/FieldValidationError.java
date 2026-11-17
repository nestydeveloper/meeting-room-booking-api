package dev.nesty.reservation.error;

/**
 * One entry of the {@code errors} extension member of a validation problem.
 */
public record FieldValidationError(String field, String message) {
}
