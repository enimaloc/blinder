package fr.enimaloc.blinder.link.check;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkHeaderCheckRepository extends JpaRepository<LinkHeaderCheck, UUID> {

    Optional<LinkHeaderCheck> findByLinkId(UUID linkId);

    /** Contrôles des liens de {@code owner} parmi {@code linkIds}, en une requête. */
    @Query("select h from LinkHeaderCheck h where h.linkId in :linkIds "
            + "and exists (select 1 from Link l where l.id = h.linkId and l.owner = :owner)")
    List<LinkHeaderCheck> findByOwnerAndLinkIdIn(@Param("owner") User owner,
                                                 @Param("linkIds") Collection<UUID> linkIds);
}
