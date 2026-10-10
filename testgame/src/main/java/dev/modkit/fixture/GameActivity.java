package dev.modkit.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class GameActivity extends Activity {
    private PlayerStats player;
    private TextView state;
    private TextView distance;
    private TextView wideStats;
    private TextView narrowStats;
    private int mode;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        player = new PlayerStats();
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
        wideStats = new TextView(this);
        wideStats.setTextSize(18);
        layout.addView(wideStats);
        narrowStats = new TextView(this);
        narrowStats.setTextSize(16);
        layout.addView(narrowStats);
        Button hit = new Button(this);
        hit.setText("Take damage");
        hit.setOnClickListener(v -> { player.hit(); showState(); });
        layout.addView(hit);
        Button sprint = new Button(this);
        sprint.setText("Sprint");
        sprint.setOnClickListener(v -> { player.sprint(); showState(); });
        layout.addView(sprint);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> { player = new PlayerStats(); mode = 0; showState(); });
        layout.addView(reset);
        Button nextMode = new Button(this);
        nextMode.setText("Next mode");
        nextMode.setOnClickListener(v -> { mode = (mode + 1) % 4; showState(); });
        layout.addView(nextMode);
        setContentView(layout);
        showState();
    }
    private void showState() {
        state.setText((player.isDead() ? "GAME OVER" : "ALIVE") + " | Health: " + player.getHealth());
        distance.setText("Distance: " + player.distance());
        narrowStats.setText("Energy: " + player.getEnergy() + " | Max health: " + player.getMaxHealth() + " | Magazine: " + (int) PlayerStats.getMagazineSize());
        int speedMode = mode == 1 ? 100 : mode == 2 ? 1000 : mode;
        wideStats.setText("Ammo: " + player.getAmmo(4294967298L, mode) + " | Speed: " + PlayerStats.getRunSpeed(4294967299L, 2.0, 4294967298L, speedMode));
    }
}
