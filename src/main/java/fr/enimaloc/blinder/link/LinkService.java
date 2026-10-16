package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.access.IpAllowlist;
import fr.enimaloc.blinder.common.BlinderProperties;
import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.common.StaleEditException;
import fr.enimaloc.blinder.common.UrlPolicy;
import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.device.DeviceRepository;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class LinkService {

    private static final int SLUG_ATTEMPTS = 5;

    private final LinkRepository links;
    private final DomainRepository domains;
    private final SlugPolicy slugs;
    private final PasswordEncoder encoder;
    private final DeviceRepository devices;
    private final ZoneId zone;

    public LinkService(LinkRepository links, DomainRepository domains, SlugPolicy slugs,
                       PasswordEncoder encoder, DeviceRepository devices, BlinderProperties properties) {
        this.links = links;
        this.domains = domains;
        this.slugs = slugs;
        this.encoder = encoder;
        this.devices = devices;
        this.zone = properties.admin().timeZone();
    }

    @Transactional(readOnly = true)
    public List<Link> list(User owner, UUID domainId) {
        if (domainId == null) {
            return links.findAllByOwnerOrderByCreatedAtDesc(owner);
        }
        Domain domain = domains.findByIdAndOwner(domainId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return links.findAllByOwnerAndDomainOrderByCreatedAtDesc(owner, domain);
    }

    @Transactional(readOnly = true)
    public Link get(User owner, UUID id) {
        return links.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Lien protégé par appareils dont tous les appareils ont été révoqués. */
    public boolean hasNoDeviceLeft(Link link) {
        return link.isDeviceProtected() && links.findDeviceIds(link.getId()).isEmpty();
    }

    /** {@code link.domain} doit être chargé (les finders de {@link #get} l'assurent). */
    public LinkForm toForm(Link link) {
        LinkForm form = new LinkForm();
        form.setDomainId(link.getDomain().getId());
        form.setSlug(link.getSlug());
        form.setTargetUrl(link.getTargetUrl());
        form.setEnabled(link.isEnabled());
        form.setNotBefore(toLocal(link.getNotBefore()));
        form.setExpiresAt(toLocal(link.getExpiresAt()));
        form.setTtlMinutes(link.getTtlAfterFirstUse() == null ? null : link.getTtlAfterFirstUse().toMinutes());
        form.setMaxUses(link.getMaxUses());
        form.setDeniedUrl(link.getDeniedUrl());
        form.setVersion(link.getVersion());
        form.setIpAllowlist(link.getIpAllowlist() == null ? null : link.getIpAllowlist().replace(",", "\n"));
        form.setDeviceIds(new LinkedHashSet<>(links.findDeviceIds(link.getId())));
        return form;
    }

    @Transactional
    public Link create(User owner, LinkForm form) {
        Domain domain = ownedDomain(owner, form.getDomainId());
        Link link = new Link(domain, resolveSlug(domain, form.getSlug(), null), null, owner);
        apply(link, form, owner);
        return links.saveAndFlush(link);
    }

    @Transactional
    public Link update(User owner, UUID id, LinkForm form) {
        Link link = get(owner, id);
        if (form.getVersion() != null && !Objects.equals(form.getVersion(), link.getVersion())) {
            throw new StaleEditException();
        }
        Domain domain = ownedDomain(owner, form.getDomainId());
        // Slug vide à l'édition : on conserve l'actuel (revalidé : unicité sur le domaine cible).
        String requested = form.getSlug() == null || form.getSlug().isBlank() ? link.getSlug() : form.getSlug();
        link.setSlug(resolveSlug(domain, requested, id));
        link.setDomain(domain);
        apply(link, form, owner);
        return links.saveAndFlush(link);
    }

    @Transactional
    public void resetUsage(User owner, UUID id) {
        links.resetUsage(get(owner, id).getId());
    }

    @Transactional
    public void delete(User owner, UUID id) {
        links.delete(get(owner, id));
    }

    private Domain ownedDomain(User owner, UUID domainId) {
        return domains.findByIdAndOwner(domainId, owner)
                .orElseThrow(() -> new FieldErrorException("domainId", "Domaine inconnu"));
    }

    private String resolveSlug(Domain domain, String raw, UUID currentId) {
        if (raw == null || raw.isBlank()) {
            for (int i = 0; i < SLUG_ATTEMPTS; i++) {
                String candidate = slugs.generate();
                if (!links.existsByDomainAndSlug(domain, candidate)) {
                    return candidate;
                }
            }
            throw new FieldErrorException("slug", "Impossible de générer un slug libre, saisissez-en un");
        }
        String slug = slugs.normalize(raw);
        slugs.validate(slug).ifPresent(error -> {
            throw new FieldErrorException("slug", error.message());
        });
        links.findByDomainAndSlug(domain, slug)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new FieldErrorException("slug", "Ce slug existe déjà sur ce domaine");
                });
        return slug;
    }

    private void apply(Link link, LinkForm form, User owner) {
        if (!UrlPolicy.isHttpUrl(form.getTargetUrl())) {
            throw new FieldErrorException("targetUrl", "URL http(s) absolue requise");
        }
        String deniedUrl = blankToNull(form.getDeniedUrl());
        if (deniedUrl != null && !UrlPolicy.isHttpUrl(deniedUrl)) {
            throw new FieldErrorException("deniedUrl", "URL http(s) absolue requise");
        }
        Instant notBefore = toInstant(form.getNotBefore());
        Instant expiresAt = toInstant(form.getExpiresAt());
        if (notBefore != null && expiresAt != null && !notBefore.isBefore(expiresAt)) {
            throw new FieldErrorException("expiresAt", "L'expiration doit être postérieure à l'activation");
        }
        link.setTargetUrl(form.getTargetUrl().trim());
        link.setEnabled(form.isEnabled());
        link.setNotBefore(notBefore);
        link.setExpiresAt(expiresAt);
        link.setTtlAfterFirstUse(form.getTtlMinutes() == null ? null : Duration.ofMinutes(form.getTtlMinutes()));
        link.setMaxUses(form.getMaxUses());
        link.setDeniedUrl(deniedUrl);
        applyProtection(link, form, owner);
    }

    private void applyProtection(Link link, LinkForm form, User owner) {
        if (form.isRemovePassword()) {
            link.setPasswordHash(null);
        } else if (form.getNewPassword() != null && !form.getNewPassword().isEmpty()) {
            String password = form.getNewPassword();
            if (password.length() < 4) {
                throw new FieldErrorException("newPassword", "4 caractères minimum");
            }
            if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new FieldErrorException("newPassword", "72 octets maximum");
            }
            link.setPasswordHash(encoder.encode(password));
        }
        try {
            link.setIpAllowlist(IpAllowlist.normalize(form.getIpAllowlist()));
        } catch (IllegalArgumentException e) {
            throw new FieldErrorException("ipAllowlist", e.getMessage());
        }
        Set<UUID> ids = form.getDeviceIds() == null ? Set.of() : form.getDeviceIds();
        List<Device> owned = ids.isEmpty() ? List.of() : devices.findAllByIdInAndOwner(ids, owner);
        if (owned.size() != ids.size()) {
            throw new FieldErrorException("deviceIds", "Appareil inconnu");
        }
        link.getDevices().clear();
        link.getDevices().addAll(owned);
        link.setDeviceProtected(!owned.isEmpty());
    }

    private Instant toInstant(LocalDateTime local) {
        return local == null ? null : local.atZone(zone).toInstant();
    }

    private LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, zone);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
