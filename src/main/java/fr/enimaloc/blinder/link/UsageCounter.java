package fr.enimaloc.blinder.link;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UsageCounter {

    private final LinkRepository links;

    /** @return {@code true} si un usage a été consommé, {@code false} si le lien est épuisé. */
    @Transactional
    public boolean consume(Link link, Instant now) {
        return links.consume(link.getId(), now) == 1;
    }
}
