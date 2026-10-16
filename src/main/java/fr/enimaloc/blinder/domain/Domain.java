package fr.enimaloc.blinder.domain;

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

@Entity
@Table(name = "domains")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Domain extends BaseEntity {

    /** Toujours normalisé via {@code HostNormalizer} avant affectation. */
    @Column(nullable = false, unique = true, length = 253)
    private String host;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 2048)
    private String notFoundUrl;

    public Domain(String host, User owner) {
        this.host = host;
        this.owner = owner;
    }
}
