package fr.enimaloc.blinder.redirect;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.preview.LinkPreviewRepository;
import fr.enimaloc.blinder.link.preview.PreviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class LinkMetaResolver {

    private final LinkPreviewRepository previews;

    public Optional<LinkMeta> forLink(Link link) {
        return switch (link.getMetaMode()) {
            case NONE -> Optional.empty();
            case CUSTOM -> link.getMetaTitle() == null || link.getMetaTitle().isBlank()
                    ? Optional.empty()
                    : Optional.of(new LinkMeta(link.getMetaTitle(), link.getMetaDescription(), link.getMetaImageUrl()));
            case PASSTHROUGH -> previews.findByLinkId(link.getId())
                    .filter(preview -> preview.getStatus() == PreviewStatus.OK)
                    .filter(preview -> preview.getTitle() != null || preview.getDescription() != null
                            || preview.getImageUrl() != null)
                    .map(preview -> new LinkMeta(preview.getTitle(), preview.getDescription(), preview.getImageUrl()));
        };
    }
}
