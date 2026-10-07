package io.github.ffenuss.modkit.space;

public final class SpacePolicyTest {
    public static void main(String[] args) {
        String[] good = {"com.x.aniimos", "com.android.vending", "com.google.android.gms", "io.github.ffenuss.modkit.test"};
        for (String pkg : good) if (!SpacePolicy.validSession(pkg, 0) || !SpacePolicy.validSession(pkg, 7)) throw new AssertionError(pkg);
        String[] bad = {null, "", "com", "../com.game", "com.game:guest", "com.game\n", "com..game", "com.game/Activity"};
        for (String pkg : bad) if (SpacePolicy.validSession(pkg, 0)) throw new AssertionError("Accepted: " + pkg);
        if (SpacePolicy.validSession("com.game", -1)) throw new AssertionError("Accepted negative virtual user");
        System.out.println("PASS: virtual-session identity validation");
    }
}
