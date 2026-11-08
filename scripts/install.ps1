# Install Probatio's toolchain into $env:PROBATIO_HOME (default ~\.probatio) without admin rights:
#   - tla2tools.jar, pinned and checksum-verified
#   - a Temurin JDK 21 (x64), only when no JDK 17+ with a compiler is already on PATH
# Afterwards run tools with the java.exe path this script prints.
$ErrorActionPreference = 'Stop'

$TlaVersion = 'v1.8.0'
$TlaUrl = "https://github.com/tlaplus/tlaplus/releases/download/$TlaVersion/tla2tools.jar"
$TlaSha256 = 'e1f11b75c4b5bc4478ba0ac5cb6ae28e40a01b0016bc33e0aa97da22dfdbcd7f'
$JdkRelease = 'jdk-21.0.13+9'
$JdkUrl = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip'
$JdkSha256 = 'a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9'

$HomeDir = if ($env:PROBATIO_HOME) { $env:PROBATIO_HOME } else { Join-Path $HOME '.probatio' }

function Get-Verified([string]$Url, [string]$Destination, [string]$Sha256) {
    $partial = "$Destination.partial"
    Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing
    $actual = (Get-FileHash -Algorithm SHA256 $partial).Hash.ToLower()
    if ($actual -ne $Sha256) {
        Remove-Item $partial
        throw "checksum mismatch for $Url (expected $Sha256, got $actual)"
    }
    Move-Item -Force $partial $Destination
}

function Test-UsableJdk([string]$Java) {
    $modules = & $Java --list-modules 2>$null
    if (-not ($modules -match '^jdk\.compiler')) { return $false }
    $settings = & $Java -XshowSettings:properties -version 2>&1 | Out-String
    if ($settings -match 'java\.specification\.version = (\d+)') { return [int]$Matches[1] -ge 17 }
    return $false
}

New-Item -ItemType Directory -Force $HomeDir | Out-Null

$jar = Join-Path $HomeDir 'tla2tools.jar'
if ((Test-Path $jar) -and ((Get-FileHash -Algorithm SHA256 $jar).Hash.ToLower() -eq $TlaSha256)) {
    Write-Host "tla2tools.jar $TlaVersion already installed"
} else {
    Write-Host "Downloading tla2tools.jar $TlaVersion"
    Get-Verified $TlaUrl $jar $TlaSha256
}

$existing = Get-Command java -ErrorAction SilentlyContinue
if ($existing -and (Test-UsableJdk $existing.Source)) {
    $java = $existing.Source
    Write-Host "Using the JDK on PATH: $java"
} else {
    if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw "unsupported CPU $env:PROCESSOR_ARCHITECTURE; install a JDK 17+ manually" }
    $target = Join-Path $HomeDir $JdkRelease
    if (-not (Test-Path $target)) {
        $zip = Join-Path $HomeDir 'jdk.zip'
        Write-Host "Downloading Temurin $JdkRelease"
        Get-Verified $JdkUrl $zip $JdkSha256
        $unpack = "$target.partial"
        Expand-Archive -Force $zip $unpack
        Move-Item (Get-ChildItem $unpack | Select-Object -First 1).FullName $target
        Remove-Item -Recurse $unpack, $zip
    }
    $java = Join-Path $target 'bin\java.exe'
}

Set-Content -Path (Join-Path $HomeDir 'java-path.txt') -Value $java
Write-Host "Probatio toolchain ready in $HomeDir"
Write-Host "Run: & '$java' scripts\Probatio.java tlc Model.tla Model.cfg --out checks\run-01"
