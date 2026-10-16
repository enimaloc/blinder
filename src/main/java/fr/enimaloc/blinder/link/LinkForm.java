package fr.enimaloc.blinder.link;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Data
public class LinkForm {

    public static final String DATETIME_LOCAL = "yyyy-MM-dd'T'HH:mm";

    @NotNull(message = "Choisissez un domaine")
    private UUID domainId;

    /** Vide = généré. */
    private String slug;

    @NotBlank
    @Size(max = 2048)
    private String targetUrl;

    private boolean enabled = true;

    /** Heure locale dans {@code blinder.admin.time-zone}. */
    @DateTimeFormat(pattern = DATETIME_LOCAL)
    private LocalDateTime notBefore;

    @DateTimeFormat(pattern = DATETIME_LOCAL)
    private LocalDateTime expiresAt;

    @Positive
    @Max(5_256_000)
    private Long ttlMinutes;

    @Min(1)
    private Integer maxUses;

    @Size(max = 2048)
    private String deniedUrl;

    private Long version;

    /** Vide = conserver le mot de passe actuel. Jamais réaffiché. */
    @ToString.Exclude
    private String newPassword;

    private boolean removePassword;

    /** Une entrée par ligne (IP ou CIDR). */
    @Size(max = 4000)
    private String ipAllowlist;

    /** Réserver aux appareils autorisés (reste coché même si plus aucun appareil ne subsiste). */
    private boolean deviceProtected;

    private Set<UUID> deviceIds = new LinkedHashSet<>();
}
