package fr.enimaloc.blinder.api.token;

import fr.enimaloc.blinder.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    @Query("select t from ApiToken t join fetch t.owner where t.tokenHash = :hash")
    Optional<ApiToken> findByTokenHash(@Param("hash") String hash);

    List<ApiToken> findByOwnerOrderByCreatedAtDesc(User owner);

    Optional<ApiToken> findByIdAndOwner(UUID id, User owner);

    /** Met à jour {@code last_used_at} seulement s'il est absent ou antérieur à {@code notAfter}. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ApiToken t set t.lastUsedAt = :now
             where t.id = :id and (t.lastUsedAt is null or t.lastUsedAt < :notAfter)
            """)
    int touch(@Param("id") UUID id, @Param("now") Instant now, @Param("notAfter") Instant notAfter);
}
