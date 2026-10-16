package fr.enimaloc.blinder.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BlinderPropertiesTest {

    @Autowired
    BlinderProperties properties;

    @Autowired
    Clock clock;

    @Test
    void exposesDefaults() {
        assertThat(properties.admin().domain()).isNull();
        assertThat(properties.admin().timeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(properties.slug().generatedLength()).isEqualTo(7);
        assertThat(properties.slug().reserved())
                .containsExactlyInAnyOrder("favicon.ico", "robots.txt", ".well-known", "error");
        assertThat(properties.bots().userAgents()).contains("Discordbot", "facebookexternalhit");
    }

    @Test
    void clockIsUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
