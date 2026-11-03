package dev.modkit.fixture;

/**
 * Owned offline movement fixture.
 *
 * An obfuscated float getter affects a real displacement write-back rather
 * than simply being displayed in the HUD. The patched getter should double
 * the distance traveled by each step from 1 to 2.
 */
public final class MovementStats {
    private float moveSpeed = 1.0f;
    private float distance = 0.0f;

    public float a() {
        return moveSpeed;
    }

    public void step() {
        distance += a();
    }

    public int traveled() {
        return Math.round(distance);
    }
}
