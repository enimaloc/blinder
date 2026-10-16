package fr.enimaloc.blinder.tag;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class TagService {

    public static final int MAX_TAGS_PER_LINK = 20;

    private final TagRepository tags;

    @Transactional(readOnly = true)
    public List<Tag> list(User owner) {
        return tags.findByOwnerOrderByNameKeyAsc(owner);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> linkCounts(User owner) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : tags.countLinksByTag(owner)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public Tag get(User owner, UUID id) {
        return tags.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Tag create(User owner, String name, String color) {
        String normalized = validName("name", name);
        if (tags.findByOwnerAndNameKey(owner, key(normalized)).isPresent()) {
            throw new FieldErrorException("name", "Ce tag existe déjà");
        }
        Tag tag = new Tag(owner, normalized);
        tag.setColor(validColor(color));
        try {
            return tags.saveAndFlush(tag);
        } catch (DataIntegrityViolationException e) {
            throw new FieldErrorException("name", "Ce tag existe déjà");
        }
    }

    public Tag update(User owner, UUID id, String name, String color) {
        Tag tag = get(owner, id);
        String normalized = validName("name", name);
        tags.findByOwnerAndNameKey(owner, key(normalized))
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new FieldErrorException("name", "Ce tag existe déjà");
                });
        String validated = validColor(color);
        tag.rename(normalized);
        tag.setColor(validated);
        try {
            return tags.saveAndFlush(tag);
        } catch (DataIntegrityViolationException e) {
            throw new FieldErrorException("name", "Ce tag existe déjà");
        }
    }

    /** Retire le tag de ses liens puis le supprime ; les liens sont conservés. */
    public void delete(User owner, UUID id) {
        Tag tag = get(owner, id);
        tags.detachFromLinks(tag.getId());
        tags.delete(tag);
    }

    /** Résout des noms chez {@code owner}, en créant les manquants (sans couleur). */
    public Set<Tag> resolve(User owner, Collection<String> names) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String name : names) {
            String normalized = validName("tags", name);
            byKey.putIfAbsent(key(normalized), normalized);
        }
        if (byKey.size() > MAX_TAGS_PER_LINK) {
            throw new FieldErrorException("tags", "20 tags maximum");
        }
        if (byKey.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Map<String, Tag> existing = tags.findByOwnerAndNameKeyIn(owner, byKey.keySet()).stream()
                .collect(Collectors.toMap(Tag::getNameKey, t -> t));
        Set<Tag> resolved = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : byKey.entrySet()) {
            Tag tag = existing.get(entry.getKey());
            resolved.add(tag != null ? tag : tags.save(new Tag(owner, entry.getValue())));
        }
        return resolved;
    }

    /** Remplace les tags du lien (qui doit être géré par la transaction courante). */
    public void apply(User owner, Link link, Collection<String> names) {
        Set<Tag> resolved = resolve(owner, names);
        link.getTags().clear();
        link.getTags().addAll(resolved);
    }

    /** Noms triés, séparés par {@code ", "} ; {@code link.tags} doit être chargé. */
    public String format(Link link) {
        return link.getTags().stream()
                .sorted(Comparator.comparing(Tag::getNameKey))
                .map(Tag::getName)
                .collect(Collectors.joining(", "));
    }

    private static String validName(String field, String name) {
        String normalized = TagNames.normalize(name);
        TagNames.validate(normalized).ifPresent(error -> {
            throw new FieldErrorException(field, error);
        });
        return normalized;
    }

    private static String validColor(String color) {
        try {
            return TagNames.normalizeColor(color);
        } catch (IllegalArgumentException e) {
            throw new FieldErrorException("color", e.getMessage());
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
