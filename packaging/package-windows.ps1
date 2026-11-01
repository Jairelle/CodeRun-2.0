# Builds the Windows installer of CodeRun: dist\CodeRun-<version>.exe (launcher + game + its own Java).
# Needs on the build PC (players need nothing):  JDK 21+  and  WiX Toolset 3.14 (jpackage uses it for .exe).
#   Run from the project folder:   powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$version = if ($env:CODERUN_VERSION) { $env:CODERUN_VERSION } else { "2.0.0" }

# ---- JDK with jpackage
$jdk = $env:JAVA_HOME
if (-not $jdk -or -not (Test-Path "$jdk\bin\jpackage.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks", "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
           Where-Object { Test-Path "$($_.FullName)\bin\jpackage.exe" } | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $jdk) { throw "No JDK 21+ with jpackage found. Install Temurin 21 or set JAVA_HOME." }
$env:JAVA_HOME = $jdk; $env:Path = "$jdk\bin;$env:Path"
Write-Host "Using JDK: $jdk"

# ---- WiX 3 (candle.exe / light.exe) on PATH
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = Get-ChildItem "C:\Program Files (x86)" -Directory -Filter "WiX Toolset v3*" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($wix) { $env:Path = "$($wix.FullName)\bin;$env:Path" } else { throw "WiX Toolset 3.14 not found (https://github.com/wixtoolset/wix3/releases)." }
}

# ---- config: from CI secrets (environment) or packaging\config
New-Item -ItemType Directory -Force packaging\config | Out-Null
if ($env:CODERUN_DB_HOST) {
    @"
db.host=$env:CODERUN_DB_HOST
db.port=$env:CODERUN_DB_PORT
db.name=$env:CODERUN_DB_NAME
db.user=$env:CODERUN_DB_USER
db.password=$env:CODERUN_DB_PASSWORD
"@ | Set-Content -Encoding ASCII packaging\config\db.properties
}
if ($env:CODERUN_MAIL_USER) {
    @"
mail.user=$env:CODERUN_MAIL_USER
mail.password=$env:CODERUN_MAIL_PASSWORD
"@ | Set-Content -Encoding ASCII packaging\config\mail.properties
}
if (-not (Test-Path packaging\config\db.properties)) { throw "Missing packaging\config\db.properties (the limited player login)." }
if (-not (Test-Path game_client\windows\CodeRun.exe)) { throw "Missing game_client\windows\CodeRun.exe (copy the Unity Windows build there)." }

# ---- compile the launcher and collect its libraries
& .\mvnw.cmd -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
$jar = Get-ChildItem target\CodeRunLauncher-*.jar | Where-Object { $_.Name -notlike "*sources*" } | Select-Object -First 1

Remove-Item -Recurse -Force dist\input, dist\*.exe -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist\input\config, dist\input\game_client | Out-Null
Copy-Item $jar.FullName dist\input\
Copy-Item target\libs\*.jar dist\input\
Copy-Item packaging\config\*.properties dist\input\config\
Copy-Item -Recurse game_client\windows dist\input\game_client\windows
Get-ChildItem dist\input\game_client\windows -Directory -Filter "*_BackUpThisFolder_ButDontShipItWithYourGame" | Remove-Item -Recurse -Force

# ---- installer: per-user (no admin prompt), Start menu + desktop shortcut, choose folder
& jpackage --type exe `
  --name "CodeRun 2.0" `
  --app-version $version `
  --vendor "CodeRun" `
  --description "CodeRun 2.0 launcher and game" `
  --input dist\input `
  --main-jar $jar.Name `
  --main-class com.example.coderunlauncher.Main `
  --icon packaging\icon.ico `
  --java-options "-Dfile.encoding=UTF-8" `
  --win-per-user-install `
  --win-dir-chooser `
  --win-shortcut `
  --win-menu `
  --win-menu-group "CodeRun 2.0" `
  --win-upgrade-uuid "6c1f2b8e-3d4a-4e57-9a1c-2f6e8b0d4c21" `
  --dest dist
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Remove-Item -Recurse -Force dist\input
# a download name without spaces
Get-ChildItem dist\*.exe | ForEach-Object { Move-Item -Force $_.FullName "dist\CodeRun-2.0-Setup.exe" }
Get-ChildItem dist\*.exe | ForEach-Object { Write-Host ("Done: {0}  ({1:N0} MB)" -f $_.FullName, ($_.Length / 1MB)) }
