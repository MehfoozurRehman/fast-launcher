package app.fastlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

final class Migrate {
    private Migrate() {}

    private static final String[] FILES = {"launcher", "ui", "walls"};

    private static File file() {
        return new File(Environment.getExternalStorageDirectory(), "Documents/FastLauncher/settings.json");
    }

    static void export(Context c) {
        try {
            JSONObject all = new JSONObject();
            for (String name : FILES) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : c.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object v = e.getValue();
                    JSONObject t = new JSONObject();
                    if (v instanceof Set) {
                        t.put("t", "set");
                        t.put("v", new JSONArray((Set<?>) v));
                    } else {
                        t.put("t", v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l" : v instanceof Float ? "f" : "s");
                        t.put("v", v);
                    }
                    o.put(e.getKey(), t);
                }
                all.put(name, o);
            }
            File f = file();
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f)) { w.write(all.toString()); }
            Diag.log("exported settings to " + f);
        } catch (Exception e) {
            Diag.log("export: " + e);
        }
    }

    static void importIfFresh(Context c) {
        try {
            SharedPreferences marker = c.getSharedPreferences("migrate", Context.MODE_PRIVATE);
            if (marker.getBoolean("done", false)) return;
            if (!c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getAll().isEmpty()) {
                marker.edit().putBoolean("done", true).commit();
                return;
            }
            File f = file();
            if (!f.exists()) return;
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            JSONObject all = new JSONObject(new String(data, "UTF-8"));
            for (String name : FILES) {
                JSONObject o = all.optJSONObject(name);
                if (o == null) continue;
                SharedPreferences.Editor ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject t = o.getJSONObject(k);
                    switch (t.getString("t")) {
                        case "b": ed.putBoolean(k, t.getBoolean("v")); break;
                        case "i": ed.putInt(k, t.getInt("v")); break;
                        case "l": ed.putLong(k, t.getLong("v")); break;
                        case "f": ed.putFloat(k, (float) t.getDouble("v")); break;
                        case "set": {
                            JSONArray a = t.getJSONArray("v");
                            Set<String> s = new HashSet<>();
                            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                            ed.putStringSet(k, s);
                            break;
                        }
                        default: ed.putString(k, t.getString("v"));
                    }
                }
                ed.commit();
            }
            marker.edit().putBoolean("done", true).commit();
            Diag.log("imported settings from " + f);
        } catch (Exception e) {
            Diag.log("import: " + e);
        }
    }
}
