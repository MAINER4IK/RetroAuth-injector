package moe.yushi.authlibinjector.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import moe.yushi.authlibinjector.transform.ClassTransformer;
import moe.yushi.authlibinjector.transform.support.ImageBufferDownloadTransformUnit;

public class ImageBufferDownloadTransformUnitTest {

	@Test
	public void transformsParseUserSkinEvenWhenHelpersAppearLater() throws Exception {
		assertTransformed(moe.yushi.authlibinjector.test.support.DummyImageBufferDownload.class);
	}

	@Test
	public void transforms18ImageBufferAlias() throws Exception {
		assertTransformed(moe.yushi.authlibinjector.test.support.DummyImageBufferDownload18.class);
	}

	@Test
	public void transforms152ImageBufferAliasWithoutCallbackMethod() throws Exception {
		assertTransformed(moe.yushi.authlibinjector.test.support.DummyImageBufferDownload152.class);
	}

	private static byte[] readClassBytes(Class<?> type) throws IOException {
		String resourceName = type.getSimpleName() + ".class";
		try (InputStream in = type.getResourceAsStream(resourceName)) {
			if (in == null) {
				throw new IOException("Unable to load class bytes for " + type.getName());
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private static void assertTransformed(Class<?> type) throws Exception {
		ClassTransformer transformer = new ClassTransformer();
		transformer.units.add(new ImageBufferDownloadTransformUnit());

		String internalName = type.getName().replace('.', '/');
		byte[] classBytes = readClassBytes(type);
		byte[] transformed = transformer.transform(
				ImageBufferDownloadTransformUnitTest.class.getClassLoader(),
				internalName,
				null,
				null,
				classBytes);

		assertNotNull(transformed);
	}
}
