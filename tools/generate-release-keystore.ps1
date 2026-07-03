param()

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$keystorePath = Join-Path $projectRoot 'mockgps-release.jks'
$propertiesPath = Join-Path $projectRoot 'keystore.properties'

if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $propertiesPath)) {
    throw '簽名檔已存在，為避免破壞更新金鑰，本工具不會覆寫。'
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    $javaHomeLine = java -XshowSettings:properties -version 2>&1 |
        Select-String '^\s*java.home\s*=' |
        Select-Object -First 1
    if (-not $javaHomeLine) {
        throw '找不到 keytool，請先安裝 JDK 17 以上版本。'
    }
    $javaHome = ($javaHomeLine.Line -split '=', 2)[1].Trim()
    $keytoolPath = Join-Path $javaHome 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytoolPath)) {
        throw '找不到 keytool，請先安裝 JDK 17 以上版本。'
    }
} else {
    $keytoolPath = $keytool.Source
}

$randomBytes = [byte[]]::new(36)
[Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$password = [Convert]::ToBase64String($randomBytes)
$env:MOCKGPS_KEYSTORE_PASSWORD = $password

try {
    & $keytoolPath `
        -genkeypair `
        -keystore $keystorePath `
        -storetype JKS `
        -storepass:env MOCKGPS_KEYSTORE_PASSWORD `
        -keypass:env MOCKGPS_KEYSTORE_PASSWORD `
        -alias mockgps `
        -keyalg RSA `
        -keysize 3072 `
        -validity 10000 `
        -dname 'CN=Mock GPS Joystick, O=Local Development, C=TW'
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool 無法建立 release keystore。'
    }

    $properties = @(
        'storeFile=mockgps-release.jks'
        "storePassword=$password"
        'keyAlias=mockgps'
        "keyPassword=$password"
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
} finally {
    Remove-Item Env:MOCKGPS_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}

Write-Host 'Release 簽名檔已建立。密碼未輸出。'
Write-Warning '請立即安全備份 mockgps-release.jks 與 keystore.properties；遺失後無法覆蓋升級既有 App。'

