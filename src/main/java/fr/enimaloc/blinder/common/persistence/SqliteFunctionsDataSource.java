package fr.enimaloc.blinder.common.persistence;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Source des connexions physiques SQLite du pool : enregistre les fonctions de Blinder sur chacune. */
class SqliteFunctionsDataSource extends DelegatingDataSource {

    SqliteFunctionsDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withFunctions(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withFunctions(super.getConnection(username, password));
    }

    private static Connection withFunctions(Connection connection) throws SQLException {
        try {
            SqliteUnicodeLower.register(connection);
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
