package fr.enimaloc.blinder.notification;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.link.Link;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/** Règles de notification d'un lien. */
@Entity
@Table(name = "link_notification_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkNotificationSettings extends BaseEntity {

    @Setter(AccessLevel.NONE)
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false, unique = true)
    private Link link;

    @Column(nullable = false)
    private boolean onExhausted;

    @Column(nullable = false)
    private boolean onExpired;

    @Column(nullable = false)
    private boolean onDenials;

    @Column(nullable = false)
    private boolean onEachVisit;

    private Integer visitThreshold;

    @ManyToMany
    @JoinTable(name = "link_notification_channels",
            joinColumns = @JoinColumn(name = "settings_id"),
            inverseJoinColumns = @JoinColumn(name = "channel_id"))
    private Set<NotificationChannel> channels = new HashSet<>();

    public LinkNotificationSettings(Link link) {
        this.link = link;
    }
}
