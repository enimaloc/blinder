package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.stats.StatsPeriod;
import fr.enimaloc.blinder.stats.StatsQuery;
import fr.enimaloc.blinder.stats.StatsScope;
import fr.enimaloc.blinder.tag.TagService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Statistiques (spec §7), mêmes requêtes que le tableau de bord du panel. */
@RestController
@RequestMapping("/_/api/v1/stats")
@RequiredArgsConstructor
public class StatsApiController {

    private final CurrentUser currentUser;
    private final LinkService links;
    private final TagService tags;
    private final StatsQuery query;

    public record TotalsResource(long total, Map<String, Long> byOutcome, long uniqueIps, long bots) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatsResource(int days, TotalsResource totals, List<StatsQuery.DayPoint> daily,
                                List<StatsQuery.Count> countries, List<StatsQuery.Count> referers,
                                List<StatsQuery.Count> browsers, List<StatsQuery.Count> os,
                                List<StatsQuery.Count> topLinks) {
    }

    @GetMapping
    public StatsResource stats(@RequestParam(name = "link", required = false) UUID linkId,
                               @RequestParam(name = "tag", required = false) UUID tagId,
                               @RequestParam(name = "days", required = false) Integer days) {
        if (linkId != null && tagId != null) {
            throw new FieldErrorException("tag", "Incompatible avec link");
        }
        User owner = currentUser.require();
        StatsScope scope = linkId != null ? StatsScope.link(links.get(owner, linkId).getId())
                : tagId != null ? StatsScope.tag(tags.get(owner, tagId).getId()) : StatsScope.all();
        StatsPeriod period = StatsPeriod.of(days);
        StatsQuery.Totals totals = query.totals(owner, scope, period);
        Map<String, Long> byOutcome = new LinkedHashMap<>();
        totals.byOutcome().forEach((outcome, count) -> byOutcome.put(outcome.name(), count));
        return new StatsResource(period.days(),
                new TotalsResource(totals.total(), byOutcome, totals.uniqueIps(), totals.bots()),
                query.daily(owner, scope, period), query.topCountries(owner, scope, period),
                query.topReferers(owner, scope, period), query.browsers(owner, scope, period),
                query.oss(owner, scope, period),
                linkId == null ? query.topLinks(owner, scope, period) : null);
    }
}
