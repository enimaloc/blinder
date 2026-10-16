package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.common.AssignedIdEntity;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Compteur de visites par lien, jour (fuseau serveur) et issue ; survit à la purge des visites. */
@Entity
@Table(name = "visit_daily_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitDailyStat extends AssignedIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private Link link;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** Colonne {@code stat_day} : {@code day} est un mot réservé (H2). */
    @Column(name = "stat_day", nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VisitOutcome outcome;

    @Column(nullable = false)
    private long visits;

    public VisitDailyStat(Link link, User owner, LocalDate day, VisitOutcome outcome) {
        this.link = link;
        this.owner = owner;
        this.day = day;
        this.outcome = outcome;
    }

    public void increment(long n) {
        visits += n;
    }
}
