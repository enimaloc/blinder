package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.common.DurationSecondsConverter;
import fr.enimaloc.blinder.device.Device;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OptimisticLock;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "links")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "domain_id", nullable = false)
    private Domain domain;

    /** Toujours normalisé via {@link SlugPolicy#normalize(String)}. */
    @Column(nullable = false, length = 64)
    private String slug;

    @Column(nullable = false, length = 2048)
    private String targetUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant notBefore;

    private Instant expiresAt;

    @Convert(converter = DurationSecondsConverter.class)
    @Column(name = "ttl_after_first_use_seconds")
    private Duration ttlAfterFirstUse;

    /** Écrit uniquement à l'insertion : ensuite {@link LinkRepository} (consume / resetUsage). */
    @OptimisticLock(excluded = true)
    @Column(updatable = false)
    private Instant firstUsedAt;

    private Integer maxUses;

    /** Modifié en production uniquement par {@link UsageCounter} (UPDATE atomique) ou {@link LinkRepository#resetUsage}; jamais écrit par un UPDATE d'entité. */
    @OptimisticLock(excluded = true)
    @Column(nullable = false, updatable = false)
    private int useCount;

    @Column(length = 2048)
    private String deniedUrl;

    /** BCrypt (PasswordEncoder délégant) ; {@code null} = pas de mot de passe. */
    @Column(length = 255)
    private String passwordHash;

    /** Entrées normalisées par {@code IpAllowlist.normalize}, séparées par des virgules. */
    @Column(length = 4000)
    private String ipAllowlist;

    @ManyToMany
    @JoinTable(name = "link_devices",
            joinColumns = @JoinColumn(name = "link_id"),
            inverseJoinColumns = @JoinColumn(name = "device_id"))
    private Set<Device> devices = new HashSet<>();

    public Link(Domain domain, String slug, String targetUrl, User owner) {
        this.domain = domain;
        this.slug = slug;
        this.targetUrl = targetUrl;
        this.owner = owner;
    }
}
