package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.domain.Domain;
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

@Entity
@Table(name = "device_enrollments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceEnrollment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "start_domain_id", nullable = false)
    private Domain startDomain;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Posé uniquement par {@link DeviceEnrollmentRepository#consume}. */
    private Instant usedAt;

    public DeviceEnrollment(User owner, Domain startDomain, String name, String tokenHash, Instant expiresAt) {
        this.owner = owner;
        this.startDomain = startDomain;
        this.name = name;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }
}
