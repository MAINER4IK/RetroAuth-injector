package moe.yushi.authlibinjector.test.support;

import java.awt.image.BufferedImage;

public class DummyImageBufferDownload implements TestImageBufferWithCallback {
	private int[] pixels;
	private int width;
	private int height;

	public DummyImageBufferDownload() {
	}

	@Override
	public BufferedImage parseUserSkin(BufferedImage image) {
		return image;
	}

	@Override
	public void skinAvailable() {
	}

	private void clearRegion(int x0, int y0, int x1, int y1) {
	}

	private void forceOpaqueRegion(int x0, int y0, int x1, int y1) {
	}
}
