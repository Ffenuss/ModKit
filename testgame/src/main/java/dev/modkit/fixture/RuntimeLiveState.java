package dev.modkit.fixture;

/**
 * An explicitly cooperative, in-process game fixture controller. No other
 * installed game is modified. State is volatile and never persisted; leaving
 * the running activity always restores normal movement.
 */
final class RuntimeLiveState {
    private static volatile boolean activityResumed;
    private static volatile boolean doubleMovement;
    private static volatile boolean damageShield;

    private RuntimeLiveState() {}

    static synchronized void resume() {
        activityResumed = true;
        doubleMovement = false;
        damageShield = false;
    }

    static synchronized void pause() {
        doubleMovement = false;
        damageShield = false;
        activityResumed = false;
    }

    static synchronized boolean setDoubleMovement(boolean enabled) {
        if (!activityResumed) {
            doubleMovement = false;
            return false;
        }
        doubleMovement = enabled;
        return true;
    }

    static synchronized boolean setDamageShield(boolean enabled) {
        if (!activityResumed) {
            damageShield = false;
            return false;
        }
        damageShield = enabled;
        return true;
    }

    static boolean isDamageShieldEnabled() {
        return activityResumed && damageShield;
    }

    static boolean isResumed() {
        return activityResumed;
    }

    static boolean isDoubleMovementEnabled() {
        return activityResumed && doubleMovement;
    }
}
