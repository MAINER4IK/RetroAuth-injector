package moe.yushi.authlibinjector.httpd;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import moe.yushi.authlibinjector.Config;

public final class LegacySkinProcessor {

	private LegacySkinProcessor() {
	}

	public static BufferedImage processSkin(BufferedImage source) {
		return processSkin(source, Config.agentaSkinHd);
	}

	public static BufferedImage processSkin(BufferedImage source, boolean preserveHd) {
		if (source == null) {
			return null;
		}
		if (!Config.agentaSkinMerge && !Config.agentaSkinResize) {
			return source;
		}
		if (source.getWidth() < 64 || source.getHeight() < 32) {
			return source;
		}

		int scale = Math.max(1, Math.min(source.getWidth() / 64, source.getHeight() / 32));
		BufferedImage skin = source;

		if (Config.agentaSkinMerge && source.getHeight() >= 64 * scale) {
			skin = mergeSquareSkin(skin, scale);
		}

		if (!Config.agentaSkinResize) {
			return skin;
		}

		int targetWidth = preserveHd ? Math.max(64, scale * 64) : 64;
		int targetHeight = preserveHd ? Math.max(32, scale * 32) : 32;
		if (skin.getWidth() == targetWidth && skin.getHeight() == targetHeight) {
			return skin;
		}
		return copyRegion(skin, targetWidth, targetHeight);
	}

	private static BufferedImage mergeSquareSkin(BufferedImage skin, int scale) {
		BufferedImage merged = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = merged.createGraphics();
		g2d.drawImage(skin, 0, -16 * scale, null);
		g2d.setComposite(AlphaComposite.Clear);
		g2d.fillRect(0, 0, 64 * scale, 16 * scale);
		g2d.setComposite(AlphaComposite.DstOver);
		g2d.drawImage(skin, 0, 0, null);
		g2d.dispose();
		return merged;
	}

	private static BufferedImage copyRegion(BufferedImage source, int width, int height) {
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = copy.createGraphics();
		g2d.drawImage(source, 0, 0, width, height, 0, 0, width, height, null);
		g2d.dispose();
		return copy;
	}
}
