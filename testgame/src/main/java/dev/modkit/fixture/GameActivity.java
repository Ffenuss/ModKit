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
    private TextView state;
    private TextView distance;
    private TextView obfState;
    private TextView walletState;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        player = new PlayerStats();
        obf = new ObfuscatedStats();
        wallet = new WalletStats();
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
        walletState = new TextView(this);
        walletState.setTextSize(24);
        layout.addView(walletState);
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
        Button buyCoins = new Button(this);
        buyCoins.setText("Buy coins item");
        buyCoins.setOnClickListener(v -> { wallet.purchase(); showState(); });
        layout.addView(buyCoins);
        Button earnCoins = new Button(this);
        earnCoins.setText("Earn coins");
        earnCoins.setOnClickListener(v -> { wallet.earnCoins(); showState(); });
        layout.addView(earnCoins);
        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setOnClickListener(v -> { player = new PlayerStats(); obf = new ObfuscatedStats(); wallet = new WalletStats(); showState(); });
        layout.addView(reset);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        showState();
    }
    private void showState() {
        state.setText((player.isDead() ? "GAME OVER" : "ALIVE") + " | Health: " + player.getHealth());
        distance.setText("Distance: " + player.distance());
        obfState.setText((obf.g() ? "OBF DEAD" : "OBF ALIVE") + " | Obf HP: " + obf.d());
        walletState.setText("Coins: " + wallet.getCoins() + " | Purchases: " + wallet.purchases());
    }
}
