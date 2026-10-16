package fr.enimaloc.blinder.common.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteUnicodeLowerTest {

    private static String lower(Connection connection, String value) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select lower('" + value + "'), lower(null)")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(2)).isNull();
            return result.getString(1);
        }
    }

    @Test
    void registeredConnectionLowersUnicodeLetters() throws SQLException {
        // Même chemin que le pool : la source des connexions physiques enregistre la fonction.
        try (Connection connection = new SqliteFunctionsDataSource(
                new DriverManagerDataSource("jdbc:sqlite::memory:")).getConnection()) {
            assertThat(lower(connection, "Promo ÉTÉ")).isEqualTo("promo été");
        }
    }

    @Test
    void connectionWithoutRegistrationFallsBackToTheBuiltInAsciiLower() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertThat(lower(connection, "Promo ÉTÉ")).isEqualTo("promo ÉtÉ");
        }
    }
}
