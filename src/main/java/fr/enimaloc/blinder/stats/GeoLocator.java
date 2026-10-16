package fr.enimaloc.blinder.stats;

import java.util.Optional;

public interface GeoLocator {

    Optional<GeoLocation> locate(String ip);
}
