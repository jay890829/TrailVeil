param(
    [string]$AsciiStagingRoot = $env:TEMP,
    # V02-013: 0.2.0 publishes two APKs, so both are built and audited by default. A builder who
    # holds no Google release key opts out explicitly rather than silently shipping one asset.
    [switch]$OpenFreeMapOnly
)

$ErrorActionPreference = 'Stop'

function Invoke-CheckedNative {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string[]]$ArgumentList,
        [Parameter(Mandatory)] [string]$Description
    )

    $output = @(& $FilePath @ArgumentList 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed with exit code ${LASTEXITCODE}:`n$($output -join "`n")"
    }
    return $output
}

function Get-NormalizedTextSha256 {
    param([Parameter(Mandatory)] [string]$Text)

    $normalized = $Text.Replace("`r`n", "`n").Replace("`r", "`n").TrimEnd() + "`n"
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($normalized)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ($sha256.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha256.Dispose()
    }
}

function Assert-NativeHelperContract {
    $pwsh = (Get-Process -Id $PID).Path
    $zero = @(Invoke-CheckedNative -FilePath $pwsh `
        -ArgumentList @('-NoProfile', '-Command', 'exit 0') -Description 'zero-line probe')
    $one = @(Invoke-CheckedNative -FilePath $pwsh `
        -ArgumentList @('-NoProfile', '-Command', 'Write-Output only') -Description 'one-line probe')
    $many = @(Invoke-CheckedNative -FilePath $pwsh `
        -ArgumentList @('-NoProfile', '-Command', 'Write-Output first; Write-Output second') `
        -Description 'multi-line probe')
    if ($zero.Count -ne 0 -or $one.Count -ne 1 -or $one[0] -ne 'only') {
        throw 'Checked-native helper does not preserve zero/one-line cardinality.'
    }
    if ($many.Count -ne 2 -or $many[0] -ne 'first' -or $many[1] -ne 'second') {
        throw 'Checked-native helper does not preserve multi-line cardinality.'
    }
    $failedClosed = $false
    try {
        Invoke-CheckedNative -FilePath $pwsh `
            -ArgumentList @('-NoProfile', '-Command', 'exit 7') -Description 'nonzero probe'
    } catch {
        $failedClosed = $_.Exception.Message -match 'exit code 7'
    }
    if (-not $failedClosed) {
        throw 'Checked-native helper did not fail closed on a nonzero exit.'
    }
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$distributionDirectory = Join-Path $repositoryRoot 'app/build/github-release'
# V02-008: the Google variant is built by whoever holds a key and is never published, because a
# built Google APK carries that key uncompressed in resources.arsc. Gradle writes every variant's
# APK under app/build/outputs/apk/, so the directory this script hands to a release upload must
# never be that tree or anything inside it.
$gradleApkOutputRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'app/build/outputs'))
$distributionFullPath = [System.IO.Path]::GetFullPath($distributionDirectory)
if ($distributionFullPath.StartsWith($gradleApkOutputRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to distribute from the Gradle output tree: $distributionFullPath"
}
# A failed or dirty invocation must not leave a prior candidate looking current.
if (Test-Path -LiteralPath $distributionDirectory) {
    $priorReadyMarker = Join-Path $distributionDirectory 'RELEASE-READY.txt'
    if (Test-Path -LiteralPath $priorReadyMarker) {
        Remove-Item -LiteralPath $priorReadyMarker -Force
    }
    Remove-Item -LiteralPath $distributionDirectory -Recurse -Force
}
Assert-NativeHelperContract

$gitStatus = @(Invoke-CheckedNative -FilePath 'git' `
    -ArgumentList @('-C', $repositoryRoot, 'status', '--porcelain', '--untracked-files=normal') `
    -Description 'Git worktree inspection')
if ($gitStatus) {
    throw "Refusing to build a release from a dirty worktree:`n$($gitStatus -join "`n")"
}

if ([string]::IsNullOrWhiteSpace($AsciiStagingRoot)) {
    throw 'AsciiStagingRoot or TEMP must name a temporary directory.'
}
$asciiRoot = [System.IO.Path]::GetFullPath($AsciiStagingRoot)
if ($asciiRoot -notmatch '^[\x00-\x7f]+$') {
    throw "Native Android tools require an ASCII staging path; got $asciiRoot"
}
New-Item -ItemType Directory -Force -Path $asciiRoot | Out-Null
$stagingDirectory = Join-Path $asciiRoot ("trailveil-release-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stagingDirectory | Out-Null
$inheritedJavaOptions = $env:_JAVA_OPTIONS
$javaOptionsCleared = $false
$published = $false

try {
    $gradle = Join-Path $repositoryRoot 'gradlew.bat'
    $gradleTasks = @('clean', 'assembleRelease', 'assembleInternal', 'lintRelease', 'testDebugUnitTest')
    if (-not $OpenFreeMapOnly) {
        # V02-013: the same clean commit, the same invocation. Two published APKs assembled from
        # two invocations could differ by an edit between them, and nothing downstream would say so.
        $gradleTasks += @('assembleGoogleRelease', 'lintGoogleRelease')
    }
    & $gradle -p $repositoryRoot @gradleTasks
    if ($LASTEXITCODE -ne 0) {
        throw "Release Gradle gate failed with exit code $LASTEXITCODE."
    }
    # Gradle needs this AF_UNIX override on the local Windows checkout, but every Java-based Android
    # audit tool echoes `_JAVA_OPTIONS` to stderr. Remove it after Gradle so a one-value audit really
    # returns one line; restore it in finally for the caller.
    if (Test-Path Env:_JAVA_OPTIONS) {
        Remove-Item Env:_JAVA_OPTIONS
        $javaOptionsCleared = $true
    }

    $sourceApk = Join-Path $repositoryRoot 'app/build/outputs/apk/release/app-release.apk'
    $internalApk = Join-Path $repositoryRoot 'app/build/outputs/apk/internal/app-internal.apk'
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
        throw "Release APK was not produced at $sourceApk"
    }
    if (-not (Test-Path -LiteralPath $internalApk -PathType Leaf)) {
        throw "Internal lineage APK was not produced at $internalApk"
    }

    $candidateApk = Join-Path $stagingDirectory 'candidate-release.apk'
    $candidateInternalApk = Join-Path $stagingDirectory 'candidate-internal.apk'
    Copy-Item -LiteralPath $sourceApk -Destination $candidateApk
    Copy-Item -LiteralPath $internalApk -Destination $candidateInternalApk

    $androidSdk = if ($env:ANDROID_HOME) {
        $env:ANDROID_HOME
    } elseif ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } else {
        throw 'Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory.'
    }
    $apksigner = Join-Path $androidSdk 'build-tools/36.0.0/apksigner.bat'
    $aapt2 = Join-Path $androidSdk 'build-tools/36.0.0/aapt2.exe'
    $apkanalyzer = Join-Path $androidSdk 'cmdline-tools/latest/bin/apkanalyzer.bat'
    foreach ($tool in $apksigner, $aapt2, $apkanalyzer) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
            throw "Required Android tool was not found at $tool"
        }
    }

    $certificateReport = @(Invoke-CheckedNative -FilePath $apksigner `
        -ArgumentList @('verify', '--verbose', '--print-certs', $candidateApk) `
        -Description 'Release APK signature verification')
    foreach ($requiredSignatureFact in @(
        'Verifies',
        'Verified using v2 scheme (APK Signature Scheme v2): true',
        'Number of signers: 1'
    )) {
        if ($requiredSignatureFact -notin $certificateReport) {
            throw "Release signature report is missing: $requiredSignatureFact"
        }
    }
    $internalCertificateReport = @(Invoke-CheckedNative -FilePath $apksigner `
        -ArgumentList @('verify', '--verbose', '--print-certs', $candidateInternalApk) `
        -Description 'Internal lineage APK signature verification')

    $digestPattern = '^Signer #1 certificate SHA-256 digest: ([0-9a-f]+)$'
    $releaseDigestMatches = @($certificateReport | Select-String -Pattern $digestPattern)
    $internalDigestMatches = @($internalCertificateReport | Select-String -Pattern $digestPattern)
    if ($releaseDigestMatches.Count -ne 1 -or $internalDigestMatches.Count -ne 1) {
        throw 'Each APK must report exactly one signer certificate digest.'
    }
    $releaseCertificateDigest = $releaseDigestMatches[0].Matches[0].Groups[1].Value
    $internalCertificateDigest = $internalDigestMatches[0].Matches[0].Groups[1].Value
    $expectedCertificateDigest = '307963f32352e6565889982c2b6021af960c94db5c40e0e38c52a2f2cf13856d'
    if ($releaseCertificateDigest -ne $internalCertificateDigest) {
        throw 'Release and internal APKs are not signed by the same certificate.'
    }
    if ($releaseCertificateDigest -ne $expectedCertificateDigest) {
        throw "Release certificate $releaseCertificateDigest is not the pinned TrailVeil certificate."
    }

    $manifestCommands = [ordered]@{
        applicationId = @('manifest', 'application-id', $candidateApk)
        versionName = @('manifest', 'version-name', $candidateApk)
        versionCode = @('manifest', 'version-code', $candidateApk)
        minSdk = @('manifest', 'min-sdk', $candidateApk)
        targetSdk = @('manifest', 'target-sdk', $candidateApk)
        debuggable = @('manifest', 'debuggable', $candidateApk)
    }
    $manifestFacts = [ordered]@{}
    foreach ($key in $manifestCommands.Keys) {
        $lines = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList $manifestCommands[$key] -Description "Release manifest $key audit")
        if ($lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($lines[0])) {
            throw "Release manifest $key audit returned no single value."
        }
        $manifestFacts[$key] = $lines[0].Trim()
    }
    $expectedFacts = [ordered]@{
        applicationId = 'app.trailveil'
        versionName = '0.2.0'
        versionCode = '2'
        minSdk = '34'
        targetSdk = '36'
        debuggable = 'false'
    }
    foreach ($key in $expectedFacts.Keys) {
        if ($manifestFacts[$key] -ne $expectedFacts[$key]) {
            throw "Unexpected release manifest $key=$($manifestFacts[$key]); expected $($expectedFacts[$key])."
        }
    }
    $internalApplicationId = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('manifest', 'application-id', $candidateInternalApk) `
        -Description 'Internal lineage application-id audit')
    $internalVersionName = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('manifest', 'version-name', $candidateInternalApk) `
        -Description 'Internal lineage version-name audit')
    $internalVersionCode = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('manifest', 'version-code', $candidateInternalApk) `
        -Description 'Internal lineage version-code audit')
    if (
        $internalApplicationId.Count -ne 1 -or
        $internalApplicationId[0].Trim() -ne $manifestFacts.applicationId
    ) {
        throw 'Internal and release APKs do not share applicationId app.trailveil.'
    }
    if ($internalVersionName.Count -ne 1 -or $internalVersionName[0].Trim() -ne '0.2.0-internal') {
        throw "Unexpected internal lineage version name: $($internalVersionName -join ', ')."
    }
    if ($internalVersionCode.Count -ne 1 -or $internalVersionCode[0].Trim() -notmatch '^\d+$') {
        throw "Invalid internal lineage version code: $($internalVersionCode -join ', ')."
    }
    if ([long]$manifestFacts.versionCode -lt [long]($internalVersionCode[0].Trim())) {
        throw 'Release versionCode is lower than the internal lineage and cannot replace it.'
    }

    $headLines = @(Invoke-CheckedNative -FilePath 'git' `
        -ArgumentList @('-C', $repositoryRoot, 'rev-parse', '--short=12', 'HEAD') `
        -Description 'Release HEAD lookup')
    if ($headLines.Count -ne 1 -or $headLines[0].Trim() -notmatch '^[0-9a-f]{12}$') {
        throw "Unable to resolve one clean 12-character release HEAD: $($headLines -join ', ')"
    }
    $head = $headLines[0].Trim()
    $buildConfig = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('dex', 'code', '--class', 'app.trailveil.BuildConfig', $candidateApk) `
        -Description 'Release BuildConfig audit')
    $expectedBuildConfigFields = @(
        '.field public static final APPLICATION_ID:Ljava/lang/String; = "app.trailveil"',
        '.field public static final BUILD_TYPE:Ljava/lang/String; = "release"',
        '.field public static final DEBUG:Z = false',
        ".field public static final GIT_COMMIT:Ljava/lang/String; = `"$head`"",
        '.field public static final VERSION_CODE:I = 0x2',
        '.field public static final VERSION_NAME:Ljava/lang/String; = "0.2.0"'
    )
    foreach ($field in $expectedBuildConfigFields) {
        if ($field -notin $buildConfig) {
            throw "Release APK BuildConfig is missing exact field: $field"
        }
    }

    # V02-008: refuse a Google output explicitly. Only the OpenFreeMap (MapLibre) variant is
    # published; a Google APK is built by whoever supplies the key and must never reach a release.
    # Three independent places a key or the Maps SDK would show: the merged manifest's key marker,
    # the dex package list, and the string resource the key is compiled into.
    #
    # There are two Google build types now - googlePoc and the release-configured googleRelease -
    # and every check below is on what the APK CONTAINS, so neither is named and neither can slip
    # past by being the one that was not thought of. The BuildConfig audit above already pins
    # BUILD_TYPE to "release", which refuses both by a fourth, independent route.
    #
    # One asymmetry worth stating: since `V02-008` split the engineering harness out of the shipped
    # Google sources, only a googlePoc output carries the PoC components, so that check alone no
    # longer refuses a googleRelease APK. The key marker, the Maps SDK and the key resource all
    # still do, and any one of them is enough.
    $manifestXml = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('manifest', 'print', $candidateApk) `
        -Description 'Release manifest provider audit') -join "`n"
    if ($manifestXml -match 'com\.google\.android\.geo\.API_KEY') {
        throw 'Refusing to release: the candidate APK declares the Google Maps API-key marker, so it is a Google build.'
    }
    if ($manifestXml -match 'app\.trailveil\.googlepoc\.') {
        throw 'Refusing to release: the candidate APK carries the Google PoC components, so it is a Google build.'
    }
    $dexPackages = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('dex', 'packages', $candidateApk) `
        -Description 'Release dex package audit')
    $googleMapsPackages = @($dexPackages | Where-Object { $_ -match '\bcom\.google\.android\.gms\.maps\b' })
    if ($googleMapsPackages.Count -ne 0) {
        throw 'Refusing to release: the candidate APK packages the Google Maps SDK, so it is a Google build.'
    }
    $mapLibrePackages = @($dexPackages | Where-Object { $_ -match '\borg\.maplibre\.android\b' })
    if ($mapLibrePackages.Count -eq 0) {
        throw 'Refusing to release: the candidate APK does not package MapLibre, so it is not the OpenFreeMap variant.'
    }
    # `V02-013` verifier, 2026-09-07: these two were asserted absent from the Google artifact and
    # nowhere else. The manifest regex above matches only the `googlepoc` PACKAGE, so it could never
    # have caught `app.trailveil.map.GoogleMapSurfaceTestActivity`, nor a dex-only class with no
    # manifest component. "The harness is not in the published build" is a claim about EVERY
    # published build, so both artifacts get the same two-surface check.
    foreach ($harnessClass in @(
        'app.trailveil.googlepoc.GoogleMapsPocActivity',
        'app.trailveil.map.GoogleMapSurfaceTestActivity'
    )) {
        if (@($dexPackages | Where-Object { $_ -match [regex]::Escape($harnessClass) }).Count -ne 0) {
            throw "Refusing to release: the candidate APK carries the engineering class $harnessClass."
        }
        if ($manifestXml -match [regex]::Escape($harnessClass)) {
            throw "Refusing to release: the candidate APK declares the engineering component $harnessClass."
        }
    }

    $permissions = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('manifest', 'permissions', $candidateApk) `
        -Description 'Release permission audit') | Sort-Object -Unique
    $expectedPermissions = @(
        'android.permission.ACCESS_BACKGROUND_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_LOCATION',
        'android.permission.INTERNET',
        'android.permission.POST_NOTIFICATIONS',
        'app.trailveil.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
    ) | Sort-Object
    $permissionDifference = @(Compare-Object $expectedPermissions $permissions)
    if ($permissions.Count -eq 0 -or $permissionDifference.Count -ne 0) {
        throw "Unexpected release permissions: $($permissions -join ', ')."
    }

    $files = @(Invoke-CheckedNative -FilePath $apkanalyzer `
        -ArgumentList @('files', 'list', $candidateApk) -Description 'Release file inventory')
    $abis = $files | ForEach-Object {
        if ($_ -match '^/?lib/([^/]+)/') { $Matches[1] }
    } | Sort-Object -Unique
    $expectedAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
    if (@(Compare-Object $expectedAbis $abis).Count -ne 0) {
        throw "Unexpected packaged ABIs: $($abis -join ', ')."
    }
    # `V02-013` verifier, 2026-09-07: the Google path asserts this library is ABSENT, and nothing
    # asserted it is PRESENT here - so a MapLibre build that packaged no native library at all would
    # have satisfied both sides. An absence is only evidence next to the matching presence.
    $mapLibreNative = @($files | Where-Object { $_ -match 'libmaplibre\.so$' })
    if ($mapLibreNative.Count -eq 0) {
        throw 'Refusing to release: the candidate APK packages no MapLibre native library, so its renderer is not there.'
    }

    $expectedNoticeDigest = 'db3cc41e2c79f394a1dddd890c55c263426175029a898d5167820498ddebf152'
    $noticeSource = Join-Path $repositoryRoot 'app/src/mapLibre/res/raw/maplibre_third_party_notices.txt'
    $sourceNoticeDigest = Get-NormalizedTextSha256 -Text ([IO.File]::ReadAllText($noticeSource))
    if ($sourceNoticeDigest -ne $expectedNoticeDigest) {
        throw "MapLibre notice source digest $sourceNoticeDigest is not the pinned android-v13.4.1 notice."
    }
    $resources = @(Invoke-CheckedNative -FilePath $aapt2 `
        -ArgumentList @('dump', 'resources', $candidateApk) -Description 'Release resource audit')
    $resourceText = $resources -join "`n"
    # V02-008, the third place: both Google build types compile the key (or its missing-key
    # sentinel) into a string resource. Neither the resource nor a key-shaped value may exist here.
    foreach ($googleResourceMarker in 'trailveil_google_maps_poc_api_key', 'TRAILVEIL_GOOGLE_MAPS_POC_MISSING_KEY') {
        if ($resourceText.Contains($googleResourceMarker)) {
            throw "Refusing to release: the candidate APK compiles the Google Maps key resource ($googleResourceMarker)."
        }
    }
    if ($resourceText -match 'AIza[A-Za-z0-9_-]{35}') {
        throw 'Refusing to release: the candidate APK contains a Google API key-shaped string.'
    }
    $noticeEntryMatch = [regex]::Match(
        $resourceText,
        'raw/maplibre_third_party_notices[\s\S]*?\(file\)\s+(\S+)'
    )
    if (-not $noticeEntryMatch.Success) {
        throw 'Release APK does not package the MapLibre third-party notices resource.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($candidateApk)
    try {
        $noticeEntry = $archive.GetEntry($noticeEntryMatch.Groups[1].Value)
        if ($null -eq $noticeEntry) {
            throw 'The compiled MapLibre notice resource has no APK ZIP entry.'
        }
        $reader = [IO.StreamReader]::new($noticeEntry.Open(), [Text.UTF8Encoding]::new($false))
        try {
            $packagedNotice = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }
    $packagedNoticeDigest = Get-NormalizedTextSha256 -Text $packagedNotice
    if ($packagedNoticeDigest -ne $expectedNoticeDigest) {
        throw "Packaged MapLibre notice digest $packagedNoticeDigest is incomplete or altered."
    }
    # V02-016, the mirror of the Google side's own check: a Google artifact inside the MapLibre APK
    # is the same V02-008 defect pointing the other way, and the notices are the piece of it a
    # source-set mistake would move first.
    if ($resourceText.Contains('google_third_party_notices')) {
        throw 'Refusing to release: the candidate APK packages the Google third-party notices.'
    }

    $postBuildStatus = @(Invoke-CheckedNative -FilePath 'git' `
        -ArgumentList @('-C', $repositoryRoot, 'status', '--porcelain', '--untracked-files=normal') `
        -Description 'Post-build Git worktree inspection')
    if ($postBuildStatus) {
        throw "Worktree changed during the release build:`n$($postBuildStatus -join "`n")"
    }

    $version = "v$($manifestFacts.versionName)"
    $artifactName = "TrailVeil-$version.apk"
    $artifact = Join-Path $stagingDirectory $artifactName
    Move-Item -LiteralPath $candidateApk -Destination $artifact
    $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    $utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
    $checksumName = "$artifactName.sha256"
    $certificateName = "TrailVeil-$version-certificate.txt"
    $auditName = "TrailVeil-$version-audit.txt"
    [IO.File]::WriteAllText(
        (Join-Path $stagingDirectory $checksumName),
        "$hash  $artifactName`n",
        $utf8WithoutBom
    )
    [IO.File]::WriteAllLines(
        (Join-Path $stagingDirectory $certificateName),
        $certificateReport,
        $utf8WithoutBom
    )
    $auditLines = @(
        "artifact=$artifactName",
        "sha256=$hash",
        "certificateSha256=$releaseCertificateDigest"
    ) + ($manifestFacts.Keys | ForEach-Object { "$_=$($manifestFacts[$_])" }) + @(
        "gitCommit=$head",
        'buildType=release',
        'signatureSchemeV2=true',
        'signerCount=1',
        "abis=$($abis -join ',')",
        # `V02-013` verifier, 2026-09-07: named for what it actually is. This digest is taken after
        # CRLF->LF, TrimEnd and a single trailing newline, so a reader who hashes the packaged file
        # gets a different value and would reasonably conclude the notice had been tampered with.
        # The internal-lineage comparison still runs and still gates the release; its three facts
        # were dropped from here because they describe an APK that is never published, so nobody
        # reading this file beside the artifact can check them.
        "maplibreAndroidNoticeNormalizedSha256=$packagedNoticeDigest",
        'provider=openfreemap',
        'googleMapsMarkers=absent',
        'permissions:'
    ) + ($permissions | ForEach-Object { "  $_" })
    [IO.File]::WriteAllLines(
        (Join-Path $stagingDirectory $auditName),
        $auditLines,
        $utf8WithoutBom
    )
    Remove-Item -LiteralPath $candidateInternalApk -Force
    $publicNames = @($artifactName, $checksumName, $certificateName, $auditName)
    $publishedDigests = [ordered]@{ $artifactName = $hash }

    # V02-013: the Google artifact. Every assertion below is the mirror image of the refusals above,
    # because this APK is the one that must contain what the other must not. The key it carries is
    # public by construction - uncompressed in resources.arsc - so what protects it is its Android
    # restriction to this package and to the certificate pinned above, which is why the signer check
    # is not a formality here: a Google APK signed by anything else would ship a key that cannot
    # authorize, exactly the failure this task exists to fix.
    if (-not $OpenFreeMapOnly) {
        $googleSourceApk = Join-Path $repositoryRoot 'app/build/outputs/apk/googleRelease/app-googleRelease.apk'
        if (-not (Test-Path -LiteralPath $googleSourceApk -PathType Leaf)) {
            throw "Google APK was not produced at $googleSourceApk"
        }
        $candidateGoogleApk = Join-Path $stagingDirectory 'candidate-google.apk'
        Copy-Item -LiteralPath $googleSourceApk -Destination $candidateGoogleApk

        $googleCertificateReport = @(Invoke-CheckedNative -FilePath $apksigner `
            -ArgumentList @('verify', '--verbose', '--print-certs', $candidateGoogleApk) `
            -Description 'Google APK signature verification')
        foreach ($requiredSignatureFact in @(
            'Verifies',
            'Verified using v2 scheme (APK Signature Scheme v2): true',
            'Number of signers: 1'
        )) {
            if ($requiredSignatureFact -notin $googleCertificateReport) {
                throw "Google signature report is missing: $requiredSignatureFact"
            }
        }
        $googleDigestMatches = @($googleCertificateReport | Select-String -Pattern $digestPattern)
        if ($googleDigestMatches.Count -ne 1) {
            throw 'The Google APK must report exactly one signer certificate digest.'
        }
        $googleCertificateDigest = $googleDigestMatches[0].Matches[0].Groups[1].Value
        if ($googleCertificateDigest -ne $expectedCertificateDigest) {
            throw "Google certificate $googleCertificateDigest is not the pinned TrailVeil certificate."
        }

        $googleManifestCommands = [ordered]@{
            applicationId = @('manifest', 'application-id', $candidateGoogleApk)
            versionName = @('manifest', 'version-name', $candidateGoogleApk)
            versionCode = @('manifest', 'version-code', $candidateGoogleApk)
            minSdk = @('manifest', 'min-sdk', $candidateGoogleApk)
            targetSdk = @('manifest', 'target-sdk', $candidateGoogleApk)
            debuggable = @('manifest', 'debuggable', $candidateGoogleApk)
        }
        $googleManifestFacts = [ordered]@{}
        foreach ($key in $googleManifestCommands.Keys) {
            $lines = @(Invoke-CheckedNative -FilePath $apkanalyzer `
                -ArgumentList $googleManifestCommands[$key] -Description "Google manifest $key audit")
            if ($lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($lines[0])) {
                throw "Google manifest $key audit returned no single value."
            }
            $googleManifestFacts[$key] = $lines[0].Trim()
        }
        # Derived from the OpenFreeMap expectations rather than repeated, so the two published APKs
        # cannot drift apart on identity: same application, same version code, same SDK range, both
        # not debuggable, and a version name that is the release name plus this variant's suffix.
        $expectedGoogleFacts = [ordered]@{}
        foreach ($key in $expectedFacts.Keys) {
            $expectedGoogleFacts[$key] = $expectedFacts[$key]
        }
        $expectedGoogleFacts.versionName = "$($expectedFacts.versionName)-google"
        foreach ($key in $expectedGoogleFacts.Keys) {
            if ($googleManifestFacts[$key] -ne $expectedGoogleFacts[$key]) {
                throw "Unexpected Google manifest $key=$($googleManifestFacts[$key]); expected $($expectedGoogleFacts[$key])."
            }
        }

        $googleBuildConfig = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList @('dex', 'code', '--class', 'app.trailveil.BuildConfig', $candidateGoogleApk) `
            -Description 'Google BuildConfig audit')
        # GOOGLE_MAPS_POC_KEY_REASON is the build's own account of which key it resolved. A keyless
        # Google build still builds, on purpose, and fails closed onto the provider-unavailable
        # surface; it must never be the thing that gets published, and this is where that is caught.
        $expectedGoogleBuildConfigFields = @(
            ".field public static final APPLICATION_ID:Ljava/lang/String; = `"$($expectedGoogleFacts.applicationId)`"",
            '.field public static final BUILD_TYPE:Ljava/lang/String; = "googleRelease"',
            '.field public static final DEBUG:Z = false',
            ".field public static final GIT_COMMIT:Ljava/lang/String; = `"$head`"",
            '.field public static final GOOGLE_MAPS_POC_KEY_CONFIGURED:Z = true',
            '.field public static final GOOGLE_MAPS_POC_KEY_GUIDANCE:Ljava/lang/String; = ""',
            '.field public static final GOOGLE_MAPS_POC_KEY_REASON:Ljava/lang/String; = "VALID"',
            (".field public static final VERSION_CODE:I = " +
                ('0x{0:x}' -f [int]$expectedGoogleFacts.versionCode)),
            ".field public static final VERSION_NAME:Ljava/lang/String; = `"$($expectedGoogleFacts.versionName)`""
        )
        foreach ($field in $expectedGoogleBuildConfigFields) {
            if ($field -notin $googleBuildConfig) {
                throw "Google APK BuildConfig is missing exact field: $field"
            }
        }

        # The key resource, and the marker that has to point at it. `apkanalyzer manifest print`
        # renders a resource reference as its numeric id, so the binding is proven by resolving the
        # resource's id from the resource table and matching the id the marker actually holds. The
        # value itself is never read into a variable that anything prints.
        $googleResources = @(Invoke-CheckedNative -FilePath $aapt2 `
            -ArgumentList @('dump', 'resources', $candidateGoogleApk) -Description 'Google resource audit')
        $googleResourceText = $googleResources -join "`n"
        $keyResourceMatches = @([regex]::Matches(
            $googleResourceText,
            '(?m)^\s*resource\s+(0x[0-9a-f]{8})\s+\S*string/trailveil_google_maps_poc_api_key\b'
        ))
        if ($keyResourceMatches.Count -ne 1) {
            throw "The Google APK must define trailveil_google_maps_poc_api_key exactly once; found $($keyResourceMatches.Count)."
        }
        $keyResourceId = $keyResourceMatches[0].Groups[1].Value
        $googleManifestXml = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList @('manifest', 'print', $candidateGoogleApk) `
            -Description 'Google manifest provider audit') -join "`n"
        if ($googleManifestXml -notmatch 'com\.google\.android\.geo\.API_KEY') {
            throw 'Refusing to release the Google APK: it does not declare the Google Maps API-key marker.'
        }
        $keyMarkerMatch = [regex]::Match(
            $googleManifestXml,
            'android:name="com\.google\.android\.geo\.API_KEY"\s*android:value="@ref/(0x[0-9a-f]{8})"'
        )
        if (-not $keyMarkerMatch.Success -or $keyMarkerMatch.Groups[1].Value -ne $keyResourceId) {
            throw 'Refusing to release the Google APK: its API-key marker does not reference the key resource.'
        }
        if ($googleResourceText.Contains('TRAILVEIL_GOOGLE_MAPS_POC_MISSING_KEY')) {
            throw 'Refusing to release the Google APK: it carries the missing-key sentinel, so it was built without a key.'
        }
        $googleKeyShaped = @([regex]::Matches($googleResourceText, 'AIza[A-Za-z0-9_-]{35}'))
        if ($googleKeyShaped.Count -ne 1) {
            throw "The Google APK must compile exactly one key-shaped string; found $($googleKeyShaped.Count)."
        }
        # Which key, not just a key. Shape checks cannot tell the release key from any other valid
        # one, and publishing the wrong key means publishing a build that cannot authorize - or
        # someone else's key. The fingerprint is optional for a local build and mandatory here.
        $googleKeyPropertiesPath = if ($env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES) {
            $env:TRAILVEIL_GOOGLE_MAPS_PROPERTIES
        } else {
            Join-Path $HOME '.trailveil/maps/google-maps.properties'
        }
        if (-not (Test-Path -LiteralPath $googleKeyPropertiesPath -PathType Leaf)) {
            throw "The Google Maps properties file was not found at $googleKeyPropertiesPath."
        }
        $expectedKeyFingerprint = $null
        foreach ($propertyLine in [IO.File]::ReadAllLines($googleKeyPropertiesPath)) {
            if ($propertyLine -match '^\s*releaseApiKeySha256\s*=\s*([0-9a-fA-F]{64})\s*$') {
                $expectedKeyFingerprint = $Matches[1].ToLowerInvariant()
            }
        }
        if (-not $expectedKeyFingerprint) {
            throw ("Publishing a Google APK requires releaseApiKeySha256 in $googleKeyPropertiesPath " +
                '(the lowercase hex SHA-256 of the release key text), so the packaged key can be ' +
                'proven to be the intended one without ever being printed.')
        }
        # Scoped so the value leaves no variable behind, and compared only as a digest.
        $packagedKeyFingerprint = & {
            $packagedKeyBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($googleKeyShaped[0].Value)
            $sha256 = [System.Security.Cryptography.SHA256]::Create()
            try {
                return ($sha256.ComputeHash($packagedKeyBytes) | ForEach-Object { $_.ToString('x2') }) -join ''
            } finally {
                $sha256.Dispose()
            }
        }
        if ($packagedKeyFingerprint -ne $expectedKeyFingerprint) {
            throw 'The key packaged in the Google APK does not match the configured releaseApiKeySha256.'
        }
        # A MapLibre artifact inside the Google APK is the V02-008 defect this pair of source sets
        # was split to prevent, so it is asserted on the artifact rather than assumed from the split.
        if ($googleResourceText.Contains('maplibre_third_party_notices')) {
            throw 'Refusing to release the Google APK: it packages the MapLibre third-party notices.'
        }
        # V02-016. The absence above is only half of it: an APK that packages NEITHER provider's
        # notices would pass every check here while shipping a map with no attributions reachable
        # from the app at all. So the presence is asserted too, and pinned by digest the way the
        # MapLibre notice is, because a truncated or half-regenerated harvest is the failure this
        # cannot otherwise see. The text is Google's own, taken from the `third_party_licenses.txt`
        # and `.json` files each Play services AAR carries at its root; regenerate it and update
        # this digest together, never one without the other.
        $expectedGoogleNoticeDigest = '5feb0606e8600dc72bfddbcb834da6b472841925019b23327b0d928a4b38f223'
        $googleNoticeSource = Join-Path $repositoryRoot 'app/src/google/res/raw/google_third_party_notices.txt'
        $googleSourceNoticeDigest = Get-NormalizedTextSha256 -Text ([IO.File]::ReadAllText($googleNoticeSource))
        if ($googleSourceNoticeDigest -ne $expectedGoogleNoticeDigest) {
            throw "Google notice source digest $googleSourceNoticeDigest is not the pinned harvest."
        }
        $googleNoticeEntryMatch = [regex]::Match(
            $googleResourceText,
            'raw/google_third_party_notices[\s\S]*?\(file\)\s+(\S+)'
        )
        if (-not $googleNoticeEntryMatch.Success) {
            throw 'Refusing to release the Google APK: it does not package the Google third-party notices resource.'
        }
        $googleArchive = [System.IO.Compression.ZipFile]::OpenRead($candidateGoogleApk)
        try {
            $googleNoticeEntry = $googleArchive.GetEntry($googleNoticeEntryMatch.Groups[1].Value)
            if ($null -eq $googleNoticeEntry) {
                throw 'The compiled Google notice resource has no APK ZIP entry.'
            }
            $googleNoticeReader = [IO.StreamReader]::new($googleNoticeEntry.Open(), [Text.UTF8Encoding]::new($false))
            try {
                $packagedGoogleNotice = $googleNoticeReader.ReadToEnd()
            } finally {
                $googleNoticeReader.Dispose()
            }
        } finally {
            $googleArchive.Dispose()
        }
        $packagedGoogleNoticeDigest = Get-NormalizedTextSha256 -Text $packagedGoogleNotice
        if ($packagedGoogleNoticeDigest -ne $expectedGoogleNoticeDigest) {
            throw "Packaged Google notice digest $packagedGoogleNoticeDigest is incomplete or altered."
        }

        $googleDexPackages = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList @('dex', 'packages', $candidateGoogleApk) `
            -Description 'Google dex package audit')
        if (@($googleDexPackages | Where-Object { $_ -match '\bcom\.google\.android\.gms\.maps\b' }).Count -eq 0) {
            throw 'Refusing to release the Google APK: it does not package the Google Maps SDK.'
        }
        if (@($googleDexPackages | Where-Object { $_ -match '\borg\.maplibre\.android\b' }).Count -ne 0) {
            throw 'Refusing to release the Google APK: it packages MapLibre, so the two providers are not exclusive.'
        }
        # The engineering harness, by class and by component. Naming the two Activities is what this
        # can assert: the production Google surface legitimately lives in the `googlepoc` PACKAGE,
        # so a package-name scan here would refuse every correct build.
        foreach ($harnessClass in @(
            'app.trailveil.googlepoc.GoogleMapsPocActivity',
            'app.trailveil.map.GoogleMapSurfaceTestActivity'
        )) {
            if (@($googleDexPackages | Where-Object { $_ -match [regex]::Escape($harnessClass) }).Count -ne 0) {
                throw "Refusing to release the Google APK: it carries the engineering class $harnessClass."
            }
            if ($googleManifestXml -match [regex]::Escape($harnessClass)) {
                throw "Refusing to release the Google APK: it declares the engineering component $harnessClass."
            }
        }

        $googlePermissions = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList @('manifest', 'permissions', $candidateGoogleApk) `
            -Description 'Google permission audit') | Sort-Object -Unique
        if (@(Compare-Object $expectedPermissions $googlePermissions).Count -ne 0) {
            throw "Unexpected Google permissions: $($googlePermissions -join ', ')."
        }
        $googleFiles = @(Invoke-CheckedNative -FilePath $apkanalyzer `
            -ArgumentList @('files', 'list', $candidateGoogleApk) -Description 'Google file inventory')
        $googleAbis = $googleFiles | ForEach-Object {
            if ($_ -match '^/?lib/([^/]+)/') { $Matches[1] }
        } | Sort-Object -Unique
        if (@(Compare-Object $expectedAbis $googleAbis).Count -ne 0) {
            throw "Unexpected Google packaged ABIs: $($googleAbis -join ', ')."
        }
        if (@($googleFiles | Where-Object { $_ -match 'libmaplibre\.so$' }).Count -ne 0) {
            throw 'Refusing to release the Google APK: it packages the MapLibre native library.'
        }

        $googleVersion = "v$($googleManifestFacts.versionName)"
        $googleArtifactName = "TrailVeil-$googleVersion.apk"
        $googleArtifact = Join-Path $stagingDirectory $googleArtifactName
        Move-Item -LiteralPath $candidateGoogleApk -Destination $googleArtifact
        $googleHash = (Get-FileHash -LiteralPath $googleArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        $googleChecksumName = "$googleArtifactName.sha256"
        $googleCertificateName = "TrailVeil-$googleVersion-certificate.txt"
        $googleAuditName = "TrailVeil-$googleVersion-audit.txt"
        [IO.File]::WriteAllText(
            (Join-Path $stagingDirectory $googleChecksumName),
            "$googleHash  $googleArtifactName`n",
            $utf8WithoutBom
        )
        [IO.File]::WriteAllLines(
            (Join-Path $stagingDirectory $googleCertificateName),
            $googleCertificateReport,
            $utf8WithoutBom
        )
        # This file is published. It records that a key is present and how it is bound, never the
        # key, and never anything from which the key could be reconstructed.
        $googleAuditLines = @(
            "artifact=$googleArtifactName",
            "sha256=$googleHash",
            "certificateSha256=$googleCertificateDigest"
        ) + ($googleManifestFacts.Keys | ForEach-Object { "$_=$($googleManifestFacts[$_])" }) + @(
            "gitCommit=$head",
            'buildType=googleRelease',
            'signatureSchemeV2=true',
            'signerCount=1',
            "abis=$($googleAbis -join ',')",
            'provider=google',
            'mapLibrePackages=absent',
            'mapLibreNativeLibrary=absent',
            'googleMapsSdk=present',
            'engineeringComponents=absent',
            "apiKeyResource=present:$keyResourceId",
            'apiKeyMarkerReferencesKeyResource=true',
            'apiKeySentinel=absent',
            'apiKeyFingerprintVerified=true',
            # `V02-013` verifier, 2026-09-07: this used to read
            # `apiKeyRestriction=android-package-and-release-certificate`, published as a derived
            # fact beside facts that really are derived. Nothing here can see Google Cloud Console,
            # and the restriction is the key's ONLY protection once the APK is public - so stating
            # it as though it had been checked is the most misleading line the file could carry.
            # It is the operator's attestation, and it is labelled as one.
            'apiKeyRestrictionAttestedByOperator=android-package-and-release-certificate',
            'permissions:'
        ) + ($googlePermissions | ForEach-Object { "  $_" })
        [IO.File]::WriteAllLines(
            (Join-Path $stagingDirectory $googleAuditName),
            $googleAuditLines,
            $utf8WithoutBom
        )
        $publicNames += @($googleArtifactName, $googleChecksumName, $googleCertificateName, $googleAuditName)
        $publishedDigests[$googleArtifactName] = $googleHash
    }

    # TEMP and the repository may appear as different drives (for example C: and a subst T:).
    # Copy validated public files across that boundary, then write the ready marker LAST. Consumers
    # must treat a directory without the marker as incomplete; finally removes it on any failure.
    New-Item -ItemType Directory -Path $distributionDirectory | Out-Null
    foreach ($publicName in $publicNames) {
        Copy-Item -LiteralPath (Join-Path $stagingDirectory $publicName) `
            -Destination (Join-Path $distributionDirectory $publicName)
    }
    # Every APK is re-hashed where it will be uploaded from, not only the first one.
    foreach ($publishedName in $publishedDigests.Keys) {
        $publishedArtifact = Join-Path $distributionDirectory $publishedName
        $publishedHash = (Get-FileHash -LiteralPath $publishedArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($publishedHash -ne $publishedDigests[$publishedName]) {
            throw "Published digest $publishedHash for $publishedName does not match the validated staging digest $($publishedDigests[$publishedName])."
        }
    }
    $expectedPublicNames = @($publicNames) | Sort-Object
    $actualPublicNames = @(Get-ChildItem -LiteralPath $distributionDirectory -Force | ForEach-Object {
        if (-not $_.PSIsContainer) { $_.Name } else { "<directory>:$($_.Name)" }
    }) | Sort-Object
    if (@(Compare-Object $expectedPublicNames $actualPublicNames).Count -ne 0) {
        throw "Release directory does not contain exactly the $($expectedPublicNames.Count) audited public files: $($actualPublicNames -join ', ')."
    }
    $readyLines = @("commit=$head", "artifact=$artifactName", "sha256=$hash")
    if (-not $OpenFreeMapOnly) {
        $readyLines += @("googleArtifact=$googleArtifactName", "googleSha256=$googleHash")
    }
    [IO.File]::WriteAllText(
        (Join-Path $distributionDirectory 'RELEASE-READY.txt'),
        (($readyLines -join "`n") + "`n"),
        $utf8WithoutBom
    )
    $published = $true

    Write-Output "Release artifact: $(Join-Path $distributionDirectory $artifactName)"
    Write-Output "SHA-256: $hash"
    Write-Output "Certificate report: $(Join-Path $distributionDirectory $certificateName)"
    Write-Output "Audit report: $(Join-Path $distributionDirectory $auditName)"
    if (-not $OpenFreeMapOnly) {
        Write-Output "Google artifact: $(Join-Path $distributionDirectory $googleArtifactName)"
        Write-Output "Google SHA-256: $googleHash"
        Write-Output "Google certificate report: $(Join-Path $distributionDirectory $googleCertificateName)"
        Write-Output "Google audit report: $(Join-Path $distributionDirectory $googleAuditName)"
    }
} finally {
    if ($javaOptionsCleared) {
        $env:_JAVA_OPTIONS = $inheritedJavaOptions
    }
    if (-not $published -and (Test-Path -LiteralPath $distributionDirectory)) {
        $failedReadyMarker = Join-Path $distributionDirectory 'RELEASE-READY.txt'
        if (Test-Path -LiteralPath $failedReadyMarker) {
            Remove-Item -LiteralPath $failedReadyMarker -Force
        }
        Remove-Item -LiteralPath $distributionDirectory -Recurse -Force
    }
    if ($stagingDirectory -and (Test-Path -LiteralPath $stagingDirectory)) {
        Remove-Item -LiteralPath $stagingDirectory -Recurse -Force
    }
}
