package retroauth.probe;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;

import moe.yushi.authlibinjector.httpd.URLFilter;
import moe.yushi.authlibinjector.httpd.URLProcessor;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;

public class AgentProbe {
    public static void main(String[] args) throws Exception {
        try {
            if ("factory".equals(args[0])) {
                // Точный конфликтующий вызов из ModuleClassLoader NeoForge.
                URL.setURLStreamHandlerFactory(protocol -> null);
            } else if ("redirect".equals(args[0])) {
                String transformed = "https://authserver.mojang.com/authenticate";
                String expected = "https://auth.example/authserver/authenticate";
                if (!expected.equals(transformed)) {
                    throw new AssertionError("Authentication URL was not transformed: " + transformed);
                }
            } else {
                verifyReverseProxy();
            }
            System.out.println("PASS: " + args[0]);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void verifyReverseProxy() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/probe", exchange -> {
            byte[] response = "upstream-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
        try {
            URLFilter filter = new URLFilter() {
                public boolean canHandle(String domain) {
                    return domain.equals("probe.invalid");
                }

                public Optional<Response> handle(String domain, String path, IHTTPSession session) {
                    return Optional.empty();
                }
            };
            URLProcessor processor = new URLProcessor(Collections.singletonList(filter),
                    (domain, path) -> Optional.of("http://127.0.0.1:" + upstream.getAddress().getPort() + path));
            String target = processor.transformURL("http://probe.invalid/probe").get();
            java.net.URLConnection connection = new URL(target).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = new byte[32];
                int count = input.read(bytes);
                if (!"upstream-ok".equals(new String(bytes, 0, count, StandardCharsets.UTF_8))) {
                    throw new AssertionError("Reverse proxy response mismatch");
                }
            }
        } finally {
            upstream.stop(0);
        }
    }
}
