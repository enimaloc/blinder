package fr.enimaloc.blinder.common.persistence;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * SQLite et H2 (mode fichier) ne créent pas le dossier parent de leur base : on le crée avant
 * l'initialisation du DataSource pour que le premier démarrage fonctionne sur un checkout vierge.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public class DatabaseDirectoryPostProcessor implements EnvironmentPostProcessor {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String H2_FILE_PREFIX = "jdbc:h2:file:";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        parentDirectoryOf(environment.getProperty("spring.datasource.url")).ifPresent(directory -> {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new UncheckedIOException("Impossible de créer le dossier de la base : " + directory, e);
            }
        });
    }

    /** @return le dossier parent du fichier de base désigné par l'URL JDBC, ou vide (mémoire, autre SGBD, pas de parent). */
    static Optional<Path> parentDirectoryOf(String jdbcUrl) {
        if (jdbcUrl == null) {
            return Optional.empty();
        }
        String location;
        if (jdbcUrl.startsWith(SQLITE_PREFIX)) {
            location = stripAfter(jdbcUrl.substring(SQLITE_PREFIX.length()), '?');
            if (location.startsWith("file:")) {
                location = location.substring("file:".length());
            }
        } else if (jdbcUrl.startsWith(H2_FILE_PREFIX)) {
            location = stripAfter(jdbcUrl.substring(H2_FILE_PREFIX.length()), ';');
        } else {
            return Optional.empty();
        }
        if (location.isBlank() || location.startsWith(":") || location.contains(":memory:")) {
            return Optional.empty();
        }
        return Optional.ofNullable(Path.of(location).getParent());
    }

    private static String stripAfter(String value, char separator) {
        int index = value.indexOf(separator);
        return index >= 0 ? value.substring(0, index) : value;
    }
}
