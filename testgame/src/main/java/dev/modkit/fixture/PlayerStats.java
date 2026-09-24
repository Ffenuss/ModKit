package dev.modkit.fixture;

/** Owned, offline fixture. Getters feed the death rule as well as the display. */
public final class PlayerStats {
    private int health = 20;
    private int stamina = 10;
    public int getHealth() { return health; }
    public int a() { return health; } // Partial obfuscation; field dataflow is recoverable.
    public int getStamina() { return stamina; }
    public int getInventoryCapacity() { return 8; }
    public void hit() { health = Math.max(0, getHealth() - 7); }
    public boolean isDead() { return a() <= 0; }
}
