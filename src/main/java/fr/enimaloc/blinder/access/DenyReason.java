package fr.enimaloc.blinder.access;

public enum DenyReason {
    DISABLED(false, "Désactivé"),
    NOT_YET(false, "Pas encore actif"),
    EXPIRED(true, "Expiré"),
    EXHAUSTED(true, "Épuisé"),
    /** Protection non satisfaite sans moyen interactif (allowlist IP / appareils seuls). */
    FORBIDDEN(false, "Interdit"),
    /** La destination n'a pas renvoyé l'en-tête attendu (absent, différent, injoignable, délai dépassé). */
    HEADER_CHECK_FAILED(false, "Contrôle de destination refusé");

    private final boolean gone;
    private final String label;

    DenyReason(boolean gone, String label) {
        this.gone = gone;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** {@code true} : le lien a existé et est mort (410) ; {@code false} : on masque son existence (404). */
    public boolean gone() {
        return gone;
    }
}
