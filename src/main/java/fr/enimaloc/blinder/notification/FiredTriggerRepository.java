package fr.enimaloc.blinder.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface FiredTriggerRepository extends JpaRepository<FiredTrigger, UUID> {

    @Query("select f from FiredTrigger f where f.link.id = :linkId and f.triggerType = :type")
    Optional<FiredTrigger> findByLinkIdAndTriggerType(@Param("linkId") UUID linkId, @Param("type") TriggerType type);

    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from FiredTrigger f where f.link.id = :linkId and f.triggerType in :types")
    int deleteByLinkIdAndTriggerTypeIn(@Param("linkId") UUID linkId, @Param("types") Collection<TriggerType> types);
}
