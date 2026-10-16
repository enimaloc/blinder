package fr.enimaloc.blinder.stats;

/** Issue d'une requête sur un lien court. */
public enum VisitOutcome {
    REDIRECTED, PREVIEWED, CHALLENGED, PASSWORD_REJECTED, DENIED, THROTTLED;

    /** {@code true} : la visite a été refusée (compte pour le déclencheur « refus répétés »). */
    public boolean refusal() {
        return this == DENIED || this == PASSWORD_REJECTED || this == THROTTLED;
    }
}
