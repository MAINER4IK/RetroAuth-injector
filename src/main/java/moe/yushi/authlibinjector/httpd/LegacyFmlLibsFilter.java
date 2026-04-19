package moe.yushi.authlibinjector.httpd;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;

import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;

/**
 * Agenta FmlFix equivalent:
 * reroute old Forge fmllibs endpoint to a configurable mirror.
 */
public class LegacyFmlLibsFilter implements URLFilter {

    private static final String DOMAIN = "files.minecraftforge.net";
    private static final String PREFIX = "/fmllibs";

    @Override
    public boolean canHandle(String domain) {
        return DOMAIN.equals(domain);
    }

    @Override
    public Optional<Response> handle(String domain, String path, IHTTPSession session) throws IOException {
        if (!DOMAIN.equals(domain) || !path.startsWith(PREFIX)) {
            return empty();
        }

        String base = Config.agentaAssetsFmlUrl;
        if (base == null || base.isEmpty()) {
            return empty();
        }

        String suffix = path.substring(PREFIX.length());
        String target = base + suffix;
        log(INFO, "Rerouting legacy Forge fmllibs: " + target);

        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        InputStream in;
        try {
            in = conn.getInputStream();
        } catch (IOException e) {
            in = conn.getErrorStream();
            if (in == null) {
                return of(Response.newFixedLength(Status.BAD_GATEWAY, null, null));
            }
        }

        long len = conn.getContentLengthLong();
        Status status = Status.lookup(code);
        if (status == null) {
            status = Status.OK;
        }
        if (len < 0) {
            return of(Response.newChunked(status, conn.getContentType(), in));
        }
        return of(Response.newFixedLength(status, conn.getContentType(), in, len));
    }
}
