package fr.enimaloc.blinder.stats;

/** Période d'un tableau de bord, en jours (le jour courant compte). */
public enum StatsPeriod {
    D7(7), D30(30), D90(90), D365(365);

    private final int days;

    StatsPeriod(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }

    /** @return la période correspondant à {@code days} ; inconnu ou {@code null} : 30 jours. */
    public static StatsPeriod of(Integer days) {
        if (days != null) {
            for (StatsPeriod period : values()) {
                if (period.days == days) {
                    return period;
                }
            }
        }
        return D30;
    }
}
