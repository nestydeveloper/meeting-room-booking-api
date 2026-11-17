package dev.nesty.reservation.error;

/**
 * The request is semantically invalid in a way bean validation cannot express. Mapped to HTTP 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
