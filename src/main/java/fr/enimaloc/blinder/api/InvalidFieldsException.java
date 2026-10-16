package fr.enimaloc.blinder.api;

import java.util.Map;

/** Plusieurs erreurs de champ à la fois (validation Bean Validation d'une entrée d'API) → 400 {@code errors}. */
public class InvalidFieldsException extends RuntimeException {

    private final Map<String, String> errors;

    public InvalidFieldsException(Map<String, String> errors) {
        super("Données invalides");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> errors() {
        return errors;
    }
}
