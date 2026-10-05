# 原子岛媒体会话解锁 — LSPosed 模块构建脚本
#
# 用法： pwsh -File build.ps1
# 产物： out\islandfix.apk
#
# 工具链路径请按你本机环境修改下面的变量：
#   JDK 21 / Android build-tools 34 / android.jar 34 / 你自己的签名 keystore
#
# Xposed API 采用本地 stub 目录 stub/（compileOnly 语义）：
#   只参与 javac，不进入 dex。运行时由 LSPosed 注入真实实现。

[CmdletBinding()]
param(
  [string]$OutName  = 'islandfix.apk',
  [string]$Keystore = '<YOUR_KEYSTORE_PATH>',
  [string]$KsAlias  = '<YOUR_KEY_ALIAS>',
  [string]$KsPass   = '<YOUR_KEYSTORE_PASSWORD>'
)

$ErrorActionPreference = 'Stop'

$root = 'D:\islandfix\module'
# --- paths (edit these to match your environment) ---
$jdk  = '<YOUR_JDK_21_PATH>'           # e.g. C:\jdk-21
$sdk  = '<YOUR_ANDROID_SDK_PATH>'     # e.g. C:\android-sdk
$bt   = "$sdk\build-tools\34.0.0"
$aj   = "$sdk\platforms\android-34\android.jar"

$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$build  = "$root\build"
$apkDir = "$build\apk"
$dexDir = "$build\dex"
$clsDir = "$build\classes"
$stubD  = "$build\stub-classes"
$resDir = "$build\res-compiled"
$outDir = "$root\out"

# ── 原生工具运行器 ────────────────────────────────────────────
# 统一走 Start-Process，参数数组传入、
# stdout/stderr 各自重定向，只按 ExitCode 判定，免受 PowerShell
# 错误流语义与 cmd 剥引号规则影响。
function Invoke-Native {
  param(
    [Parameter(Mandatory=$true)][string]$Exe,
    [Parameter(Mandatory=$true)][string[]]$ArgList,
    [string]$Label = 'run',
    [switch]$Quiet
  )
  $out = Join-Path $build "$Label.out.txt"
  $err = Join-Path $build "$Label.err.txt"
  $p = Start-Process -FilePath $Exe -ArgumentList $ArgList `
        -RedirectStandardOutput $out -RedirectStandardError $err `
        -NoNewWindow -Wait -PassThru
  $text = ''
  foreach ($f in @($out, $err)) {
    if (Test-Path $f) {
      $t = Get-Content $f -Raw -ErrorAction SilentlyContinue
      if ($t) { $text += $t }
    }
  }
  if (-not $Quiet -and $text) { Write-Host ($text.TrimEnd()) }
  return @{ rc = $p.ExitCode; text = $text }
}

function Step($m) { Write-Host "== $m" -ForegroundColor Cyan }
function Die($m)  { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ── 清理 ──────────────────────────────────────────────────────
Step 'clean'
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
foreach ($d in @($apkDir, $dexDir, $clsDir, $stubD, $resDir, $outDir)) {
  New-Item -ItemType Directory -Force $d | Out-Null
}

# ── ① 生成启动图标 ────────────────────────────────────────────
Step 'generate launcher icon'
$iconDir = "$root\res\mipmap-hdpi"
New-Item -ItemType Directory -Force $iconDir | Out-Null
Add-Type -AssemblyName System.Drawing
$sz = 144
$bmp = New-Object System.Drawing.Bitmap($sz, $sz)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.TextRenderingHint = 'AntiAliasGridFit'
$r = 34
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.AddArc(0, 0, $r, $r, 180, 90)
$path.AddArc($sz-$r, 0, $r, $r, 270, 90)
$path.AddArc($sz-$r, $sz-$r, $r, $r, 0, 90)
$path.AddArc(0, $sz-$r, $r, $r, 90, 90)
$path.CloseFigure()
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
      (New-Object System.Drawing.Point(0,0)), (New-Object System.Drawing.Point($sz,$sz)),
      [System.Drawing.Color]::FromArgb(255,18,30,52), [System.Drawing.Color]::FromArgb(255,10,12,16))
$g.FillPath($bg, $path)
# 中央胶囊（原子岛）
$cap = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,46,204,113))
$g.FillEllipse($cap, 30, 58, 84, 28)
$cap2 = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,232,234,237))
$g.FillEllipse($cap2, 44, 64, 16, 16)
$fnt = New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Bold)
$fg  = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255,120,180,255))
$g.DrawString('MS', $fnt, $fg, 56, 104)
$g.Dispose()
$bmp.Save("$iconDir\ic_launcher.png", [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose(); $fnt.Dispose(); $fg.Dispose(); $bg.Dispose(); $path.Dispose()
Write-Host "   icon -> $iconDir\ic_launcher.png"

# ── ② 编译 Xposed API stub（compileOnly） ──────────────────────
Step 'javac (xposed api stub)'
$stubSrc = Get-ChildItem "$root\stub" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$r = Invoke-Native "$jdk\bin\javac.exe" (@('-nowarn','-encoding','UTF-8',
        '-source','8','-target','8','-Xlint:-options','-parameters',
        '-bootclasspath',$aj,'-d',$stubD) + $stubSrc) -Label 'javac-stub' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'javac (stub) failed' }
Write-Host "   $((Get-ChildItem $stubD -Recurse -Filter *.class).Count) stub class file(s)"

# ── ③ 资源编译 ────────────────────────────────────────────────
Step 'aapt2 compile'
$r = Invoke-Native "$bt\aapt2.exe" @('compile','--dir',"$root\res",'-o',$resDir) -Label 'aapt2compile' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'aapt2 compile failed' }
$flats = Get-ChildItem $resDir -Recurse -Filter *.flat
if ($flats.Count -eq 0) { Die 'aapt2 compile produced no .flat files' }
Write-Host "   $($flats.Count) compiled resource file(s)"

# ── ④ 链接资源 + manifest -> 基础 APK ────────────────────────
Step 'aapt2 link'
$baseApk = "$build\base.apk"
$linkArgs = @('link','-o',$baseApk,'-I',$aj,
              '--manifest',"$root\AndroidManifest.xml",
              '-A',"$root\assets",
              '--min-sdk-version','26','--target-sdk-version','34',
              '--version-code','1','--version-name','1.0',
              '--java',"$build\gen")
$linkArgs += ($flats | ForEach-Object { $_.FullName })
$r = Invoke-Native "$bt\aapt2.exe" $linkArgs -Label 'aapt2link' -Quiet
if ($r.rc -ne 0 -or -not (Test-Path $baseApk)) { Write-Host $r.text; Die 'aapt2 link failed' }
Write-Host ("   base apk = {0:N0} bytes" -f (Get-Item $baseApk).Length)

# ── ⑤ 编译模块 ────────────────────────────────────────────────
# ★ -parameters 必须带：JDK21 默认写出的空名 MethodParameters 会让 d8 抛 NPE。
Step 'javac (module)'
# 注意：必须强制 [string[]]，否则只有一个源文件时 $srcs 退化为字符串，
# 后面的 += 会变成字符串拼接（IslandFix.java 与 R.java 粘成一个非法文件名）。
[string[]]$srcs = @(Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$genSrc = "$build\gen"
if (Test-Path $genSrc) {
  $srcs += @(Get-ChildItem $genSrc -Recurse -Filter *.java | ForEach-Object { $_.FullName })
}
$javacArgs = @('-nowarn','-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-parameters',
               '-bootclasspath',$aj,
               '-classpath',"$stubD",
               '-d',$clsDir) + $srcs
$r = Invoke-Native "$jdk\bin\javac.exe" $javacArgs -Label 'javac' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'javac (module) failed' }
Write-Host "   $((Get-ChildItem $clsDir -Recurse -Filter *.class).Count) class file(s)"

# ── ⑥ dex（仅模块类，stub 不进包） ────────────────────────────
Step 'd8 (dex)'
$d8Args = @('--min-api','26','--output',$dexDir) +
          (Get-ChildItem $clsDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$r = Invoke-Native "$bt\d8.bat" $d8Args -Label 'd8' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'd8 failed' }
if (-not (Test-Path "$dexDir\classes.dex")) { Die 'd8 produced no classes.dex' }
Write-Host ("   classes.dex = {0:N0} bytes" -f (Get-Item "$dexDir\classes.dex").Length)

# ── ⑦ 合成未签名 APK ──────────────────────────────────────────
Step 'package unsigned apk'
$unsigned = "$build\unsigned.apk"
Copy-Item $baseApk $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
  $dexEntry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
  $es = $dexEntry.Open()
  $fs = [System.IO.File]::OpenRead("$dexDir\classes.dex")
  $fs.CopyTo($es); $fs.Close(); $es.Close()
} finally { $zip.Dispose() }
Write-Host ("   unsigned = {0:N0} bytes" -f (Get-Item $unsigned).Length)

# ── ⑧ zipalign ────────────────────────────────────────────────
Step 'zipalign'
$aligned = "$build\aligned.apk"
$r = Invoke-Native "$bt\zipalign.exe" @('-f','-p','4',$unsigned,$aligned) -Label 'zipalign' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'zipalign failed' }

# ── ⑨ 签名 ────────────────────────────────────────────────────
Step 'apksigner sign'
$final = Join-Path $outDir $OutName
if (-not (Test-Path $Keystore)) { Die "签名库不存在：$Keystore" }
$r = Invoke-Native "$bt\apksigner.bat" @(
        'sign','--ks',$Keystore,'--ks-key-alias',$KsAlias,
        '--ks-pass',"pass:$KsPass",'--key-pass',"pass:$KsPass",
        '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true',
        '--out',$final,$aligned) -Label 'apksigner' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'apksigner failed' }

# ── ⑩ 校验 ────────────────────────────────────────────────────
Step 'verify'
$r = Invoke-Native "$bt\apksigner.bat" @('verify','--print-certs',$final) -Label 'verify' -Quiet
if ($r.rc -ne 0) { Write-Host $r.text; Die 'apksigner verify failed' }
Write-Host (($r.text -split "`n" | Select-Object -First 4) -join "`n")
$r = Invoke-Native "$bt\aapt2.exe" @('dump','badging',$final) -Label 'badging' -Quiet
Write-Host (($r.text -split "`n" | Select-Object -First 4) -join "`n")

Write-Host ''
Write-Host ("DONE -> {0}  ({1:N0} bytes)" -f $final, (Get-Item $final).Length) -ForegroundColor Green
