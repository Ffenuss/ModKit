package io.github.ffenuss.modkit.spacecompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

/** Never advertise an unverified executor for arbitrary game packages. */
public class RuntimeControlBridgeTest {
    @Test public void noRuntimeControlWithoutExplicitSupportedOwnedProfile() {
        assertFalse(RuntimeControlBridge.supports(null));
        assertEquals("dev.modkit.fixture", RuntimeControlBridge.OWNED_PACKAGE);
    }
}
