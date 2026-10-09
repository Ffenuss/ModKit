package dev.modkit.fixture;

/** Owned, offline fixture. Getters feed the death rule as well as the display. */
public final class PlayerStats {
    private int health = 20;
    private int stamina = 10;
    private int distance;
    private long ammo = 0L;
    private static double runSpeed = 0.125;
    public long getAmmo(long amount, int mode) {
        switch (mode) {
            case 0: return ammo + amount;
            case 1: return ammo + amount + 1L;
            case 2: return ammo + amount + 2L;
            default: return ammo + amount + 3L;
        }
    }
    public static double getRunSpeed(long first, double scale, long second, int mode) {
        double value = runSpeed * scale + (first - second);
        switch (mode) {
            case 0: return value;
            case 100: return value + 1.0;
            case 1000: return value + 2.0;
            default: return value + 3.0;
        }
    }
    public int getHealth() { return health; }
    public int a() { return health; } // Partial obfuscation; field dataflow is recoverable.
    public int getStamina() { return stamina; }
    public int getInventoryCapacity() { return 8; }
    public void hit() { health = Math.max(0, getHealth() - 7); }
    public boolean isDead() { return a() <= 0; }
    public boolean canSprint() { return stamina > 0; }
    public void sprint() { if (canSprint()) { distance++; stamina = Math.max(0, stamina - 5); } }
    public int distance() { return distance; }
}
