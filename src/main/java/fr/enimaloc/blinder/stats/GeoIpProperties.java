package fr.enimaloc.blinder.stats;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** @param database chemin d'une base MaxMind (.mmdb) ; {@code null} = pas de géolocalisation */
@ConfigurationProperties("blinder.geoip")
public record GeoIpProperties(Path database) {
}
