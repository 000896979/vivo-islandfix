# IslandFix — vivo 原子岛 MediaSession 解锁模块

一个 LSPosed / Xposed 模块，解除 vivo 原子岛（SmartIsland）对标准 Android `MediaSession` 的白名单限制，使符合原生规范的第三方应用也能驱动原子岛显示封面、标题、进度条。

## 背景

vivo OriginOS 的原子岛音乐卡片（`com.vivo.musicwidgetmix`）对第三方应用有白名单限制：只有内置酷狗、QQ音乐、网易云音乐等厂商预置应用才能驱动。第三方应用即使注册了标准 `MediaSession`，也会被四道闸门拦截。

本模块通过运行时 Hook 解除全部四道闸门，**不修改任何 APK、不刷写分区、不触碰 Bootloader**。

## 闸门与 Hook

| 闸门 | 位置 | 原行为 | 模块处置 |
|:---:|---|---|---|
| 1 | `s4.m0` 的 `e()/d()/b()/c()` | 返回写死的厂商白名单 | 合并注入目标包 |
| 2 | `utils.d.Z/W/Y(Context,String)` | 白名单/黑名单判定 | 目标包强制 Z/W=true、Y=false |
| 3 | `utils.d.e(Context,String)` + `MusicWidgetMixService.n0(String)` | `e()` 写死包名表，第三方一律 false -> `n0` 返回 true -> **跳过控制器创建** | `e()` 强制 true；`n0()` 强制 false |

> **闸门 3 是"有岛无封面/进度条"的根因**。1 和 2 只让岛出现、能控制播放暂停，但控制器从未创建，`MediaMetadata` 永远读不到。

## 数据链

```
vivo framework AudioFeatures TrackState
  -> MainApplication$m.onCallback()
    -> utils.d.Z()               gate 2
      -> MainApplication.g1()
        -> MusicWidgetMixService.k1()
          -> $j.run()
            -> n0()              gate 3
              -> d3.a()          controller factory
                -> new z2()       generic MediaSession controller
                  -> a() -> u.c() get MediaController
                    -> a0() -> read MediaMetadata
                      -> x(259) -> utils.p.s()  cover/title/progress
```

## 适用环境

- vivo OriginOS 16.0 (Android 16 / SDK 36), build `PD2352B_A_16.2.17.0.W10`
- Root: KernelSU
- Hook framework: LSPosed (Zygisk mode)

> After a system update, if vivo re-obfuscates class names, set `PROBE=true` and rebuild to reproduce the full chain tracing logs.

## Build

```powershell
# Requires: JDK 21, Android build-tools 34, android.jar 34
# Edit paths in build.ps1 to point to your SDK
& .\app\build.ps1 -OutName islandfix.apk
```

The Xposed API uses local compile stubs (`stub/`), no network dependency.

## Install & Activate

```powershell
adb install -r islandfix.apk
# In LSPosed: enable module, select scope:
#   com.vivo.musicwidgetmix
#   com.vivo.systemuiplugin
# Soft reboot to activate
adb shell "su -c 'ksud soft-reboot'"
```

## Configuration

```java
// IslandFix.java
private static final String[] TARGET_PKGS = { "com.example.piliplus" };
private static final boolean ALLOW_ALL = true;   // true = lift all whitelists
private static final boolean PROBE     = false;  // diagnostic probes
```

Set `ALLOW_ALL=true` to allow any package. To allow only specific apps, set `false` and maintain `TARGET_PKGS`.

## Verification

```
adb shell dumpsys media_session | grep -E "metadata:|state=PlaybackState"
adb shell logcat -d | grep IslandFix
# Expected:
#   controller z2 created for <pkg>
#   metadata delivered for <pkg> -> title=..., cover=bitmap
```

## Rollback

```powershell
# Disable in LSPosed, or:
adb shell "su -c 'sqlite3 /data/adb/lspd/config/modules_config.db \"UPDATE modules_state SET enabled=0 WHERE module_pkg_name=''com.islandfix.module'';\"'"
adb shell "su -c 'ksud soft-reboot'"
# Or uninstall:
adb uninstall com.islandfix.module
```

## License

GPL-3.0-or-later

## Disclaimer

For personal device research and customization only. Does not modify system partitions or flash firmware. Class names may change after system updates; re-trace the data chain if needed.
