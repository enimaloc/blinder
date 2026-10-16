package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.link.preview.LinkPreviewService;
import fr.enimaloc.blinder.notification.LinkNotificationService;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Enregistre un lien et ses réglages annexes en une seule transaction (tout ou rien). */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkEditService {

    private final LinkService links;
    private final LinkNotificationService notifications;
    private final LinkPreviewService previews;

    @Transactional
    public Link create(User owner, LinkForm form) {
        notifications.validate(owner, form);
        Link created = links.create(owner, form);
        notifications.apply(owner, created, form);
        return created;
    }

    @Transactional
    public Link update(User owner, UUID id, LinkForm form) {
        notifications.validate(owner, form);
        Link updated = links.update(owner, id, form);
        notifications.apply(owner, updated, form);
        return updated;
    }

    @Transactional
    public void resetUsage(User owner, UUID id) {
        links.resetUsage(owner, id);
    }

    /**
     * À appeler après la validation de la transaction : l'aperçu fait des appels réseau.
     * Un échec de l'aperçu ne doit jamais faire échouer l'enregistrement.
     *
     * @param previousTarget cible avant modification, {@code null} à la création
     */
    public void refreshPreviewAfterSave(User owner, Link saved, String previousTarget) {
        if (previousTarget != null && !previousTarget.equals(saved.getTargetUrl())) {
            previews.discard(saved.getId());
        }
        if (saved.getMetaMode() != MetaMode.PASSTHROUGH) {
            return;
        }
        try {
            previews.refresh(owner, saved.getId());
        } catch (RuntimeException e) {
            log.warn("Preview refresh failed for link {}", saved.getId(), e);
        }
    }
}
