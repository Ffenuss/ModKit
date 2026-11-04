package io.github.ffenuss.modkit.spacecompanion;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class VerifiedRecipeContractTest {
    private static final String[] IDS = {
            VerifiedRecipeContract.MOVEMENT, VerifiedRecipeContract.SHIELD};
    private static final String[] LABELS = {
            "Движение ×2", "Защита здоровья"};

    @Test public void acceptsIndependentKnownRecipesInEitherOrder() {
        List<VerifiedRecipeContract.Recipe> recipes =
                VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION,
                        true, IDS, LABELS, new boolean[]{true, false});
        assertNotNull(recipes);
        assertEquals(2, recipes.size());
        assertTrue(recipes.get(0).enabled);
        assertFalse(recipes.get(1).enabled);
        assertNotNull(VerifiedRecipeContract.accept(
                VerifiedRecipeContract.VERSION, true,
                new String[]{IDS[1], IDS[0]},
                new String[]{LABELS[1], LABELS[0]},
                new boolean[]{false, true}));
    }

    @Test public void rejectsUnknownDuplicateAndMismatchedRecipeDeclarations() {
        assertNull(VerifiedRecipeContract.accept("wrong", true,
                IDS, LABELS, new boolean[]{false, false}));
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, true,
                new String[]{IDS[0], "injected_unknown"},
                LABELS, new boolean[]{false, false}));
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, true,
                new String[]{IDS[0], IDS[0]}, LABELS,
                new boolean[]{false, false}));
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, true,
                IDS, new String[]{LABELS[0], "Fake mod"},
                new boolean[]{false, false}));
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, true,
                IDS, LABELS, new boolean[]{false}));
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, true,
                IDS, LABELS, null));
    }

    @Test public void pausedGameCannotClaimEnabledCapabilities() {
        assertNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, false,
                IDS, LABELS, new boolean[]{true, false}));
        assertNotNull(VerifiedRecipeContract.accept(VerifiedRecipeContract.VERSION, false,
                IDS, LABELS, new boolean[]{false, false}));
    }
}
