package fr.enimaloc.blinder.link;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.common.DurationSecondsConverter;
import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OptimisticLock;

import java.time.Duration;
import java.time.Instant;

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

    @OptimisticLock(excluded = true)
    private Instant firstUsedAt;

    private Integer maxUses;

    /** Modifié en production uniquement par {@link UsageCounter} (UPDATE atomique) ou un reset admin. */
    @OptimisticLock(excluded = true)
    @Column(nullable = false)
    private int useCount;

    @Column(length = 2048)
    private String deniedUrl;

    public Link(Domain domain, String slug, String targetUrl, User owner) {
        this.domain = domain;
        this.slug = slug;
        this.targetUrl = targetUrl;
        this.owner = owner;
    }
}
