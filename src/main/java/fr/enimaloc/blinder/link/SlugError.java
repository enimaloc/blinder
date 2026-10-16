package fr.enimaloc.blinder.link;

public enum SlugError {
    INVALID_FORMAT("Slug invalide : lettres minuscules, chiffres, « . », « _ », « - », 64 caractères max, commence par une lettre ou un chiffre"),
    RESERVED("Ce slug est réservé");

    private final String message;

    SlugError(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
