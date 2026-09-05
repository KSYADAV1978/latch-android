import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a business card carrying a vCard QR code, for FR-1203/FR-1225's device row.
 *
 * The fixture the device backlog names, card-anita.png, was never committed — it was a local
 * file. This regenerates an equivalent from the vCard grammar itself, using the ZXing already
 * on this project's classpath, so nothing new is added to build it.
 *
 * The printed text is deliberately DIFFERENT from the vCard's contents. That is the whole point
 * of the row: if the sheet opens from the grammar it shows the vCard's fields, and if it falls
 * through to the classifier it shows the printed ones. One glance tells you which happened.
 */
public class MakeCard {
    public static void main(String[] args) throws Exception {
        String vcard = String.join("\r\n",
                "BEGIN:VCARD",
                "VERSION:3.0",
                "N:Kapoor;Anita;;;",
                "FN:Anita Kapoor",
                "ORG:Northwind Textiles Private Limited",
                "TITLE:Head of Sourcing",
                "TEL;TYPE=CELL:+91 98200 12345",
                "EMAIL;TYPE=WORK:anita.kapoor@northwind.example",
                "URL:www.northwind.example",
                "END:VCARD") + "\r\n";

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(vcard, BarcodeFormat.QR_CODE, 900, 900, hints);

        // 1050x600 at 300dpi is a 3.5 x 2 inch card. Rendered large so a photograph of a screen
        // still gives the decoder enough module size to work with.
        BufferedImage img = new BufferedImage(1700, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1700, 1000);

        g.setColor(Color.BLACK);
        g.setFont(new Font("Serif", Font.BOLD, 62));
        g.drawString("Vikram Chandrasekaran", 50, 300);
        g.setFont(new Font("Serif", Font.PLAIN, 40));
        g.drawString("Regional Sales Director", 50, 380);
        g.setFont(new Font("Serif", Font.PLAIN, 44));
        g.drawString("Southern Weaving Mills Ltd", 50, 500);
        g.setFont(new Font("SansSerif", Font.PLAIN, 34));
        g.drawString("m  +91 90000 11111", 50, 640);
        g.drawString("e  vikram@southernweaving.example", 50, 700);
        g.drawString("14 Anna Salai, Chennai - 600 002", 50, 760);

        // The QR, right-hand side.
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                if (matrix.get(x, y)) {
                    img.setRGB(740 + x, 50 + y, 0x000000);
                }
            }
        }
        g.setFont(new Font("SansSerif", Font.PLAIN, 26));
        g.setColor(Color.DARK_GRAY);
        g.drawString("scan for contact", 1080, 985);
        g.dispose();

        File out = new File(args[0]);
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
        System.out.println("PRINTED name : Vikram Chandrasekaran  (what the classifier would read)");
        System.out.println("VCARD   name : Anita Kapoor            (what the grammar should read)");
    }
}
