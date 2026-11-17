package dev.nesty.reservation.error;

/**
 * The request conflicts with the current state, such as a duplicate name. Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
