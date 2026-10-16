package fr.enimaloc.blinder.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonMerge;
import fr.enimaloc.blinder.link.MetaMode;
import lombok.Data;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Représentation d'un lien en écriture (spec §7). Un champ {@code null} prend la valeur par défaut du formulaire.
 * Mutable : sert aussi de base au PATCH ({@code readerForUpdating}). Les champs en lecture seule de
 * {@link LinkResource} sont ignorés, pour qu'une représentation lue puisse être renvoyée telle quelle.
 */
@Data
@JsonIgnoreProperties({"id", "domain", "url", "useCount", "hasPassword", "status", "createdAt", "updatedAt"})
public class LinkInput {

    private UUID domainId;
    private String slug;
    private String targetUrl;
    private Boolean enabled;
    private OffsetDateTime notBefore;
    private OffsetDateTime expiresAt;
    private Long ttlMinutes;
    private Integer maxUses;
    private String deniedUrl;
    /** Écriture seule : définit le mot de passe. Jamais présent dans la base d'un PATCH. */
    @ToString.Exclude
    private String password;
    private Boolean removePassword;
    private List<String> ipAllowlist;
    private Boolean deviceProtected;
    private List<UUID> deviceIds;
    private Integer redirectStatus;
    private Boolean forwardQuery;
    private Boolean interstitial;
    private Integer interstitialSeconds;
    private MetaMode metaMode;
    private String metaTitle;
    private String metaDescription;
    private String metaImageUrl;
    private List<String> tags;
    /** Fusionné champ par champ lors d'un PATCH. */
    @JsonMerge
    private Notifications notifications;
    private Long version;

    @Data
    public static class Notifications {
        private Boolean exhausted;
        private Boolean expired;
        private Boolean denials;
        private Boolean eachVisit;
        private Integer threshold;
        private List<UUID> channelIds;
    }
}
