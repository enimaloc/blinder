package fr.enimaloc.blinder.link.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewExtractorTest {

    @Test
    void prefersOpenGraphAndResolvesRelativeImage() {
        String html = """
                <html><head><title>Fallback</title>
                <meta property="og:title" content="OG title">
                <meta property="og:description" content="OG desc">
                <meta property="og:image" content="/img/card.png">
                </head><body></body></html>""";
        PreviewData data = PreviewExtractor.extract(html, "https://site.example.org/page");
        assertThat(data).isEqualTo(new PreviewData("OG title", "OG desc", "https://site.example.org/img/card.png"));
    }

    @Test
    void fallsBackToTwitterThenTitleAndDescription() {
        String html = """
                <html><head><title> Page title </title>
                <meta name="description" content="Plain description">
                <meta name="twitter:image" content="https://cdn.example.org/t.png">
                </head></html>""";
        assertThat(PreviewExtractor.extract(html, "https://site.example.org/"))
                .isEqualTo(new PreviewData("Page title", "Plain description", "https://cdn.example.org/t.png"));
    }

    @Test
    void truncatesAndDropsUnsafeImage() {
        String html = "<html><head><meta property=\"og:title\" content=\"" + "a".repeat(300) + "\">"
                + "<meta property=\"og:image\" content=\"javascript:alert(1)\"></head></html>";
        PreviewData data = PreviewExtractor.extract(html, "https://site.example.org/");
        assertThat(data.title()).hasSize(200);
        assertThat(data.imageUrl()).isNull();
    }

    @Test
    void emptyDocumentGivesNull() {
        assertThat(PreviewExtractor.extract("<html><head></head></html>", "https://site.example.org/")).isNull();
    }
}
