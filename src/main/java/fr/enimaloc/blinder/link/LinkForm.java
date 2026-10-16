package fr.enimaloc.blinder.link;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class LinkForm {

    public static final String DATETIME_LOCAL = "yyyy-MM-dd'T'HH:mm";

    @NotNull(message = "Choisissez un domaine")
    private UUID domainId;

    /** Vide = généré. */
    private String slug;

    @NotBlank
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

    private String deniedUrl;

    private Long version;
}
