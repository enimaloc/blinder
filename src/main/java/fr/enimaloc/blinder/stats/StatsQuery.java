package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lecture des statistiques d'un propriétaire, pour tous ses liens, un seul lien ou les liens d'un tag (voir {@link StatsScope}). */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StatsQuery {

    static final String HEADER = "date,domaine,slug,issue,raison,methode,bot,ip,pays,ville,navigateur,os,referer,user_agent";
    private static final int TOP = 10;
    private static final int EXPORT_PAGE = 1000;

    public record DayPoint(LocalDate day, long redirected, long refused, long other) {
    }

    public record Totals(long total, Map<VisitOutcome, Long> byOutcome, long uniqueIps, long bots) {
    }

    public record Count(String label, long count) {
    }

    public record VisitRow(ZonedDateTime occurredAt, String domain, String slug, VisitOutcome outcome,
                           DenyReason denyReason, String ip, String country, String browser, String refererHost) {
    }

    private record Page(List<String[]> lines, Instant lastAt, UUID lastId) {
    }

    private final EntityManager em;
    private final StatsProperties stats;
    private final BlinderProperties blinder;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public List<DayPoint> daily(User owner, StatsScope scope, StatsPeriod period) {
        ZoneId zone = blinder.admin().timeZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = today.minusDays(period.days() - 1L);
        String jpql = "select s.day, s.outcome, sum(s.visits) from VisitDailyStat s where s.owner = :owner"
                + scope.clause("s")
                + " and s.day >= :first and s.day <= :last group by s.day, s.outcome";
        TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class)
                .setParameter("owner", owner).setParameter("first", first).setParameter("last", today);
        scope.bind(query);
        Map<LocalDate, long[]> byDay = new java.util.HashMap<>();
        for (Object[] r : query.getResultList()) {
            long[] cells = byDay.computeIfAbsent((LocalDate) r[0], d -> new long[3]);
            VisitOutcome outcome = (VisitOutcome) r[1];
            long n = ((Number) r[2]).longValue();
            cells[outcome == VisitOutcome.REDIRECTED ? 0 : outcome.refusal() ? 1 : 2] += n;
        }
        List<DayPoint> points = new ArrayList<>(period.days());
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            long[] c = byDay.getOrDefault(d, new long[3]);
            points.add(new DayPoint(d, c[0], c[1], c[2]));
        }
        return points;
    }

    public Totals totals(User owner, StatsScope scope, StatsPeriod period) {
        Map<VisitOutcome, Long> byOutcome = new EnumMap<>(VisitOutcome.class);
        long total = 0;
        for (Object[] r : visitQuery("select v.outcome, count(v) from Visit v", "group by v.outcome", owner, scope, period)
                .getResultList()) {
            long n = ((Number) r[1]).longValue();
            byOutcome.put((VisitOutcome) r[0], n);
            total += n;
        }
        Object[] extra = visitQuery("select count(distinct v.ip), sum(case when v.bot = true then 1 else 0 end) from Visit v",
                "", owner, scope, period).getSingleResult();
        long uniqueIps = extra[0] == null ? 0 : ((Number) extra[0]).longValue();
        long bots = extra[1] == null ? 0 : ((Number) extra[1]).longValue();
        return new Totals(total, byOutcome, uniqueIps, bots);
    }

    public List<Count> topCountries(User owner, StatsScope scope, StatsPeriod period) {
        return top("v.country", "Inconnu", owner, scope, period);
    }

    public List<Count> topReferers(User owner, StatsScope scope, StatsPeriod period) {
        return top("v.refererHost", "Direct", owner, scope, period);
    }

    public List<Count> browsers(User owner, StatsScope scope, StatsPeriod period) {
        return top("v.browser", "Inconnu", owner, scope, period);
    }

    public List<Count> oss(User owner, StatsScope scope, StatsPeriod period) {
        return top("v.os", "Inconnu", owner, scope, period);
    }

    /** Vue globale uniquement : libellé {@code domaine/slug}. */
    public List<Count> topLinks(User owner, StatsScope scope, StatsPeriod period) {
        TypedQuery<Object[]> query = visitQuery(
                "select d.host, l.slug, count(v) from Visit v join v.link l join l.domain d",
                "group by d.host, l.slug order by count(v) desc, d.host, l.slug", owner, scope, period);
        return query.setMaxResults(TOP).getResultList().stream()
                .map(r -> new Count(r[0] + "/" + r[1], ((Number) r[2]).longValue())).toList();
    }

    public List<VisitRow> recent(User owner, StatsScope scope, StatsPeriod period, int limit) {
        ZoneId zone = blinder.admin().timeZone();
        return visitQuery("select v from Visit v join fetch v.link l join fetch l.domain",
                "order by v.occurredAt desc, v.id", Visit.class, owner, scope, period)
                .setMaxResults(limit).getResultList().stream().map(v -> row(v, zone)).toList();
    }

    public boolean exceedsRetention(StatsPeriod period) {
        var retention = stats.retention();
        return !retention.isZero() && period.days() > retention.toDays();
    }

    /**
     * Écrit toutes les visites de la période, de la plus ancienne à la plus récente. Chaque page est lue dans sa
     * propre transaction en lecture seule ; l'écriture vers le client se fait hors transaction pour ne pas
     * garder la connexion à la base pendant un envoi lent.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void exportCsv(User owner, StatsScope scope, StatsPeriod period, Writer out) {
        ZoneId zone = blinder.admin().timeZone();
        CsvWriter csv = new CsvWriter(out);
        TransactionTemplate reads = new TransactionTemplate(transactions.getTransactionManager());
        reads.setReadOnly(true);
        // Bornes calculées une fois : l'export reste cohérent même s'il dure.
        Instant to = clock.instant();
        Instant from = periodStart(period);
        try {
            out.write(HEADER + "\r\n");
            Instant lastAt = null;
            UUID lastId = null;
            while (true) {
                String after = lastAt == null ? ""
                        : "and (v.occurredAt > :lastAt or (v.occurredAt = :lastAt and v.id > :lastId)) ";
                Instant cursorAt = lastAt;
                UUID cursorId = lastId;
                Page page = reads.execute(status -> {
                    TypedQuery<Visit> query = visitQuery("select v from Visit v join fetch v.link l join fetch l.domain",
                            after + "order by v.occurredAt, v.id", Visit.class, owner, scope, from, to);
                    if (cursorAt != null) {
                        query.setParameter("lastAt", cursorAt).setParameter("lastId", cursorId);
                    }
                    List<Visit> visits = query.setMaxResults(EXPORT_PAGE).getResultList();
                    List<String[]> lines = new ArrayList<>(visits.size());
                    for (Visit v : visits) {
                        lines.add(new String[]{DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(v.getOccurredAt().atZone(zone)),
                                v.getLink().getDomain().getHost(), v.getLink().getSlug(), v.getOutcome().name(),
                                v.getDenyReason() == null ? null : v.getDenyReason().name(), v.getHttpMethod(),
                                String.valueOf(v.isBot()), v.getIp(), v.getCountry(), v.getCity(), v.getBrowser(),
                                v.getOs(), v.getReferer(), v.getUserAgent()});
                    }
                    Visit last = visits.isEmpty() ? null : visits.getLast();
                    return new Page(lines, last == null ? null : last.getOccurredAt(), last == null ? null : last.getId());
                });
                for (String[] line : page.lines()) {
                    csv.row(line);
                }
                if (page.lines().size() < EXPORT_PAGE) {
                    break;
                }
                lastAt = page.lastAt();
                lastId = page.lastId();
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public StatsView view(User owner, StatsScope scope, StatsPeriod period) {
        return new StatsView(period, daily(owner, scope, period), totals(owner, scope, period),
                topCountries(owner, scope, period), topReferers(owner, scope, period),
                browsers(owner, scope, period), oss(owner, scope, period),
                scope.linkId() == null ? topLinks(owner, scope, period) : List.of(),
                recent(owner, scope, period, 50), exceedsRetention(period));
    }

    private List<Count> top(String column, String fallback, User owner, StatsScope scope, StatsPeriod period) {
        return visitQuery("select " + column + ", count(v) from Visit v",
                "group by " + column + " order by count(v) desc, " + column, owner, scope, period)
                .setMaxResults(TOP).getResultList().stream()
                .map(r -> new Count(r[0] == null ? fallback : r[0].toString(), ((Number) r[1]).longValue())).toList();
    }

    private static VisitRow row(Visit v, ZoneId zone) {
        return new VisitRow(v.getOccurredAt().atZone(zone), v.getLink().getDomain().getHost(), v.getLink().getSlug(),
                v.getOutcome(), v.getDenyReason(), v.getIp(), v.getCountry(), v.getBrowser(), v.getRefererHost());
    }

    private Instant periodStart(StatsPeriod period) {
        ZoneId zone = blinder.admin().timeZone();
        return LocalDate.now(clock.withZone(zone)).minusDays(period.days() - 1L).atStartOfDay(zone).toInstant();
    }

    private TypedQuery<Object[]> visitQuery(String select, String tail, User owner, StatsScope scope, StatsPeriod period) {
        return visitQuery(select, tail, Object[].class, owner, scope, period);
    }

    private <T> TypedQuery<T> visitQuery(String select, String tail, Class<T> type, User owner, StatsScope scope,
                                         StatsPeriod period) {
        return visitQuery(select, tail, type, owner, scope, periodStart(period), clock.instant());
    }

    /** Fragments JPQL constants uniquement ; toutes les valeurs passent par des paramètres. */
    private <T> TypedQuery<T> visitQuery(String select, String tail, Class<T> type, User owner, StatsScope scope,
                                         Instant from, Instant to) {
        String jpql = select + " where v.owner = :owner" + scope.clause("v")
                + " and v.occurredAt >= :from and v.occurredAt <= :to " + tail;
        TypedQuery<T> query = em.createQuery(jpql, type)
                .setParameter("owner", owner).setParameter("from", from).setParameter("to", to);
        scope.bind(query);
        return query;
    }
}
