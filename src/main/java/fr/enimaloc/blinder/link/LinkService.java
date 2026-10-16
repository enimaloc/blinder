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
import fr.enimaloc.blinder.notification.FiredTriggerRepository;
import fr.enimaloc.blinder.notification.TriggerType;
import fr.enimaloc.blinder.redirect.RedirectPolicy;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagRepository;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LinkService {

    private static final int SLUG_ATTEMPTS = 5;
    private static final char LIKE_ESCAPE = '\\';
    private static final Sort SEARCH_ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final LinkRepository links;
    private final DomainRepository domains;
    private final TagRepository tags;
    private final SlugPolicy slugs;
    private final PasswordEncoder encoder;
    private final DeviceRepository devices;
    private final FiredTriggerRepository firedTriggers;
    private final ZoneId zone;

    public LinkService(LinkRepository links, DomainRepository domains, TagRepository tags, SlugPolicy slugs,
                       PasswordEncoder encoder, DeviceRepository devices, FiredTriggerRepository firedTriggers,
                       BlinderProperties properties) {
        this.links = links;
        this.domains = domains;
        this.tags = tags;
        this.slugs = slugs;
        this.encoder = encoder;
        this.devices = devices;
        this.firedTriggers = firedTriggers;
        this.zone = properties.admin().timeZone();
    }

    /** @param domainId filtre optionnel ; @param tagId filtre optionnel (tag d'un autre propriétaire : 404) */
    @Transactional(readOnly = true)
    public List<Link> list(User owner, UUID domainId, UUID tagId) {
        Domain domain = domainId == null ? null : domains.findByIdAndOwner(domainId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Tag tag = tagId == null ? null : tags.findByIdAndOwner(tagId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (tag != null) {
            return domain == null ? links.findAllByOwnerAndTag(owner, tag)
                    : links.findAllByOwnerAndDomainAndTag(owner, domain, tag);
        }
        return domain == null ? links.findAllByOwnerOrderByCreatedAtDesc(owner)
                : links.findAllByOwnerAndDomainOrderByCreatedAtDesc(owner, domain);
    }

    /**
     * Liste paginée, plus récents d'abord ({@code createdAt desc, id}) ; le tri de {@code pageable} est ignoré.
     *
     * @param domainId filtre optionnel (domaine d'un autre propriétaire : 404)
     * @param tagId    filtre optionnel (tag d'un autre propriétaire : 404)
     * @param q        filtre optionnel, sans casse, sur le slug ou l'URL cible ({@code %} et {@code _} littéraux)
     */
    @Transactional(readOnly = true)
    public Page<Link> search(User owner, UUID domainId, UUID tagId, String q, Pageable pageable) {
        Domain domain = domainId == null ? null : domains.findByIdAndOwner(domainId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Tag tag = tagId == null ? null : tags.findByIdAndOwner(tagId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String pattern = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        Specification<Link> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("owner"), owner));
            if (domain != null) {
                where.add(cb.equal(root.get("domain"), domain));
            }
            if (tag != null) {
                where.add(cb.isMember(tag, root.get("tags")));
            }
            if (pattern != null) {
                where.add(cb.or(cb.like(cb.lower(root.get("slug")), pattern, LIKE_ESCAPE),
                        cb.like(cb.lower(root.get("targetUrl")), pattern, LIKE_ESCAPE)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), SEARCH_ORDER);
        Page<Link> page = links.findAll(spec, sorted);
        if (page.isEmpty()) {
            return page;
        }
        List<UUID> ids = page.getContent().stream().map(Link::getId).toList();
        Map<UUID, Link> loaded = links.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Link::getId, Function.identity()));
        return new PageImpl<>(ids.stream().map(loaded::get).filter(Objects::nonNull).toList(), sorted,
                page.getTotalElements());
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Appareils autorisés du lien. */
    @Transactional(readOnly = true)
    public List<UUID> deviceIds(UUID linkId) {
        return links.findDeviceIds(linkId);
    }

    /**
     * Appareils autorisés de chaque lien de {@code owner}, en une requête ; un lien sans appareil, ou d'un autre
     * propriétaire, n'a pas d'entrée.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> deviceIds(User owner, Collection<UUID> linkIds) {
        if (linkIds.isEmpty()) {
            return Map.of();
        }
        return links.findDeviceIdPairs(owner, linkIds).stream().collect(Collectors.groupingBy(
                row -> (UUID) row[0], Collectors.mapping(row -> (UUID) row[1], Collectors.toList())));
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

    public static boolean isLimitedOrProtected(Link link) {
        return link.getPasswordHash() != null
                || (link.getIpAllowlist() != null && !link.getIpAllowlist().isBlank())
                || link.isDeviceProtected() || link.getMaxUses() != null || link.getTtlAfterFirstUse() != null;
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
        form.setDeviceProtected(link.isDeviceProtected());
        form.setDeviceIds(new LinkedHashSet<>(links.findDeviceIds(link.getId())));
        form.setRedirectStatus(link.getRedirectStatus());
        form.setForwardQuery(link.isForwardQuery());
        form.setInterstitial(link.isInterstitial());
        form.setInterstitialSeconds(link.getInterstitialSeconds());
        form.setMetaMode(link.getMetaMode());
        form.setMetaTitle(link.getMetaTitle());
        form.setMetaDescription(link.getMetaDescription());
        form.setMetaImageUrl(link.getMetaImageUrl());
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
        Instant notBeforeBefore = link.getNotBefore();
        Instant expiresAtBefore = link.getExpiresAt();
        Duration ttlBefore = link.getTtlAfterFirstUse();
        Integer maxUsesBefore = link.getMaxUses();
        Domain domain = ownedDomain(owner, form.getDomainId());
        // Slug vide à l'édition : on conserve l'actuel (revalidé : unicité sur le domaine cible).
        String requested = form.getSlug() == null || form.getSlug().isBlank() ? link.getSlug() : form.getSlug();
        link.setSlug(resolveSlug(domain, requested, id));
        link.setDomain(domain);
        apply(link, form, owner);
        Link saved = links.saveAndFlush(link);
        Set<TriggerType> rearm = EnumSet.noneOf(TriggerType.class);
        if (!Objects.equals(maxUsesBefore, saved.getMaxUses())) {
            rearm.add(TriggerType.EXHAUSTED);
        }
        if (!Objects.equals(notBeforeBefore, saved.getNotBefore())
                || !Objects.equals(expiresAtBefore, saved.getExpiresAt())
                || !Objects.equals(ttlBefore, saved.getTtlAfterFirstUse())) {
            rearm.add(TriggerType.EXPIRED);
        }
        if (!rearm.isEmpty()) {
            firedTriggers.deleteByLinkIdAndTriggerTypeIn(id, rearm);
        }
        return saved;
    }

    @Transactional
    public void resetUsage(User owner, UUID id) {
        links.resetUsage(get(owner, id).getId());
        // Compteur remis à zéro : seuil, épuisement et expiration (le TTL repart) peuvent de nouveau notifier.
        firedTriggers.deleteByLinkIdAndTriggerTypeIn(id,
                Set.of(TriggerType.EXHAUSTED, TriggerType.EXPIRED, TriggerType.THRESHOLD));
    }

    @Transactional
    public void delete(User owner, UUID id) {
        links.delete(get(owner, id));
    }

    /**
     * Relecture après une violation d'intégrité : le slug demandé est-il effectivement pris par un autre lien du
     * domaine ? {@code false} si le slug est vide (généré) ou le domaine inconnu : la violation a une autre cause.
     */
    @Transactional(readOnly = true)
    public boolean slugTaken(User owner, UUID domainId, String rawSlug, UUID currentId) {
        if (domainId == null || rawSlug == null || rawSlug.isBlank()) {
            return false;
        }
        String slug = slugs.normalize(rawSlug);
        return domains.findByIdAndOwner(domainId, owner)
                .flatMap(domain -> links.findByDomainAndSlug(domain, slug))
                .filter(existing -> !existing.getId().equals(currentId))
                .isPresent();
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
        Instant notBefore = resolveDate(form.getNotBefore(), form.getExactNotBefore(), link.getNotBefore());
        Instant expiresAt = resolveDate(form.getExpiresAt(), form.getExactExpiresAt(), link.getExpiresAt());
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
        applyRedirect(link, form);
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
        link.setDeviceProtected(form.isDeviceProtected() || !owned.isEmpty());
    }

    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 307, 308);

    /** Appelée après applyProtection : la restriction des statuts permanents dépend des protections du lien. */
    private void applyRedirect(Link link, LinkForm form) {
        int status = form.getRedirectStatus() == null ? 302 : form.getRedirectStatus();
        if (!REDIRECT_STATUSES.contains(status)) {
            throw new FieldErrorException("redirectStatus", "Code de redirection non pris en charge");
        }
        MetaMode mode = form.getMetaMode() == null ? MetaMode.NONE : form.getMetaMode();
        String title = blankToNull(form.getMetaTitle());
        String image = blankToNull(form.getMetaImageUrl());
        if (mode == MetaMode.CUSTOM && title == null) {
            throw new FieldErrorException("metaTitle", "Titre requis pour un aperçu personnalisé");
        }
        if (image != null && !UrlPolicy.isHttpUrl(image)) {
            throw new FieldErrorException("metaImageUrl", "URL http(s) absolue requise");
        }
        link.setRedirectStatus(status);
        link.setForwardQuery(form.isForwardQuery());
        link.setInterstitial(form.isInterstitial());
        link.setInterstitialSeconds(form.getInterstitialSeconds());
        link.setMetaMode(mode);
        link.setMetaTitle(title);
        link.setMetaDescription(blankToNull(form.getMetaDescription()));
        link.setMetaImageUrl(image);
        if ((status == 301 || status == 308) && !RedirectPolicy.isBare(link)) {
            throw new FieldErrorException("redirectStatus",
                    "301/308 réservés aux liens sans limite, date ni protection (mis en cache par les navigateurs)");
        }
    }

    /**
     * Instant d'une date du formulaire, sans ambiguïté autour des changements d'heure :
     * l'instant exact (API) s'il correspond à l'heure locale ; sinon l'instant actuel si l'heure locale est
     * inchangée (à la minute près, précision du panel) ; sinon le premier décalage dans l'heure répétée.
     */
    Instant resolveDate(LocalDateTime local, Instant exact, Instant current) {
        if (local == null) {
            return null;
        }
        if (exact != null && local.equals(toLocal(exact))) {
            return exact;
        }
        if (current != null) {
            LocalDateTime currentLocal = toLocal(current);
            boolean minutePrecision = local.getSecond() == 0 && local.getNano() == 0;
            if (local.equals(currentLocal)
                    || (minutePrecision && local.equals(currentLocal.truncatedTo(ChronoUnit.MINUTES)))) {
                return current;
            }
        }
        return local.atZone(zone).toInstant();
    }

    private LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, zone);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
