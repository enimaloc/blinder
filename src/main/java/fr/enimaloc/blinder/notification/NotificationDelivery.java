package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.BaseEntity;
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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** Envoi (en file) d'une notification vers un canal. {@code linkId} sans FK : l'historique survit au lien. */
@Entity
@Table(name = "notification_deliveries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id", nullable = false)
    private NotificationChannel channel;

    @Setter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID linkId;

    @Setter(AccessLevel.NONE)
    @Column(length = 400)
    private String linkLabel;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 24)
    private TriggerType event;

    @Setter(AccessLevel.NONE)
    @Column(nullable = false, length = 8000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    private Instant sentAt;

    public NotificationDelivery(User owner, NotificationChannel channel, UUID linkId, String linkLabel,
                                TriggerType event, String payload, Instant nextAttemptAt) {
        this.owner = owner;
        this.channel = channel;
        this.linkId = linkId;
        this.linkLabel = linkLabel;
        this.event = event;
        this.payload = payload;
        this.nextAttemptAt = nextAttemptAt;
    }
}
