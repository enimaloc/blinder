package fr.enimaloc.blinder.access;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VisitContextTest {

    @Test
    void toStringMasksThePassword() {
        VisitContext ctx = new VisitContext("203.0.113.1", "ua", false, "POST", Instant.EPOCH, "hunter2", null);
        assertThat(ctx.toString()).doesNotContain("hunter2").contains("***");
        assertThat(new VisitContext("203.0.113.1", "ua", false, "GET", Instant.EPOCH).toString())
                .contains("submittedPassword=null");
    }

    @Test
    void toStringIncludesTheRefererButNeverThePassword() {
        VisitContext ctx = new VisitContext("203.0.113.1", "ua", false, "GET", Instant.EPOCH,
                "hunter2", null, "https://news.example/a");
        assertThat(ctx.toString()).contains("referer=https://news.example/a").doesNotContain("hunter2");
        assertThat(ctx.referer()).isEqualTo("https://news.example/a");
    }

    @Test
    void legacyConstructorsLeaveRefererNull() {
        assertThat(new VisitContext("ip", "ua", false, "GET", Instant.EPOCH).referer()).isNull();
        assertThat(new VisitContext("ip", "ua", false, "GET", Instant.EPOCH, null, null).referer()).isNull();
    }
}
