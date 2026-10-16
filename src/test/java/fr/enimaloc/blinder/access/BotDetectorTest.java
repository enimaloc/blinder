package fr.enimaloc.blinder.access;

import fr.enimaloc.blinder.common.BlinderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotDetectorTest {

    final BotDetector detector = new BotDetector(new BlinderProperties(
            new BlinderProperties.Admin(null, ZoneOffset.UTC),
            new BlinderProperties.Slug(7, List.of()),
            new BlinderProperties.Bots(List.of("Discordbot", "facebookexternalhit", "WhatsApp"))));

    @ParameterizedTest
    @ValueSource(strings = {
            "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)",
            "facebookexternalhit/1.1",
            "whatsapp/2.23.20"})
    void detectsPreviewBotsCaseInsensitively(String ua) {
        assertThat(detector.isBot(ua)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0"})
    void humansAreNotBots(String ua) {
        assertThat(detector.isBot(ua)).isFalse();
    }

    @Test
    void blankNeedlesDoNotClassifyEveryoneAsBot() {
        BotDetector lenient = new BotDetector(new BlinderProperties(
                new BlinderProperties.Admin(null, ZoneOffset.UTC),
                new BlinderProperties.Slug(7, List.of()),
                new BlinderProperties.Bots(List.of("Discordbot", "", "  ", " Slackbot "))));

        assertThat(lenient.isBot("Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0")).isFalse();
        assertThat(lenient.isBot("Discordbot/2.0")).isTrue();
        assertThat(lenient.isBot("Slackbot-LinkExpanding 1.0")).isTrue();
    }
}
