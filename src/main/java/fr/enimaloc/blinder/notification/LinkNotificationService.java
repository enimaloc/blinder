package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LinkNotificationService {

    private final LinkNotificationSettingsRepository settings;
    private final NotificationChannelRepository channels;
    private final LinkRepository links;
    private final FiredTriggerRepository firedTriggers;

    @Transactional(readOnly = true)
    public Optional<LinkNotificationSettings> settingsFor(UUID linkId) {
        return settings.findWithChannelsByLinkId(linkId);
    }

    /**
     * Réglages (canaux chargés) de chaque lien de {@code owner}, en une requête ; un lien sans réglage, ou d'un autre
     * propriétaire, n'a pas d'entrée.
     */
    @Transactional(readOnly = true)
    public Map<UUID, LinkNotificationSettings> settingsFor(User owner, Collection<UUID> linkIds) {
        if (linkIds.isEmpty()) {
            return Map.of();
        }
        return settings.findWithChannelsByOwnerAndLinkIdIn(owner, linkIds).stream()
                // Fusion explicite : en cas de doublon (que la contrainte d'unicité en base interdit), on garde le premier
                // réglage plutôt que de faire échouer toute la page de liste.
                .collect(Collectors.toMap(s -> s.getLink().getId(), Function.identity(), (first, duplicate) -> first));
    }

    /** Vérifie les canaux choisis avant toute écriture du lien. */
    @Transactional(readOnly = true)
    public void validate(User owner, LinkForm form) {
        resolveChannels(owner, form);
    }

    /** @return {@code true} si les réglages enregistrés ont changé (création, modification ou suppression) */
    @Transactional
    public boolean apply(User owner, Link link, LinkForm form) {
        Set<NotificationChannel> selected = resolveChannels(owner, form);
        boolean anyTrigger = form.isNotifyExhausted() || form.isNotifyExpired() || form.isNotifyDenials()
                || form.isNotifyEachVisit() || form.getNotifyThreshold() != null;
        Optional<LinkNotificationSettings> existing = settings.findWithChannelsByLinkId(link.getId());
        rearm(link.getId(), existing.orElse(null), form);
        if (!anyTrigger && selected.isEmpty()) {
            existing.ifPresent(settings::delete);
            return existing.isPresent();
        }
        boolean changed = existing.map(s -> differs(s, form, selected)).orElse(true);
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
        return changed;
    }

    private static boolean differs(LinkNotificationSettings s, LinkForm form, Set<NotificationChannel> selected) {
        return s.isOnExhausted() != form.isNotifyExhausted()
                || s.isOnExpired() != form.isNotifyExpired()
                || s.isOnDenials() != form.isNotifyDenials()
                || s.isOnEachVisit() != form.isNotifyEachVisit()
                || !Objects.equals(s.getVisitThreshold(), form.getNotifyThreshold())
                || !ids(s.getChannels()).equals(ids(selected));
    }

    private static Set<UUID> ids(Set<NotificationChannel> channels) {
        return channels.stream().map(NotificationChannel::getId).collect(Collectors.toSet());
    }

    /** Réarme ce qui a changé : seuil modifié, ou case « épuisé »/« expiré »/seuil nouvellement activée. */
    private void rearm(UUID linkId, LinkNotificationSettings before, LinkForm form) {
        Set<TriggerType> types = EnumSet.noneOf(TriggerType.class);
        if (form.isNotifyExhausted() && (before == null || !before.isOnExhausted())) {
            types.add(TriggerType.EXHAUSTED);
        }
        if (form.isNotifyExpired() && (before == null || !before.isOnExpired())) {
            types.add(TriggerType.EXPIRED);
        }
        Integer previousThreshold = before == null ? null : before.getVisitThreshold();
        if (!Objects.equals(previousThreshold, form.getNotifyThreshold())) {
            types.add(TriggerType.THRESHOLD);
        }
        if (!types.isEmpty()) {
            firedTriggers.deleteByLinkIdAndTriggerTypeIn(linkId, types);
        }
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
