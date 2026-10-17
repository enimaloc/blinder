package fr.enimaloc.blinder.link;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /**
     * Instants exacts fournis par l'API, prioritaires sur {@link #notBefore}/{@link #expiresAt} quand ils leur
     * correspondent (heure répétée au passage à l'heure d'hiver). Sans setter : le panel ne peut pas les lier.
     */
    @Setter(AccessLevel.NONE)
    private Instant exactNotBefore;

    @Setter(AccessLevel.NONE)
    private Instant exactExpiresAt;

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

    @NotNull
    private Integer redirectStatus = 302;

    private boolean forwardQuery;

    private boolean interstitial;

    @Min(0)
    @Max(60)
    private int interstitialSeconds;

    @NotNull
    private MetaMode metaMode = MetaMode.NONE;

    @Size(max = 200)
    private String metaTitle;

    @Size(max = 500)
    private String metaDescription;

    @Size(max = 2048)
    private String metaImageUrl;

    private boolean notifyExhausted;

    private boolean notifyExpired;

    private boolean notifyDenials;

    private boolean notifyEachVisit;

    @Min(1)
    private Integer notifyThreshold;

    private Set<UUID> notifyChannelIds = new LinkedHashSet<>();

    /**
     * Contrôle de destination : en-tête que la cible doit renvoyer (nom, valeur exacte) pour que la redirection ait
     * lieu. Les deux vides = pas de contrôle ; validés par {@code LinkHeaderCheckService}. Un lien contrôlé redirige
     * toujours en temporaire (301 → 302, 308 → 307).
     */
    private String headerName;

    private String headerValue;

    /** Noms séparés par des virgules ; les inconnus sont créés. */
    @Size(max = 1000)
    private String tags;

    /** Date d'activation exacte (API) ; renseigne aussi l'heure locale correspondante dans {@code zone}. */
    public void exactNotBefore(Instant instant, ZoneId zone) {
        this.exactNotBefore = instant;
        this.notBefore = LocalDateTime.ofInstant(instant, zone);
    }

    /** Date d'expiration exacte (API) ; renseigne aussi l'heure locale correspondante dans {@code zone}. */
    public void exactExpiresAt(Instant instant, ZoneId zone) {
        this.exactExpiresAt = instant;
        this.expiresAt = LocalDateTime.ofInstant(instant, zone);
    }

    /** Au moins une alerte (déclencheur) est-elle cochée ? */
    public boolean declaresAlerts() {
        return notifyExhausted || notifyExpired || notifyDenials || notifyEachVisit || notifyThreshold != null;
    }

    /** Le formulaire posté déclare-t-il une limite ou une protection (pour l'avertissement d'aperçu) ? */
    public boolean declaresLimitOrProtection(boolean hasExistingPassword) {
        return (newPassword != null && !newPassword.isEmpty())
                || (hasExistingPassword && !removePassword)
                || (ipAllowlist != null && !ipAllowlist.isBlank())
                || deviceProtected
                || (deviceIds != null && !deviceIds.isEmpty())
                || maxUses != null
                || ttlMinutes != null;
    }
}
