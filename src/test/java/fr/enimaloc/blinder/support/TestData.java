package fr.enimaloc.blinder.support;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.domain.DomainRepository;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.user.Role;
import fr.enimaloc.blinder.user.User;
import fr.enimaloc.blinder.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;

@TestComponent
@RequiredArgsConstructor
public class TestData {

    public static final String PASSWORD = "correct-horse-battery";

    private final UserRepository users;
    private final DomainRepository domains;
    private final LinkRepository links;

    public void reset() {
        links.deleteAllInBatch();
        domains.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    public User admin(String username) {
        return users.save(new User(username, "{noop}" + PASSWORD, Role.ADMIN));
    }

    public Domain domain(String host, User owner) {
        return domains.save(new Domain(host, owner));
    }

    public Link link(Domain domain, String slug, String targetUrl) {
        return links.save(new Link(domain, slug, targetUrl, domain.getOwner()));
    }

    public Link save(Link link) {
        return links.save(link);
    }
}
