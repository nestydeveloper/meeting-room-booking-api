package dev.nesty.reservation.error;

/**
 * Requested resource does not exist. Mapped to HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
