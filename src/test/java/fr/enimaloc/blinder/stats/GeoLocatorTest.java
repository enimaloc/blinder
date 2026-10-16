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

    private static Path testDatabase() throws Exception {
        return Path.of(GeoLocatorTest.class.getResource("/geoip/GeoIP2-City-Test.mmdb").toURI());
    }

    @Test
    void locatesKnownAddressWithCountryAndCity() throws Exception {
        try (MaxMindGeoLocator locator = new MaxMindGeoLocator(testDatabase())) {
            assertThat(locator.locate("81.2.69.142")).contains(new GeoLocation("GB", "London"));
        }
    }

    @Test
    void absentPublicAddressIsEmpty() throws Exception {
        try (MaxMindGeoLocator locator = new MaxMindGeoLocator(testDatabase())) {
            assertThat(locator.locate("8.8.8.8")).isEmpty();
        }
    }

    @Test
    void privateAndInvalidAddressesAreEmptyWithoutException() throws Exception {
        try (MaxMindGeoLocator locator = new MaxMindGeoLocator(testDatabase())) {
            for (String ip : new String[]{"10.0.0.1", "192.168.1.1", "127.0.0.1", "::1", "pas-une-ip", "", "999.1.1.1", null}) {
                assertThat(locator.locate(ip)).as("ip=%s", ip).isEmpty();
            }
        }
    }

    @Test
    void configBuildsMaxMindLocatorFromRealDatabase() throws Exception {
        var locator = config.geoLocator(new GeoIpProperties(testDatabase()));
        try {
            assertThat(locator).isInstanceOf(MaxMindGeoLocator.class);
            assertThat(locator.locate("81.2.69.142")).isPresent();
        } finally {
            if (locator instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    @Test
    void closeIsCleanAndLocateAfterCloseStaysEmpty() throws Exception {
        MaxMindGeoLocator locator = new MaxMindGeoLocator(testDatabase());
        locator.close();
        assertThat(locator.locate("81.2.69.142")).isEmpty();
    }
}
