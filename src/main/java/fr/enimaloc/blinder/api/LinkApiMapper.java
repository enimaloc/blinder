package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.access.LinkStatusResolver;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.notification.LinkNotificationService;
import fr.enimaloc.blinder.notification.NotificationChannel;
import fr.enimaloc.blinder.tag.Tag;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Conversions entre l'API et le formulaire du panel ({@link LinkForm}), pour que l'API passe par les mêmes règles.
 *
 * <p>Noms de champ d'erreur : ceux de l'API. {@code newPassword} → {@code password},
 * {@code notifyX} → {@code notifications.x} (voir {@link #apiField}) ; les autres sont identiques.
 */
@Component
public class LinkApiMapper {

    private static final Map<String, String> API_FIELDS = Map.of(
            "newPassword", "password",
            "notifyExhausted", "notifications.exhausted",
            "notifyExpired", "notifications.expired",
            "notifyDenials", "notifications.denials",
            "notifyEachVisit", "notifications.eachVisit",
            "notifyThreshold", "notifications.threshold",
            "notifyChannelIds", "notifications.channelIds");

    private final LinkService links;
    private final LinkStatusResolver statuses;
    private final LinkNotificationService notifications;
    private final Validator validator;
    private final ZoneId zone;

    public LinkApiMapper(LinkService links, LinkStatusResolver statuses, LinkNotificationService notifications,
                         Validator validator, BlinderProperties properties) {
        this.links = links;
        this.statuses = statuses;
        this.notifications = notifications;
        this.validator = validator;
        this.zone = properties.admin().timeZone();
    }

    /** Nom de champ de l'API correspondant à un champ de {@link LinkForm}. */
    public static String apiField(String formField) {
        return API_FIELDS.getOrDefault(formField, formField);
    }

    /** Même erreur, rattachée au nom de champ de l'API. */
    public static FieldErrorException toApi(FieldErrorException e) {
        String field = apiField(e.field());
        return field.equals(e.field()) ? e : new FieldErrorException(field, e.getMessage());
    }

    /**
     * @param url URL publique du lien ({@code null} si inutile, ex. base d'un PATCH)
     * {@code link.domain} et {@code link.tags} doivent être chargés.
     */
    public LinkResource toResource(Link link, String url) {
        LinkResource.Notifications notify = notifications.settingsFor(link.getId())
                .map(s -> new LinkResource.Notifications(s.isOnExhausted(), s.isOnExpired(), s.isOnDenials(),
                        s.isOnEachVisit(), s.getVisitThreshold(),
                        s.getChannels().stream().map(NotificationChannel::getId).sorted().toList()))
                .orElse(LinkResource.Notifications.NONE);
        return new LinkResource(
                link.getId(),
                link.getDomain().getId(),
                link.getDomain().getHost(),
                link.getSlug(),
                url,
                link.getTargetUrl(),
                link.isEnabled(),
                toOffset(link.getNotBefore()),
                toOffset(link.getExpiresAt()),
                link.getTtlAfterFirstUse() == null ? null : link.getTtlAfterFirstUse().toMinutes(),
                link.getMaxUses(),
                link.getUseCount(),
                link.getDeniedUrl(),
                link.getPasswordHash() != null,
                link.getIpAllowlist() == null ? List.of()
                        : Arrays.stream(link.getIpAllowlist().split(",")).filter(s -> !s.isBlank()).toList(),
                link.isDeviceProtected(),
                links.deviceIds(link.getId()).stream().sorted().toList(),
                link.getRedirectStatus(),
                link.isForwardQuery(),
                link.isInterstitial(),
                link.getInterstitialSeconds(),
                link.getMetaMode(),
                link.getMetaTitle(),
                link.getMetaDescription(),
                link.getMetaImageUrl(),
                link.getTags().stream().sorted(Comparator.comparing(Tag::getNameKey)).map(Tag::getName).toList(),
                notify,
                statuses.statusOf(link).name(),
                link.getVersion(),
                toOffset(link.getCreatedAt()),
                toOffset(link.getUpdatedAt()));
    }

    /** Représentation courante en écriture, base d'un PATCH ; le mot de passe n'y figure jamais. */
    public LinkInput toInput(Link link) {
        LinkResource r = toResource(link, null);
        LinkInput in = new LinkInput();
        in.setDomainId(r.domainId());
        in.setSlug(r.slug());
        in.setTargetUrl(r.targetUrl());
        in.setEnabled(r.enabled());
        in.setNotBefore(r.notBefore());
        in.setExpiresAt(r.expiresAt());
        in.setTtlMinutes(r.ttlMinutes());
        in.setMaxUses(r.maxUses());
        in.setDeniedUrl(r.deniedUrl());
        in.setRemovePassword(false);
        in.setIpAllowlist(r.ipAllowlist());
        in.setDeviceProtected(r.deviceProtected());
        in.setDeviceIds(r.deviceIds());
        in.setRedirectStatus(r.redirectStatus());
        in.setForwardQuery(r.forwardQuery());
        in.setInterstitial(r.interstitial());
        in.setInterstitialSeconds(r.interstitialSeconds());
        in.setMetaMode(r.metaMode());
        in.setMetaTitle(r.metaTitle());
        in.setMetaDescription(r.metaDescription());
        in.setMetaImageUrl(r.metaImageUrl());
        in.setTags(r.tags());
        LinkInput.Notifications n = new LinkInput.Notifications();
        n.setExhausted(r.notifications().exhausted());
        n.setExpired(r.notifications().expired());
        n.setDenials(r.notifications().denials());
        n.setEachVisit(r.notifications().eachVisit());
        n.setThreshold(r.notifications().threshold());
        n.setChannelIds(r.notifications().channelIds());
        in.setNotifications(n);
        in.setVersion(r.version());
        return in;
    }

    /**
     * Reporte sur {@code form} les champs non nuls de {@code in} puis valide le formulaire (Bean Validation).
     *
     * @throws InvalidFieldsException erreurs de validation, sous les noms de champ de l'API
     */
    public LinkForm toForm(LinkInput in, LinkForm form) {
        set(in.getDomainId(), form::setDomainId);
        set(in.getSlug(), form::setSlug);
        set(in.getTargetUrl(), form::setTargetUrl);
        set(in.getEnabled(), form::setEnabled);
        // Instants exacts : une heure locale seule serait ambiguë dans l'heure répétée (passage à l'heure d'hiver).
        set(in.getNotBefore(), value -> form.exactNotBefore(value.toInstant(), zone));
        set(in.getExpiresAt(), value -> form.exactExpiresAt(value.toInstant(), zone));
        set(in.getTtlMinutes(), form::setTtlMinutes);
        set(in.getMaxUses(), form::setMaxUses);
        set(in.getDeniedUrl(), form::setDeniedUrl);
        set(in.getPassword(), form::setNewPassword);
        set(in.getRemovePassword(), form::setRemovePassword);
        if (in.getIpAllowlist() != null) {
            form.setIpAllowlist(String.join("\n", in.getIpAllowlist().stream().filter(Objects::nonNull).toList()));
        }
        set(in.getDeviceProtected(), form::setDeviceProtected);
        if (in.getDeviceIds() != null) {
            form.setDeviceIds(new LinkedHashSet<>(in.getDeviceIds()));
        }
        set(in.getRedirectStatus(), form::setRedirectStatus);
        set(in.getForwardQuery(), form::setForwardQuery);
        set(in.getInterstitial(), form::setInterstitial);
        set(in.getInterstitialSeconds(), form::setInterstitialSeconds);
        set(in.getMetaMode(), form::setMetaMode);
        set(in.getMetaTitle(), form::setMetaTitle);
        set(in.getMetaDescription(), form::setMetaDescription);
        set(in.getMetaImageUrl(), form::setMetaImageUrl);
        if (in.getTags() != null) {
            List<String> names = in.getTags().stream().filter(Objects::nonNull).toList();
            // Le formulaire sépare sur la virgule : une virgule dans un nom serait silencieusement coupée.
            if (names.stream().anyMatch(name -> name.indexOf(',') >= 0)) {
                throw new FieldErrorException("tags", "Virgule interdite");
            }
            form.setTags(String.join(",", names));
        }
        LinkInput.Notifications n = in.getNotifications();
        if (n != null) {
            set(n.getExhausted(), form::setNotifyExhausted);
            set(n.getExpired(), form::setNotifyExpired);
            set(n.getDenials(), form::setNotifyDenials);
            set(n.getEachVisit(), form::setNotifyEachVisit);
            set(n.getThreshold(), form::setNotifyThreshold);
            if (n.getChannelIds() != null) {
                form.setNotifyChannelIds(new LinkedHashSet<>(n.getChannelIds()));
            }
        }
        set(in.getVersion(), form::setVersion);
        validate(form);
        return form;
    }

    private void validate(LinkForm form) {
        Map<String, String> errors = new TreeMap<>();
        for (ConstraintViolation<LinkForm> violation : validator.validate(form)) {
            errors.putIfAbsent(apiField(rootProperty(violation.getPropertyPath())), violation.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidFieldsException(errors);
        }
    }

    private static String rootProperty(Path path) {
        for (Path.Node node : path) {
            return node.getName();
        }
        return path.toString();
    }

    private static <T> void set(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
