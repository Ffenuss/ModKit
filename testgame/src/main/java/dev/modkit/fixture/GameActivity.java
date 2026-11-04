package dev.modkit.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class GameActivity extends Activity {
    private PlayerStats player;
    private ObfuscatedStats obf;
    private WalletStats wallet;
    private CooldownStats cooldown;
    private MovementStats movement;
    private TextView state;
    private TextView distance;
    private TextView obfState;
    private TextView walletState;
    private TextView cooldownState;
    private TextView movementState;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        player = new PlayerStats();
        obf = new ObfuscatedStats();
        wallet = new WalletStats();
        cooldown = new CooldownStats();
        movement = new MovementStats();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        layout.setPadding(16, 16, 16, 16);
        state = new TextView(this);
        state.setTextSize(19);
        layout.addView(state);
        distance = new TextView(this);
        distance.setTextSize(19);
        layout.addView(distance);
        obfState = new TextView(this);
        obfState.setTextSize(19);
        layout.addView(obfState);
        walletState = new TextView(this);
        walletState.setTextSize(19);
        layout.addView(walletState);
        cooldownState = new TextView(this);
        cooldownState.setTextSize(19);
        layout.addView(cooldownState);
        Button hit = new Button(this);
        hit.setText("Take damage");
        hit.setOnClickListener(v -> {
            if (!RuntimeLiveState.isDamageShieldEnabled()) player.hit();
            showState();
        });
        layout.addView(hit);
        Button sprint = new Button(this);
        sprint.setText("Sprint");
        sprint.setOnClickListener(v -> { player.sprint(); showState(); });
        layout.addView(sprint);
        Button obfHit = new Button(this);
        obfHit.setText("Obf hit");
        obfHit.setOnClickListener(v -> { obf.f(); showState(); });
        layout.addView(obfHit);
        Button buyCoins = new Button(this);
        buyCoins.setText("Buy coins item");
        buyCoins.setOnClickListener(v -> { wallet.purchase(); showState(); });
        layout.addView(buyCoins);
        Button obfMove = new Button(this);
        obfMove.setText("Obf move");
        obfMove.setOnClickListener(v -> { obf.i(); showState(); });
        layout.addView(obfMove);
        Button earnCoins = new Button(this);
        earnCoins.setText("Earn coins");
        earnCoins.setOnClickListener(v -> { wallet.earnCoins(); showState(); });
        layout.addView(earnCoins);
        Button useSkill = new Button(this);
        useSkill.setText("Use skill");
        useSkill.setOnClickListener(v -> { cooldown.useSkill(); showState(); });
        layout.addView(useSkill);
        Button tickCooldown = new Button(this);
        tickCooldown.setText("Tick cooldown");
        tickCooldown.setOnClickListener(v -> { cooldown.tick(); showState(); });
        layout.addView(tickCooldown);
        Button moveStep = new Button(this);
        moveStep.setText("Move step");
        moveStep.setOnClickListener(v -> {
            movement.step();
            // Runtime effect is real: each enabled tap writes a second step.
            // Existing static DEX movement recipes remain independently testable.
            if (RuntimeLiveState.isDoubleMovementEnabled()) movement.step();
            showState();
        });
        layout.addView(moveStep);
        movementState = new TextView(this);
        movementState.setTextSize(19);
        layout.addView(movementState);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> { player = new PlayerStats(); obf = new ObfuscatedStats(); wallet = new WalletStats(); cooldown = new CooldownStats(); movement = new MovementStats(); showState(); });
        layout.addView(reset);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        showState();
    }

    @Override protected void onResume() {
        super.onResume();
        RuntimeLiveState.resume();
    }

    @Override protected void onPause() {
        RuntimeLiveState.pause();
        super.onPause();
    }

    private void showState() {
        state.setText((player.isDead() ? "GAME OVER" : "ALIVE") + " | Health: " + player.getHealth());
        distance.setText("Distance: " + player.distance());
        obfState.setText((obf.g() ? "OBF DEAD" : "OBF ALIVE") + " | Obf HP: " + obf.d());
        walletState.setText("Coins: " + wallet.getCoins() + " | Purchases: " + wallet.purchases());
        cooldownState.setText("Cooldown: " + cooldown.remaining() + " | Actions: " + cooldown.actions());
        movementState.setText("Movement distance: " + movement.traveled());
    }
}
