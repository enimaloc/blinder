package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.tag.TagService;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tags (spec §7). */
@RestController
@RequestMapping(TagApiController.BASE)
@RequiredArgsConstructor
public class TagApiController {

    static final String BASE = "/_/api/v1/tags";

    private final CurrentUser currentUser;
    private final TagService tags;

    public record TagResource(UUID id, String name, String color, long links) {

        static TagResource of(Tag tag, long links) {
            return new TagResource(tag.getId(), tag.getName(), tag.getColor(), links);
        }
    }

    @GetMapping
    public List<TagResource> list() {
        User owner = currentUser.require();
        Map<UUID, Long> counts = tags.linkCounts(owner);
        return tags.list(owner).stream().map(t -> TagResource.of(t, counts.getOrDefault(t.getId(), 0L))).toList();
    }

    @PostMapping
    public ResponseEntity<TagResource> create(@RequestBody JsonNode body) {
        TagInput input = TagInput.parse(body);
        Tag tag = tags.create(currentUser.require(), input.name(), input.color());
        return ResponseEntity.created(URI.create(BASE + "/" + tag.getId())).body(TagResource.of(tag, 0));
    }

    @PatchMapping("/{id}")
    public TagResource update(@PathVariable UUID id, @RequestBody JsonNode patch) {
        User owner = currentUser.require();
        TagInput input = TagInput.merge(patch, tags.get(owner, id));
        Tag tag = tags.update(owner, id, input.name(), input.color());
        return TagResource.of(tag, tags.linkCounts(owner).getOrDefault(tag.getId(), 0L));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tags.delete(currentUser.require(), id);
    }
}
