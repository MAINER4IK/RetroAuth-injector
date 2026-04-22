/*
 * Copyright (C) 2020  Haowei Wen <yushijinhun@gmail.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package moe.yushi.authlibinjector.httpd;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static moe.yushi.authlibinjector.util.IOUtils.asString;
import static moe.yushi.authlibinjector.util.IOUtils.http;
import static moe.yushi.authlibinjector.util.IOUtils.newUncheckedIOException;
import static moe.yushi.authlibinjector.util.JsonUtils.asJsonObject;
import static moe.yushi.authlibinjector.util.JsonUtils.parseJson;
import static moe.yushi.authlibinjector.util.Logging.Level.DEBUG;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONObject;
import moe.yushi.authlibinjector.util.JsonUtils;
import moe.yushi.authlibinjector.yggdrasil.YggdrasilClient;

public class LegacySkinAPIFilter implements URLFilter {

    private static final Pattern PATH_MINECRAFT_SKINS = Pattern.compile("^/MinecraftSkins/(?<username>[^/]+)\\.png$");
    private static final Pattern PATH_MINECRAFT_CLOAKS = Pattern.compile("^/MinecraftCloaks/(?<username>[^/]+)\\.png$");
    private static final Pattern PATH_SKIN = Pattern.compile("^/skin/(?<username>[^/]+)\\.png$");
    private static final Pattern PATH_CLOAK = Pattern.compile("^/cloak/(?<username>[^/]+)\\.png$");

    private static final String TYPE_SKIN = "SKIN";
    private static final String TYPE_CAPE = "CAPE";

    private final YggdrasilClient upstream;
    private final ConcurrentMap<String, byte[]> textureCache = new ConcurrentHashMap<>();

    public LegacySkinAPIFilter(YggdrasilClient upstream) {
        this.upstream = upstream;
    }

    @Override
    public boolean canHandle(String domain) {
        return domain.equals("skins.minecraft.net")
                || domain.equals("www.minecraft.net")
                || domain.equals("s3.amazonaws.com");
    }

    @Override
    public Optional<Response> handle(String domain, String path, IHTTPSession session) {
        TextureRequest req = parseRequest(path, session.getParameters());
        if (req == null) {
            return empty();
        }

        String username = correctEncoding(req.username);
        String textureType = req.textureType;

        Optional<String> textureUrl;
        try {
            textureUrl = upstream.queryUUID(username)
                    .flatMap(uuid -> upstream.queryProfile(uuid, false))
                    .flatMap(profile -> Optional.ofNullable(profile.properties.get("textures")))
                    .map(property -> asString(Base64.getDecoder().decode(property.value)))
                    .flatMap(texturesPayload -> obtainTextureUrl(texturesPayload, textureType));
        } catch (UncheckedIOException e) {
            throw newUncheckedIOException("Failed to fetch " + textureType + " metadata for " + username, e);
        }

        if (!textureUrl.isPresent()) {
            log(INFO, "No " + textureType + " is found for " + username);
            return of(Response.newFixedLength(Status.NOT_FOUND, null, null));
        }

        String url = textureUrl.get();
        byte[] data;
        try {
            data = fetchTexture(username, textureType, url);
        } catch (IOException e) {
            throw newUncheckedIOException("Failed to retrieve " + textureType + " from " + url, e);
        }

        log(INFO, "Retrieved " + textureType + " for " + username + " from " + url + ", " + data.length + " bytes");
        return of(Response.newFixedLength(Status.OK, "image/png", new ByteArrayInputStream(data), data.length));
    }

    private byte[] fetchTexture(String username, String textureType, String url) throws IOException {
        String key = textureType + ":" + username.toLowerCase();
        if (Config.agentaSkinCache) {
            byte[] cached = textureCache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        byte[] raw = http("GET", url);
        byte[] processed = TYPE_SKIN.equals(textureType) ? processLegacySkin(raw) : raw;

        if (Config.agentaSkinCache) {
            textureCache.putIfAbsent(key, processed);
        }
        log(DEBUG, "Fetched " + textureType + " bytes for " + username + ", cache=" + Config.agentaSkinCache);
        return processed;
    }

    private byte[] processLegacySkin(byte[] rawPng) throws IOException {
        if (!Config.shouldApplyLegacySkinProcessing()) {
            return rawPng;
        }
        if (!Config.agentaSkinMerge && !Config.agentaSkinResize) {
            return rawPng;
        }

        BufferedImage source = ImageIO.read(new ByteArrayInputStream(rawPng));
        if (source == null || source.getHeight() < 64 || source.getWidth() < 64) {
            return rawPng;
        }

        BufferedImage skin = LegacySkinProcessor.processSkin(source);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(skin, "png", out);
        return out.toByteArray();
    }

    private Optional<String> obtainTextureUrl(String texturesPayload, String textureType) throws UncheckedIOException {
        JSONObject payload = asJsonObject(parseJson(texturesPayload));
        JSONObject textures = asJsonObject(payload.get("textures"));

        return ofNullable(textures.get(textureType))
                .map(JsonUtils::asJsonObject)
                .map(it -> ofNullable(it.get("url"))
                        .map(JsonUtils::asJsonString)
                        .orElseThrow(() -> newUncheckedIOException("Invalid JSON: Missing texture url")));
    }

    private TextureRequest parseRequest(String path, Map<String, List<String>> parameters) {
        String username = match(path, PATH_MINECRAFT_SKINS);
        if (username != null) {
            return new TextureRequest(username, TYPE_SKIN);
        }

        username = match(path, PATH_SKIN);
        if (username != null) {
            return new TextureRequest(username, TYPE_SKIN);
        }

        username = match(path, PATH_MINECRAFT_CLOAKS);
        if (username != null) {
            return new TextureRequest(username, TYPE_CAPE);
        }

        username = match(path, PATH_CLOAK);
        if (username != null) {
            return new TextureRequest(username, TYPE_CAPE);
        }

        // Very old clients: /skin/get.jsp?user=<name>
        if (path.endsWith("/get.jsp")) {
            List<String> users = parameters.get("user");
            if (users != null && !users.isEmpty() && !users.get(0).isEmpty()) {
                return new TextureRequest(users.get(0), TYPE_SKIN);
            }
        }

        return null;
    }

    private String match(String path, Pattern pattern) {
        Matcher matcher = pattern.matcher(path);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group("username");
    }

    private static String correctEncoding(String grable) {
        // platform charset is used
        return new String(grable.getBytes(ISO_8859_1));
    }

    private static final class TextureRequest {
        private final String username;
        private final String textureType;

        private TextureRequest(String username, String textureType) {
            this.username = username;
            this.textureType = textureType;
        }
    }
}
