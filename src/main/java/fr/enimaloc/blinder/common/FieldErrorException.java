package fr.enimaloc.blinder.common;

/** Erreur de validation métier rattachée à un champ de formulaire. */
public class FieldErrorException extends RuntimeException {

    private final String field;

    public FieldErrorException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
