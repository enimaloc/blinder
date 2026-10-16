package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkRepository extends JpaRepository<Link, UUID> {

    Optional<Link> findByDomainAndSlug(Domain domain, String slug);

    boolean existsByDomainAndSlug(Domain domain, String slug);

    boolean existsByDomain(Domain domain);

    @EntityGraph(attributePaths = "domain")
    List<Link> findAllByOwnerOrderByCreatedAtDesc(User owner);

    @EntityGraph(attributePaths = "domain")
    List<Link> findAllByOwnerAndDomainOrderByCreatedAtDesc(User owner, Domain domain);

    @EntityGraph(attributePaths = "domain")
    Optional<Link> findByIdAndOwner(UUID id, User owner);
}
