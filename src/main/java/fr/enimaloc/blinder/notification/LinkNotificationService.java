package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LinkNotificationService {

    private final LinkNotificationSettingsRepository settings;
    private final NotificationChannelRepository channels;
    private final LinkRepository links;

    @Transactional(readOnly = true)
    public Optional<LinkNotificationSettings> settingsFor(UUID linkId) {
        return settings.findWithChannelsByLinkId(linkId);
    }

    /** Vérifie les canaux choisis avant toute écriture du lien. */
    @Transactional(readOnly = true)
    public void validate(User owner, LinkForm form) {
        resolveChannels(owner, form);
    }

    @Transactional
    public void apply(User owner, Link link, LinkForm form) {
        Set<NotificationChannel> selected = resolveChannels(owner, form);
        boolean anyTrigger = form.isNotifyExhausted() || form.isNotifyExpired() || form.isNotifyDenials()
                || form.isNotifyEachVisit() || form.getNotifyThreshold() != null;
        Optional<LinkNotificationSettings> existing = settings.findWithChannelsByLinkId(link.getId());
        if (!anyTrigger && selected.isEmpty()) {
            existing.ifPresent(settings::delete);
            return;
        }
        LinkNotificationSettings s = existing.orElseGet(
                () -> new LinkNotificationSettings(links.getReferenceById(link.getId())));
        s.setOnExhausted(form.isNotifyExhausted());
        s.setOnExpired(form.isNotifyExpired());
        s.setOnDenials(form.isNotifyDenials());
        s.setOnEachVisit(form.isNotifyEachVisit());
        s.setVisitThreshold(form.getNotifyThreshold());
        s.getChannels().clear();
        s.getChannels().addAll(selected);
        settings.save(s);
    }

    @Transactional(readOnly = true)
    public void fillForm(LinkForm form, UUID linkId) {
        settings.findWithChannelsByLinkId(linkId).ifPresent(s -> {
            form.setNotifyExhausted(s.isOnExhausted());
            form.setNotifyExpired(s.isOnExpired());
            form.setNotifyDenials(s.isOnDenials());
            form.setNotifyEachVisit(s.isOnEachVisit());
            form.setNotifyThreshold(s.getVisitThreshold());
            form.setNotifyChannelIds(s.getChannels().stream().map(NotificationChannel::getId)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        });
    }

    @Transactional(readOnly = true)
    public void preselect(User owner, LinkForm form) {
        form.setNotifyChannelIds(channels.findByOwnerAndPreselectedTrue(owner).stream()
                .filter(NotificationChannel::isEnabled)
                .map(NotificationChannel::getId).collect(Collectors.toCollection(LinkedHashSet::new)));
        form.setNotifyExhausted(true);
        form.setNotifyExpired(true);
        form.setNotifyDenials(true);
    }

    private Set<NotificationChannel> resolveChannels(User owner, LinkForm form) {
        Set<NotificationChannel> resolved = new HashSet<>();
        Set<UUID> ids = form.getNotifyChannelIds();
        if (ids == null) {
            return resolved;
        }
        for (UUID id : ids) {
            resolved.add(channels.findByIdAndOwner(id, owner)
                    .orElseThrow(() -> new FieldErrorException("notifyChannelIds", "Canal inconnu.")));
        }
        return resolved;
    }
}
