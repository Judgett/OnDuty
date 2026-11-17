$ErrorActionPreference = "Stop"

$Root = $PSScriptRoot
$SDK  = "D:\Tools\AndroidSDK"
$BT   = Join-Path $SDK "build-tools\36.0.0"
$AJAR = Join-Path $SDK "platforms\android-34\android.jar"
$JDK  = "D:\Tools\Java\jdk-21.0.12.1"
$OUT  = Join-Path $Root "build"
$KS   = Join-Path $Root "keystore\shiftnotify.jks"

$env:JAVA_HOME = $JDK

function Invoke-Checked {
    param([string]$Exe, [string[]]$Arguments, [string]$Label)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

Write-Output "== 0. prepare dirs"
if (Test-Path -LiteralPath $OUT) { Remove-Item -LiteralPath $OUT -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OUT, "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

Write-Output "== 1. aapt2 compile resources"
Invoke-Checked "$BT\aapt2.exe" @("compile", "--dir", "$Root\res", "-o", "$OUT\res.zip") "aapt2 compile"

Write-Output "== 2. aapt2 link resources"
Invoke-Checked "$BT\aapt2.exe" @(
    "link", "-o", "$OUT\base.apk",
    "-I", $AJAR,
    "--manifest", "$Root\AndroidManifest.xml",
    "-R", "$OUT\res.zip",
    "--java", "$OUT\gen",
    "--min-sdk-version", "33",
    "--target-sdk-version", "34",
    "--version-code", "4",
    "--version-name", "2.1.0",
    "--auto-add-overlay"
) "aapt2 link"

Write-Output "== 3. javac"
$sources = @(Get-ChildItem -LiteralPath "$Root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -LiteralPath "$OUT\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($sources.Count -lt 5) { throw "source files missing" }
$javacArgs = @(
    "-encoding", "UTF-8", "-source", "8", "-target", "8",
    "-bootclasspath", $AJAR,
    "-classpath", $AJAR,
    "-d", "$OUT\classes"
)
$javacArgs += $sources
Invoke-Checked "$JDK\bin\javac.exe" $javacArgs "javac"

Write-Output "== 4. d8 dex (via classes.jar)"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath = "$OUT\classes.jar"
if (Test-Path -LiteralPath $jarPath) { Remove-Item -LiteralPath $jarPath -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory("$OUT\classes", $jarPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Invoke-Checked "$BT\d8.bat" @("--release", "--min-api", "33", "--lib", $AJAR, "--output", "$OUT\dex", $jarPath) "d8"

if (-not (Test-Path -LiteralPath "$OUT\dex\classes.dex")) { throw "classes.dex not generated" }

Write-Output "== 5. pack classes.dex into apk"
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\base.apk", "Update")
try {
    $existing = $zip.GetEntry("classes.dex")
    if ($existing -ne $null) { $existing.Delete() }
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    try {
        $bytes = [System.IO.File]::ReadAllBytes("$OUT\dex\classes.dex")
        $es.Write($bytes, 0, $bytes.Length)
    } finally { $es.Close() }
} finally { $zip.Dispose() }

Write-Output "== 6. zipalign"
Invoke-Checked "$BT\zipalign.exe" @("-f", "-p", "4", "$OUT\base.apk", "$OUT\aligned.apk") "zipalign"

Write-Output "== 7. sign"
# 签名口令不入库：优先读环境变量 ONDUTY_KS_PASS，未设置时回退到本地默认值。
# 公开仓库使用者请自备 keystore，并通过环境变量传入自己的口令。
$ksPass = $env:ONDUTY_KS_PASS
if ([string]::IsNullOrEmpty($ksPass)) { $ksPass = "shiftnotify2026" }
Invoke-Checked "$BT\apksigner.bat" @(
    "sign",
    "--ks", $KS,
    "--ks-pass", "pass:$ksPass",
    "--key-pass", "pass:$ksPass",
    "--min-sdk-version", "33",
    "--out", "$OUT\app-signed.apk",
    "$OUT\aligned.apk"
) "apksigner"

Write-Output "== 8. verify"
Invoke-Checked "$BT\apksigner.bat" @("verify", "--print-certs", "$OUT\app-signed.apk") "apksigner verify"

$final = Join-Path (Split-Path -Parent $Root) "下班装死.apk"
Copy-Item -LiteralPath "$OUT\app-signed.apk" -Destination $final -Force
$size = [math]::Round((Get-Item -LiteralPath $final).Length / 1KB, 1)
Write-Output "== DONE: $final ($size KB)"
