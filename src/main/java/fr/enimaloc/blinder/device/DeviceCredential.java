package fr.enimaloc.blinder.device;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.domain.Domain;
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

/** Secret d'un appareil pour un domaine (le cookie est propre à chaque host). */
@Entity
@Table(name = "device_credentials")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceCredential extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "domain_id", nullable = false)
    private Domain domain;

    @Column(nullable = false, length = 64)
    private String secretHash;

    public DeviceCredential(Device device, Domain domain, String secretHash) {
        this.device = device;
        this.domain = domain;
        this.secretHash = secretHash;
    }
}
