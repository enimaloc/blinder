package fr.enimaloc.blinder.stats;

import java.util.Optional;

public class NoopGeoLocator implements GeoLocator {

    @Override
    public Optional<GeoLocation> locate(String ip) {
        return Optional.empty();
    }
}
