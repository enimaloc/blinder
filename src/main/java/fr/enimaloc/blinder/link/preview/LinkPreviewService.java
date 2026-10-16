package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** Récupère l'aperçu de la cible (hors transaction : appel réseau) puis l'enregistre. */
@Service
@RequiredArgsConstructor
public class LinkPreviewService {

    private final LinkRepository links;
    private final LinkPreviewRepository previews;
    private final PreviewFetcher fetcher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LinkPreview refresh(User owner, UUID linkId) {
        String target = transactions.execute(status -> owned(owner, linkId).getTargetUrl());
        Optional<PreviewData> data = fetcher.fetch(target);
        return transactions.execute(status -> {
            Link link = owned(owner, linkId);
            if (!link.getTargetUrl().equals(target)) {
                // la cible a changé pendant la récupération : l'aperçu obtenu est périmé
                return previews.findByLinkId(linkId).orElse(null);
            }
            Optional<LinkPreview> existing = previews.findByLinkId(linkId);
            if (data.isEmpty() && existing.filter(p -> p.getStatus() == PreviewStatus.OK).isPresent()) {
                return existing.get();
            }
            LinkPreview preview = existing.orElseGet(() -> new LinkPreview(link));
            preview.setFetchedAt(clock.instant());
            preview.setStatus(data.isPresent() ? PreviewStatus.OK : PreviewStatus.FAILED);
            preview.setTitle(data.map(PreviewData::title).orElse(null));
            preview.setDescription(data.map(PreviewData::description).orElse(null));
            preview.setImageUrl(data.map(PreviewData::imageUrl).orElse(null));
            return previews.save(preview);
        });
    }

    @Transactional
    public void discard(UUID linkId) {
        previews.findByLinkId(linkId).ifPresent(previews::delete);
    }

    @Transactional(readOnly = true)
    public Optional<LinkPreview> current(UUID linkId) {
        return previews.findByLinkId(linkId);
    }

    private Link owned(User owner, UUID linkId) {
        return links.findByIdAndOwner(linkId, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
