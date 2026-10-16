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

/** Destination de notification d'un utilisateur (webhook signé ou e-mail). */
@Entity
@Table(name = "notification_channels")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationChannel extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType type;

    @Column(nullable = false, length = 2048)
    private String target;

    /** Clé HMAC du webhook ; absente pour l'e-mail. */
    @Column(length = 64)
    private String secret;

    @Column(nullable = false)
    private boolean allowPrivateNetwork;

    @Column(nullable = false)
    private boolean preselected;

    @Column(nullable = false)
    private boolean enabled = true;

    public NotificationChannel(User owner, String name, ChannelType type, String target) {
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.target = target;
    }
}
