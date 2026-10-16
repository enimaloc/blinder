package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.tag.Tag;
import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LinkRepository extends JpaRepository<Link, UUID>, JpaSpecificationExecutor<Link> {

    Optional<Link> findByDomainAndSlug(Domain domain, String slug);

    boolean existsByDomainAndSlug(Domain domain, String slug);

    boolean existsByDomain(Domain domain);

    @EntityGraph(attributePaths = {"domain", "tags"})
    List<Link> findAllByOwnerOrderByCreatedAtDesc(User owner);

    @EntityGraph(attributePaths = {"domain", "tags"})
    List<Link> findAllByOwnerAndDomainOrderByCreatedAtDesc(User owner, Domain domain);

    @EntityGraph(attributePaths = {"domain", "tags"})
    @Query("select l from Link l where l.owner = :owner and :tag member of l.tags order by l.createdAt desc")
    List<Link> findAllByOwnerAndTag(@Param("owner") User owner, @Param("tag") Tag tag);

    @EntityGraph(attributePaths = {"domain", "tags"})
    @Query("""
            select l from Link l where l.owner = :owner and l.domain = :domain and :tag member of l.tags
             order by l.createdAt desc
            """)
    List<Link> findAllByOwnerAndDomainAndTag(@Param("owner") User owner, @Param("domain") Domain domain,
                                             @Param("tag") Tag tag);

    /** Recharge une page de liens avec domaine et tags (la pagination ne peut pas joindre une collection). */
    @EntityGraph(attributePaths = {"domain", "tags"})
    List<Link> findAllByIdIn(Collection<UUID> ids);

    @EntityGraph(attributePaths = {"domain", "tags"})
    Optional<Link> findByIdAndOwner(UUID id, User owner);

    /**
     * Incrément atomique et conditionnel, portable H2/SQLite/PostgreSQL.
     * Ne passe pas par le verrou optimiste (UPDATE JPQL non versionné).
     *
     * @return nombre de lignes affectées (0 si épuisé)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Link l
               set l.useCount = l.useCount + 1,
                   l.firstUsedAt = coalesce(l.firstUsedAt, :now)
             where l.id = :id
               and (l.maxUses is null or l.useCount < l.maxUses)
            """)
    int consume(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Link l set l.useCount = 0, l.firstUsedAt = null where l.id = :id")
    int resetUsage(@Param("id") UUID id);

    @Query("""
            select case when count(d) > 0 then true else false end from Link l join l.devices d
             where l.id = :linkId and d.id = :deviceId
            """)
    boolean isDeviceAllowed(@Param("linkId") UUID linkId, @Param("deviceId") UUID deviceId);

    @Query("select d.id from Link l join l.devices d where l.id = :linkId")
    List<UUID> findDeviceIds(@Param("linkId") UUID linkId);
}
