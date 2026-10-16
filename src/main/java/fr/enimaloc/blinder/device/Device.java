package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Appareil autorisé : appartient au propriétaire, valable sur tous ses domaines (un secret par domaine). */
@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Device extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(updatable = false)
    private Instant lastSeenAt;

    /** Empreinte du ticket de synchronisation en cours ; modifiée uniquement par requêtes atomiques. */
    @Column(length = 64, updatable = false)
    private String syncTicketHash;

    @Column(updatable = false)
    private Instant syncExpiresAt;

    public Device(User owner, String name) {
        this.owner = owner;
        this.name = name;
    }
}
