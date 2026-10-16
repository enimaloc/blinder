package fr.enimaloc.blinder.api;

import fr.enimaloc.blinder.link.MetaMode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Représentation d'un lien renvoyée par l'API (spec §7) ; le mot de passe n'y figure jamais. */
public record LinkResource(
        UUID id,
        UUID domainId,
        String domain,
        String slug,
        String url,
        String targetUrl,
        boolean enabled,
        OffsetDateTime notBefore,
        OffsetDateTime expiresAt,
        Long ttlMinutes,
        Integer maxUses,
        int useCount,
        String deniedUrl,
        boolean hasPassword,
        List<String> ipAllowlist,
        boolean deviceProtected,
        List<UUID> deviceIds,
        int redirectStatus,
        boolean forwardQuery,
        boolean interstitial,
        int interstitialSeconds,
        MetaMode metaMode,
        String metaTitle,
        String metaDescription,
        String metaImageUrl,
        List<String> tags,
        Notifications notifications,
        String status,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public record Notifications(boolean exhausted, boolean expired, boolean denials, boolean eachVisit,
                                Integer threshold, List<UUID> channelIds) {

        static final Notifications NONE = new Notifications(false, false, false, false, null, List.of());
    }
}
