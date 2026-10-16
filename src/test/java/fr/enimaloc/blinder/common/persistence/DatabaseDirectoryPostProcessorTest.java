package fr.enimaloc.blinder.common.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDirectoryPostProcessorTest {

    @Test
    void parsesRelativeSqlitePathIgnoringQueryString() {
        assertThat(DatabaseDirectoryPostProcessor.parentDirectoryOf(
                "jdbc:sqlite:./data/blinder.sqlite?journal_mode=WAL&foreign_keys=true"))
                .contains(Path.of("./data"));
    }

    @Test
    void parsesAbsolutePaths() {
        assertThat(DatabaseDirectoryPostProcessor.parentDirectoryOf("jdbc:sqlite:/var/lib/blinder/b.sqlite"))
                .contains(Path.of("/var/lib/blinder"));
        assertThat(DatabaseDirectoryPostProcessor.parentDirectoryOf("jdbc:h2:file:/var/lib/blinder/h2;MODE=PostgreSQL"))
                .contains(Path.of("/var/lib/blinder"));
    }

    @Test
    void ignoresInMemoryOtherDatabasesAndBareFileNames() {
        for (String url : new String[]{null, "", "jdbc:sqlite::memory:", "jdbc:sqlite:file::memory:?cache=shared",
                "jdbc:sqlite:", "jdbc:h2:mem:blinder;DB_CLOSE_DELAY=-1", "jdbc:postgresql://localhost/blinder",
                "jdbc:sqlite:blinder.sqlite"}) {
            assertThat(DatabaseDirectoryPostProcessor.parentDirectoryOf(url)).as(String.valueOf(url)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void createsMissingDirectoryFromEnvironment(@TempDir Path tmp) {
        Path parent = tmp.resolve("nested/data");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:sqlite:" + parent.resolve("b.sqlite") + "?journal_mode=WAL");

        new DatabaseDirectoryPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(Files.isDirectory(parent)).isTrue();
    }
}
