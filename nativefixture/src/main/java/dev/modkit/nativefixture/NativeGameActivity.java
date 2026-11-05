package dev.modkit.nativefixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Repository-owned, non-cooperating test game: deliberately NO ModKit
 * ContentProvider, broadcast receiver, in-game patch menu, or runtime bridge.
 * A genuine .so function computes the value used for every movement update.
 */
public final class NativeGameActivity extends Activity {
    static { System.loadLibrary("modkit_native_fixture"); }

    private native int nativeStep();
    private int traveled;
    private TextView traveledText;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(this);
        heading.setText("Native Fixture · no ModKit control API");
        heading.setTextSize(19);
        column.addView(heading);
        traveledText = new TextView(this);
        traveledText.setTextSize(19);
        column.addView(traveledText);
        Button move = new Button(this);
        move.setText("Native move");
        move.setAllCaps(false);
        move.setOnClickListener(v -> { traveled += nativeStep(); refresh(); });
        column.addView(move);
        Button reset = new Button(this);
        reset.setText("Native reset");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> { traveled = 0; refresh(); });
        column.addView(reset);
        setContentView(column);
        refresh();
    }

    private void refresh() {
        traveledText.setText("Native distance: " + traveled);
    }
}
