package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.stats.StatsPeriod;
import fr.enimaloc.blinder.stats.StatsQuery;
import fr.enimaloc.blinder.stats.StatsScope;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagService;
import fr.enimaloc.blinder.stats.StatsView;
import fr.enimaloc.blinder.stats.VisitWriter;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequestMapping("/_")
@RequiredArgsConstructor
public class StatsAdminController {

    private final CurrentUser currentUser;
    private final LinkService links;
    private final TagService tags;
    private final StatsQuery query;
    private final VisitWriter visitWriter;
    private final Clock clock;
    private final BlinderProperties blinder;

    @GetMapping("/stats")
    public String global(@RequestParam(name = "days", required = false) Integer days,
                         @RequestParam(name = "tag", required = false) UUID tagId, Model model) {
        User owner = currentUser.require();
        Tag tag = tagId == null ? null : tags.get(owner, tagId);
        return render(owner, null, tag, StatsPeriod.of(days), model);
    }

    @GetMapping("/links/{id}/stats")
    public String forLink(@PathVariable UUID id, @RequestParam(name = "days", required = false) Integer days,
                          Model model) {
        User owner = currentUser.require();
        Link link = links.get(owner, id);
        return render(owner, link, null, StatsPeriod.of(days), model);
    }

    @GetMapping("/stats.csv")
    public void globalCsv(@RequestParam(name = "days", required = false) Integer days,
                          @RequestParam(name = "tag", required = false) UUID tagId, HttpServletResponse response)
            throws IOException {
        User owner = currentUser.require();
        if (tagId == null) {
            csv(owner, StatsScope.all(), "all", StatsPeriod.of(days), response);
        } else {
            Tag tag = tags.get(owner, tagId);
            csv(owner, StatsScope.tag(tag.getId()), "tag-" + tag.getName(), StatsPeriod.of(days), response);
        }
    }

    @GetMapping("/links/{id}/stats.csv")
    public void linkCsv(@PathVariable UUID id, @RequestParam(name = "days", required = false) Integer days,
                        HttpServletResponse response) throws IOException {
        User owner = currentUser.require();
        Link link = links.get(owner, id);
        csv(owner, StatsScope.link(link.getId()), link.getSlug(), StatsPeriod.of(days), response);
    }

    private String render(User owner, Link link, Tag tag, StatsPeriod period, Model model) {
        StatsScope scope = link != null ? StatsScope.link(link.getId())
                : tag != null ? StatsScope.tag(tag.getId()) : StatsScope.all();
        StatsView view = query.view(owner, scope, period);
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("labels", view.daily().stream().map(p -> p.day().toString()).toList());
        chart.put("redirected", view.daily().stream().map(StatsQuery.DayPoint::redirected).toList());
        chart.put("refused", view.daily().stream().map(StatsQuery.DayPoint::refused).toList());
        chart.put("other", view.daily().stream().map(StatsQuery.DayPoint::other).toList());
        model.addAttribute("view", view);
        model.addAttribute("link", link);
        model.addAttribute("chart", chart);
        model.addAttribute("periods", List.of(StatsPeriod.values()));
        model.addAttribute("dropped", visitWriter.dropped());
        model.addAttribute("tag", tag);
        model.addAttribute("tagQuery", tag == null ? "" : "&tag=" + tag.getId());
        if (link == null) {
            model.addAttribute("allTags", tags.list(owner));
        }
        model.addAttribute("basePath", link == null ? "/_/stats" : "/_/links/" + link.getId() + "/stats");
        return "admin/stats/dashboard";
    }

    private void csv(User owner, StatsScope scope, String name, StatsPeriod period, HttpServletResponse response)
            throws IOException {
        // Les slugs sont validés à la saisie ; on assainit quand même pour l'en-tête.
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"blinder-stats-" + safe + "-" + LocalDate.now(clock.withZone(blinder.admin().timeZone())) + ".csv\"");
        query.exportCsv(owner, scope, period, response.getWriter());
    }
}
