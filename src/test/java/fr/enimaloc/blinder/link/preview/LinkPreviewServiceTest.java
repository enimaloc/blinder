package fr.enimaloc.blinder.link.preview;

import fr.enimaloc.blinder.domain.Domain;
import fr.enimaloc.blinder.link.Link;
import fr.enimaloc.blinder.support.TestData;
import fr.enimaloc.blinder.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestData.class)
class LinkPreviewServiceTest {

    @Autowired
    LinkPreviewService service;
    @Autowired
    LinkPreviewRepository previews;
    @Autowired
    TestData data;
    @MockitoBean
    PreviewFetcher fetcher;

    User owner;
    Link link;

    @BeforeEach
    void setUp() {
        data.reset();
        owner = data.admin("admin");
        Domain domain = data.domain("go.example.com", owner);
        link = data.link(domain, "p", "https://dest.example.org/");
    }

    @Test
    void storesSuccessfulPreview() {
        when(fetcher.fetch("https://dest.example.org/"))
                .thenReturn(Optional.of(new PreviewData("T", "D", "https://dest.example.org/i.png")));
        LinkPreview preview = service.refresh(owner, link.getId());
        assertThat(preview.getStatus()).isEqualTo(PreviewStatus.OK);
        assertThat(previews.findByLinkId(link.getId())).get()
                .satisfies(p -> assertThat(p.getTitle()).isEqualTo("T"));
    }

    @Test
    void failureWithoutPreviousIsRecordedAsFailed() {
        when(fetcher.fetch(anyString())).thenReturn(Optional.empty());
        assertThat(service.refresh(owner, link.getId()).getStatus()).isEqualTo(PreviewStatus.FAILED);
    }

    @Test
    void failureKeepsThePreviousSuccessfulPreview() {
        data.preview(link, "Old", null, null, PreviewStatus.OK);
        when(fetcher.fetch(anyString())).thenReturn(Optional.empty());
        LinkPreview preview = service.refresh(owner, link.getId());
        assertThat(preview.getStatus()).isEqualTo(PreviewStatus.OK);
        assertThat(preview.getTitle()).isEqualTo("Old");
    }

    @Test
    void otherOwnersLinkIsNotFound() {
        User intruder = data.admin("intruder");
        assertThatThrownBy(() -> service.refresh(intruder, link.getId())).isInstanceOf(ResponseStatusException.class);
    }
}
