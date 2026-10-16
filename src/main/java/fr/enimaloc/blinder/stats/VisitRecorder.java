package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.access.DenyReason;
import fr.enimaloc.blinder.access.VisitContext;
import fr.enimaloc.blinder.redirect.AccessDenied;
import fr.enimaloc.blinder.redirect.LinkChallenged;
import fr.enimaloc.blinder.redirect.LinkResolved;
import fr.enimaloc.blinder.redirect.LinkThrottled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Traduit les événements de redirection en enregistrements ; ne fait qu'un {@code offer} non bloquant. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitRecorder {

    static final int MAX_USER_AGENT = 512;
    static final int MAX_REFERER = 1024;

    private final VisitWriter writer;

    @EventListener
    void on(LinkResolved event) {
        record(event.linkId(), event.context(), event.counted() ? VisitOutcome.REDIRECTED : VisitOutcome.PREVIEWED, null);
    }

    @EventListener
    void on(AccessDenied event) {
        record(event.linkId(), event.context(), VisitOutcome.DENIED, event.reason());
    }

    @EventListener
    void on(LinkChallenged event) {
        record(event.linkId(), event.context(),
                event.rejected() ? VisitOutcome.PASSWORD_REJECTED : VisitOutcome.CHALLENGED, null);
    }

    @EventListener
    void on(LinkThrottled event) {
        record(event.linkId(), event.context(), VisitOutcome.THROTTLED, null);
    }

    private void record(UUID linkId, VisitContext ctx, VisitOutcome outcome, DenyReason reason) {
        try {
            writer.offer(new VisitRecord(linkId, ctx.now(), outcome, reason, ctx.httpMethod(), ctx.bot(),
                    ctx.clientIp(), truncate(ctx.userAgent(), MAX_USER_AGENT), truncate(ctx.referer(), MAX_REFERER)));
        } catch (RuntimeException e) {
            log.warn("Visite non enregistrée : {}", e.toString());
        }
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
