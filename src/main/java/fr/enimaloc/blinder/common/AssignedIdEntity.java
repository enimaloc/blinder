package fr.enimaloc.blinder.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * Base des tables à fort volume (sans version ni horodatage) : identifiant UUID pré-généré,
 * {@link Persistable} évite le SELECT préalable de {@code save()} (insertion directe).
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssignedIdEntity other) || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(o)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
