package fr.enimaloc.blinder.stats;

import fr.enimaloc.blinder.access.DenyReason;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Une requête sur un lien court (immuable une fois écrite). Jamais de mot de passe ici. */
@Entity
@Table(name = "visits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Visit extends AssignedIdEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private Link link;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private VisitOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DenyReason denyReason;

    @Column(length = 8)
    private String httpMethod;

    @Column(nullable = false)
    private boolean bot;

    @Column(length = 45)
    private String ip;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 16)
    private String browser;

    @Column(length = 16)
    private String os;

    @Column(length = 1024)
    private String referer;

    @Column(length = 255)
    private String refererHost;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String country;

    @Column(length = 128)
    private String city;

    public Visit(Link link, User owner, Instant occurredAt, VisitOutcome outcome, DenyReason denyReason,
                 String httpMethod, boolean bot, String ip, String userAgent, String browser, String os,
                 String referer, String refererHost, String country, String city) {
        this.link = link;
        this.owner = owner;
        this.occurredAt = occurredAt;
        this.outcome = outcome;
        this.denyReason = denyReason;
        this.httpMethod = httpMethod;
        this.bot = bot;
        this.ip = ip;
        this.userAgent = userAgent;
        this.browser = browser;
        this.os = os;
        this.referer = referer;
        this.refererHost = refererHost;
        this.country = country;
        this.city = city;
    }
}
