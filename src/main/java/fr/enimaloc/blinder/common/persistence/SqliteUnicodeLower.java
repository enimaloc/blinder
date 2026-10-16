package fr.enimaloc.blinder.common.persistence;

import org.sqlite.Function;
import org.sqlite.SQLiteConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * {@code lower(x)} en minuscules Unicode : remplace, connexion par connexion, le {@code lower()} natif de SQLite qui ne
 * traite que l'ASCII. Enregistrée sur chaque connexion SQLite du pool ({@link SqliteFunctionsDataSource}) ; une connexion
 * qui ne l'a pas garde la fonction native au lieu d'échouer.
 */
final class SqliteUnicodeLower extends Function {

    static final String NAME = "lower";

    private static final int SQLITE_NULL = 5;

    /** Une instance par connexion : l'état d'appel de {@link Function} n'est pas partageable. */
    static void register(Connection connection) throws SQLException {
        Function.create(connection.unwrap(SQLiteConnection.class), NAME, new SqliteUnicodeLower(), 1,
                Function.FLAG_DETERMINISTIC);
    }

    @Override
    protected void xFunc() throws SQLException {
        if (value_type(0) == SQLITE_NULL) {
            result();
        } else {
            result(value_text(0).toLowerCase(Locale.ROOT));
        }
    }
}
