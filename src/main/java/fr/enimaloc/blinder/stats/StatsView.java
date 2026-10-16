package fr.enimaloc.blinder.stats;

import java.util.List;

/** Tout ce qu'affiche un tableau de bord. */
public record StatsView(StatsPeriod period,
                        List<StatsQuery.DayPoint> daily,
                        StatsQuery.Totals totals,
                        List<StatsQuery.Count> countries,
                        List<StatsQuery.Count> referers,
                        List<StatsQuery.Count> browsers,
                        List<StatsQuery.Count> oss,
                        List<StatsQuery.Count> links,
                        List<StatsQuery.VisitRow> recent,
                        boolean exceedsRetention) {
}
