package dev.grimml.detect.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * This is the model set class for the GrimMLDetector.
 * It is an immutable set of module models built from one manifest version.
 * It is swapped atomically on update, so the scan thread always sees a complete set.
 * 
 * The manifest.json file is the file that contains the models.
 * It is a json file that contains the models.
 * It is a json file that contains the models.
 */
public record ModelSet(int version, String published, List<ModuleModel> models) {

    public static final ModelSet EMPTY = new ModelSet(0, "", List.of());

    public interface FileSource {
        byte[] read(String name) throws IOException;
    }

    public static JsonObject parseManifest(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public static ModelSet build(JsonObject manifest, FileSource files) throws IOException {
        int version = manifest.get("version").getAsInt();
        String published = manifest.has("published") ? manifest.get("published").getAsString() : "";
        List<ModuleModel> out = new ArrayList<>();
        JsonArray arr = manifest.getAsJsonArray("models");
        for (JsonElement el : arr) {
            JsonObject e = el.getAsJsonObject();
            String module = e.get("module").getAsString();
            byte[] modelBytes = files.read(safeName(e.get("model").getAsString()));
            byte[] metaBytes = files.read(safeName(e.get("meta").getAsString()));
            verify(module + " model", modelBytes, e.get("model_sha256").getAsString());
            verify(module + " meta", metaBytes, e.get("meta_sha256").getAsString());
            JsonObject meta = parseManifest(metaBytes);
            List<String> feats = new ArrayList<>();
            meta.getAsJsonArray("features").forEach(f -> feats.add(f.getAsString()));
            LgbmModel model = LgbmModel.parse(new StringReader(new String(modelBytes, StandardCharsets.UTF_8)));
            if (model.numFeatures() != feats.size()) {
                throw new IOException(module + ": model has " + model.numFeatures() + " features, meta lists " + feats.size());
            }
            double share = e.has("alert_share") ? e.get("alert_share").getAsDouble() : meta.get("fight_line_share").getAsDouble();
            out.add(new ModuleModel(
                    module,
                    e.has("check") ? e.get("check").getAsString() : "ML" + module.replace(" ", ""),
                    e.has("description") ? e.get("description").getAsString() : "ML model for " + module,
                    model,
                    ModuleModel.mapFeatures(feats),
                    meta.get("window_threshold_fpr5").getAsDouble(),
                    share,
                    meta.has("fight_min_windows") ? meta.get("fight_min_windows").getAsInt() : 10,
                    !e.has("enabled") || e.get("enabled").getAsBoolean()
            ));
        }
        return new ModelSet(version, published, List.copyOf(out));
    }

    // checks if the name is a valid file name
    static String safeName(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]{1,64}") || name.contains("..")) {
            throw new IOException("bad file name in manifest: " + name);
        }
        return name;
    }

    // verifies the sha256 of the data 
    static void verify(String what, byte[] data, String sha256) throws IOException {
        String got = sha256(data);
        if (!got.equalsIgnoreCase(sha256)) {
            throw new IOException(what + " sha256 mismatch (got " + got + ")");
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
