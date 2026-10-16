package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.AssignedIdEntity;
import fr.enimaloc.blinder.link.Link;
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
import lombok.Setter;

import java.time.Instant;

/** Marque qu'un déclencheur « une seule fois » a déjà notifié ce lien. */
@Entity
@Table(name = "notification_triggers_fired")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FiredTrigger extends AssignedIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private Link link;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TriggerType triggerType;

    @Setter
    @Column(nullable = false)
    private Instant firedAt = Instant.now();

    public FiredTrigger(Link link, TriggerType triggerType) {
        this.link = link;
        this.triggerType = triggerType;
    }
}
