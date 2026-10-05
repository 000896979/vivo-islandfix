package de.robv.android.xposed.callbacks;

/**
 * Compile-time stub of the XposedBridge API (api-82).
 * NOT packaged into the module APK: at runtime LSPosed supplies the real class.
 */
public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = Integer.MIN_VALUE;
    public static final int PRIORITY_HIGHEST = Integer.MAX_VALUE;

    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static abstract class Param {
        public final Object[] args;

        public Param(Object[] args) {
            this.args = args;
        }
    }

    @Override
    public int compareTo(XCallback other) {
        if (this == other) {
            return 0;
        }
        if (other.priority > this.priority) {
            return -1;
        }
        if (other.priority < this.priority) {
            return 1;
        }
        return 0;
    }
}
