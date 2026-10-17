package fr.enimaloc.blinder.link.check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * En-tête que la destination d'un lien doit renvoyer pour que la redirection ait lieu. Au plus un par lien
 * (identifiant = celui du lien) ; pas de ligne = pas de contrôle.
 */
@Entity
@Table(name = "link_header_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkHeaderCheck {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "link_id", length = 36, nullable = false, updatable = false)
    private UUID linkId;

    @Setter
    @Column(nullable = false, length = 100)
    private String headerName;

    @Setter
    @Column(nullable = false, length = 500)
    private String expectedValue;

    public LinkHeaderCheck(UUID linkId, String headerName, String expectedValue) {
        this.linkId = linkId;
        this.headerName = headerName;
        this.expectedValue = expectedValue;
    }
}
