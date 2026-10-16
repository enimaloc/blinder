package fr.enimaloc.blinder.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoLocatorTest {

    private final GeoConfig config = new GeoConfig();

    @Test
    void noopLocatorIsAlwaysEmpty() {
        assertThat(new NoopGeoLocator().locate("8.8.8.8")).isEmpty();
    }

    @Test
    void missingDatabaseFallsBackToNoop() {
        assertThat(config.geoLocator(new GeoIpProperties(Path.of("/does/not/exist.mmdb"))))
                .isInstanceOf(NoopGeoLocator.class);
    }

    @Test
    void unconfiguredDatabaseFallsBackToNoop() {
        assertThat(config.geoLocator(new GeoIpProperties(null))).isInstanceOf(NoopGeoLocator.class);
    }

    @Test
    void invalidDatabaseFailsCleanlyAndFallsBackToNoop(@TempDir Path dir) throws Exception {
        Path bogus = dir.resolve("bogus.mmdb");
        Files.writeString(bogus, "ceci n'est pas une base mmdb");
        assertThatThrownBy(() -> new MaxMindGeoLocator(bogus)).isInstanceOf(Exception.class);
        assertThat(config.geoLocator(new GeoIpProperties(bogus))).isInstanceOf(NoopGeoLocator.class);
    }
}
