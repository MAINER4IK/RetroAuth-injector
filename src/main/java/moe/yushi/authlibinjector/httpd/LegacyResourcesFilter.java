package moe.yushi.authlibinjector.httpd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static moe.yushi.authlibinjector.util.JsonUtils.asJsonObject;
import static moe.yushi.authlibinjector.util.JsonUtils.parseJson;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONObject;
import moe.yushi.authlibinjector.util.JsonUtils;

/**
 * Polyfill for pre-1.6 resource endpoints:
 * - http://s3.amazonaws.com/MinecraftResources/
 * - http://www.minecraft.net/resources/
 */
public class LegacyResourcesFilter implements URLFilter {

    private static final String S3_DOMAIN = "s3.amazonaws.com";
    private static final String WWW_DOMAIN = "www.minecraft.net";
    private static final String S3_PREFIX = "/MinecraftResources";
    private static final String WWW_PREFIX = "/resources";

    private volatile LegacyAssetsIndex index;

    @Override
    public boolean canHandle(String domain) {
        return S3_DOMAIN.equals(domain) || WWW_DOMAIN.equals(domain);
    }

    @Override
    public Optional<Response> handle(String domain, String path, IHTTPSession session) throws IOException {
        if (S3_DOMAIN.equals(domain)) {
            return handleS3(path);
        }
        if (WWW_DOMAIN.equals(domain)) {
            return handleWww(path);
        }
        return empty();
    }

    private Optional<Response> handleS3(String path) throws IOException {
        if (!path.startsWith(S3_PREFIX)) {
            return empty();
        }
        String resourcePath = normalizeSubpath(path.substring(S3_PREFIX.length()));
        return serveResource(resourcePath, true);
    }

    private Optional<Response> handleWww(String path) throws IOException {
        if (!path.startsWith(WWW_PREFIX)) {
            return empty();
        }
        String resourcePath = normalizeSubpath(path.substring(WWW_PREFIX.length()));
        return serveResource(resourcePath, false);
    }

    private Optional<Response> serveResource(String resourcePath, boolean s3XmlIndex) throws IOException {
        LegacyAssetsIndex idx = getIndex();
        if (resourcePath.isEmpty()) {
            byte[] listing = (s3XmlIndex ? idx.compileXml() : idx.compileText()).getBytes(UTF_8);
            String mime = s3XmlIndex ? "application/xml" : "text/plain; charset=utf-8";
            return of(Response.newFixedLength(Status.OK, mime, new ByteArrayInputStream(listing), listing.length));
        }

        LegacyAsset asset = idx.objects.get(resourcePath);
        if (asset == null) {
            return of(Response.newFixedLength(Status.NOT_FOUND, null, null));
        }

        Path objectPath = idx.assetsObjects.resolve(asset.hash.substring(0, 2)).resolve(asset.hash);
        if (!Files.exists(objectPath)) {
            return of(Response.newFixedLength(Status.NOT_FOUND, null, null));
        }

        InputStream in = Files.newInputStream(objectPath);
        long size = Files.size(objectPath);
        return of(Response.newFixedLength(Status.OK, null, in, size));
    }

    private String normalizeSubpath(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) {
            return "";
        }
        String p = raw;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        try {
            p = URLDecoder.decode(p, "UTF-8");
        } catch (Exception ignored) {
        }
        return p;
    }

    private LegacyAssetsIndex getIndex() throws IOException {
        LegacyAssetsIndex local = index;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            local = index;
            if (local != null) {
                return local;
            }
            local = loadIndex();
            index = local;
            log(INFO, "Legacy resources index loaded with " + local.objects.size() + " entries");
            return local;
        }
    }

    private LegacyAssetsIndex loadIndex() throws IOException {
        RuntimePaths paths = resolveRuntimePaths();
        Path indexPath = resolveIndexPath(paths);

        String json = new String(Files.readAllBytes(indexPath), UTF_8);
        JSONObject root = asJsonObject(parseJson(json));
        JSONObject objects = asJsonObject(root.get("objects"));

        Map<String, LegacyAsset> map = new LinkedHashMap<>();
        for (Object keyObj : objects.keySet()) {
            String key = String.valueOf(keyObj);
            JSONObject entry = JsonUtils.asJsonObject(objects.get(keyObj));
            String hash = JsonUtils.asJsonString(entry.get("hash"));
            Object sizeObj = entry.get("size");
            if (!(sizeObj instanceof Number)) {
                throw new UncheckedIOException(new IOException("Invalid JSON: size is not numeric for " + key));
            }
            long size = ((Number) sizeObj).longValue();
            map.put(key, new LegacyAsset(hash, size));
        }

        return new LegacyAssetsIndex(map, paths.assetsObjects);
    }

    private Path resolveIndexPath(RuntimePaths paths) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (paths.assetIndexName != null && !paths.assetIndexName.isEmpty()) {
            candidates.add(paths.assetIndexName);
        }
        candidates.add("legacy");
        candidates.add("pre-1.6");

        for (String candidate : candidates) {
            Path p = paths.assetsIndexes.resolve(candidate + ".json");
            if (Files.exists(p)) {
                return p;
            }
        }

        if (Files.isDirectory(paths.assetsIndexes)) {
            List<Path> found = new ArrayList<>();
            try {
                Files.list(paths.assetsIndexes).forEach(p -> {
                    if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json")) {
                        found.add(p);
                    }
                });
            } catch (IOException e) {
                throw e;
            }
            if (found.size() == 1) {
                return found.get(0);
            }
        }

        throw new IOException("Legacy asset index not found under: " + paths.assetsIndexes);
    }

    private RuntimePaths resolveRuntimePaths() {
        String command = System.getProperty("sun.java.command", "");
        String[] tokens = command.split(" ");

        Path gameDir = Paths.get("").toAbsolutePath();
        Path assetsDir = gameDir.resolve("assets");
        String assetIndex = "legacy";

        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if ("--gameDir".equals(t) && i + 1 < tokens.length) {
                gameDir = Paths.get(tokens[++i]);
            } else if (t.startsWith("--gameDir=") && t.length() > "--gameDir=".length()) {
                gameDir = Paths.get(t.substring("--gameDir=".length()));
            } else if ("--assetsDir".equals(t) && i + 1 < tokens.length) {
                assetsDir = Paths.get(tokens[++i]);
            } else if (t.startsWith("--assetsDir=") && t.length() > "--assetsDir=".length()) {
                assetsDir = Paths.get(t.substring("--assetsDir=".length()));
            } else if ("--assetIndex".equals(t) && i + 1 < tokens.length) {
                assetIndex = tokens[++i];
            } else if (t.startsWith("--assetIndex=") && t.length() > "--assetIndex=".length()) {
                assetIndex = t.substring("--assetIndex=".length());
            }
        }

        if (!assetsDir.isAbsolute()) {
            assetsDir = gameDir.resolve(assetsDir).normalize();
        }
        if (!gameDir.isAbsolute()) {
            gameDir = gameDir.toAbsolutePath().normalize();
        }

        return new RuntimePaths(
                gameDir,
                assetsDir.resolve("indexes"),
                assetsDir.resolve("objects"),
                assetIndex);
    }

    private static final class RuntimePaths {
        private final Path gameDir;
        private final Path assetsIndexes;
        private final Path assetsObjects;
        private final String assetIndexName;

        private RuntimePaths(Path gameDir, Path assetsIndexes, Path assetsObjects, String assetIndexName) {
            this.gameDir = gameDir;
            this.assetsIndexes = assetsIndexes;
            this.assetsObjects = assetsObjects;
            this.assetIndexName = assetIndexName;
        }
    }

    private static final class LegacyAsset {
        private final String hash;
        private final long size;

        private LegacyAsset(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    private static final class LegacyAssetsIndex {
        private final Map<String, LegacyAsset> objects;
        private final Path assetsObjects;

        private LegacyAssetsIndex(Map<String, LegacyAsset> objects, Path assetsObjects) {
            this.objects = objects;
            this.assetsObjects = assetsObjects;
        }

        private String compileText() {
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, LegacyAsset> entry : objects.entrySet()) {
                String key = entry.getKey();
                if (key.contains("/")) {
                    sb.append(key)
                            .append(',')
                            .append(entry.getValue().size)
                            .append(',')
                            .append(now)
                            .append("\r\n");
                }
            }
            return sb.toString();
        }

        private String compileXml() {
            List<Map.Entry<String, LegacyAsset>> entries = new ArrayList<>(objects.entrySet());
            Collections.sort(entries, Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));

            List<String> packs = new ArrayList<>();
            String lastDir = "";
            for (Map.Entry<String, LegacyAsset> entry : entries) {
                String key = entry.getKey();
                if (!key.contains("/")) {
                    lastDir = "";
                    continue;
                }

                String currentDir = key.substring(0, key.lastIndexOf('/'));
                if (!lastDir.equals(currentDir)) {
                    lastDir = currentDir;
                    String prefix = "";
                    for (String seg : currentDir.split("/")) {
                        prefix += seg + "/";
                        packs.add(pack(prefix, null, 0));
                    }
                }

                packs.add(pack(key, entry.getValue().hash, (int) entry.getValue().size));
            }

            StringBuilder body = new StringBuilder();
            for (String part : packs) {
                body.append(part);
            }

            return "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Name>MinecraftResources</Name><Prefix/><Marker/><MaxKeys>1000</MaxKeys>"
                    + "<IsTruncated>false</IsTruncated>"
                    + body
                    + "</ListBucketResult>";
        }

        private static String pack(String key, String hash, int size) {
            String etag = hash == null ? Integer.toString(key.hashCode()) : hash;
            String date = new SimpleDateFormat("MM/dd/yyyy KK:mm:ss a Z", Locale.US).format(new Date());
            return "<Contents><Key>" + xmlEscape(key) + "</Key>"
                    + "<LastModified>" + date + "</LastModified>"
                    + "<ETag>\"" + etag + "\"</ETag>"
                    + "<Size>" + size + "</Size><StorageClass>STANDARD</StorageClass></Contents>";
        }

        private static String xmlEscape(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }
    }
}
