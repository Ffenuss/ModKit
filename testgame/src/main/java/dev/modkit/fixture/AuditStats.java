package dev.modkit.fixture;

/** A plausible name is insufficient: replacing this method would remove a side effect. */
public final class AuditStats {
    private int reads;
    public int getHealth() { reads++; return reads; }
}
