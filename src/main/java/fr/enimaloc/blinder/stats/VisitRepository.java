package fr.enimaloc.blinder.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface VisitRepository extends JpaRepository<Visit, UUID> {

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Visit v where v.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
