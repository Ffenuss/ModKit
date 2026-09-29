package dev.modkit.unrealfixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Owned loose-INI consumer fixture. libUnreal.so gives the APK an Unreal runtime
 * fingerprint, while this activity proves only ModKit's packaged INI executor.
 * It is deliberately not presented as a real Unreal Engine runtime test.
 */
public final class GameActivity extends Activity {
    private int health;
    private int damage;
    private TextView healthView;
    private TextView damageView;
    private TextView stateView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Map<String, Integer> config = readIni();
        health = require(config, "player.health");
        damage = require(config, "player.damage");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        healthView = text(28);
        damageView = text(24);
        stateView = text(24);
        Button hit = new Button(this);
        hit.setText("Take damage");
        hit.setOnClickListener(v -> {
            health = Math.max(0, health - damage);
            render();
        });
        layout.addView(healthView);
        layout.addView(damageView);
        layout.addView(stateView);
        layout.addView(hit);
        setContentView(layout);
        render();
    }

    private TextView text(float size) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        return view;
    }

    private void render() {
        healthView.setText("Health: " + health);
        damageView.setText("Damage: " + damage);
        stateView.setText(health == 0 ? "GAME OVER" : "ALIVE");
    }

    private Map<String, Integer> readIni() {
        HashMap<String, Integer> out = new HashMap<>();
        String section = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("Content/Config/DefaultGame.ini"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                int equal = line.indexOf('=');
                if (equal <= 0 || section.isEmpty()) continue;
                String key = line.substring(0, equal).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(equal + 1).trim();
                try { out.put(section + "." + key, Integer.parseInt(value)); }
                catch (NumberFormatException ignored) { }
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to read owned INI fixture", failure);
        }
        return out;
    }

    private static int require(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        if (value == null) throw new IllegalStateException("Missing INI key: " + key);
        return value;
    }
}
