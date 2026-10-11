package com.dualspace.multispace;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Owned Activity with the pinned host class name; it contains no virtual kernel. */
public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView grid = new TextView(this);
        grid.setText("Owned host panel fixture");
        setContentView(grid);
    }
}
