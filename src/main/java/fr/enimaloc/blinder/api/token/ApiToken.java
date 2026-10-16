package fr.enimaloc.blinder.api.token;

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

import java.time.Instant;

/** Jeton d'API : seul le SHA-256 du jeton est stocké, avec un préfixe affichable. */
@Entity
@Table(name = "api_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String tokenPrefix;

    @Column(nullable = false, length = 64, unique = true)
    private String tokenHash;

    private Instant lastUsedAt;

    public ApiToken(User owner, String name, String tokenPrefix, String tokenHash) {
        this.owner = owner;
        this.name = name;
        this.tokenPrefix = tokenPrefix;
        this.tokenHash = tokenHash;
    }

    public void touch(Instant now) {
        this.lastUsedAt = now;
    }
}
