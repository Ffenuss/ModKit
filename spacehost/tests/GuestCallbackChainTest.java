package io.github.ffenuss.modkit.space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class GuestCallbackChainTest {
    public interface Callback { void before(String app); void after(String app); String intent(String value); }
    public static final class Original implements Callback {
        final List<String> events;
        final IllegalStateException failure = new IllegalStateException("Original failure");
        Original(List<String> events) { this.events = events; }
        public void before(String app) { events.add("original-before:" + app); }
        public void after(String app) { events.add("original-after:" + app); }
        public String intent(String value) { if ("fail".equals(value)) throw failure; return "original:" + value; }
    }
    public static void main(String[] args) {
        List<String> events = new ArrayList<>();
        Original original = new Original(events);
        Callback chain = (Callback) GuestCallbackChain.wrap(Callback.class, original,
            (method, values) -> events.add("observer-" + method.getName() + ":" + values[0]));
        chain.before("one"); chain.after("one");
        if (!events.equals(Arrays.asList("original-before:one", "observer-before:one", "original-after:one", "observer-after:one")))
            throw new AssertionError("Lifecycle order changed: " + events);
        if (!"original:two".equals(chain.intent("two"))) throw new AssertionError("Original result lost");
        int count = events.size();
        try { chain.intent("fail"); throw new AssertionError("Original failure swallowed"); }
        catch (IllegalStateException expected) { if (expected != original.failure) throw new AssertionError("Failure wrapped"); }
        if (count != events.size()) throw new AssertionError("Observer ran after failed original callback");
        Callback brokenObserver = (Callback) GuestCallbackChain.wrap(Callback.class, original, (method, values) -> { throw new Exception("ModKit failure"); });
        if (!"original:three".equals(brokenObserver.intent("three"))) throw new AssertionError("ModKit interrupted original");
        Callback missingObserver = (Callback) GuestCallbackChain.wrap(Callback.class, original, (method, values) -> { throw new NoClassDefFoundError("Missing executor"); });
        if (!"original:four".equals(missingObserver.intent("four"))) throw new AssertionError("Missing ModKit class interrupted original");
        chain.toString();
        if (events.size() != count) throw new AssertionError("Object method observed as guest lifecycle");
        System.out.println("PASS: original callbacks/order/results/failures preserved; observer errors isolated");
    }
}
