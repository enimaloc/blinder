package fr.enimaloc.blinder.stats;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class GeoConfig {

    @Bean(destroyMethod = "")
    public GeoLocator geoLocator(GeoIpProperties properties) {
        Path database = properties.database();
        if (database == null) {
            return new NoopGeoLocator();
        }
        if (!Files.isReadable(database)) {
            log.warn("Base GeoIP illisible ({}), géolocalisation désactivée", database);
            return new NoopGeoLocator();
        }
        try {
            return new MaxMindGeoLocator(database);
        } catch (Exception e) {
            log.warn("Base GeoIP invalide ({}), géolocalisation désactivée : {}", database, e.toString());
            return new NoopGeoLocator();
        }
    }
}
