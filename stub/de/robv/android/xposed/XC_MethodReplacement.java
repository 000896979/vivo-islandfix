package de.robv.android.xposed;

/**
 * Compile-time stub of the XposedBridge API (api-82).
 * Subclasses replace the hooked method's result entirely.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = Integer.MIN_VALUE;
    public static final int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    public XC_MethodReplacement() {
        super();
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            Object result = replaceHookedMethod(param);
            param.setResult(result);
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return result;
            }
        };
    }

    public static XC_MethodReplacement doNothing() {
        return new XC_MethodReplacement(PRIORITY_HIGHEST * -1) {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return null;
            }
        };
    }
}
