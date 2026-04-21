package moe.yushi.authlibinjector.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.httpd.LegacySkinProcessor;

public class LegacySkinProcessorTest {

	@Test
	public void preservesHdClassicResolution() {
		boolean oldResize = Config.agentaSkinResize;
		boolean oldMerge = Config.agentaSkinMerge;
		try {
			Config.agentaSkinResize = true;
			Config.agentaSkinMerge = true;

			BufferedImage result = LegacySkinProcessor.processSkin(new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB), true);
			assertEquals(128, result.getWidth());
			assertEquals(64, result.getHeight());
		} finally {
			Config.agentaSkinResize = oldResize;
			Config.agentaSkinMerge = oldMerge;
		}
	}

	@Test
	public void convertsModernSquareSkinIntoLegacyCanvas() {
		boolean oldResize = Config.agentaSkinResize;
		boolean oldMerge = Config.agentaSkinMerge;
		try {
			Config.agentaSkinResize = true;
			Config.agentaSkinMerge = true;

			BufferedImage result = LegacySkinProcessor.processSkin(new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), true);
			assertEquals(128, result.getWidth());
			assertEquals(64, result.getHeight());
		} finally {
			Config.agentaSkinResize = oldResize;
			Config.agentaSkinMerge = oldMerge;
		}
	}
}
