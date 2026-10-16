package fr.enimaloc.blinder.admin;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkService;
import fr.enimaloc.blinder.link.PublicLinkUrls;
import fr.enimaloc.blinder.link.QrCodes;
import fr.enimaloc.blinder.user.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Controller
@RequestMapping("/_/links/{id}")
@RequiredArgsConstructor
public class QrAdminController {

    private final CurrentUser currentUser;
    private final LinkService links;
    private final QrCodes qr;

    @GetMapping("/qr.svg")
    public ResponseEntity<byte[]> svg(@PathVariable UUID id, @RequestParam(required = false) String download,
                                      HttpServletRequest request) {
        Link link = links.get(currentUser.require(), id);
        byte[] body = qr.svg(PublicLinkUrls.of(request, link)).getBytes(StandardCharsets.UTF_8);
        return response(body, MediaType.valueOf("image/svg+xml"), download, link.getSlug() + ".svg");
    }

    @GetMapping("/qr.png")
    public ResponseEntity<byte[]> png(@PathVariable UUID id, @RequestParam(required = false) Integer size,
                                      @RequestParam(required = false) String download, HttpServletRequest request) {
        Link link = links.get(currentUser.require(), id);
        byte[] body = qr.png(PublicLinkUrls.of(request, link), QrCodes.clampSize(size));
        return response(body, MediaType.IMAGE_PNG, download, link.getSlug() + ".png");
    }

    private static ResponseEntity<byte[]> response(byte[] body, MediaType type, String download, String filename) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore());
        if (download != null) {
            builder.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        }
        return builder.body(body);
    }
}
