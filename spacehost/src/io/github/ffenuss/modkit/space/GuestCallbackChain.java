package io.github.ffenuss.modkit.space;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Observe the existing callback without replacing its behavior or swallowing its failures. */
final class GuestCallbackChain implements InvocationHandler {
    interface Observer { void after(Method method, Object[] args) throws Exception; }
    private final Object delegate;
    private final Observer observer;
    private GuestCallbackChain(Object delegate, Observer observer) {
        this.delegate = delegate; this.observer = observer;
    }
    static Object wrap(Class<?> contract, Object delegate, Observer observer) {
        if (!contract.isInterface() || !contract.isInstance(delegate) || observer == null)
            throw new IllegalArgumentException("Invalid callback contract");
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, new GuestCallbackChain(delegate, observer));
    }
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final Object result;
        try { result = method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
        // A ModKit observer failure must never break the guest's original startup callback.
        if (method.getDeclaringClass() != Object.class) {
            try { observer.after(method, args); }
            catch (Exception | LinkageError ignored) { }
        }
        return result;
    }
}
