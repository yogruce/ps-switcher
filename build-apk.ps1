$ErrorActionPreference = "Stop"

$SdkDir = "$env:LOCALAPPDATA\Android\Sdk"
$BuildTools = "$SdkDir\build-tools\34.0.0"
$PlatformJar = "$SdkDir\platforms\android-34\android.jar"

$ProjectDir = "C:\Users\yogru\.gemini\antigravity\scratch\ps-switcher"
$MainDir = "$ProjectDir\app\src\main"
$BuildDir = "$ProjectDir\build"

if (Test-Path $BuildDir) {
    Remove-Item -Recurse -Force $BuildDir
}
New-Item -ItemType Directory -Force -Path "$BuildDir\compiled_res", "$BuildDir\gen", "$BuildDir\classes", "$BuildDir\dex" | Out-Null

Write-Host "1. Compiling resources with aapt2..."
& "$BuildTools\aapt2.exe" compile --dir "$MainDir\res" -o "$BuildDir\compiled_res\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "2. Linking resources and AndroidManifest.xml..."
& "$BuildTools\aapt2.exe" link `
    -I $PlatformJar `
    --manifest "$MainDir\AndroidManifest.xml" `
    --java "$BuildDir\gen" `
    -o "$BuildDir\unsigned.apk" `
    "$BuildDir\compiled_res\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "3. Compiling Java sources with javac..."
$JavaFiles = Get-ChildItem -Path "$MainDir\java", "$BuildDir\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac --release 8 -encoding UTF-8 -classpath $PlatformJar -d "$BuildDir\classes" $JavaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "4. Converting class files to DEX with d8..."
$ClassFiles = Get-ChildItem -Path "$BuildDir\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& "$BuildTools\d8.bat" --lib $PlatformJar --min-api 21 --output "$BuildDir\dex" $ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "5. Packaging classes.dex into APK..."
Push-Location "$BuildDir\dex"
& jar -uf "$BuildDir\unsigned.apk" "classes.dex"
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar uf failed" }

Write-Host "6. Aligning APK with zipalign..."
& "$BuildTools\zipalign.exe" -f 4 "$BuildDir\unsigned.apk" "$BuildDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "7. Generating keystore and signing APK with apksigner..."
$KeystorePath = "$BuildDir\release-key.jks"
& keytool -genkeypair -v `
    -keystore $KeystorePath `
    -alias ps_switcher `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -storepass "android123" `
    -keypass "android123" `
    -dname "CN=PlayStation Switcher, OU=TV, O=PSSwitcher, L=Istanbul, S=Istanbul, C=TR"
if ($LASTEXITCODE -ne 0) { throw "keytool failed" }

$FinalApk = "$ProjectDir\PlayStation-TV-Switcher.apk"
& "$BuildTools\apksigner.bat" sign `
    --ks $KeystorePath `
    --ks-key-alias ps_switcher `
    --ks-pass pass:android123 `
    --key-pass pass:android123 `
    --out $FinalApk `
    "$BuildDir\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& "$BuildTools\apksigner.bat" verify --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "SUCCESS! Signed APK generated at: $FinalApk"
