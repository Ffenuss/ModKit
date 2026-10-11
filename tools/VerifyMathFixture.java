import dev.modkit.fixture.PlayerStats;

/** Exact original values for every switch arm of the owned fixture. */
public final class VerifyMathFixture {
    private static int checks;
    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Fixture baseline changed at check " + checks);
    }
    public static void main(String[] args) {
        PlayerStats player = new PlayerStats();
        check(player.getEnergy() == -7);
        check(player.getMaxHealth() == -300);
        check(PlayerStats.getMagazineSize() == 50000);
        for (long amount : new long[]{Long.MIN_VALUE, -1, 0, 1, 4294967298L, Long.MAX_VALUE}) {
            for (int mode : new int[]{0, 1, 2, 3, -1, Integer.MAX_VALUE}) {
                long expected = Math.max(amount, 0L) + (mode == 0 ? 0 : mode == 1 ? 1 : mode == 2 ? 2 : 3);
                check(player.getAmmo(amount, mode) == expected);
            }
        }
        for (double scale : new double[]{0.0, -0.0, 2.0, -2.0, 0.125, 10.0,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int mode : new int[]{0, 100, 1000, 1, -1, Integer.MAX_VALUE}) {
                double value = Math.abs(0.125 * scale + (4294967299L - 4294967298L));
                double expected = mode == 0 ? value : value + (mode == 100 ? 1.0 : mode == 1000 ? 2.0 : 3.0);
                check(Double.doubleToLongBits(PlayerStats.getRunSpeed(4294967299L, scale, 4294967298L, mode)) ==
                    Double.doubleToLongBits(expected));
            }
        }
        check(player.getHealth() == 20 && player.a() == 20);
        for (int health : new int[]{13, 6, 0, 0}) {
            player.hit(); check(player.getHealth() == health && player.a() == health);
            check(player.isDead() == (health == 0));
        }
        check(player.canSprint()); player.sprint(); check(player.getStamina() == 5 && player.distance() == 1);
        player.sprint(); check(player.getStamina() == 0 && player.distance() == 2 && !player.canSprint());
        player.sprint(); check(player.distance() == 2);
        System.out.println("Owned fixture baseline: " + checks + " checks passed");
    }
}
