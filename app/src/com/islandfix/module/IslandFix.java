package com.islandfix.module;

import android.content.Context;
import android.media.MediaMetadata;
import android.os.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 原子岛媒体会话解锁 —— LSPosed / Xposed 模块
 *
 * 逆向自 OriginOS 16.0 / PD2352B_A_16.2.17.0.W10。
 *
 * 数据链（com.vivo.musicwidgetmix = 音乐卡片数据源）：
 *   vivo framework AudioFeature TrackState:pid=;streamType=;playState=;uid=
 *     -> MainApplication$m.onCallback()        解析状态
 *       -> utils.d.Z(ctx,pkg)                  白名单闸门 ①
 *         -> MainApplication.g1()              派发
 *           -> MusicWidgetMixService.k1(pkg)   切源
 *             -> MusicWidgetMixService$j.run()
 *               -> controller.d3.a()           控制器工厂
 *                 -> new z2(ctx,pkg,cb)        通用 MediaSession 控制器
 *                   -> z2.a()  取 MediaController
 *                      -> z2.a0() 读 MediaMetadata -> 发 259
 *                         -> z2.x(259) -> utils.p.s() -> 封面/标题/进度
 *
 * 另一个准入闸门：MainApplication$k.onActiveSessionsChanged 用 s4.m0.e()。
 *
 * 模块分两部分：
 *   [FIX]   放行闸门 ①② 与黑名单 Y()
 *   [PROBE] 打印元数据链路实际取值，定位断点
 *
 * 纯运行时 Hook：不改 APK、不挂载、不写任何分区。
 */
public class IslandFix implements IXposedHookLoadPackage {

    private static final String TAG = "IslandFix";

    /** 需要放行的目标应用。 */
    private static final String[] TARGET_PKGS = {
            "com.example.piliplus",
    };

    /** true 时任意包名放行（彻底解除白名单）；false 时仅放行 TARGET_PKGS。 */
    private static final boolean ALLOW_ALL = true;

    /** 探针开关。正式版关闭，只保留针对目标包的关键日志。 */
    private static final boolean PROBE = false;

    private static final String PKG_MUSIC_MIX = "com.vivo.musicwidgetmix";
    private static final String PKG_SYSTEMUI_PLUGIN = "com.vivo.systemuiplugin";

    private static final String CLS_WHITELIST = "s4.m0";
    private static final String CLS_APP_UTILS = "com.vivo.musicwidgetmix.utils.d";
    private static final String CLS_COMMON_UTILS = "com.vivo.musicwidgetmix.utils.u";
    private static final String CLS_BITMAP_UTILS = "com.vivo.musicwidgetmix.utils.p";
    private static final String CLS_MAIN_APP = "com.vivo.musicwidgetmix.MainApplication";
    private static final String CLS_TRACK_CALLBACK = "com.vivo.musicwidgetmix.MainApplication$m";
    private static final String CLS_CTRL_BASE = "com.vivo.musicwidgetmix.controller.c3";
    private static final String CLS_CTRL_FACTORY = "com.vivo.musicwidgetmix.controller.d3";
    private static final String CLS_CTRL_Z2 = "com.vivo.musicwidgetmix.controller.z2";
    private static final String CLS_SERVICE = "com.vivo.musicwidgetmix.service.MusicWidgetMixService";
    private static final String CLS_SERVICE_TASK = "com.vivo.musicwidgetmix.service.MusicWidgetMixService$j";

    /** u.c 栈回溯只打印前几次，避免刷屏。 */
    private static int sUCStackDumps = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        boolean isMusicMix = PKG_MUSIC_MIX.equals(lpparam.packageName);
        boolean isIsland = PKG_SYSTEMUI_PLUGIN.equals(lpparam.packageName);
        if (!isMusicMix && !isIsland) {
            return;
        }

        log("onLoad pkg=" + lpparam.packageName + " process=" + lpparam.processName);

        hookWhitelistGetters(lpparam.classLoader);
        hookPredicates(lpparam.classLoader);
        hookSupportGate(lpparam.classLoader);
        hookSuccessMarkers(lpparam.classLoader);
        if (PROBE) {
            hookProbes(lpparam.classLoader);
        }
    }

    /* ==================== 常驻成功标记（仅目标包，低噪声） ==================== */

    /**
     * 两条"链路走通"的证据，只针对 TARGET_PKGS 打印：
     *   1) 控制器工厂为目标包造出了适配器（说明 n0 闸门已放行）
     *   2) utils.p.s() 收到元数据（说明封面/标题/进度已经交给卡片 UI）
     * 日常使用可以靠这两行确认模块是否生效。
     */
    private void hookSuccessMarkers(ClassLoader cl) {
        Class<?> factory = XposedHelpers.findClassIfExists(CLS_CTRL_FACTORY, cl);
        Class<?> base = XposedHelpers.findClassIfExists(CLS_CTRL_BASE, cl);
        if (factory != null && base != null) {
            Class<?> callbackType = findNested(base, "a");
            if (callbackType != null) {
                try {
                    XposedHelpers.findAndHookMethod(factory, "a",
                            Context.class, int.class, String.class, callbackType,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    Object pkgArg = param.args[2];
                                    if (!(pkgArg instanceof String) || !isTarget((String) pkgArg)) {
                                        return;
                                    }
                                    Object r = param.getResult();
                                    log("controller " + (r == null ? "null"
                                            : r.getClass().getSimpleName())
                                            + " created for " + pkgArg);
                                }
                            });
                    log("hooked controller marker");
                } catch (Throwable t) {
                    log("hook controller marker failed: " + t);
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(CLS_BITMAP_UTILS, cl, "s",
                    Context.class, String.class, MediaMetadata.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object pkgArg = param.args[1];
                            if (!(pkgArg instanceof String) || !isTarget((String) pkgArg)) {
                                return;
                            }
                            MediaMetadata md = (MediaMetadata) param.args[2];
                            log("metadata delivered for " + pkgArg
                                    + " -> title=" + str(md, MediaMetadata.METADATA_KEY_TITLE)
                                    + ", duration=" + (md == null ? "-"
                                    : String.valueOf(md.getLong(MediaMetadata.METADATA_KEY_DURATION)))
                                    + ", cover=" + coverState(md));
                        }
                    });
            log("hooked metadata marker");
        } catch (Throwable t) {
            log("hook metadata marker failed: " + t);
        }
    }

    /** 封面是否有可用的位图或 URL。 */
    private static String coverState(MediaMetadata md) {
        if (md == null) {
            return "no-metadata";
        }
        try {
            if (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null) {
                return "bitmap";
            }
            if (md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null) {
                return "display-icon";
            }
            if (md.getDescription() != null
                    && XposedHelpers.callMethod(md.getDescription(), "getIconBitmap") != null) {
                return "desc-icon";
            }
            String uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) {
                uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            }
            return uri == null ? "none" : ("uri:" + uri);
        } catch (Throwable t) {
            return "err";
        }
    }

    /* ==================== [FIX] 闸门 ①  白名单容器 ==================== */

    private void hookWhitelistGetters(ClassLoader cl) {
        Class<?> whitelist = XposedHelpers.findClassIfExists(CLS_WHITELIST, cl);
        if (whitelist == null) {
            log("class " + CLS_WHITELIST + " not present here, skip whitelist");
            return;
        }
        for (final String name : new String[]{"e", "d", "b", "c"}) {
            try {
                XposedHelpers.findAndHookMethod(whitelist, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(mergeWhitelist(name, param.getResult()));
                    }
                });
                log("hooked " + CLS_WHITELIST + "." + name + "()");
            } catch (Throwable t) {
                log("hook " + CLS_WHITELIST + "." + name + " failed: " + t);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object mergeWhitelist(String getter, Object result) {
        if (!(result instanceof List)) {
            return result;
        }
        List<Object> list = (List<Object>) result;
        try {
            boolean changed = false;
            for (String pkg : TARGET_PKGS) {
                if (!list.contains(pkg)) {
                    list.add(pkg);
                    changed = true;
                }
            }
            if (changed) {
                log("whitelist " + getter + "() -> " + list);
            }
            return list;
        } catch (UnsupportedOperationException e) {
            List<Object> copy = new ArrayList<Object>(list);
            copy.addAll(Arrays.asList(TARGET_PKGS));
            log("whitelist " + getter + "() immutable -> replaced");
            return copy;
        }
    }

    /* ==================== [FIX] 闸门 ②  布尔判定 Z / W / Y ==================== */

    private void hookPredicates(ClassLoader cl) {
        Class<?> appUtils = XposedHelpers.findClassIfExists(CLS_APP_UTILS, cl);
        if (appUtils == null) {
            log("class " + CLS_APP_UTILS + " not present here, skip predicates");
            return;
        }

        for (final String method : new String[]{"Z", "W"}) {
            try {
                XposedHelpers.findAndHookMethod(appUtils, method, Context.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String pkg = arg1(param);
                                if (pkg != null && isAllowed(pkg) && !Boolean.TRUE.equals(param.getResult())) {
                                    param.setResult(Boolean.TRUE);
                                    logFix(pkg, "d." + method + "(" + pkg + ") -> true");
                                }
                            }
                        });
                log("hooked " + CLS_APP_UTILS + "." + method + "(Context,String)");
            } catch (Throwable t) {
                log("hook d." + method + " failed: " + t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(appUtils, "Y", Context.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String pkg = arg1(param);
                            if (pkg != null && isAllowed(pkg) && Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(Boolean.FALSE);
                                logFix(pkg, "d.Y(" + pkg + ") -> false (blacklist bypass)");
                            }
                        }
                    });
            log("hooked " + CLS_APP_UTILS + ".Y(Context,String)");
        } catch (Throwable t) {
            log("hook d.Y failed: " + t);
        }
    }

    /* ==================== [FIX] 闸门 ③  控制器准入 ==================== */

    /**
     * 白名单放行之后还有一道隐性闸门：
     *
     *   utils.d.s(ctx) 取白名单 -> d.b(ctx) 为每个包计算 d.e(ctx,pkg)
     *     -> 存入 MainApplication.f8951c0（"受支持"映射）
     *       -> MusicWidgetMixService.n0(pkg) 返回 !f8951c0.get(pkg)
     *         -> j.run() 里 if (!n0(pkg)) 才创建控制器
     *
     * d.e() 内部是写死的包名白名单，第三方 App 一律 false，于是 n0() 返回 true，
     * 控制器（z2）永远不会被创建，MediaMetadata 也就永远读不到 —— 表现为
     * "原子岛出现了、能控制播放，但没有封面和进度条"。
     *
     * 这里把 d.e 判为 false 的目标包改成 true，并兜底把 n0 强制为 false。
     */
    private void hookSupportGate(ClassLoader cl) {
        Class<?> appUtils = XposedHelpers.findClassIfExists(CLS_APP_UTILS, cl);
        if (appUtils != null) {
            try {
                XposedHelpers.findAndHookMethod(appUtils, "e", Context.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String pkg = arg1(param);
                                if (pkg != null && isAllowed(pkg)
                                        && !Boolean.TRUE.equals(param.getResult())) {
                                    param.setResult(Boolean.TRUE);
                                    logFix(pkg, "d.e(" + pkg + ") -> true (support map)");
                                }
                            }
                        });
                log("hooked " + CLS_APP_UTILS + ".e(Context,String)");
            } catch (Throwable t) {
                log("hook d.e failed: " + t);
            }
        }

        Class<?> service = XposedHelpers.findClassIfExists(CLS_SERVICE, cl);
        if (service != null) {
            try {
                XposedHelpers.findAndHookMethod(service, "n0", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object pkg = param.args[0];
                        if (pkg instanceof String && isAllowed((String) pkg)
                                && Boolean.TRUE.equals(param.getResult())) {
                            param.setResult(Boolean.FALSE);
                            logFix((String) pkg, "service.n0(" + pkg + ") -> false (controller unblocked)");
                        }
                    }
                });
                log("hooked " + CLS_SERVICE + ".n0(String)");
            } catch (Throwable t) {
                log("hook service.n0 failed: " + t);
            }
        }
    }

    /* ==================== [PROBE] 元数据链路观测 ==================== */

    private void hookProbes(ClassLoader cl) {
        probeTrackState(cl);
        probeCommonUtils(cl);
        probeBitmapUtils(cl);
        probeControllerFactory(cl);
        probeZ2(cl);
        probeService(cl);
    }

    /** TrackState 原始串。 */
    private void probeTrackState(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_TRACK_CALLBACK, cl, "onCallback",
                    String.class, Object.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object a0 = param.args[0];
                            if (a0 instanceof String && ((String) a0).startsWith("TrackState:")) {
                                log("PROBE TrackState = " + a0);
                            }
                        }
                    });
            log("probe hooked TrackState callback");
        } catch (Throwable t) {
            log("probe TrackState failed: " + t);
        }
    }

    /** utils.u.c —— 能否拿到目标包的 MediaController。 */
    private void probeCommonUtils(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_COMMON_UTILS, cl, "c", Context.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String pkg = arg1(param);
                            if (!isInteresting(pkg)) {
                                return;
                            }
                            Object mc = param.getResult();
                            String extra = "";
                            if (sUCStackDumps < 3) {
                                sUCStackDumps++;
                                extra = " stack=[" + fullStack() + "]";
                            }
                            log("PROBE u.c(" + pkg + ") -> "
                                    + (mc == null ? "null" : describeController(mc)) + extra);
                        }
                    });
            log("probe hooked u.c(Context,String)");
        } catch (Throwable t) {
            log("probe u.c failed: " + t);
        }
    }

    /** utils.p.s —— 元数据落地处（封面/标题解析入口）。 */
    private void probeBitmapUtils(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_BITMAP_UTILS, cl, "s",
                    Context.class, String.class, MediaMetadata.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String pkg = arg1(param);
                            if (!isInteresting(pkg)) {
                                return;
                            }
                            log("PROBE p.s(" + pkg + ") metadata="
                                    + describeMetadata((MediaMetadata) param.args[2]));
                        }
                    });
            log("probe hooked p.s(Context,String,MediaMetadata,boolean)");
        } catch (Throwable t) {
            log("probe p.s failed: " + t);
        }
    }

    /** 控制器工厂 d3.a —— 决定 piliplus 落到哪个适配器。 */
    private void probeControllerFactory(ClassLoader cl) {
        Class<?> factory = XposedHelpers.findClassIfExists(CLS_CTRL_FACTORY, cl);
        Class<?> base = XposedHelpers.findClassIfExists(CLS_CTRL_BASE, cl);
        if (factory == null || base == null) {
            log("probe factory: class missing (factory=" + factory + ", base=" + base + ")");
            return;
        }
        Class<?> callbackType = findNested(base, "a");
        if (callbackType == null) {
            log("probe factory: c3.a callback type not found");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(factory, "a",
                    Context.class, int.class, String.class, callbackType, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            log("PROBE factory d3.a(pkg=" + param.args[2] + ", type=" + param.args[1]
                                    + ") -> " + (r == null ? "null" : r.getClass().getSimpleName()));
                        }
                    });
            log("probe hooked d3.a(Context,int,String,c3.a)");
        } catch (Throwable t) {
            log("probe factory failed: " + t);
        }
    }

    /** z2（通用 MediaSession 控制器）内部流程。 */
    private void probeZ2(ClassLoader cl) {
        Class<?> z2 = XposedHelpers.findClassIfExists(CLS_CTRL_Z2, cl);
        if (z2 == null) {
            log("probe z2 absent");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(z2, "x", Message.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Message m = (Message) param.args[0];
                    log("PROBE z2.x(what=" + (m == null ? "null" : String.valueOf(m.what)) + ")");
                }
            });
            log("probe hooked z2.x(Message)");
        } catch (Throwable t) {
            log("probe z2.x failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(z2, "a0", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object ctrl = XposedHelpers.getObjectField(param.thisObject, "D");
                    Object md = ctrl == null ? null : XposedHelpers.callMethod(ctrl, "getMetadata");
                    log("PROBE z2.a0() controller=" + (ctrl == null ? "null" : "ok")
                            + " metadata=" + (md == null ? "null" : describeMetadata((MediaMetadata) md)));
                }
            });
            log("probe hooked z2.a0()");
        } catch (Throwable t) {
            log("probe z2.a0 failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(z2, "Z", MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("PROBE z2.Z() metadata="
                            + describeMetadata((MediaMetadata) param.args[0]));
                }
            });
            log("probe hooked z2.Z(MediaMetadata)");
        } catch (Throwable t) {
            log("probe z2.Z failed: " + t);
        }
    }

    /** 服务侧：切源方法、准入判定 n0、派发任务 j.run。 */
    private void probeService(ClassLoader cl) {
        Class<?> svc = XposedHelpers.findClassIfExists(CLS_SERVICE, cl);
        if (svc != null) {
            try {
                XposedHelpers.findAndHookMethod(svc, "k1", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("PROBE service.k1(pkg=" + param.args[0] + ")");
                    }
                });
                log("probe hooked service.k1(String)");
            } catch (Throwable t) {
                log("probe service.k1 failed: " + t);
            }
            try {
                XposedHelpers.findAndHookMethod(svc, "n0", String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        log("PROBE service.n0(pkg=" + param.args[0] + ") -> " + param.getResult());
                    }
                });
                log("probe hooked service.n0(String)");
            } catch (Throwable t) {
                log("probe service.n0 failed: " + t);
            }
        }
        Class<?> task = XposedHelpers.findClassIfExists(CLS_SERVICE_TASK, cl);
        if (task != null) {
            try {
                XposedHelpers.findAndHookMethod(task, "run", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        log("PROBE service$j.run()");
                    }
                });
                log("probe hooked service$j.run()");
            } catch (Throwable t) {
                log("probe service$j.run failed: " + t);
            }
        }
    }

    /* ==================== 工具 ==================== */

    /** 在内部类里按简单名找嵌套类型（c3.a 之类）。 */
    private static Class<?> findNested(Class<?> outer, String simpleName) {
        Class<?>[] inner = outer.getDeclaredClasses();
        if (inner != null) {
            for (Class<?> c : inner) {
                if (simpleName.equals(c.getSimpleName())) {
                    return c;
                }
            }
        }
        return null;
    }

    private static boolean isInteresting(String pkg) {
        return pkg != null && pkg.contains("piliplus");
    }

    /** 反射读取 MediaController 的 tag / 包名。 */
    private static String describeController(Object mc) {
        try {
            Object tag = XposedHelpers.callMethod(mc, "getTag");
            Object pkg = XposedHelpers.callMethod(mc, "getPackageName");
            return "controller{pkg=" + pkg + ", tag=" + tag + "}";
        } catch (Throwable t) {
            return "controller{?}";
        }
    }

    /** 摘出卡片需要的几个关键 metadata 字段。 */
    private static String describeMetadata(MediaMetadata md) {
        if (md == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("title=").append(str(md, MediaMetadata.METADATA_KEY_TITLE));
        sb.append(", artist=").append(str(md, MediaMetadata.METADATA_KEY_ARTIST));
        sb.append(", album=").append(str(md, MediaMetadata.METADATA_KEY_ALBUM));
        sb.append(", albumArtUri=").append(str(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
        sb.append(", displayIconUri=").append(str(md, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        sb.append(", duration=").append(md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        try {
            Object art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            Object icon = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            sb.append(", albumArt=").append(art == null ? "null" : "bitmap");
            sb.append(", displayIcon=").append(icon == null ? "null" : "bitmap");
            Object desc = md.getDescription();
            if (desc != null) {
                Object ib = XposedHelpers.callMethod(desc, "getIconBitmap");
                Object iu = XposedHelpers.callMethod(desc, "getIconUri");
                sb.append(", descIcon=").append(ib == null ? "null" : "bitmap");
                sb.append(", descIconUri=").append(iu);
            } else {
                sb.append(", description=null");
            }
        } catch (Throwable t) {
            sb.append(", bitmapErr=").append(t);
        }
        return sb.toString();
    }

    private static String str(MediaMetadata md, String key) {
        try {
            String v = md.getString(key);
            return v == null ? "null" : v;
        } catch (Throwable t) {
            return "err";
        }
    }

    /** 完整栈（最多 14 帧），用于定位真正的调用方。 */
    private static String fullStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.contains("Xposed") || cn.contains("IslandFix") || cn.contains("MethodHook")
                        || cn.startsWith("java.lang")) {
                    continue;
                }
                if (n > 0) {
                    sb.append(" < ");
                }
                sb.append(shortName(cn)).append('.').append(e.getMethodName());
                if (++n >= 14) {
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String shortName(String cn) {
        int dot = cn.lastIndexOf('.');
        return dot > 0 ? cn.substring(dot + 1) : cn;
    }

    private static String arg1(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null || args.length < 2) {
            return null;
        }
        Object v = args[1];
        return v instanceof String ? (String) v : null;
    }

    private static boolean isAllowed(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        if (ALLOW_ALL) {
            return true;
        }
        for (String t : TARGET_PKGS) {
            if (t.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    /**
     * 只对显式关注的目标包打日志。
     * ALLOW_ALL 打开时 d.Z/d.W/d.e 会被系统进程频繁命中，全量打印只会刷屏，
     * 所以这里按 TARGET_PKGS 过滤，保留可验证的关键证据。
     */
    private static void logFix(String pkg, String msg) {
        if (isTarget(pkg)) {
            log(msg);
        }
    }

    private static boolean isTarget(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String t : TARGET_PKGS) {
            if (t.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
