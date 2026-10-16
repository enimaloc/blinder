package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.common.FieldErrorException;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkEditService;
import fr.enimaloc.blinder.link.LinkForm;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.link.PublicLinkUrls;
import fr.enimaloc.blinder.link.QrCodes;
import fr.enimaloc.blinder.user.CurrentUser;
import fr.enimaloc.blinder.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Liens (spec §7) : mêmes services et mêmes règles que le panel. */
@RestController
@RequestMapping(LinkApiController.BASE)
@RequiredArgsConstructor
public class LinkApiController {

    static final String BASE = "/_/api/v1/links";
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 100;
    static final String DUPLICATE_SLUG = "Ce slug existe déjà sur ce domaine";

    private final CurrentUser currentUser;
    private final LinkService links;
    private final LinkEditService edits;
    private final LinkApiMapper mapper;
    private final QrCodes qr;
    private final JsonMapper json;

    @GetMapping
    public PageResource<LinkResource> list(@RequestParam(name = "domain", required = false) UUID domainId,
                                           @RequestParam(name = "tag", required = false) UUID tagId,
                                           @RequestParam(name = "q", required = false) String q,
                                           @RequestParam(name = "page", defaultValue = "0") int page,
                                           @RequestParam(name = "size", defaultValue = "" + DEFAULT_SIZE) int size,
                                           HttpServletRequest request) {
        if (page < 0) {
            throw new FieldErrorException("page", "Doit être positif ou nul");
        }
        if (size < 1) {
            throw new FieldErrorException("size", "Doit être au moins 1");
        }
        var result = links.search(currentUser.require(), domainId, tagId, q,
                PageRequest.of(page, Math.min(size, MAX_SIZE)));
        return PageResource.of(result, link -> resource(link, request));
    }

    @GetMapping("/{id}")
    public LinkResource get(@PathVariable UUID id, HttpServletRequest request) {
        return resource(links.get(currentUser.require(), id), request);
    }

    @PostMapping
    public ResponseEntity<LinkResource> create(@RequestBody LinkInput input, HttpServletRequest request) {
        User owner = currentUser.require();
        rejectUnknown(input);
        LinkForm form = mapper.toForm(input, new LinkForm());
        Link created = apiFields(() -> edits.create(owner, form));
        edits.refreshPreviewAfterSave(owner, created, null);
        return ResponseEntity.created(URI.create(BASE + "/" + created.getId()))
                .body(resource(links.get(owner, created.getId()), request));
    }

    /** Remplacement complet : un champ absent prend la valeur par défaut (sauf le mot de passe, conservé). */
    @PutMapping("/{id}")
    public LinkResource replace(@PathVariable UUID id, @RequestBody LinkInput input, HttpServletRequest request) {
        User owner = currentUser.require();
        Link current = links.get(owner, id);
        return save(owner, current, input, request);
    }

    /** Fusion JSON sur la représentation courante : seuls les champs présents changent ({@code null} efface). */
    @PatchMapping("/{id}")
    public LinkResource patch(@PathVariable UUID id, @RequestBody JsonNode body, HttpServletRequest request) {
        User owner = currentUser.require();
        Link current = links.get(owner, id);
        if (!body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiProblems.UNREADABLE);
        }
        LinkInput merged = json.readerForUpdating(mapper.toInput(current)).readValue(body);
        return save(owner, current, merged, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        links.delete(currentUser.require(), id);
    }

    @PostMapping("/{id}/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable UUID id) {
        edits.resetUsage(currentUser.require(), id);
    }

    @GetMapping("/{id}/qr.svg")
    public ResponseEntity<byte[]> svg(@PathVariable UUID id, HttpServletRequest request) {
        Link link = links.get(currentUser.require(), id);
        byte[] body = qr.svg(PublicLinkUrls.of(request, link)).getBytes(StandardCharsets.UTF_8);
        return image(body, MediaType.valueOf("image/svg+xml"));
    }

    @GetMapping("/{id}/qr.png")
    public ResponseEntity<byte[]> png(@PathVariable UUID id, @RequestParam(required = false) Integer size,
                                      HttpServletRequest request) {
        Link link = links.get(currentUser.require(), id);
        return image(qr.png(PublicLinkUrls.of(request, link), QrCodes.clampSize(size)), MediaType.IMAGE_PNG);
    }

    /** Course entre deux créations du même slug : la contrainte d'unicité tranche. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicateSlug() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ApiProblems.CONFLICT);
        problem.setProperty("errors", Map.of("slug", DUPLICATE_SLUG));
        return problem;
    }

    private LinkResource save(User owner, Link current, LinkInput input, HttpServletRequest request) {
        // `current` vient d'un chargement distinct de celui de update() (open-in-view désactivé)
        String previousTarget = current.getTargetUrl();
        rejectUnknown(input);
        LinkForm form = mapper.toForm(input, new LinkForm());
        Link updated = apiFields(() -> edits.update(owner, current.getId(), form));
        edits.refreshPreviewAfterSave(owner, updated, previousTarget);
        return resource(links.get(owner, current.getId()), request);
    }

    private static void rejectUnknown(LinkInput input) {
        Map<String, String> unknown = input.unknownFieldErrors();
        if (!unknown.isEmpty()) {
            throw new InvalidFieldsException(unknown);
        }
    }

    private LinkResource resource(Link link, HttpServletRequest request) {
        return mapper.toResource(link, PublicLinkUrls.of(request, link));
    }

    private static Link apiFields(Supplier<Link> action) {
        try {
            return action.get();
        } catch (FieldErrorException e) {
            throw LinkApiMapper.toApi(e);
        }
    }

    private static ResponseEntity<byte[]> image(byte[] body, MediaType type) {
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(body);
    }
}
