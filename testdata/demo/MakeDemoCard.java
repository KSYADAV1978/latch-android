import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the business card photographed in the demo video (docs/OAUTH-VERIFICATION.md).
 *
 * A fictional person on a reserved domain, printed and nothing else. It deliberately has no QR
 * code, unlike testdata/fr1203/card-qr.png: the video is meant to show the photograph being read,
 * and a card whose QR decodes would show the vCard grammar answering instead. The telephone number
 * is the one card-qr.png already uses. It is not a real card and never enters FR-1222's corpus.
 *
 *     java testdata/demo/MakeDemoCard.java testdata/demo/card-demo.png
 *
 * Java's own imaging only — single-file source launch, no classpath, no dependency.
 */
public class MakeDemoCard {
    public static void main(String[] args) throws Exception {
        int width = 1750;
        int height = 1000;
        BufferedImage card = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(0xB3, 0x26, 0x1E));
        g.fillRect(0, 0, 36, height);

        g.setColor(new Color(0x1F, 0x1D, 0x1A));
        g.setFont(new Font("SansSerif", Font.BOLD, 84));
        g.drawString("Meera Menon", 110, 250);
        g.setFont(new Font("SansSerif", Font.PLAIN, 52));
        g.drawString("Head of Operations", 110, 330);
        g.setFont(new Font("SansSerif", Font.BOLD, 56));
        g.drawString("Example Textiles Private Limited", 110, 470);

        g.setFont(new Font("SansSerif", Font.PLAIN, 48));
        g.drawString("Mobile  +91 90000 11111", 110, 640);
        g.drawString("meera@example.com", 110, 710);
        g.drawString("www.example.com", 110, 780);
        g.drawString("12 MG Road, Bengaluru 560001", 110, 850);
        g.dispose();

        File out = new File(args[0]);
        ImageIO.write(card, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }
}
