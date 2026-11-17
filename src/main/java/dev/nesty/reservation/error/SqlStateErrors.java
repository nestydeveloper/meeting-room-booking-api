package dev.nesty.reservation.error;

import java.sql.SQLException;

/**
 * Detects the PostgreSQL SQLSTATE codes this API maps to domain failures.
 *
 * <p>The driver wraps the original {@link SQLException} several levels deep (Spring's
 * {@code DataIntegrityViolationException} → Hibernate's {@code ConstraintViolationException} →
 * {@code PSQLException}), so the whole cause chain is inspected.
 */
public final class SqlStateErrors {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FOREIGN_KEY_VIOLATION = "23503";
    public static final String EXCLUSION_VIOLATION = "23P01";
    /** Two transactions inserted into the same exclusion index and PostgreSQL killed one. */
    public static final String DEADLOCK_DETECTED = "40P01";

    private SqlStateErrors() {
    }

    public static boolean is(Throwable error, String sqlState) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
