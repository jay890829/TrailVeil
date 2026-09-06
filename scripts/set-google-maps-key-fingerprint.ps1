<#
.SYNOPSIS
    Writes a Google Maps key fingerprint into the external properties file, printing nothing.

.DESCRIPTION
    `releaseApiKeySha256` is not a value Google shows anyone. It is the lowercase hex SHA-256 of the
    key text as the build reads it, and it exists because it is the only check that can tell your
    release key from any other valid key: `scripts/build-github-release.ps1` extracts the key
    actually compiled into a candidate APK, hashes it, and refuses to publish unless the two agree.
    A key restricted to the wrong certificate produces an APK that looks entirely normal and cannot
    authorize, which is what that check is there to stop.

    This derives the digest from the key already in the file, so the two lines cannot disagree, and
    writes it back in place. Neither the key nor the digest is printed, and the file is neither
    copied nor moved. Comments, ordering, encoding and line endings are preserved, and an existing
    fingerprint keeps its position rather than being appended a second time.

    The same applies to `debugApiKeySha256`, which the build treats as an optional typo self-check.

.PARAMETER Property
    Which key to fingerprint: `releaseApiKey` (default) or `debugApiKey`.

.PARAMETER PropertiesPath
    The properties file to edit. Defaults to $env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES, and then to
    ~/.trailveil/maps/google-maps.properties - the same resolution the build and the release script
    use.

.PARAMETER VerifyOnly
    Report whether the stored fingerprint matches the key and change nothing. Exit code 0 means it
    matches, 2 that none is stored, 3 that the stored one is wrong.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/set-google-maps-key-fingerprint.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/set-google-maps-key-fingerprint.ps1 -Property debugApiKey

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/set-google-maps-key-fingerprint.ps1 -VerifyOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('releaseApiKey', 'debugApiKey')]
    [string]$Property = 'releaseApiKey',

    [string]$PropertiesPath,

    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# The digest the build computes (`String.sha256Hex` in app/build.gradle.kts) and the one the release
# script compares against: UTF-8 bytes of the key text, lowercase hex, no trailing newline.
function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Text)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($Text)
        return ($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha256.Dispose()
    }
}

if (-not $PropertiesPath) {
    $PropertiesPath = if ($env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES) {
        $env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES
    } else {
        Join-Path $HOME '.trailveil/maps/google-maps.properties'
    }
}

if (-not (Test-Path -LiteralPath $PropertiesPath -PathType Leaf)) {
    throw ("No Google Maps properties file at $PropertiesPath. Create it as README.md describes, " +
        'or pass -PropertiesPath.')
}

$resolvedProperties = (Resolve-Path -LiteralPath $PropertiesPath).Path

# The build refuses a key file inside the checkout, because a secret in the working tree is one
# `git add -A` away from being published. Refuse to write into one here for the same reason: this
# script would otherwise be a convenient way to put a fresh secret exactly where it must not go.
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repositoryPrefix = $repositoryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($resolvedProperties.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw ("Refusing to touch ${resolvedProperties}: it is inside the repository, and the build " +
        'rejects a key file there for the same reason.')
}

$originalText = [IO.File]::ReadAllText($resolvedProperties)
$newline = if ($originalText.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = @($originalText -split "`r`n|`n")

# A trailing newline splits into a final empty element. Remember it, so the file keeps its shape
# instead of quietly gaining or losing its last line break.
$endsWithNewline = $false
if ($lines.Count -gt 0 -and $lines[$lines.Count - 1] -eq '') {
    $endsWithNewline = $true
    # Written as statements rather than `$lines = if (...) {...}`: the output of an `if` flows
    # through the pipeline, which unrolls a one-element array into a bare string - and a file with a
    # single property line is exactly the one-element case.
    if ($lines.Count -eq 1) {
        $lines = @()
    } else {
        $lines = @($lines[0..($lines.Count - 2)])
    }
}

# `Properties.load` accepts `=` and `:` and ignores surrounding whitespace, so read both forms. The
# fingerprint is always WRITTEN with `=`, because that is the only form the release script accepts.
$keyPattern = '^\s*' + [regex]::Escape($Property) + '\s*[=:]\s*(.*?)\s*$'
$key = $null
foreach ($line in $lines) {
    if ($line -match $keyPattern) { $key = $Matches[1] }
}

if ([string]::IsNullOrEmpty($key)) {
    throw "$Property is not set in $resolvedProperties, so there is nothing to fingerprint."
}

# Fingerprinting a malformed key would only bless a typo: the build rejects the key as INVALID_KEY
# and compiles the missing-key sentinel, and the fingerprint would agree with it all the way down.
if ($key -cnotmatch '^AIza[A-Za-z0-9_-]{35}$') {
    throw ("The value of $Property is not shaped like a Google Maps key (AIza followed by 35 " +
        'characters from A-Z a-z 0-9 _ -). The build would reject it as INVALID_KEY, so it is ' +
        'not fingerprinted here. Its value is deliberately not shown.')
}

$fingerprintProperty = $Property + 'Sha256'
$fingerprintPattern = '^\s*' + [regex]::Escape($fingerprintProperty) + '\s*[=:]\s*(.*?)\s*$'
$digest = Get-Sha256Hex -Text $key

# Last occurrence wins, which is what `Properties.load` and the release script's own scan both do.
$storedFingerprint = $null
$fingerprintIndexes = @()
for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($lines[$index] -match $fingerprintPattern) {
        $storedFingerprint = $Matches[1]
        $fingerprintIndexes += $index
    }
}

$fingerprintIsCorrect = $null -ne $storedFingerprint -and
    $storedFingerprint.ToLowerInvariant() -eq $digest

if ($VerifyOnly) {
    if ($null -eq $storedFingerprint -or $storedFingerprint -eq '') {
        Write-Output ("$fingerprintProperty is not set. The build accepts that; publishing a " +
            'Google APK does not.')
        exit 2
    }
    if ($fingerprintIsCorrect) {
        Write-Output "$fingerprintProperty matches $Property."
        exit 0
    }
    Write-Output ("$fingerprintProperty does NOT match $Property. Re-run without -VerifyOnly to " +
        'correct it.')
    exit 3
}

if ($fingerprintIsCorrect -and $fingerprintIndexes.Count -eq 1) {
    Write-Output "$fingerprintProperty already matches $Property; nothing to change."
    exit 0
}

# Keep the last occurrence's position, so a comment written above it still describes it, and drop
# any earlier duplicates rather than leaving lines that read as authoritative but are not.
$replaceAt = if ($fingerprintIndexes.Count -gt 0) {
    $fingerprintIndexes[$fingerprintIndexes.Count - 1]
} else {
    -1
}
$dropped = @($fingerprintIndexes | Where-Object { $_ -ne $replaceAt })

$updatedLines = New-Object System.Collections.Generic.List[string]
for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($dropped -contains $index) { continue }
    if ($index -eq $replaceAt) {
        $updatedLines.Add("$fingerprintProperty=$digest")
    } else {
        $updatedLines.Add($lines[$index])
    }
}
if ($replaceAt -lt 0) {
    $updatedLines.Add("$fingerprintProperty=$digest")
}

$updatedText = ($updatedLines -join $newline)
if ($endsWithNewline) { $updatedText += $newline }

# Written in one call with the content fully computed first, and in place: a temporary file beside
# this one would be a second copy of a secret, and replacing the original would hand it the
# temporary file's permissions instead of its own.
[IO.File]::WriteAllText($resolvedProperties, $updatedText, [System.Text.UTF8Encoding]::new($false))

# Read the file back and check two things: that the fingerprint parses under the exact pattern the
# release script requires (`=`, 64 hex, nothing else on the line), and that the key itself came
# through the rewrite unchanged - compared as a digest, so neither value is ever held for printing.
$writtenFingerprint = $null
$writtenKey = $null
$strictFingerprintPattern = '^\s*' + [regex]::Escape($fingerprintProperty) + '\s*=\s*([0-9a-fA-F]{64})\s*$'
foreach ($line in [IO.File]::ReadAllLines($resolvedProperties)) {
    if ($line -match $strictFingerprintPattern) { $writtenFingerprint = $Matches[1].ToLowerInvariant() }
    if ($line -match $keyPattern) { $writtenKey = $Matches[1] }
}

if ($writtenFingerprint -ne $digest) {
    throw ("$fingerprintProperty was written but does not read back in the form the release " +
        "script requires. Check $resolvedProperties by hand before building.")
}
if ([string]::IsNullOrEmpty($writtenKey) -or (Get-Sha256Hex -Text $writtenKey) -ne $digest) {
    throw ("$Property did not survive the rewrite intact. Check $resolvedProperties by hand " +
        'before building.')
}

Write-Output "$fingerprintProperty written to $resolvedProperties."
Write-Output 'Neither the key nor the digest was printed.'
