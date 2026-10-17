package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LinkHeaderCheckService {

    private final LinkHeaderCheckRepository checks;

    @Transactional(readOnly = true)
    public Optional<LinkHeaderCheck> checkFor(UUID linkId) {
        return checks.findByLinkId(linkId);
    }

    /** Contrôle de chaque lien de {@code owner}, en une requête ; un lien sans contrôle (ou d'un autre) n'a pas d'entrée. */
    @Transactional(readOnly = true)
    public Map<UUID, LinkHeaderCheck> forLinks(User owner, Collection<UUID> linkIds) {
        if (linkIds.isEmpty()) {
            return Map.of();
        }
        return checks.findByOwnerAndLinkIdIn(owner, linkIds).stream()
                .collect(Collectors.toMap(LinkHeaderCheck::getLinkId, Function.identity()));
    }

    /** Valide les champs avant toute écriture du lien. */
    public void validate(String name, String value) {
        HeaderCheckRules.normalize(name, value);
    }

    /**
     * Enregistre, remplace ou supprime le contrôle du lien (déjà vérifié comme appartenant à l'appelant).
     *
     * @return {@code true} si le contrôle enregistré a changé
     */
    @Transactional
    public boolean apply(Link link, String name, String value) {
        Optional<HeaderCheckRules.Normalized> wanted = HeaderCheckRules.normalize(name, value);
        Optional<LinkHeaderCheck> existing = checks.findByLinkId(link.getId());
        if (wanted.isEmpty()) {
            existing.ifPresent(checks::delete);
            return existing.isPresent();
        }
        HeaderCheckRules.Normalized n = wanted.get();
        if (existing.isEmpty()) {
            checks.save(new LinkHeaderCheck(link.getId(), n.name(), n.value()));
            return true;
        }
        LinkHeaderCheck current = existing.get();
        if (current.getHeaderName().equals(n.name()) && current.getExpectedValue().equals(n.value())) {
            return false;
        }
        current.setHeaderName(n.name());
        current.setExpectedValue(n.value());
        checks.save(current);
        return true;
    }
}
