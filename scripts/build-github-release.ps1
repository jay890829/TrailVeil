param(
    [string]$AsciiStagingRoot = $env:TEMP
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
    & $gradle -p $repositoryRoot clean assembleRelease assembleInternal lintRelease testDebugUnitTest
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
        versionName = '0.1.0'
        versionCode = '1'
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
    if ($internalVersionName.Count -ne 1 -or $internalVersionName[0].Trim() -ne '0.1.0-internal') {
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
        '.field public static final VERSION_CODE:I = 0x1',
        '.field public static final VERSION_NAME:Ljava/lang/String; = "0.1.0"'
    )
    foreach ($field in $expectedBuildConfigFields) {
        if ($field -notin $buildConfig) {
            throw "Release APK BuildConfig is missing exact field: $field"
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

    $expectedNoticeDigest = 'db3cc41e2c79f394a1dddd890c55c263426175029a898d5167820498ddebf152'
    $noticeSource = Join-Path $repositoryRoot 'app/src/main/res/raw/maplibre_third_party_notices.txt'
    $sourceNoticeDigest = Get-NormalizedTextSha256 -Text ([IO.File]::ReadAllText($noticeSource))
    if ($sourceNoticeDigest -ne $expectedNoticeDigest) {
        throw "MapLibre notice source digest $sourceNoticeDigest is not the pinned android-v13.4.1 notice."
    }
    $resources = @(Invoke-CheckedNative -FilePath $aapt2 `
        -ArgumentList @('dump', 'resources', $candidateApk) -Description 'Release resource audit')
    $resourceText = $resources -join "`n"
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
        "internalApplicationId=$($internalApplicationId[0].Trim())",
        "internalVersionName=$($internalVersionName[0].Trim())",
        "internalVersionCode=$($internalVersionCode[0].Trim())",
        "abis=$($abis -join ',')",
        "maplibreAndroidNoticeSha256=$packagedNoticeDigest",
        'permissions:'
    ) + ($permissions | ForEach-Object { "  $_" })
    [IO.File]::WriteAllLines(
        (Join-Path $stagingDirectory $auditName),
        $auditLines,
        $utf8WithoutBom
    )
    Remove-Item -LiteralPath $candidateInternalApk -Force

    # TEMP and the repository may appear as different drives (for example C: and a subst T:).
    # Copy validated public files across that boundary, then write the ready marker LAST. Consumers
    # must treat a directory without the marker as incomplete; finally removes it on any failure.
    New-Item -ItemType Directory -Path $distributionDirectory | Out-Null
    foreach ($publicName in $artifactName, $checksumName, $certificateName, $auditName) {
        Copy-Item -LiteralPath (Join-Path $stagingDirectory $publicName) `
            -Destination (Join-Path $distributionDirectory $publicName)
    }
    $publishedArtifact = Join-Path $distributionDirectory $artifactName
    $publishedHash = (Get-FileHash -LiteralPath $publishedArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($publishedHash -ne $hash) {
        throw "Published APK digest $publishedHash does not match the validated staging digest $hash."
    }
    $expectedPublicNames = @($artifactName, $checksumName, $certificateName, $auditName) | Sort-Object
    $actualPublicNames = @(Get-ChildItem -LiteralPath $distributionDirectory -Force | ForEach-Object {
        if (-not $_.PSIsContainer) { $_.Name } else { "<directory>:$($_.Name)" }
    }) | Sort-Object
    if (@(Compare-Object $expectedPublicNames $actualPublicNames).Count -ne 0) {
        throw "Release directory does not contain exactly the four audited public files: $($actualPublicNames -join ', ')."
    }
    [IO.File]::WriteAllText(
        (Join-Path $distributionDirectory 'RELEASE-READY.txt'),
        "commit=$head`nartifact=$artifactName`nsha256=$hash`n",
        $utf8WithoutBom
    )
    $published = $true

    Write-Output "Release artifact: $(Join-Path $distributionDirectory $artifactName)"
    Write-Output "SHA-256: $hash"
    Write-Output "Certificate report: $(Join-Path $distributionDirectory $certificateName)"
    Write-Output "Audit report: $(Join-Path $distributionDirectory $auditName)"
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
