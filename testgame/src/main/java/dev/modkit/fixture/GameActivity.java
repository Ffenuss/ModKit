package dev.modkit.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class GameActivity extends Activity {
    private PlayerStats player;
    private ObfuscatedStats obf;
    private TextView state;
    private TextView distance;
    private TextView obfState;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        player = new PlayerStats();
        obf = new ObfuscatedStats();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 64, 32, 64);
        state = new TextView(this);
        state.setTextSize(24);
        layout.addView(state);
        distance = new TextView(this);
        distance.setTextSize(24);
        layout.addView(distance);
        obfState = new TextView(this);
        obfState.setTextSize(24);
        layout.addView(obfState);
        Button hit = new Button(this);
        hit.setText("Take damage");
        hit.setOnClickListener(v -> { player.hit(); showState(); });
        layout.addView(hit);
        Button sprint = new Button(this);
        sprint.setText("Sprint");
        sprint.setOnClickListener(v -> { player.sprint(); showState(); });
        layout.addView(sprint);
        Button obfHit = new Button(this);
        obfHit.setText("Obf hit");
        obfHit.setOnClickListener(v -> { obf.f(); showState(); });
        layout.addView(obfHit);
        Button obfMove = new Button(this);
        obfMove.setText("Obf move");
        obfMove.setOnClickListener(v -> { obf.i(); showState(); });
        layout.addView(obfMove);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> { player = new PlayerStats(); obf = new ObfuscatedStats(); showState(); });
        layout.addView(reset);
        setContentView(layout);
        showState();
    }
    private void showState() {
        state.setText((player.isDead() ? "GAME OVER" : "ALIVE") + " | Health: " + player.getHealth());
        distance.setText("Distance: " + player.distance());
        obfState.setText((obf.g() ? "OBF DEAD" : "OBF ALIVE") + " | Obf HP: " + obf.d());
    }
}
