package fr.enimaloc.blinder.access;

public enum LinkStatus {
    ACTIVE("Actif"),
    PROTECTED("Protégé"),
    SCHEDULED("Programmé"),
    EXPIRED("Expiré"),
    EXHAUSTED("Épuisé"),
    DISABLED("Désactivé");

    private final String label;

    LinkStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
