package fr.enimaloc.blinder.redirect;

/** Métadonnées d'aperçu servies aux bots (toutes facultatives, au moins une présente). */
public record LinkMeta(String title, String description, String imageUrl) {
}
