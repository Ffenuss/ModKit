package io.github.ffenuss.modkit.spacecompanion;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.List;

/** A positive native write must never be confused with gameplay verification. */
public final class PreparedNativeRecipeContractTest {
    private static final String PACKAGE = "dev.modkit.fixture";
    private static List<PreparedNativeRecipeContract.Recipe> valid(
            String[] ids, String[] labels, boolean[] states) {
        return PreparedNativeRecipeContract.accept(
                PreparedNativeRecipeContract.VERSION, PACKAGE, PACKAGE,
                1, 12345, true, ids, labels, states);
    }

    @Test public void strictAcknowledgementPreservesPerRecipeState() {
        List<PreparedNativeRecipeContract.Recipe> items = valid(
                new String[]{"movement_patch", "health_patch"},
                new String[]{"Movement patch", "Health patch"},
                new boolean[]{true, false});
        assertNotNull(items);
        assertEquals(2, items.size());
        assertTrue(items.get(0).applied);
        assertFalse(items.get(1).applied);
        assertNotNull(valid(new String[0], new String[0], new boolean[0]));
    }

    @Test public void neverAcceptsUntrustedOrInconsistentReply() {
        assertNull(PreparedNativeRecipeContract.accept(
                "wrong", PACKAGE, PACKAGE, 1, 2, true,
                new String[0], new String[0], new boolean[0]));
        assertNull(PreparedNativeRecipeContract.accept(
                PreparedNativeRecipeContract.VERSION, "other.pkg", PACKAGE, 1, 2, true,
                new String[0], new String[0], new boolean[0]));
        assertNull(PreparedNativeRecipeContract.accept(
                PreparedNativeRecipeContract.VERSION, PACKAGE, PACKAGE, 1, 2, false,
                new String[0], new String[0], new boolean[0]));
        assertNull(valid(new String[]{"duplicate", "duplicate"},
                new String[]{"One", "Two"}, new boolean[]{false, true}));
        assertNull(valid(new String[]{"bad/id"},
                new String[]{"Injected"}, new boolean[]{false}));
        assertNull(valid(new String[]{"good"},
                new String[]{"Bad\nlabel"}, new boolean[]{true}));
        assertNull(valid(new String[]{"good"}, new String[0], new boolean[]{true}));
        assertNull(valid(new String[]{"good"}, new String[]{"Okay"}, new boolean[0]));
        assertNull(valid(null, null, null));
        String[] large = new String[65], labels = new String[65];
        for (int i = 0; i < 65; i++) {
            large[i] = "id_" + i;
            labels[i] = "Label " + i;
        }
        assertNull(valid(large, labels, new boolean[65]));
    }
}
