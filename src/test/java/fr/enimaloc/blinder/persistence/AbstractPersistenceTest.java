package fr.enimaloc.blinder.persistence;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.link.LinkRepository;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestData.class)
abstract class AbstractPersistenceTest {

    @Autowired
    TestData data;

    @Autowired
    LinkRepository links;

    User owner;
    Domain domain;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        domain = data.domain("go.example.com", owner);
    }

    @Test
    void roundTripsAllLinkFields() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Link link = new Link(domain, "promo", "https://example.com/x", owner);
        link.setEnabled(false);
        link.setNotBefore(base);
        link.setExpiresAt(base.plus(Duration.ofDays(2)));
        link.setTtlAfterFirstUse(Duration.ofHours(24));
        link.setFirstUsedAt(base.plusSeconds(5));
        link.setMaxUses(3);
        link.setUseCount(1);
        link.setDeniedUrl("https://example.com/denied");
        Link saved = data.save(link);

        Link found = links.findById(saved.getId()).orElseThrow();
        assertThat(found.getSlug()).isEqualTo("promo");
        assertThat(found.getTargetUrl()).isEqualTo("https://example.com/x");
        assertThat(found.isEnabled()).isFalse();
        assertThat(found.getNotBefore()).isEqualTo(base);
        assertThat(found.getExpiresAt()).isEqualTo(base.plus(Duration.ofDays(2)));
        assertThat(found.getTtlAfterFirstUse()).isEqualTo(Duration.ofHours(24));
        assertThat(found.getFirstUsedAt()).isEqualTo(base.plusSeconds(5));
        assertThat(found.getMaxUses()).isEqualTo(3);
        assertThat(found.getUseCount()).isEqualTo(1);
        assertThat(found.getDeniedUrl()).isEqualTo("https://example.com/denied");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getVersion()).isNotNull();
        assertThat(found.getDomain().getId()).isEqualTo(domain.getId());
    }

    @Test
    void rejectsDuplicateSlugOnSameDomain() {
        data.link(domain, "promo", "https://a.example");
        assertThatThrownBy(() -> links.saveAndFlush(new Link(domain, "promo", "https://b.example", owner)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsSameSlugOnDifferentDomains() {
        Domain other = data.domain("other.example.com", owner);
        data.link(domain, "promo", "https://a.example");
        data.link(other, "promo", "https://b.example");
        assertThat(links.findByDomainAndSlug(other, "promo")).isPresent();
    }

    @Test
    void scopesQueriesByOwner() {
        User intruder = data.admin("intruder");
        Link mine = data.link(domain, "promo", "https://a.example");
        assertThat(links.findAllByOwnerOrderByCreatedAtDesc(owner)).extracting(Link::getId).containsExactly(mine.getId());
        assertThat(links.findAllByOwnerOrderByCreatedAtDesc(intruder)).isEmpty();
        assertThat(links.findByIdAndOwner(mine.getId(), intruder)).isEmpty();
        assertThat(links.findByIdAndOwner(mine.getId(), owner).orElseThrow().getDomain().getHost())
                .isEqualTo("go.example.com");
    }
}
