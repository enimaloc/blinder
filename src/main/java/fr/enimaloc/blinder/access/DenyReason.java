package fr.enimaloc.blinder.access;

public enum DenyReason {
    DISABLED(false),
    NOT_YET(false),
    EXPIRED(true),
    EXHAUSTED(true);

    private final boolean gone;

    DenyReason(boolean gone) {
        this.gone = gone;
    }

    /** {@code true} : le lien a existé et est mort (410) ; {@code false} : on masque son existence (404). */
    public boolean gone() {
        return gone;
    }
}
