package fr.enimaloc.blinder.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface VisitDailyStatRepository extends JpaRepository<VisitDailyStat, UUID> {

    @Query("select s from VisitDailyStat s where s.link.id = :linkId and s.day = :day and s.outcome = :outcome")
    Optional<VisitDailyStat> findByLinkIdAndDayAndOutcome(@Param("linkId") UUID linkId, @Param("day") LocalDate day,
                                                          @Param("outcome") VisitOutcome outcome);
}
