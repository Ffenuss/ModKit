package dev.modkit.fixture;

/** Owned offline skill/cooldown fixture, not connected to any service. */
public final class CooldownStats {
    private int cooldownTicks = 3;
    private int actions = 0;

    /** Proven, read-only predicate whose return controls the action gate. */
    public boolean isOnCooldown() {
        return cooldownTicks > 0;
    }

    /** Real countdown write-back needed for structural confirmation. */
    public void tick() {
        cooldownTicks = Math.max(0, cooldownTicks - 1);
    }

    public void useSkill() {
        if (!isOnCooldown()) {
            actions++;
            cooldownTicks = 3;
        }
    }

    public int remaining() { return cooldownTicks; }
    public int actions() { return actions; }
}
