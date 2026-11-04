package io.github.ffenuss.modkit.spacecompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import org.junit.Test;

public class OverlaySessionPolicyTest {
    private final String artifact = "a".repeat(64);
    @Test public void onlyExactCompanionPublisherCanStartOverlay() {
        assertTrue(OverlaySessionPolicy.valid("dev.modkit.fixture", artifact,
                "io.github.ffenuss.modkit.test"));
        assertTrue(OverlaySessionPolicy.valid("dev.modkit.fixture", artifact,
                "io.github.ffenuss.modkit"));
        assertFalse(OverlaySessionPolicy.valid("dev.modkit.fixture", artifact,
                "dev.modkit.attacker"));
        assertFalse(OverlaySessionPolicy.valid("dev.modkit.fixture", "invalid",
                "io.github.ffenuss.modkit.test"));
        assertFalse(OverlaySessionPolicy.valid("../fixture", artifact,
                "io.github.ffenuss.modkit.test"));
    }

    @Test public void noSessionWithoutCurrentExactCatalogEntry() {
        assertNull(OverlaySessionPolicy.find(Collections.emptyList(),
                "dev.modkit.fixture", artifact, "io.github.ffenuss.modkit.test"));
        assertNull(OverlaySessionPolicy.find(null,
                "dev.modkit.fixture", artifact, "io.github.ffenuss.modkit.test"));
    }
}
