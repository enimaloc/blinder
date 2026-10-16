package fr.enimaloc.blinder.link;

import io.nayuki.qrcodegen.QrCode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Génération de QR codes (correction d'erreur M, marge de 4 modules, noir sur blanc). */
@Component
public class QrCodes {

    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 2048;
    public static final int DEFAULT_SIZE = 512;
    private static final int BORDER = 4;

    public static int clampSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_SIZE;
        }
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, requested));
    }

    public String svg(String text) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int total = qr.size + BORDER * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + BORDER).append(',').append(y + BORDER).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + total + " " + total
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000\"/></svg>\n";
    }

    /** Image carrée de {@code size} px exactement ; les modules sont arrondis à l'entier et le motif est centré. */
    public byte[] png(String text, int size) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int total = qr.size + BORDER * 2;
        int scale = Math.max(1, size / total);
        int offset = (size - scale * total) / 2;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setColor(Color.BLACK);
            for (int y = 0; y < qr.size; y++) {
                for (int x = 0; x < qr.size; x++) {
                    if (qr.getModule(x, y)) {
                        g.fillRect(offset + (x + BORDER) * scale, offset + (y + BORDER) * scale, scale, scale);
                    }
                }
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
