package fr.enimaloc.blinder.notification;

public enum TriggerType {
    VISIT("Visite"), THRESHOLD("Seuil de visites atteint"), EXHAUSTED("Lien épuisé"), EXPIRED("Lien expiré"),
    DENIALS("Refus répétés"), TEST("Test");

    private final String label;

    TriggerType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
