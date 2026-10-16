package fr.enimaloc.blinder.domain;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DomainRepository extends JpaRepository<Domain, UUID> {

    Optional<Domain> findByHost(String host);

    List<Domain> findAllByOwnerOrderByHost(User owner);

    Optional<Domain> findByIdAndOwner(UUID id, User owner);
}
