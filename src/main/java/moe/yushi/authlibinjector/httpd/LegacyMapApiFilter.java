package moe.yushi.authlibinjector.httpd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static moe.yushi.authlibinjector.util.IOUtils.CONTENT_TYPE_TEXT;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.Level.WARNING;
import static moe.yushi.authlibinjector.util.Logging.log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import moe.yushi.authlibinjector.Config;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.IHTTPSession;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Response;
import moe.yushi.authlibinjector.internal.fi.iki.elonen.Status;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONArray;
import moe.yushi.authlibinjector.internal.org.json.simple.JSONObject;
import moe.yushi.authlibinjector.util.JsonUtils;

/**
 * Agenta MapFix equivalent for very old world-save endpoints.
 */
public class LegacyMapApiFilter implements URLFilter {

    private static final int SLOT_COUNT = 5;

    private final Slot[] saves = new Slot[SLOT_COUNT];
    private final Path saveFile;
    private boolean loaded;

    public LegacyMapApiFilter() {
        this.saveFile = Paths.get(Config.agentaSaveFile);
    }

    @Override
    public boolean canHandle(String domain) {
        return domain.endsWith("minecraft.net");
    }

    @Override
    public Optional<Response> handle(String domain, String path, IHTTPSession session) throws IOException {
        switch (path) {
            case "/listmaps.jsp":
                return of(handleListMaps());
            case "/level/save.html":
                return of(handleSave(session));
            case "/level/load.html":
                return of(handleLoad(session));
            default:
                return empty();
        }
    }

    private synchronized Response handleListMaps() {
        loadIfNeeded();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i > 0) {
                sb.append(';');
            }
            Slot slot = saves[i];
            sb.append(slot == null ? "-" : slot.name);
        }
        return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, sb.toString());
    }

    private synchronized Response handleSave(IHTTPSession session) throws IOException {
        loadIfNeeded();
        InputStream body = session.getInputStream();
        if (body == null) {
            return Response.newFixedLength(Status.BAD_REQUEST, CONTENT_TYPE_TEXT, "bad request");
        }

        byte[] bytes = readAll(body);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.readUTF(); // username
            in.readUTF(); // token
            String name = in.readUTF();
            int mapId = in.readByte();
            int length = in.readInt();
            if (mapId < 0 || mapId >= SLOT_COUNT || length < 0) {
                return Response.newFixedLength(Status.BAD_REQUEST, CONTENT_TYPE_TEXT, "bad slot");
            }

            byte[] data = new byte[length];
            in.readFully(data);
            saves[mapId] = new Slot(name, data);
            persist();

            return Response.newFixedLength(Status.OK, CONTENT_TYPE_TEXT, "ok");
        }
    }

    private synchronized Response handleLoad(IHTTPSession session) {
        loadIfNeeded();
        int id = parseSlotId(session.getParameters());
        if (id < 0 || id >= SLOT_COUNT || saves[id] == null) {
            return Response.newFixedLength(Status.NOT_FOUND, CONTENT_TYPE_TEXT, "not found");
        }

        Slot slot = saves[id];
        byte[] response = new byte[slot.data.length + 4];
        response[0] = 0x00;
        response[1] = 0x02;
        response[2] = 0x6F;
        response[3] = 0x6B;
        System.arraycopy(slot.data, 0, response, 4, slot.data.length);
        return Response.newFixedLength(Status.OK, null, new ByteArrayInputStream(response), response.length);
    }

    private int parseSlotId(Map<String, List<String>> params) {
        try {
            List<String> values = params.get("id");
            if (values == null || values.isEmpty()) {
                return -1;
            }
            return Integer.parseInt(values.get(0));
        } catch (Exception e) {
            return -1;
        }
    }

    private void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(saveFile)) {
            return;
        }

        try {
            String text = new String(Files.readAllBytes(saveFile), UTF_8);
            JSONObject root = JsonUtils.asJsonObject(JsonUtils.parseJson(text));
            JSONArray arr = JsonUtils.asJsonArray(root.get("saves"));
            for (int i = 0; i < arr.size() && i < SLOT_COUNT; i++) {
                Object obj = arr.get(i);
                if (obj == null) {
                    continue;
                }
                JSONObject entry = JsonUtils.asJsonObject(obj);
                String name = JsonUtils.asJsonString(entry.get("name"));
                String b64 = JsonUtils.asJsonString(entry.get("data"));
                saves[i] = new Slot(name, Base64.getDecoder().decode(b64));
            }
            log(INFO, "Loaded legacy map slots from " + saveFile.toAbsolutePath());
        } catch (Exception e) {
            log(WARNING, "Failed to load legacy map slots", e);
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < SLOT_COUNT; i++) {
                Slot slot = saves[i];
                if (slot == null) {
                    arr.add(null);
                } else {
                    JSONObject entry = new JSONObject();
                    entry.put("name", slot.name);
                    entry.put("data", Base64.getEncoder().encodeToString(slot.data));
                    arr.add(entry);
                }
            }
            root.put("saves", arr);

            Path parent = saveFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(saveFile, root.toJSONString().getBytes(UTF_8));
        } catch (IOException e) {
            log(WARNING, "Failed to persist legacy map slots", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    private static final class Slot {
        private final String name;
        private final byte[] data;

        private Slot(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }
}
