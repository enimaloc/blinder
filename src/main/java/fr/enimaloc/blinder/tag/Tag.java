package fr.enimaloc.blinder.tag;

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

import java.util.Locale;

/** Étiquette d'un utilisateur ; unique par (propriétaire, nom sans casse). */
@Entity
@Table(name = "tags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 40)
    private String name;

    /** Nom en minuscules ({@link Locale#ROOT}) : clé de comparaison sans casse. */
    @Column(nullable = false, length = 40)
    private String nameKey;

    /** {@code #rrggbb} ou {@code null}. */
    @Setter
    @Column(length = 7)
    private String color;

    public Tag(User owner, String name) {
        this.owner = owner;
        rename(name);
    }

    public void rename(String name) {
        this.name = name;
        this.nameKey = name.toLowerCase(Locale.ROOT);
    }

    /** Couleur de texte lisible sur {@link #color} (pour les gabarits). */
    public String textColor() {
        return TagNames.textColor(color);
    }
}
