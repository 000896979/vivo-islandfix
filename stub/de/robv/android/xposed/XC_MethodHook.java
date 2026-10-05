package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XCallback;

import java.lang.reflect.Member;

/**
 * Compile-time stub of the XposedBridge API (api-82).
 */
public abstract class XC_MethodHook extends XCallback {

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam extends XCallback.Param {
        public MethodHookParam() {
            super(null);
        }

        /** The hooked member (Method or Constructor). */
        public Member method;

        /** The instance the method was called on; null for static methods. */
        public Object thisObject;

        /** Arguments of the call. Read/write. */
        public Object[] args;

        private Object result = null;
        private Throwable throwable = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }
    }

    /** Returned by the hooking helpers so a hook can be removed again. */
    public class Unhook implements Runnable {
        public final Member hookMethod;

        public Unhook(Member hookMethod) {
            this.hookMethod = hookMethod;
        }

        public void unhook() {
        }

        @Override
        public void run() {
            unhook();
        }
    }
}
