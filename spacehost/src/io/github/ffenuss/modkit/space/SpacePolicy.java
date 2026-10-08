package io.github.ffenuss.modkit.space;

final class SpacePolicy {
    private SpacePolicy() {}
    static boolean validSession(String pkg, int user) {
        return user >= 0 && pkg != null && pkg.length() <= 255
            && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
