package dev.modkit.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Arrays;

/**
 * Fixture-owned, opt-in runtime control contract. Only the exact installed
 * Companion process UID can call this provider. These controls intentionally
 * do not generalize to unaffiliated packages or unknown game processes.
 */
public final class FixtureRuntimeControlProvider extends ContentProvider {
    public static final String AUTHORITY = "dev.modkit.fixture.modkit-runtime";
    public static final String CONTRACT = "owned_fixture_v1";
    public static final String METHOD_STATUS = "status";
    public static final String METHOD_SET = "set_double_movement";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ACTIVE = "active";

    /** Versioned recipe contract. No addresses, executable blobs or arbitrary
     * method names are accepted from a client. Exact IDs are implemented by
     * the owned in-process fixture and acknowledged after mutation. */
    public static final String CONTRACT_V2 = "owned_fixture_recipe_v2";
    public static final String METHOD_RECIPES = "recipes_v2";
    public static final String METHOD_SET_RECIPE = "set_recipe_v2";
    public static final String KEY_RECIPE_ID = "recipeId";
    public static final String RECIPE_MOVEMENT = "movement_x2";
    public static final String RECIPE_SHIELD = "damage_shield";
    private static final String[] RECIPE_IDS = {RECIPE_MOVEMENT, RECIPE_SHIELD};
    private static final String[] RECIPE_LABELS = {"Движение ×2", "Защита здоровья"};


    @Override public boolean onCreate() { return true; }

    private void authorize() {
        Context context = getContext();
        if (context == null) throw new SecurityException("Missing fixture context");
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        String companion = "io.github.ffenuss.modkit.spacecompanion";
        PackageManager pm = context.getPackageManager();
        if (packages == null || !Arrays.asList(packages).contains(companion)) {
            throw new SecurityException("Only the installed ModKit Space Companion may control fixture");
        }
        // The caller's UID owns the exact installed companion package AND
        // its certificate must match the currently installed ModKit publisher.
        // The fixture's separate disposable split-signing key is irrelevant.
        boolean trustedPublisher =
                pm.checkSignatures(companion, "io.github.ffenuss.modkit.test")
                        == PackageManager.SIGNATURE_MATCH ||
                pm.checkSignatures(companion, "io.github.ffenuss.modkit")
                        == PackageManager.SIGNATURE_MATCH;
        if (!trustedPublisher) {
            throw new SecurityException("Companion signer does not match ModKit");
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize();
        if (METHOD_RECIPES.equals(method) || METHOD_SET_RECIPE.equals(method)) {
            if (METHOD_SET_RECIPE.equals(method)) {
                if (extras == null || !extras.containsKey(KEY_ENABLED))
                    throw new IllegalArgumentException("Explicit ON/OFF state required");
                String id = extras.getString(KEY_RECIPE_ID);
                if (RECIPE_MOVEMENT.equals(id)) {
                    RuntimeLiveState.setDoubleMovement(extras.getBoolean(KEY_ENABLED));
                } else if (RECIPE_SHIELD.equals(id)) {
                    RuntimeLiveState.setDamageShield(extras.getBoolean(KEY_ENABLED));
                } else {
                    throw new IllegalArgumentException("Unknown runtime recipe identifier");
                }
            }
            Bundle result = new Bundle();
            result.putString("contract", CONTRACT_V2);
            result.putBoolean(KEY_ACTIVE, RuntimeLiveState.isResumed());
            result.putStringArray("ids", RECIPE_IDS.clone());
            result.putStringArray("labels", RECIPE_LABELS.clone());
            result.putBooleanArray("enabledStates", new boolean[] {
                    RuntimeLiveState.isDoubleMovementEnabled(),
                    RuntimeLiveState.isDamageShieldEnabled(),
            });
            return result;
        }
        if (!METHOD_STATUS.equals(method) && !METHOD_SET.equals(method))
            throw new IllegalArgumentException("Unsupported fixture runtime operation");
        if (METHOD_SET.equals(method)) {
            if (extras == null || !extras.containsKey(KEY_ENABLED))
                throw new IllegalArgumentException("Explicit toggle state required");
            RuntimeLiveState.setDoubleMovement(extras.getBoolean(KEY_ENABLED));
        }
        Bundle result = new Bundle();
        result.putString("contract", CONTRACT);
        result.putBoolean(KEY_ACTIVE, RuntimeLiveState.isResumed());
        result.putBoolean(KEY_ENABLED, RuntimeLiveState.isDoubleMovementEnabled());
        return result;
    }

    @Override public String getType(Uri uri) { authorize(); return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        authorize();
        throw new UnsupportedOperationException("Use explicit call contract");
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        authorize();
        throw new UnsupportedOperationException("Read-only except controlled call");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        authorize();
        throw new UnsupportedOperationException("Read-only except controlled call");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        authorize();
        throw new UnsupportedOperationException("Read-only except controlled call");
    }
}
