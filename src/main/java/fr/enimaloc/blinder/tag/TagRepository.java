package fr.enimaloc.blinder.tag;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByOwnerOrderByNameKeyAsc(User owner);

    Optional<Tag> findByIdAndOwner(UUID id, User owner);

    List<Tag> findByOwnerAndNameKeyIn(User owner, Collection<String> nameKeys);

    Optional<Tag> findByOwnerAndNameKey(User owner, String nameKey);

    /** Nombre de liens par tag du propriétaire : {@code (UUID tagId, long count)}. */
    @Query("select t.id, count(l) from Link l join l.tags t where t.owner = :owner group by t.id")
    List<Object[]> countLinksByTag(@Param("owner") User owner);
}
