package fr.enimaloc.blinder.common.persistence;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.internal.util.JdbcExceptionHelper;

/**
 * SQLite dialect that reports constraint failures as {@link ConstraintViolationException}
 * (hence Spring's {@code DataIntegrityViolationException}), like the other databases.
 * sqlite-jdbc returns extended result codes (e.g. 2067) whose low byte is SQLITE_CONSTRAINT (19),
 * which the stock community dialect does not translate.
 *
 * <p>{@code lower()} stays {@code lower()}: on every pooled connection it is replaced by a Unicode-aware Java function
 * ({@link SqliteFunctionsPostProcessor}), since SQLite's built-in one only folds ASCII letters. A connection without
 * that registration falls back to the built-in function (ASCII only) instead of failing.
 */
public class BlinderSqliteDialect extends SQLiteDialect {

    private static final int SQLITE_CONSTRAINT = 19;

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        SQLExceptionConversionDelegate base = super.buildSQLExceptionConversionDelegate();
        return (sqlException, message, sql) -> {
            if ((JdbcExceptionHelper.extractErrorCode(sqlException) & 0xFF) == SQLITE_CONSTRAINT) {
                String constraint = getViolatedConstraintNameExtractor().extractConstraintName(sqlException);
                return new ConstraintViolationException(message, sqlException, sql, constraint);
            }
            return base.convert(sqlException, message, sql);
        };
    }
}
