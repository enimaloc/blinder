package fr.enimaloc.blinder;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Scénario de {@link QueryCountTest} sur PostgreSQL (Testcontainers, ignoré sans Docker). */
@Tag("postgres")
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class PostgresQueryCountTest extends QueryCountTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
}
