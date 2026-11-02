package dev.modkit.fixture;

/**
 * Owned offline economy fixture.
 * The coin value controls a real purchase gate, not just a displayed label.
 * A replenishing/depleting counter with a different name must not be labeled
 * as currency solely from its instruction shapes.
 */
public final class WalletStats {
    private int coins = 20;
    private int purchases = 0;

    public int getCoins() { return coins; }
    public int purchases() { return purchases; }

    public void earnCoins() {
        coins = Math.min(99, getCoins() + 5);
    }

    public boolean canPurchase() {
        return getCoins() >= 10;
    }

    public void purchase() {
        if (canPurchase()) {
            coins = Math.max(0, getCoins() - 10);
            purchases++;
        }
    }
}
