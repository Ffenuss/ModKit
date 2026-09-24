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
        Button hit = new Button(this);
        hit.setText("Take damage");
        hit.setOnClickListener(v -> { player.hit(); showState(); });
        layout.addView(hit);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> { player = new PlayerStats(); showState(); });
        layout.addView(reset);
        setContentView(layout);
        showState();
    }
    private void showState() {
        state.setText((player.isDead() ? "GAME OVER" : "ALIVE") + " | Health: " + player.getHealth());
    }
}
