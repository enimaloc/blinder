package fr.enimaloc.blinder.redirect;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
class RedirectEventLogger {

    @EventListener
    void on(LinkResolved event) {
        log.debug("Link {} resolved (counted={})", event.linkId(), event.counted());
    }

    @EventListener
    void on(AccessDenied event) {
        log.debug("Link {} denied: {}", event.linkId(), event.reason());
    }

    @EventListener
    void on(LinkChallenged event) {
        log.debug("Link {} challenged (rejected={})", event.linkId(), event.rejected());
    }

    @EventListener
    void on(LinkThrottled event) {
        log.debug("Link {} throttled", event.linkId());
    }
}
