package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;

/**
 * Compile-time stub of the XposedBridge API (api-82).
 * At runtime LSPosed injects the real implementation.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass,
                                                           String methodName,
                                                           XC_MethodHook callback) {
        return new HashSet<>();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass,
                                                                XC_MethodHook callback) {
        return new HashSet<>();
    }
}
