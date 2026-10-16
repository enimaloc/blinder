package fr.enimaloc.blinder.stats;

/** Issue d'une requête sur un lien court. */
public enum VisitOutcome {
    REDIRECTED("Redirigée"), PREVIEWED("Aperçu"), CHALLENGED("Mot de passe demandé"),
    PASSWORD_REJECTED("Mot de passe refusé"), DENIED("Refusée"), THROTTLED("Bloquée");

    private final String label;

    VisitOutcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** {@code true} : la visite a été refusée (compte pour le déclencheur « refus répétés »). */
    public boolean refusal() {
        return this == DENIED || this == PASSWORD_REJECTED || this == THROTTLED;
    }
}
