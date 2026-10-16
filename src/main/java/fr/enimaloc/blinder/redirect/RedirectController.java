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
import fr.enimaloc.blinder.security.RetryAfter;
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

import java.net.URI;
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
    private final LinkMetaResolver metas;

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
                events.publishEvent(new LinkThrottled(link.getId(), context));
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

        if (!context.countable() && isAlive(decision)) {
            Optional<LinkMeta> meta = metas.forLink(link);
            if (meta.isPresent()) {
                publishOutcome(decision, link, context, false);
                return metaPage(request, meta.get());
            }
        }

        if (decision instanceof Allow && context.countable() && showsInterstitial(link, context)) {
            return interstitial(request, link);
        }

        boolean counted = false;
        if (decision instanceof Allow && context.countable()) {
            counted = usageCounter.consume(link, context.now());
            if (!counted) {
                decision = new Deny(DenyReason.EXHAUSTED);
            }
        }

        publishOutcome(decision, link, context, counted);
        return switch (decision) {
            case Allow allow -> !context.countable() && hasUsageLimits(link)
                    ? preview()
                    : redirectTo(RedirectPolicy.target(link, request.getParameterMap()),
                            RedirectPolicy.effectiveStatus(link, context.httpMethod()));
            case Deny(DenyReason reason) -> denied(domain.get(), link, reason);
            case Challenge challenge -> passwordPage(request, challenge.rejected());
        };
    }

    /** Allow → LinkResolved ; Deny → AccessDenied ; Challenge → LinkChallenged. */
    private void publishOutcome(AccessDecision decision, Link link, VisitContext context, boolean counted) {
        switch (decision) {
            case Allow allow -> events.publishEvent(new LinkResolved(link.getId(), context, counted));
            case Deny(DenyReason reason) -> events.publishEvent(new AccessDenied(link.getId(), reason, context));
            case Challenge c -> events.publishEvent(new LinkChallenged(link.getId(), context, c.rejected()));
        }
    }

    /** Lien existant et ouvert à quelqu'un : autorisé, protégé par mot de passe, ou réservé (appareils/IP). */
    private static boolean isAlive(AccessDecision decision) {
        return decision instanceof Allow || decision instanceof Challenge
                || decision instanceof Deny deny && deny.reason() == DenyReason.FORBIDDEN;
    }

    private static ModelAndView metaPage(HttpServletRequest request, LinkMeta meta) {
        ModelAndView mv = new ModelAndView("public/meta");
        mv.addObject("meta", meta);
        mv.addObject("pageUrl", request.getRequestURL().toString());
        mv.setStatus(HttpStatus.OK);
        return mv;
    }

    /** Page intermédiaire : GET comptable, lien sans mot de passe (le formulaire de mot de passe confirme déjà). */
    private static boolean showsInterstitial(Link link, VisitContext context) {
        return link.isInterstitial() && link.getPasswordHash() == null && "GET".equals(context.httpMethod());
    }

    /** N'affiche l'URL complète que si le lien n'a pas de limite d'usage (sinon elle contournerait le compteur). */
    private static ModelAndView interstitial(HttpServletRequest request, Link link) {
        String query = request.getQueryString();
        ModelAndView mv = new ModelAndView("public/interstitial");
        mv.addObject("action", request.getRequestURI() + (query == null ? "" : "?" + query));
        mv.addObject("targetHost", URI.create(link.getTargetUrl()).getHost());
        mv.addObject("targetUrl", hasUsageLimits(link) ? null : link.getTargetUrl());
        mv.addObject("seconds", link.getInterstitialSeconds());
        mv.setStatus(HttpStatus.OK);
        return mv;
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
        String query = RedirectPolicy.withoutParameters(request.getQueryString(), RedirectPolicy.NEVER_FORWARDED);
        mv.addObject("action", request.getRequestURI() + (query == null ? "" : "?" + query));
        mv.addObject("rejected", rejected);
        mv.setStatus(HttpStatus.OK);
        return mv;
    }

    private static ModelAndView tooManyAttempts(HttpServletResponse response, Duration retryAfter) {
        RetryAfter.set(response, retryAfter);
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
        return redirectTo(url, HttpStatus.FOUND);
    }

    private static ModelAndView redirectTo(String url, HttpStatus status) {
        RedirectView view = new RedirectView(url);
        view.setStatusCode(status);
        view.setExposeModelAttributes(false);
        view.setExpandUriTemplateVariables(false);
        return new ModelAndView(view);
    }
}
