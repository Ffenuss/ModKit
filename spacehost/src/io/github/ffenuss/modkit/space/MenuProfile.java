package io.github.ffenuss.modkit.space;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Data-only menu contract. An imported static recipe never grants runtime write capability. */
final class MenuProfile {
    static final int MAX_BYTES = 256 * 1024;
    final String packageName, label, genre, artifactSha256;
    final List<String> engines = new ArrayList<>();
    final List<Item> items = new ArrayList<>();
    final List<String> sourceIdentities = new ArrayList<>();
    final boolean truncated;
    static final class Item {
        final String title, detail, evidence, state;
        Item(JSONObject json) throws Exception {
            text(json, "id", 512); text(json, "category", 180);
            title = text(json, "title", 180); detail = text(json, "detail", 1200);
            evidence = text(json, "evidence", 512); state = text(json, "state", 32);
            if (!"candidate".equals(state) && !"static_recipe".equals(state)) throw new IllegalArgumentException("Unknown item state");
        }
    }
    MenuProfile(String content) throws Exception {
        JSONObject json = new JSONObject(content);
        if (json.getInt("schema") != 1 || !"none".equals(json.getString("backend")))
            throw new IllegalArgumentException("Unsupported menu contract/backend");
        packageName = text(json, "packageName", 255);
        if (!SpacePolicy.validSession(packageName, 0)) throw new IllegalArgumentException("Invalid package");
        label = text(json, "label", 180); genre = text(json, "genre", 80);
        artifactSha256 = text(json, "artifactSha256", 64);
        if (!artifactSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid artifact hash");
        truncated = json.getBoolean("truncated");
        JSONArray source = json.getJSONArray("sources");
        if (source.length() < 1 || source.length() > 64) throw new IllegalArgumentException("Invalid source count");
        for (int i = 0; i < source.length(); i++) {
            JSONObject entry = source.getJSONObject(i);
            String hash = text(entry, "sha256", 64); long size = entry.getLong("size");
            if (!hash.matches("[0-9a-f]{64}") || size <= 0) throw new IllegalArgumentException("Invalid APK identity");
            sourceIdentities.add(hash + ":" + size);
        }
        Collections.sort(sourceIdentities);
        JSONArray runtime = json.getJSONArray("engines");
        if (runtime.length() > 32) throw new IllegalArgumentException("Too many engines");
        for (int i = 0; i < runtime.length(); i++) {
            String value = runtime.getString(i);
            if (value.length() > 200) throw new IllegalArgumentException("Engine text too large");
            engines.add(value);
        }
        JSONArray menu = json.getJSONArray("items");
        if (menu.length() > 256) throw new IllegalArgumentException("Too many menu items");
        for (int i = 0; i < menu.length(); i++) items.add(new Item(menu.getJSONObject(i)));
    }
    boolean matches(List<File> sources, SourceInventory.Cancellation token) throws Exception {
        if (sources.size() != sourceIdentities.size()) return false;
        List<String> actual = new ArrayList<>();
        for (File file : sources) {
            token.check();
            SourceInventory.Source source = new SourceInventory.Source(file, token);
            source.verify(token);
            actual.add(source.sha256 + ":" + source.size);
        }
        Collections.sort(actual);
        return sourceIdentities.equals(actual);
    }
    private static String text(JSONObject json, String key, int limit) throws Exception {
        String value = json.getString(key);
        if (value.length() > limit) throw new IllegalArgumentException("Profile field too large: " + key);
        return value;
    }
}
