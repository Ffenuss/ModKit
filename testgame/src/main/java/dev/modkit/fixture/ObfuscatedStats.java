package dev.modkit.fixture;

/**
 * Fully obfuscated counterpart of PlayerStats: single-letter members and no
 * gameplay names. The same resource shapes (decrement + write-back, zero
 * threshold check, guarded consumption) must be recoverable from code
 * structure alone, without relying on any identifier.
 *
 *   a  ~ health   (d reads it, f damages it, g is the death check)
 *   b  ~ stamina  (e reads it, h is the availability gate, i consumes it)
 *   c  ~ distance (j reads it, i increments it)
 */
public final class ObfuscatedStats {
    private int a = 20;
    private int b = 10;
    private int c;

    public int d() { return a; }
    public int e() { return b; }
    public int j() { return c; }

    public void f() { a = Math.max(0, d() - 7); }
    public boolean g() { return d() <= 0; }
    public boolean h() { return b > 0; }
    public void i() { if (h()) { c++; b = Math.max(0, b - 5); } }
}
