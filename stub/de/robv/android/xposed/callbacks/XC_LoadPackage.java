package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * Compile-time stub of the XposedBridge API (api-82).
 */
public abstract class XC_LoadPackage extends XCallback {

    public XC_LoadPackage() {
    }

    public XC_LoadPackage(int priority) {
        super(priority);
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public LoadPackageParam(XCallback.Param param) {
            super(param.args);
        }

        /** Package name of the app/process being loaded. */
        public String packageName;

        /** Real process name; differs from packageName for sub-processes. */
        public String processName;

        /** Class loader that can be used to reach the target's classes. */
        public ClassLoader classLoader;

        public ApplicationInfo appInfo;

        public boolean isFirstApplication;
    }
}
