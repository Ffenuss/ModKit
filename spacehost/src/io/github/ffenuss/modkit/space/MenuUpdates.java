package io.github.ffenuss.modkit.space;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import java.util.HashSet;
import java.util.Set;

/** Notifications carry no menu data; sync still authenticates and checks each profile hash. */
final class MenuUpdates extends ContentObserver {
    private final Context context;
    private final Runnable refresh;
    private final Set<String> registered = new HashSet<>();
    MenuUpdates(Context context, Handler handler, Runnable refresh) {
        super(handler); this.context = context; this.refresh = refresh;
    }
    void register() {
        for (String authority : new String[]{"io.github.ffenuss.modkit.test.space-menu", "io.github.ffenuss.modkit.space-menu"}) {
            if (registered.contains(authority)) continue;
            try {
                context.getContentResolver().registerContentObserver(Uri.parse("content://" + authority), true, this);
                registered.add(authority);
            } catch (RuntimeException ignored) { /* ModKit may be installed later. */ }
        }
    }
    @Override public void onChange(boolean selfChange, Uri uri) { refresh.run(); }
}
