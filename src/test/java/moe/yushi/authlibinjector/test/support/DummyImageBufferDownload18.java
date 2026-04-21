package moe.yushi.authlibinjector.test.support;

import java.awt.image.BufferedImage;

public class DummyImageBufferDownload18 implements TestImageBufferWithCallback {
	private int[] pixels;
	private int width;
	private int height;

	public DummyImageBufferDownload18() {
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

	private boolean hasTransparentPixel(int x0, int y0, int x1, int y1) {
		return false;
	}
}
