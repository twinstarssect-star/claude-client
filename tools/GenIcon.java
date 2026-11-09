import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** java tools/GenIcon.java src/main/resources/assets/claudeclient/icon.png - clay tile with a cream "C" ring. */
public class GenIcon {
	public static void main(String[] args) throws Exception {
		int s = 128;
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(new GradientPaint(0, 0, new Color(0xE08A6B), 0, s, new Color(0xC2603F)));
		g.fill(new RoundRectangle2D.Float(4, 4, s - 8, s - 8, 34, 34));

		// "C": a thick ring with a wedge cut out on the right.
		Area ring = new Area(new Ellipse2D.Float(28, 28, 72, 72));
		ring.subtract(new Area(new Ellipse2D.Float(48, 48, 32, 32)));
		ring.subtract(new Area(new Rectangle2D.Float(72, 54, 40, 20)));
		g.setColor(new Color(0x7A3A24));
		g.translate(0, 3);
		g.fill(ring);
		g.translate(0, -3);
		g.setColor(new Color(0xF5F0E6));
		g.fill(ring);
		g.dispose();
		File out = new File(args[0]);
		out.getParentFile().mkdirs();
		ImageIO.write(img, "png", out);
	}
}
