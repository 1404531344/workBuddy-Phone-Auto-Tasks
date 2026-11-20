# AutoTask APK build script (no Gradle, no Android Studio needed)
# Requires: JDK 17, Android SDK build-tools 34.0.0, platforms/android-34/android.jar
#
# Usage:
#   $env:JAVA_HOME = 'C:\Path\To\jdk-17'
#   $env:ANDROID_HOME = 'C:\Path\To\Android\Sdk'
#   powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
#
# Output: .\AutoTask.apk  (signed with .\signkey.p12, created on first run)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$Root  = $PSScriptRoot
$App   = Join-Path $Root 'app'
$Build = Join-Path $Root 'build'
$Log   = Join-Path $Root 'build.log'

# --- SDK / JDK discovery -----------------------------------------------------
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = Join-Path $Root 'sdk' }        # portable sdk/ folder also works
$BT  = Join-Path $Sdk 'build-tools\34.0.0'
$Jar = Join-Path $Sdk 'platforms\android-34\android.jar'

if (-not $env:JAVA_HOME) {
  $cand = @('C:\Program Files\Java\jdk-17', 'C:\Program Files\Eclipse Adoptium\jdk-17*')
  foreach ($c in $cand) {
    $p = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($p) { $env:JAVA_HOME = $p.FullName; break }
  }
}
$javac   = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

function L($m) { Add-Content -LiteralPath $Log -Value ("[" + (Get-Date -f HH:mm:ss) + "] " + $m) -Encoding utf8 }
function Die($m) { L ("FATAL " + $m); throw $m }

Set-Content -LiteralPath $Log -Value '' -Encoding utf8
L 'build start'

if (-not (Test-Path $Jar))       { Die "android.jar missing: $Jar" }
if (-not (Test-Path (Join-Path $BT 'aapt2.exe'))) { Die "aapt2 missing under $BT" }
if (-not (Test-Path $javac))     { Die "javac missing: $javac (set JAVA_HOME to a JDK 17)" }

if (Test-Path $Build) { Remove-Item $Build -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$Build\classes", "$Build\gen", "$Build\dex" | Out-Null

# ---- 1. compile resources ---------------------------------------------------
& "$BT\aapt2.exe" compile --dir "$App\res" -o "$Build\res.zip" 2>&1 | ForEach-Object { L $_ }
if ($LASTEXITCODE -ne 0) { Die "aapt2 compile failed" }
L 'aapt2 compile ok'

# ---- 2. link ----------------------------------------------------------------
& "$BT\aapt2.exe" link -o "$Build\base.apk" -I $Jar --manifest "$App\AndroidManifest.xml" `
    -R "$Build\res.zip" --java "$Build\gen" --auto-add-overlay `
    --min-sdk-version 24 --target-sdk-version 34 2>&1 | ForEach-Object { L $_ }
if ($LASTEXITCODE -ne 0) { Die "aapt2 link failed" }
L ('aapt2 link ok  base.apk ' + (Get-Item "$Build\base.apk").Length + ' B')

# ---- 3. javac ---------------------------------------------------------------
$src   = Get-ChildItem "$App\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rjava = Get-ChildItem "$Build\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
$all   = @($src) + @($rjava)
L ("java sources: " + $all.Count)

$argFile    = "$Build\javac.args"
$classesDir = Join-Path $Build 'classes'
$lines = @('--release','8','-encoding','UTF-8','-nowarn','-classpath',$Jar,'-d',$classesDir) + $all
[IO.File]::WriteAllLines($argFile, [string[]]$lines, [Text.Encoding]::ASCII)

& cmd /c "`"$javac`" @`"$argFile`" > `"$Build\javac.out`" 2>&1"
$jc = $LASTEXITCODE
if (Test-Path "$Build\javac.out") { Get-Content "$Build\javac.out" -Encoding Default | ForEach-Object { L $_ } }
if ($jc -ne 0) { Die ("javac failed (exit " + $jc + ")") }
L 'javac ok'

# ---- 4. d8 ------------------------------------------------------------------
$classes = Get-ChildItem "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
L ("classes: " + $classes.Count)
# 类一多，命令行就超 cmd 的 8191 上限，d8 会静默失败（只留一句「命令语法不正确」）。
# 改成写 @argfile 再直接调 java，绕过 cmd 的长度限制。
$d8args = "$Build\d8.args"
$lines8 = @('--release','--lib',$Jar,'--min-api','24','--output',"$Build\dex") + $classes
[IO.File]::WriteAllLines($d8args, [string[]]$lines8, [Text.Encoding]::ASCII)
New-Item -ItemType Directory -Force -Path "$Build\dex" | Out-Null
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $javaExe -cp "$BT\lib\d8.jar" com.android.tools.r8.D8 "@$d8args" 2>&1 | ForEach-Object { L $_ }
if (-not (Test-Path "$Build\dex\classes.dex")) { Die "d8 produced no classes.dex" }
L ('d8 ok  classes.dex ' + (Get-Item "$Build\dex\classes.dex").Length + ' B')

# ---- 5. put dex into apk ----------------------------------------------------
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$Build\base.apk" "$Build\app.unsigned.apk" -Force
$zip = [IO.Compression.ZipFile]::Open("$Build\app.unsigned.apk", 'Update')
$entry = $zip.GetEntry('classes.dex')
if ($entry) { $entry.Delete() }
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$Build\dex\classes.dex", 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()
L ('apk with dex ' + (Get-Item "$Build\app.unsigned.apk").Length + ' B')

# ---- 6. zipalign ------------------------------------------------------------
& "$BT\zipalign.exe" -f 4 "$Build\app.unsigned.apk" "$Build\app.aligned.apk" 2>&1 | ForEach-Object { L $_ }
if ($LASTEXITCODE -ne 0) { Die "zipalign failed" }
L 'zipalign ok'

# ---- 7. signing key (PKCS12, created on first run; keep it out of git) ------
$ks = Join-Path $Root 'signkey.p12'
if (-not (Test-Path $ks)) {
  & $keytool -genkeypair -v -storetype PKCS12 -keystore $ks -storepass android -keypass android `
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
      -dname "CN=AutoTask Debug,O=FZ,C=CN" 2>&1 | ForEach-Object { L $_ }
  if (-not (Test-Path $ks)) { Die "keytool failed" }
  L 'signing key created'
}

# ---- 8. sign ----------------------------------------------------------------
$out = Join-Path $Root 'AutoTask.apk'
if (Test-Path $out) { Remove-Item $out -Force }
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --v1-signing-enabled true --v2-signing-enabled true --out $out "$Build\app.aligned.apk" 2>&1 | ForEach-Object { L $_ }
if ($LASTEXITCODE -ne 0) { Die "apksigner failed" }

& "$BT\apksigner.bat" verify --print-certs $out 2>&1 | ForEach-Object { L $_ }
L ('SIGNED ' + $out + '  ' + (Get-Item $out).Length + ' B')
L ('SHA256 ' + (Get-FileHash $out -Algorithm SHA256).Hash)
L 'build done'
