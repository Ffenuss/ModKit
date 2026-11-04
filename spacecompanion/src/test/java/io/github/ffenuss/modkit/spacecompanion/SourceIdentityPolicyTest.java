package io.github.ffenuss.modkit.spacecompanion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SourceIdentityPolicyTest {
    private static final String BASE = "a".repeat(64) + ":1024";
    private static final String SPLIT = "b".repeat(64) + ":2048";

    @Test public void completeSplitSetIsOrderIndependent() {
        assertTrue(SourceIdentityPolicy.matches(
                Arrays.asList(BASE, SPLIT), Arrays.asList(SPLIT, BASE)));
    }

    @Test public void missingOrChangedSplitIsRejected() {
        assertFalse(SourceIdentityPolicy.matches(Arrays.asList(BASE, SPLIT),
                Collections.singletonList(BASE)));
        assertFalse(SourceIdentityPolicy.matches(Arrays.asList(BASE, SPLIT),
                Arrays.asList(BASE, "c".repeat(64) + ":2048")));
        assertFalse(SourceIdentityPolicy.matches(Arrays.asList(BASE, SPLIT),
                Arrays.asList(BASE, "b".repeat(64) + ":4096")));
    }

    @Test public void duplicateInvalidAndEmptyIdentitiesAreRejected() {
        assertFalse(SourceIdentityPolicy.matches(Collections.emptyList(), Collections.emptyList()));
        assertFalse(SourceIdentityPolicy.matches(Arrays.asList(BASE, BASE),
                Arrays.asList(BASE, BASE)));
        assertFalse(SourceIdentityPolicy.matches(Collections.singletonList("z".repeat(64) + ":1"),
                Collections.singletonList("z".repeat(64) + ":1")));
        assertFalse(SourceIdentityPolicy.matches(Collections.singletonList("a".repeat(64) + ":0"),
                Collections.singletonList("a".repeat(64) + ":0")));
        assertFalse(SourceIdentityPolicy.matches(Collections.singletonList("a".repeat(64) + ":9223372036854775808"),
                Collections.singletonList("a".repeat(64) + ":9223372036854775808")));
    }
}
