package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.common.BaseEntity;
import fr.enimaloc.blinder.link.Link;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Dernier aperçu récupéré sur la cible d'un lien en mode PASSTHROUGH. */
@Entity
@Table(name = "link_previews")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkPreview extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false, unique = true)
    private Link link;

    @Column(length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(length = 2048)
    private String imageUrl;

    @Column(nullable = false)
    private Instant fetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PreviewStatus status;

    public LinkPreview(Link link) {
        this.link = link;
    }
}
