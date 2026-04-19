package moe.yushi.authlibinjector.legacy;

import static moe.yushi.authlibinjector.util.Logging.log;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.Level.WARNING;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Optional;

import moe.yushi.authlibinjector.AuthlibInjector;

/**
 * Agenta-style global URL interception for legacy Minecraft versions.
 *
 * This hook catches HTTP(S) requests even when bytecode transformation does not apply,
 * then forwards them through authlib-injector URL processing and local HTTP polyfills.
 */
public final class LegacyURLStreamHandlerFactory implements URLStreamHandlerFactory {

    private static final URLStreamHandler HTTP_HANDLER = new InterceptingHandler("http");
    private static final URLStreamHandler HTTPS_HANDLER = new InterceptingHandler("https");

    private LegacyURLStreamHandlerFactory() {
    }

    public static void tryInstall() {
        try {
            URL.setURLStreamHandlerFactory(new LegacyURLStreamHandlerFactory());
            log(INFO, "Legacy URL stream handler installed");
        } catch (Error e) {
            // URLStreamHandlerFactory can only be installed once per JVM.
            log(WARNING, "Legacy URL stream handler skipped: factory is already set");
        } catch (Throwable e) {
            log(WARNING, "Legacy URL stream handler failed to initialize", e);
        }
    }

    @Override
    public URLStreamHandler createURLStreamHandler(String protocol) {
        switch (protocol) {
            case "http":
                return HTTP_HANDLER;
            case "https":
                return HTTPS_HANDLER;
            case "forward":
                return createJreHandler("sun.net.www.protocol.http.Handler");
            case "forwards":
                return createJreHandler("sun.net.www.protocol.https.Handler");
            default:
                return null;
        }
    }

    private URLStreamHandler createJreHandler(String className) {
        try {
            Object handler = Class.forName(className).getDeclaredConstructor().newInstance();
            if (handler instanceof URLStreamHandler) {
                return (URLStreamHandler) handler;
            }
            log(WARNING, "Legacy URL handler is not a URLStreamHandler: " + className);
        } catch (ReflectiveOperationException e) {
            log(WARNING, "Legacy URL handler unavailable: " + className, e);
        }
        return null;
    }

    private static final class InterceptingHandler extends URLStreamHandler {

        private final String protocol;

        private InterceptingHandler(String protocol) {
            this.protocol = protocol;
        }

        @Override
        protected URLConnection openConnection(URL url) throws IOException {
            return openConnection(url, null);
        }

        @Override
        protected URLConnection openConnection(URL url, Proxy proxy) throws IOException {
            String input = url.toString();
            URL upstream = AuthlibInjector.transformURLForLegacy(input)
                    .map(target -> {
                        try {
                            return new URL(target);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .orElse(url);

            String bridgeProtocol = "http".equalsIgnoreCase(upstream.getProtocol()) ? "forward" : "forwards";
            URL bridged = new URL(bridgeProtocol, upstream.getHost(), upstream.getPort(), upstream.getFile());

            if (proxy != null) {
                return bridged.openConnection(proxy);
            }
            return bridged.openConnection();
        }

        @Override
        protected int getDefaultPort() {
            return "https".equals(protocol) ? 443 : 80;
        }
    }
}
