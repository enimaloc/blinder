package fr.enimaloc.blinder.persistence;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ActiveProfiles("sqlite")
class SqlitePersistenceTest extends AbstractPersistenceTest {

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) throws IOException {
        Path file = Files.createTempFile("blinder-test-", ".sqlite");
        file.toFile().deleteOnExit();
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + file + "?journal_mode=WAL&foreign_keys=true");
    }

    @Override
    boolean supportsOverlappingTransactions() {
        return false;
    }
}
