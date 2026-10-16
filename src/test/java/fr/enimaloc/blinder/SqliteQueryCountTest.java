package fr.enimaloc.blinder;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Scénario de {@link QueryCountTest} sur SQLite (profil {@code sqlite}, fichier temporaire, pool de taille 1). */
@ActiveProfiles("sqlite")
class SqliteQueryCountTest extends QueryCountTest {

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempFile("blinder-test-", ".sqlite");
        file.toFile().deleteOnExit();
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + file + "?journal_mode=WAL&foreign_keys=true");
    }
}
