package moe.yushi.authlibinjector.test.support;

import java.awt.image.BufferedImage;

public interface TestImageBufferWithCallback {

	BufferedImage parseUserSkin(BufferedImage image);

	void skinAvailable();
}
