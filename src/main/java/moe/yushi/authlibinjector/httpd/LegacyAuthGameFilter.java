package moe.yushi.authlibinjector.httpd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static moe.yushi.authlibinjector.util.IOUtils.CONTENT_TYPE_TEXT;
import static moe.yushi.authlibinjector.util.Logging.Level.WARNING;
import static moe.yushi.authlibinjector.util.Logging.log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;

/**
 * Agenta-style legacy auth game endpoints:
 * - /game/joinserver.jsp
 * - /game/checkserver.jsp
 */
public class LegacyAuthGameFilter implements URLFilter {

    private static final String DOMAIN_WWW = "www.minecraft.net";
    private static final String PATH_JOIN = "/game/joinserver.jsp";
    private static final String PATH_CHECK = "/game/checkserver.jsp";

    private static final String SERVER_ID = randomHex(10);

    @Override
    public boolean canHandle(String domain) {
        return DOMAIN_WWW.equals(domain);
    }

    @Override
    public Optional<Response> handle(String domain, String path, IHTTPSession session) throws IOException {
        if (!DOMAIN_WWW.equals(domain)) {
            return empty();
        }

        if (PATH_JOIN.equals(path)) {
            return of(proxyJoinServer(session.getParameters()));
        }

        if (PATH_CHECK.equals(path)) {
            return of(handleCheckServer(session.getParameters()));
        }

        return empty();
    }

    private Response proxyJoinServer(Map<String, List<String>> params) throws IOException {
        String query = buildQuery(params);
        String target = "https://session.minecraft.net" + PATH_JOIN + (query.isEmpty() ? "" : ("?" + query));

        byte[] body = httpGet(target);
        return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, new ByteArrayInputStream(body), body.length);
    }

    private Response handleCheckServer(Map<String, List<String>> params) {
        String user = first(params, "user");
        String token = first(params, "sessionId");

        if (user == null || token == null) {
            return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, "ERROR");
        }

        String join = "https://session.minecraft.net" + PATH_JOIN
                + "?user=" + encode(user)
                + "&sessionId=" + encode(token)
                + "&serverId=" + encode(SERVER_ID);

        try {
            String body = new String(httpGet(join), UTF_8).trim();
            if ("OK".equals(body)) {
                return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, "1\n");
            }
        } catch (IOException e) {
            log(WARNING, "Legacy checkserver fallback failed", e);
        }
        return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, "ERROR");
    }

    private static byte[] httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream()) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buf = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static String buildQuery(Map<String, List<String>> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) {
                if (!first) sb.append('&');
                sb.append(encode(key));
                first = false;
            } else {
                for (String value : values) {
                    if (!first) sb.append('&');
                    sb.append(encode(key)).append('=').append(encode(value == null ? "" : value));
                    first = false;
                }
            }
        }
        return sb.toString();
    }

    private static String first(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String randomHex(int len) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(len);
        while (sb.length() < len) {
            sb.append(Integer.toHexString(random.nextInt()));
        }
        return sb.substring(0, len);
    }
}
