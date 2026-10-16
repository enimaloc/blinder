package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.access.AccessDecision;
import fr.enimaloc.blinder.access.AccessEvaluator;
import fr.enimaloc.blinder.access.Allow;
import fr.enimaloc.blinder.access.Challenge;
import fr.enimaloc.blinder.access.Deny;
import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.access.VisitContext;
import fr.enimaloc.blinder.common.HostNormalizer;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.link.SlugPolicy;
import fr.enimaloc.blinder.link.UsageCounter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import java.time.Duration;
import java.util.Optional;

@Controller
@RequiredArgsConstructor
public class RedirectController {

    private final DomainRepository domains;
    private final LinkRepository links;
    private final SlugPolicy slugs;
    private final AccessEvaluator evaluator;
    private final UsageCounter usageCounter;
    private final VisitContextFactory contexts;
    private final ApplicationEventPublisher events;
    private final LinkPasswordThrottle throttle;

    @RequestMapping(value = "/{slug}", method = {RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST})
    public ModelAndView redirect(@PathVariable String slug, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        Optional<Domain> domain = domains.findByHost(HostNormalizer.normalize(request.getServerName()))
                .filter(Domain::isEnabled);
        if (domain.isEmpty()) {
            return notFound();
        }
        Optional<Link> found = links.findByDomainAndSlug(domain.get(), slugs.normalize(slug));
        if (found.isEmpty()) {
            return unknownLink(domain.get());
        }

        Link link = found.get();
        VisitContext context = contexts.from(request);
        boolean passwordAttempt = context.submittedPassword() != null && link.getPasswordHash() != null;
        if (passwordAttempt) {
            Optional<Duration> blocked = throttle.tryReserve(link.getId(), context.clientIp());
            if (blocked.isPresent()) {
                return tooManyAttempts(response, blocked.get());
            }
        }

        AccessDecision decision;
        try {
            decision = evaluator.evaluate(link, context);
        } catch (RuntimeException | Error e) {
            if (passwordAttempt) {
                throttle.abandoned(link.getId(), context.clientIp());
            }
            throw e;
        }
        if (passwordAttempt) {
            if (decision instanceof Allow) {
                throttle.succeeded(link.getId(), context.clientIp());
            } else if (decision instanceof Challenge challenge && challenge.rejected()) {
                throttle.rejected(link.getId(), context.clientIp());
            } else {
                throttle.abandoned(link.getId(), context.clientIp());
            }
        }

        boolean counted = false;
        if (decision instanceof Allow && context.countable()) {
            counted = usageCounter.consume(link, context.now());
            if (!counted) {
                decision = new Deny(DenyReason.EXHAUSTED);
            }
        }

        return switch (decision) {
            case Allow allow -> {
                events.publishEvent(new LinkResolved(link.getId(), context, counted));
                yield !context.countable() && hasUsageLimits(link) ? preview() : redirectTo(link.getTargetUrl());
            }
            case Deny(DenyReason reason) -> {
                events.publishEvent(new AccessDenied(link.getId(), reason, context));
                yield denied(domain.get(), link, reason);
            }
            case Challenge challenge -> passwordPage(request, challenge.rejected());
        };
    }

    private ModelAndView denied(Domain domain, Link link, DenyReason reason) {
        if (link.getDeniedUrl() != null) {
            return redirectTo(link.getDeniedUrl());
        }
        if (reason.gone()) {
            ModelAndView mv = new ModelAndView("public/gone");
            mv.setStatus(HttpStatus.GONE);
            return mv;
        }
        return unknownLink(domain);
    }

    /** Outcome shared by unknown slugs and disabled / not-yet-active / forbidden links so they are indistinguishable. */
    private static ModelAndView unknownLink(Domain domain) {
        String notFoundUrl = domain.getNotFoundUrl();
        return notFoundUrl != null ? redirectTo(notFoundUrl) : notFound();
    }

    private static ModelAndView notFound() {
        ModelAndView mv = new ModelAndView("public/not-found");
        mv.setStatus(HttpStatus.NOT_FOUND);
        return mv;
    }

    /** Formulaire de mot de passe : la cible n'apparaît jamais dans le modèle. */
    private static ModelAndView passwordPage(HttpServletRequest request, boolean rejected) {
        ModelAndView mv = new ModelAndView("public/password");
        mv.addObject("action", request.getRequestURI());
        mv.addObject("rejected", rejected);
        mv.setStatus(HttpStatus.OK);
        return mv;
    }

    private static ModelAndView tooManyAttempts(HttpServletResponse response, Duration retryAfter) {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        ModelAndView mv = new ModelAndView("public/too-many-attempts");
        mv.setStatus(HttpStatus.TOO_MANY_REQUESTS);
        return mv;
    }

    /** Les visites non comptées (bots, HEAD) ne doivent pas révéler la cible d'un lien limité. */
    private static boolean hasUsageLimits(Link link) {
        return link.getMaxUses() != null || link.getTtlAfterFirstUse() != null;
    }

    private static ModelAndView preview() {
        ModelAndView mv = new ModelAndView("public/preview");
        mv.setStatus(HttpStatus.OK);
        return mv;
    }

    private static ModelAndView redirectTo(String url) {
        RedirectView view = new RedirectView(url);
        view.setStatusCode(HttpStatus.FOUND);
        view.setExposeModelAttributes(false);
        return new ModelAndView(view);
    }
}
