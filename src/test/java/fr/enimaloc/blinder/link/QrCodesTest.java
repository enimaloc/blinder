package fr.enimaloc.blinder.link;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodesTest {

    private final QrCodes qr = new QrCodes();

    @Test
    void svgIsASinglePathWithViewBox() {
        String svg = qr.svg("http://go.example.com/promo");
        assertThat(svg).startsWith("<svg").contains("viewBox=").contains("<path").contains("crispEdges");
        assertThat(svg.split("<path", -1)).hasSize(2);
    }

    @Test
    void pngHasExactSizeWhiteMarginAndBlackFinder() throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(qr.png("http://go.example.com/promo", 300)));
        assertThat(image.getWidth()).isEqualTo(300);
        assertThat(image.getHeight()).isEqualTo(300);
        assertThat(image.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        int modules = io.nayuki.qrcodegen.QrCode.encodeText("http://go.example.com/promo",
                io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM).size + 8;
        int scale = 300 / modules;
        int offset = (300 - scale * modules) / 2;
        int corner = offset + 4 * scale + scale / 2;
        assertThat(image.getRGB(corner, corner) & 0xFFFFFF).isEqualTo(0x000000);
    }

    @Test
    void sizeIsClamped() {
        assertThat(QrCodes.clampSize(10)).isEqualTo(128);
        assertThat(QrCodes.clampSize(5000)).isEqualTo(2048);
        assertThat(QrCodes.clampSize(null)).isEqualTo(512);
        assertThat(QrCodes.clampSize(700)).isEqualTo(700);
    }
}
