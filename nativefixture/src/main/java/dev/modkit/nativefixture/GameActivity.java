package dev.modkit.nativefixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owned integration fixture. The value is read from real native machine code. */
public final class GameActivity extends Activity {
    static { System.loadLibrary("modkit_fixture"); }
    private static native int readNativeValue();
    public static native int getHealth();
    public static native long getStamina();
    public static native double getMoveSpeed();
    public static native int getAmmo(int slot);
    public static native int getAmmo(long slot);
    public static native int getBullets(String slot);
    public static native byte getEnergy();
    public static native short getMaxHealth();
    public static native char getMagazineSize();
    private TextView value;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        layout.setPadding(32, 64, 32, 140);
        value = new TextView(this);
        value.setTextSize(24);
        layout.addView(value);
        Button read = new Button(this);
        read.setText("Read native value");
        read.setOnClickListener(v -> showValue());
        layout.addView(read);
        setContentView(layout);
        showValue();
    }

    private void showValue() { value.setText("Native value: " + readNativeValue()); }
}
