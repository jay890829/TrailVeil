<#
.SYNOPSIS
    Copies the installed app's Room databases off a device, without launching it and without leaving
    a debuggable build behind.

.DESCRIPTION
    `run-as` refuses a package that is not debuggable, so the shipped build's database cannot be read
    directly. The method `V02-009` established, and this script performs, is to install a
    same-certificate, same-or-lower-`versionCode` **debuggable** twin over it for the length of one
    copy: `adb install -r` keeps the data directory because the certificate matches and the
    `versionCode` does not go down, the app is force-stopped and never launched under the twin, and
    the shipped build is installed back afterwards - in a `finally`, so a failure anywhere in the
    middle still restores it.

    A debuggable published APK was proposed on 2026-09-07 and declined, precisely so that this
    friction stays ours instead of becoming every user's exposure (`0.3.0/PLAN.md`, "A debuggable
    published APK"). This script is the other half of that decision.

    When the installed build is ALREADY debuggable - a `debug` or `internal` build on an emulator or
    a development phone - there is nothing to swap, and the twin arguments are neither needed nor
    accepted.

    The tar is written with a raw byte copy from the child process rather than PowerShell's `>`,
    which re-encodes the stream and inserts a BOM, corrupting it. Only sizes and digests are
    printed; the tar itself holds the user's real track history and is never read into the console
    or committed.

.PARAMETER Tag
    A label for this snapshot, such as `before` or `after`. Names the output file.

.PARAMETER TwinApk
    A debuggable APK with the same certificate and the same package as the installed build, whose
    `versionCode` is not higher than the installed one. Required only when the installed build is
    not debuggable.

.PARAMETER RestoreApk
    The build to install back afterwards - normally the published APK. Required whenever -TwinApk is.
    Verified BEFORE anything is installed, so the run cannot start if the build it would restore is
    missing or wrong.

.PARAMETER OutputDirectory
    Where the tar and the report go. Defaults to a directory under $env:TEMP. Never inside the
    repository.

.PARAMETER Serial
    adb serial. Required when more than one device is attached.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-device-database-snapshot.ps1 -Tag before -TwinApk .\internal.apk -RestoreApk .\published.apk

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/capture-device-database-snapshot.ps1 -Tag emulator-baseline
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]{1,40}$')]
    [string]$Tag,

    [string]$TwinApk,
    [string]$RestoreApk,
    [string]$OutputDirectory,
    [string]$Serial,
    [string]$PackageName = 'app.trailveil'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Description
    )

    # PowerShell 5.1 wraps EVERY stderr line from a native command in a NativeCommandError, and
    # this script runs with $ErrorActionPreference = 'Stop'. Without the guard below, a command
    # that fails and explains itself on stderr terminates here as a raw NativeCommandError, and the
    # message this function exists to produce - which command, which exit code, what it printed -
    # is never reached. Lowered only around the call, restored in a finally.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $FilePath @ArgumentList 2>&1 | ForEach-Object { $_.ToString() })
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code ${LASTEXITCODE}:`n$($output -join "`n")"
    }
    return $output
}

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ---------------------------------------------------------------- tools and device

$androidSdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory.'
}

$adb = Join-Path $androidSdk 'platform-tools/adb.exe'
$apksigner = Join-Path $androidSdk 'build-tools/36.0.0/apksigner.bat'
$apkanalyzer = Join-Path $androidSdk 'cmdline-tools/latest/bin/apkanalyzer.bat'
foreach ($tool in $adb, $apksigner, $apkanalyzer) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android tool was not found at $tool"
    }
}

$attached = @(
    & $adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match '^(\S+)\s+device$' } |
        ForEach-Object { $Matches[1] }
)
if ($Serial) {
    if ($Serial -notin $attached) {
        throw "Device $Serial is not attached. Attached: $($attached -join ', ')"
    }
} elseif ($attached.Count -eq 1) {
    $Serial = $attached[0]
} else {
    throw ("Expected exactly one attached device, found $($attached.Count). Pass -Serial. " +
        "Attached: $($attached -join ', ')")
}
$adbTarget = @('-s', $Serial)

# ---------------------------------------------------------------- output location

if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $env:TEMP 'trailveil-db-snapshots'
}
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repositoryPrefix = $repositoryRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
}
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
if ($resolvedOutput.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw ("Refusing to write to ${resolvedOutput}: it is inside the repository, and this tar is a " +
        "copy of the user's real exploration history.")
}

# ---------------------------------------------------------------- is a twin needed at all

# `V02-013` item 5, 2026-09-07: this probe used to be the bare command, and it took the script down
# on exactly the case it exists to detect. `run-as` on a non-debuggable package writes to stderr and
# exits non-zero; under 'Stop' that stderr line is a terminating NativeCommandError, so the answer
# "not debuggable" - the whole reason a twin install is needed - arrived as a crash instead of as a
# false. The exit code is the answer; the output is not wanted at all.
function Test-PackageDebuggable {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $adb @adbTarget shell run-as $PackageName true 2>&1 | Out-Null
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return $LASTEXITCODE -eq 0
}

$alreadyDebuggable = Test-PackageDebuggable

if ($alreadyDebuggable) {
    if ($TwinApk -or $RestoreApk) {
        throw ("The installed $PackageName is already debuggable, so no twin is needed. Re-run " +
            'without -TwinApk and -RestoreApk rather than reinstalling over a build that does ' +
            'not need replacing.')
    }
} else {
    if (-not $TwinApk -or -not $RestoreApk) {
        throw ("The installed $PackageName is not debuggable, so `run-as` cannot read it. Pass " +
            '-TwinApk (a debuggable same-certificate build) and -RestoreApk (the build to put ' +
            'back). Both are verified before anything is installed.')
    }
}

# ---------------------------------------------------------------- verify the APKs, before any install

function Get-ApkFacts {
    param([Parameter(Mandatory = $true)][string]$Path, [Parameter(Mandatory = $true)][string]$Label)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label APK was not found at $Path"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path

    $report = @(Invoke-CheckedNative -FilePath $apksigner `
        -ArgumentList @('verify', '--verbose', '--print-certs', $resolved) `
        -Description "$Label APK signature verification")
    $digests = @($report | Select-String -Pattern '^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$')
    if ($digests.Count -ne 1) {
        throw "$Label APK does not report exactly one signer certificate digest."
    }

    # Each of these was once written inline as `(Invoke-CheckedNative ...)[0].Trim()`, and every one
    # of them was broken. PowerShell UNROLLS a one-element array when a function returns it, so a
    # single-line answer came back as a [string]; `[0]` then took its first CHARACTER and `.Trim()`
    # failed on a [char]. apkanalyzer prints exactly one line for all three of these queries, which
    # is precisely the case that unrolls, so all three failed - on 2026-09-07, before a single byte
    # had been installed, which is the one piece of luck in it. `@()` re-wraps the result.
    $readValue = {
        param([string]$Query, [string]$Description)

        $lines = @(Invoke-CheckedNative -FilePath $apkanalyzer -Description $Description `
            -ArgumentList @('manifest', $Query, $resolved))
        if ($lines.Count -lt 1) {
            throw "$Description returned no output."
        }
        return $lines[0].Trim()
    }

    return [pscustomobject]@{
        Path = $resolved
        Label = $Label
        CertificateSha256 = $digests[0].Matches[0].Groups[1].Value
        ApplicationId = & $readValue 'application-id' "$Label application id"
        VersionCode = [long](& $readValue 'version-code' "$Label version code")
        Debuggable = & $readValue 'debuggable' "$Label debuggable flag"
    }
}

$installedVersionCode = $null
if (-not $alreadyDebuggable) {
    $twin = Get-ApkFacts -Path $TwinApk -Label 'Twin'
    $restore = Get-ApkFacts -Path $RestoreApk -Label 'Restore'

    if ($twin.Debuggable -ne 'true') {
        throw 'The twin APK is not debuggable, so `run-as` would refuse it too.'
    }
    # The whole point of the decision this script implements: what goes back on the device must not
    # be debuggable. Checked before the twin is installed, so this can never fail too late.
    if ($restore.Debuggable -eq 'true') {
        throw ('The restore APK is debuggable. Restoring it would leave the device carrying a ' +
            'build whose database anyone with adb can copy.')
    }
    foreach ($facts in $twin, $restore) {
        if ($facts.ApplicationId -ne $PackageName) {
            throw "The $($facts.Label) APK declares $($facts.ApplicationId), not $PackageName."
        }
    }
    if ($twin.CertificateSha256 -ne $restore.CertificateSha256) {
        throw ('The twin and the restore APK are signed by different certificates, so installing ' +
            'one over the other would wipe the data this snapshot exists to read.')
    }
    if ($restore.VersionCode -lt $twin.VersionCode) {
        throw ("Restore versionCode $($restore.VersionCode) is lower than the twin's " +
            "$($twin.VersionCode), so the shipped build could not be installed back.")
    }

    $packageDump = @(& $adb @adbTarget shell dumpsys package $PackageName)
    $installedVersionCodes = @($packageDump | Select-String -Pattern 'versionCode=(\d+)')
    if ($installedVersionCodes.Count -lt 1) {
        throw "$PackageName does not appear to be installed on $Serial."
    }
    $installedVersionCode = [long]$installedVersionCodes[0].Matches[0].Groups[1].Value
    if ($twin.VersionCode -gt $installedVersionCode) {
        throw ("The twin's versionCode $($twin.VersionCode) is higher than the installed " +
            "$installedVersionCode. `adb install -r` would succeed, but the shipped build could " +
            'then only be restored by a downgrade. Build the twin at the installed versionCode.')
    }
}

# ---------------------------------------------------------------- the snapshot

$tarPath = Join-Path $resolvedOutput "$Tag-databases.tar"
$swapped = $false

try {
    if (-not $alreadyDebuggable) {
        Write-Output "Installing the debuggable twin over $PackageName (data directory untouched)."
        Invoke-CheckedNative -FilePath $adb `
            -ArgumentList ($adbTarget + @('install', '-r', $twin.Path)) `
            -Description 'Twin install' | Out-Null
        $swapped = $true
    }

    # Never launched under the twin: a launch would let the app write, and the snapshot is supposed
    # to be the state the shipped build left behind.
    Invoke-CheckedNative -FilePath $adb `
        -ArgumentList ($adbTarget + @('shell', 'am', 'force-stop', $PackageName)) `
        -Description 'Force-stop' | Out-Null

    # Raw byte copy. PowerShell's `>` re-encodes the stream and adds a BOM, which corrupts a tar;
    # reading the child's BaseStream avoids the redirect operator altogether.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $adb
    $startInfo.Arguments = "-s $Serial exec-out run-as $PackageName tar -cf - databases"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $fileStream = [IO.File]::Create($tarPath)
    try {
        $process.StandardOutput.BaseStream.CopyTo($fileStream)
    } finally {
        $fileStream.Dispose()
    }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "run-as tar failed with exit code $($process.ExitCode): $stderr"
    }

    # A tar whose header is wrong is worse than no tar: it looks like evidence. `ustar` sits at
    # offset 257 of the first header block.
    $tarLength = (Get-Item -LiteralPath $tarPath).Length
    if ($tarLength -lt 512) {
        throw "The captured tar is $tarLength bytes, which is too small to be a tar archive."
    }
    $header = [byte[]]::new(512)
    $reader = [IO.File]::OpenRead($tarPath)
    try {
        $reader.Read($header, 0, 512) | Out-Null
    } finally {
        $reader.Dispose()
    }
    $magic = [Text.Encoding]::ASCII.GetString($header, 257, 5)
    if ($magic -ne 'ustar') {
        throw ("The captured file is not a tar archive (magic '$magic'). If this run used a " +
            'redirect instead of a raw byte copy, the stream was re-encoded.')
    }
} finally {
    if ($swapped) {
        # In `finally` on purpose: whatever went wrong above, the device must not be left carrying
        # the debuggable twin.
        Write-Output 'Restoring the shipped build.'
        & $adb @adbTarget install -r $restore.Path | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning ("RESTORE FAILED. The device is still carrying the debuggable twin. " +
                "Install $($restore.Path) by hand before using the device again.")
        } else {
            if (Test-PackageDebuggable) {
                Write-Warning ('The restored build still answers `run-as`, so it is debuggable. ' +
                    'Check which APK was restored before using the device again.')
            } else {
                Write-Output 'Restored, and it is not debuggable.'
            }
        }
    }
}

# ---------------------------------------------------------------- report: sizes and digests only

# Mode, size and name only. The timestamp column is dropped rather than shown: `tar.exe` renders
# month names in the console codepage, which turns into mojibake on a CJK host and would go into
# evidence looking like corruption.
$entries = @(& tar.exe -tvf $tarPath | ForEach-Object {
    $parts = $_ -split '\s+'
    if ($parts.Count -ge 6) {
        '{0,-12} {1,10}  {2}' -f $parts[0], $parts[4], $parts[$parts.Count - 1]
    } else {
        $_
    }
})
Write-Output ''
Write-Output "tag                $Tag"
Write-Output "device             $Serial"
Write-Output "package            $PackageName"
if ($null -ne $installedVersionCode) {
    Write-Output "installed vCode    $installedVersionCode"
}
Write-Output "twin needed        $(-not $alreadyDebuggable)"
Write-Output "tar                $tarPath"
Write-Output "tar sha256         $(Get-FileSha256 -Path $tarPath)"
Write-Output "tar bytes          $((Get-Item -LiteralPath $tarPath).Length)"
Write-Output 'contents (size and name only - no row is ever read):'
$entries | ForEach-Object { Write-Output "  $_" }
