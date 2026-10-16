package fr.enimaloc.blinder.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;
import java.util.List;

@Validated
@ConfigurationProperties("blinder")
public record BlinderProperties(
        @DefaultValue @Valid Admin admin,
        @DefaultValue @Valid Slug slug,
        @DefaultValue @Valid Bots bots) {

    /**
     * @param domain   host du panel dédié ; {@code null} = panel uniquement via le préfixe {@code /_/}
     * @param timeZone fuseau dans lequel sont saisies les dates du panel
     */
    public record Admin(String domain, @DefaultValue("UTC") ZoneId timeZone) {
    }

    public record Slug(
            @DefaultValue("7") @Min(4) @Max(32) int generatedLength,
            @DefaultValue({"favicon.ico", "robots.txt", ".well-known", "error"}) List<String> reserved) {
    }

    public record Bots(
            @DefaultValue({"Discordbot", "Slackbot", "Twitterbot", "facebookexternalhit", "WhatsApp",
                    "TelegramBot", "LinkedInBot", "SkypeUriPreview"}) List<String> userAgents) {
    }
}
