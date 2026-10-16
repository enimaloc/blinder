package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.tag.Tag;
import tools.jackson.databind.JsonNode;

/** Corps de création d'un tag ; la mise à jour partielle suit {@link #merge}. */
public record TagInput(String name, String color) {

    /**
     * Fusion d'un PATCH : un champ absent garde la valeur courante, {@code color} nul ou vide retire la couleur,
     * {@code name} nul est refusé par le service ("Nom requis").
     */
    static TagInput merge(JsonNode patch, Tag current) {
        if (!patch.isObject()) {
            throw new FieldErrorException("body", "Objet JSON attendu");
        }
        String name = current.getName();
        if (patch.has("name")) {
            name = text(patch.get("name"), "name");
        }
        String color = current.getColor();
        if (patch.has("color")) {
            color = text(patch.get("color"), "color");
        }
        return new TagInput(name, color);
    }

    private static String text(JsonNode node, String field) {
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new FieldErrorException(field, "Texte attendu");
        }
        return node.asString();
    }
}
