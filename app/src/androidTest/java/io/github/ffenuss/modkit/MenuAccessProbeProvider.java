package io.github.ffenuss.modkit;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.io.InputStream;

/** Java-only because this provider starts in the standalone test APK's process. */
public final class MenuAccessProbeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"probe".equals(method)) throw new IllegalArgumentException();
        Bundle result = new Bundle();
        result.putInt("uid", getContext().getApplicationInfo().uid);
        try (InputStream input = getContext().getContentResolver().openInputStream(
            Uri.parse("content://io.github.ffenuss.modkit.test.space-menu/index"))) {
            result.putBoolean("denied", false);
        } catch (SecurityException expected) { result.putBoolean("denied", true); }
        catch (Exception error) { result.putString("error", error.toString()); }
        return result;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
