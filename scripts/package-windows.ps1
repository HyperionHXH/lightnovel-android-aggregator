param(
    [string]$OutputDir = "desktopApp/build/windows/Mixn"
)

$ErrorActionPreference = "Stop"

& "$PSScriptRoot/../gradlew.bat" ":desktopApp:installDist"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$installDir = Join-Path $PSScriptRoot "../desktopApp/build/install/Mixn"
$resolvedInstallDir = (Resolve-Path $installDir).Path
$resolvedOutputDir = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDir))
New-Item -ItemType Directory -Force -Path $resolvedOutputDir | Out-Null
$appImagePath = Join-Path $resolvedOutputDir "Mixn"
if (Test-Path -LiteralPath $appImagePath) {
    Remove-Item -LiteralPath $appImagePath -Recurse -Force
}

$mainJar = Get-ChildItem (Join-Path $resolvedInstallDir "lib") -Filter "Mixn-*.jar" |
    Where-Object { $_.Name -notmatch "kotlin|annotations" } |
    Select-Object -First 1 -ExpandProperty Name
if ([string]::IsNullOrWhiteSpace($mainJar)) {
    throw "Mixn main jar was not found"
}

$jpackageCommand = Get-Command jpackage -ErrorAction SilentlyContinue | Select-Object -First 1
$jpackage = $jpackageCommand.Path
if ([string]::IsNullOrWhiteSpace($jpackage)) { $jpackage = $jpackageCommand.Source }
if ([string]::IsNullOrWhiteSpace($jpackage) -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin/jpackage.exe"
    if (Test-Path -LiteralPath $candidate) { $jpackage = $candidate }
}
if ([string]::IsNullOrWhiteSpace($jpackage)) {
    $jdkRoots = @(
        (Join-Path ${env:ProgramFiles} "Java"),
        (Join-Path ${env:ProgramFiles} "Eclipse Adoptium")
    ) | Where-Object { Test-Path -LiteralPath $_ }
    $jpackage = Get-ChildItem -Path $jdkRoots -Filter "jpackage.exe" -Recurse -File -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}

if ([string]::IsNullOrWhiteSpace($jpackage)) {
    Write-Warning "jpackage was not found; install JDK 17+ to create an app-image"
    Write-Host "Runnable distribution: $resolvedInstallDir"
    exit 0
}

$jpackageArgs = @(
    "--type", "app-image",
    "--name", "Mixn",
    "--app-version", $(if ([string]::IsNullOrWhiteSpace($env:APP_VERSION_NAME)) { "1.4.2" } else { $env:APP_VERSION_NAME }),
    "--input", (Join-Path $resolvedInstallDir "lib"),
    "--main-jar", $mainJar,
    "--main-class", "io.github.jiangyuyi.lightnovel.desktop.MainKt",
    "--dest", $resolvedOutputDir,
    "--vendor", "Mixn Community",
    "--description", "Mixn desktop reader"
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "App-image created: $(Join-Path $resolvedOutputDir 'Mixn')"
