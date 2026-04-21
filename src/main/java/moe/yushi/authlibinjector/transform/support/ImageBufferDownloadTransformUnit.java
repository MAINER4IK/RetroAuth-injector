package moe.yushi.authlibinjector.transform.support;

import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.httpd.LegacySkinProcessor;
import moe.yushi.authlibinjector.transform.CallbackMethod;
import moe.yushi.authlibinjector.transform.TransformContext;
import moe.yushi.authlibinjector.transform.TransformUnit;

public class ImageBufferDownloadTransformUnit implements TransformUnit {
	private static final String TARGET_DESCRIPTOR = "(Ljava/awt/image/BufferedImage;)Ljava/awt/image/BufferedImage;";
	private static final String CALLBACK_DESCRIPTOR = "()V";
	private static final ConcurrentMap<String, Boolean> IMAGE_BUFFER_INTERFACE_CACHE = new ConcurrentHashMap<>();

	@Override
	public Optional<ClassVisitor> transform(ClassLoader classLoader, String className, ClassVisitor writer, TransformContext ctx) {
		return Optional.of(new ClassVisitor(ASM9, writer) {
			private boolean imageBufferInterface;
			private int intFieldCount;
			private boolean hasIntArrayField;
			private boolean patched;

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				if (interfaces != null) {
					for (String iface : interfaces) {
						if (isImageBufferInterface(classLoader, iface)) {
							imageBufferInterface = true;
							break;
						}
					}
				}
				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				if ("I".equals(descriptor)) {
					intFieldCount++;
				} else if ("[I".equals(descriptor)) {
					hasIntArrayField = true;
				}
				return super.visitField(access, name, descriptor, signature, value);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				if (!patched && imageBufferInterface && hasIntArrayField && intFieldCount >= 2 && TARGET_DESCRIPTOR.equals(descriptor)) {
					ctx.markModified();
					patched = true;
					MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
					mv.visitCode();
					mv.visitVarInsn(ALOAD, 0);
					mv.visitVarInsn(ALOAD, 1);
					ctx.invokeCallback(mv, ImageBufferDownloadTransformUnit.class, "processSkin");
					mv.visitInsn(ARETURN);
					mv.visitMaxs(-1, -1);
					mv.visitEnd();
					return null;
				}
				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}
		});
	}

	private static boolean isImageBufferInterface(ClassLoader classLoader, String internalName) {
		Boolean cached = IMAGE_BUFFER_INTERFACE_CACHE.get(internalName);
		if (cached != null) {
			return cached.booleanValue();
		}

		boolean match = inspectImageBufferInterface(classLoader, internalName);
		IMAGE_BUFFER_INTERFACE_CACHE.putIfAbsent(internalName, Boolean.valueOf(match));
		return match;
	}

	private static boolean inspectImageBufferInterface(ClassLoader classLoader, String internalName) {
		try (InputStream in = openClassStream(classLoader, internalName)) {
			if (in == null) {
				return false;
			}

			ClassReader reader = new ClassReader(in);
			if ((reader.getAccess() & Opcodes.ACC_INTERFACE) == 0) {
				return false;
			}

			InterfaceShapeVisitor visitor = new InterfaceShapeVisitor();
			reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if (visitor.matchesImageBufferShape()) {
				return true;
			}

			for (String parent : reader.getInterfaces()) {
				if (isImageBufferInterface(classLoader, parent)) {
					return true;
				}
			}
			return false;
		} catch (IOException ignored) {
			return false;
		}
	}

	private static InputStream openClassStream(ClassLoader classLoader, String internalName) {
		String resource = internalName + ".class";
		InputStream in = null;
		if (classLoader != null) {
			in = classLoader.getResourceAsStream(resource);
		}
		if (in == null) {
			in = ClassLoader.getSystemResourceAsStream(resource);
		}
		return in;
	}

	@CallbackMethod
	public static BufferedImage processSkin(Object imageBuffer, BufferedImage source) {
		if (source == null) {
			return null;
		}

		BufferedImage processed = LegacySkinProcessor.processSkin(source, Config.agentaSkinHd);
		int scale = Math.max(1, Math.min(processed.getWidth() / 64, processed.getHeight() / 32));
		int width = Math.max(64, scale * 64);
		int height = Math.max(32, scale * 32);

		BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = canvas.getGraphics();
		graphics.drawImage(processed, 0, 0, null);
		graphics.dispose();

		try {
			Field intArrayField = findPixelsField(imageBuffer);
			List<Field> intFields = findIntFields(imageBuffer);
			Field widthField = intFields.get(0);
			Field heightField = intFields.get(1);
			MaskMethods maskMethods = findMaskMethods(imageBuffer, intArrayField, widthField, heightField);

			widthField.setInt(imageBuffer, width);
			heightField.setInt(imageBuffer, height);
			intArrayField.set(imageBuffer, ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData());
			maskMethods.opaque.invoke(imageBuffer, 0, 0, 32 * scale, 16 * scale);
			maskMethods.transparent.invoke(imageBuffer, 32 * scale, 0, 64 * scale, 32 * scale);
			maskMethods.opaque.invoke(imageBuffer, 0, 16 * scale, 64 * scale, 32 * scale);
			log(INFO, "HD skin patch path hit: class=" + imageBuffer.getClass().getName() + " source=" + source.getWidth() + "x" + source.getHeight() + " result=" + canvas.getWidth() + "x" + canvas.getHeight());
			return canvas;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to patch ImageBufferDownload class: " + imageBuffer.getClass().getName(), e);
		}
	}

	private static Field findPixelsField(Object target) {
		for (Field field : target.getClass().getDeclaredFields()) {
			if (field.getType() == int[].class) {
				field.setAccessible(true);
				return field;
			}
		}
		throw new IllegalStateException("No int[] buffer field found in " + target.getClass().getName());
	}

	private static List<Field> findIntFields(Object target) {
		List<Field> intFields = new ArrayList<>();
		for (Field field : target.getClass().getDeclaredFields()) {
			if (field.getType() == int.class) {
				field.setAccessible(true);
				intFields.add(field);
			}
		}
		if (intFields.size() < 2) {
			throw new IllegalStateException("Expected at least 2 int fields in " + target.getClass().getName());
		}
		return intFields;
	}

	private static MaskMethods findMaskMethods(Object target, Field intArrayField, Field widthField, Field heightField) throws ReflectiveOperationException {
		Method opaque = null;
		Method transparent = null;

		for (Method method : target.getClass().getDeclaredMethods()) {
			if (method.getReturnType() != Void.TYPE || method.getParameterCount() != 4) {
				continue;
			}
			Class<?>[] params = method.getParameterTypes();
			if (params[0] != int.class || params[1] != int.class || params[2] != int.class || params[3] != int.class) {
				continue;
			}

			method.setAccessible(true);
			ProbeResult result = probeMaskMethod(target, method, intArrayField, widthField, heightField);
			if (result.makesOpaque) {
				opaque = method;
			}
			if (result.makesTransparent) {
				transparent = method;
			}
		}

		if (opaque == null || transparent == null) {
			throw new IllegalStateException("Unable to identify mask methods in " + target.getClass().getName());
		}
		return new MaskMethods(opaque, transparent);
	}

	private static ProbeResult probeMaskMethod(Object target, Method method, Field intArrayField, Field widthField, Field heightField) throws ReflectiveOperationException {
		int oldWidth = widthField.getInt(target);
		int oldHeight = heightField.getInt(target);
		Object oldPixels = intArrayField.get(target);
		try {
			widthField.setInt(target, 1);
			heightField.setInt(target, 1);

			int translucentResult = invokeProbe(target, method, intArrayField, 0x11223344);
			int opaqueResult = invokeProbe(target, method, intArrayField, 0xFF223344);

			boolean makesOpaque = translucentResult == 0xFF223344;
			boolean makesTransparent = opaqueResult == 0x00223344;
			return new ProbeResult(makesOpaque, makesTransparent);
		} finally {
			widthField.setInt(target, oldWidth);
			heightField.setInt(target, oldHeight);
			intArrayField.set(target, oldPixels);
		}
	}

	private static int invokeProbe(Object target, Method method, Field intArrayField, int initialPixel) throws ReflectiveOperationException {
		int[] probe = new int[] {initialPixel};
		intArrayField.set(target, probe);
		method.invoke(target, 0, 0, 1, 1);
		return probe[0];
	}

	private static final class MaskMethods {
		private final Method opaque;
		private final Method transparent;

		private MaskMethods(Method opaque, Method transparent) {
			this.opaque = opaque;
			this.transparent = transparent;
		}
	}

	private static final class ProbeResult {
		private final boolean makesOpaque;
		private final boolean makesTransparent;

		private ProbeResult(boolean makesOpaque, boolean makesTransparent) {
			this.makesOpaque = makesOpaque;
			this.makesTransparent = makesTransparent;
		}
	}

	private static final class InterfaceShapeVisitor extends ClassVisitor {
		private boolean hasImageMethod;
		private boolean hasCallbackMethod;
		private int methodCount;

		private InterfaceShapeVisitor() {
			super(ASM9);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			methodCount++;
			if (TARGET_DESCRIPTOR.equals(descriptor)) {
				hasImageMethod = true;
			} else if (CALLBACK_DESCRIPTOR.equals(descriptor)) {
				hasCallbackMethod = true;
			}
			return null;
		}

		private boolean matchesImageBufferShape() {
			return hasImageMethod && (hasCallbackMethod || methodCount == 1);
		}
	}

	@Override
	public String toString() {
		return "ImageBufferDownload HD Skin Transformer";
	}
}
