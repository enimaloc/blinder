package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainService;
import fr.enimaloc.blinder.user.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/_/api/v1/domains")
@RequiredArgsConstructor
public class DomainApiController {

    private final CurrentUser currentUser;
    private final DomainService domains;

    public record DomainView(UUID id, String host) {

        static DomainView of(Domain domain) {
            return new DomainView(domain.getId(), domain.getHost());
        }
    }

    @GetMapping
    public List<DomainView> list() {
        return domains.list(currentUser.require()).stream().map(DomainView::of).toList();
    }
}
