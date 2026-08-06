[CmdletBinding()]
param(
    [string]$Repository = "jiangyuyi/lightnovel-android",
    [string]$KeyAlias = "lightnovel-release"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $repoRoot ".signing"
$keystorePath = Join-Path $signingDirectory "lightnovel-release.jks"
$propertiesPath = Join-Path $repoRoot "signing.properties"
$distinguishedName = "CN=jiangyuyi, OU=LightNovel Android, O=jiangyuyi, C=CN"

function ConvertFrom-SecureValue([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-ConfirmedPassword {
    while ($true) {
        $first = ConvertFrom-SecureValue (Read-Host "Enter the Release signing password (at least 12 characters)" -AsSecureString)
        $second = ConvertFrom-SecureValue (Read-Host "Enter the signing password again" -AsSecureString)
        if ($first.Length -lt 12) {
            Write-Warning "The password must contain at least 12 characters."
        }
        elseif ($first -cne $second) {
            Write-Warning "The passwords do not match. Please try again."
        }
        else {
            return $first
        }
        $first = $null
        $second = $null
    }
}

if (Test-Path $keystorePath) {
    throw "The signing keystore already exists at $keystorePath. It will not be overwritten."
}

$keytool = (Get-Command keytool -ErrorAction Stop).Source
$gh = (Get-Command gh -ErrorAction Stop).Source
& $gh auth status | Out-Null
New-Item -ItemType Directory -Force $signingDirectory | Out-Null

$password = Read-ConfirmedPassword
try {
    & $keytool -genkeypair -v `
        -keystore $keystorePath `
        -storetype JKS `
        -storepass $password `
        -keypass $password `
        -alias $KeyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname $distinguishedName
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed to create the signing key (exit code $LASTEXITCODE)."
    }

    $properties = @(
        "storeFile=.signing/lightnovel-release.jks"
        "storePassword=$password"
        "keyAlias=$KeyAlias"
        "keyPassword=$password"
        ""
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))

    $keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
    $keystoreBase64 | & $gh secret set ANDROID_KEYSTORE_BASE64 --repo $Repository
    $password | & $gh secret set ANDROID_KEYSTORE_PASSWORD --repo $Repository
    $KeyAlias | & $gh secret set ANDROID_KEY_ALIAS --repo $Repository
    $password | & $gh secret set ANDROID_KEY_PASSWORD --repo $Repository
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to write the GitHub Actions signing secrets."
    }

    Write-Host "Release signing is configured and GitHub Actions secrets were updated." -ForegroundColor Green
    Write-Host "Back up these files offline: $keystorePath and $propertiesPath"
    Write-Host "Both files are excluded by .gitignore and will not be committed."
}
finally {
    $password = $null
    $keystoreBase64 = $null
}
