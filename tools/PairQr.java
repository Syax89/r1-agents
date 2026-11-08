import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;

/** Generatore offline: nel QR entrano indirizzo e utente, mai la password. */
class PairQr {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Uso: tools/pair-qr.sh <url-dashboard> <utente> [collegamento.png]");
            System.exit(2);
        }
        String address = args[0].contains("://") ? args[0] : "http://" + args[0];
        URI uri = new URI(address);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Usa solo l’origine del dashboard, senza password, percorsi o token.");
        String origin = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toString();
        String link = "r1agents://connect?v=1&url=" + URLEncoder.encode(origin, StandardCharsets.UTF_8) + "&user=" + URLEncoder.encode(args[1], StandardCharsets.UTF_8);
        var matrix = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 600, 600, Map.of(EncodeHintType.MARGIN, 4));
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) image.setRGB(x, y, matrix.get(x, y) ? 0 : 0xffffff);
        Path output = Path.of(args.length == 3 ? args[2] : "collegamento.png").toAbsolutePath();
        if (output.getParent() != null) java.nio.file.Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println(output);
    }
}
