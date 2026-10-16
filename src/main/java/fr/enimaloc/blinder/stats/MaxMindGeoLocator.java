package fr.enimaloc.blinder.stats;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import fr.enimaloc.blinder.common.IpLiterals;
import fr.enimaloc.blinder.link.preview.PublicAddressPolicy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Optional;

/** Localisation via une base MaxMind (GeoLite2 City ou Country) ; jamais de DNS, adresses publiques seulement. */
public class MaxMindGeoLocator implements GeoLocator, AutoCloseable {

    private final DatabaseReader reader;
    private final boolean city;
    private final PublicAddressPolicy policy = new PublicAddressPolicy();

    public MaxMindGeoLocator(Path database) throws IOException {
        this.reader = new DatabaseReader.Builder(database.toFile()).withCache(new CHMCache()).build();
        this.city = reader.metadata().databaseType().contains("City");
    }

    @Override
    public Optional<GeoLocation> locate(String ip) {
        try {
            Optional<byte[]> bytes = IpLiterals.parse(ip);
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            InetAddress address = InetAddress.getByAddress(bytes.get()); // octets : aucune résolution DNS
            if (!policy.allowed(address)) {
                return Optional.empty();
            }
            if (city) {
                return reader.tryCity(address).flatMap(r -> {
                    String country = r.country().isoCode();
                    return country == null ? Optional.empty()
                            : Optional.of(new GeoLocation(country.toUpperCase(java.util.Locale.ROOT), r.city().name()));
                });
            }
            return reader.tryCountry(address).flatMap(r -> {
                String country = r.country().isoCode();
                return country == null ? Optional.empty()
                        : Optional.of(new GeoLocation(country.toUpperCase(java.util.Locale.ROOT), null));
            });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
