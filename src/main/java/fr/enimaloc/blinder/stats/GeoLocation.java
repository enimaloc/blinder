package fr.enimaloc.blinder.stats;

/** @param country code ISO-3166 alpha-2 en majuscules ; @param city peut être nul */
public record GeoLocation(String country, String city) {
}
