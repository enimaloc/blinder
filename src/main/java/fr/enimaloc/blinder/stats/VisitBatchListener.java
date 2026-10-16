package fr.enimaloc.blinder.stats;

import java.util.List;

/** Notifié après chaque lot de visites écrit (dans le thread d'écriture : ne doit pas bloquer). */
public interface VisitBatchListener {

    void onVisits(List<Visit> visits);
}
