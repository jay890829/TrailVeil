import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val defaultInternalSigningPropertiesFile = File(
    System.getProperty("user.home"),
    ".trailveil/signing/internal-signing.properties",
)
val internalSigningPropertiesOverride = providers
    .environmentVariable("TRAILVEIL_INTERNAL_SIGNING_PROPERTIES")
    .orNull
val internalSigningPropertiesFile = internalSigningPropertiesOverride
    ?.let(::file)
    ?: defaultInternalSigningPropertiesFile
val internalSigningProperties = Properties().apply {
    if (internalSigningPropertiesFile.isFile) {
        internalSigningPropertiesFile.inputStream().use(::load)
    }
}
val internalStoreFile = internalSigningProperties
    .getProperty("storeFile")
    ?.takeIf(String::isNotBlank)
    ?.let { configuredPath ->
        File(configuredPath).let { configuredFile ->
            if (configuredFile.isAbsolute) {
                configuredFile
            } else {
                internalSigningPropertiesFile.parentFile.resolve(configuredFile)
            }
        }
    }
val repositoryRoot = rootProject.projectDir.canonicalFile

private val androidTestBuildTypeProperty = "trailveilAndroidTestBuildType"
private val androidTestBuildType = providers
    .gradleProperty(androidTestBuildTypeProperty)
    .orElse("debug")
    .get()
    .trim()
    .also { buildType ->
        require(buildType in setOf("debug", "googlePoc")) {
            "$androidTestBuildTypeProperty must be debug or googlePoc; received '$buildType'"
        }
    }

/**
 * Every build type that renders with MapLibre, and therefore the ONLY ones that may link it.
 *
 * `V02-008`: this single list decides both the source wiring and the dependency, so the two cannot
 * disagree. A build type left out of it does not silently link two map engines - it compiles with
 * no `TrailVeilMapSurface` actual at all, and says so. Google Maps Platform Terms 3.2.3(e) forbid
 * using the Maps SDK with a non-Google map, so this is a compliance boundary, not a size
 * optimisation: before this list scoped it, `implementation(libs.maplibre.opengl)` was unscoped and
 * the Google build packaged 4 `libmaplibre.so` and 1657 `Lorg/maplibre/` class descriptors.
 */
private val openFreeMapBuildTypes = listOf("debug", "internal", "release")

/**
 * Every build type that renders with the Maps SDK: the PoC harness the Google instrumentation
 * suites drive, and the release-configured variant a key holder builds and never publishes.
 */
private val googleBuildTypes = listOf("googlePoc", "googleRelease")

// The two lists are the boundary. A build type in both would link both map engines, which is
// the one arrangement Google Maps Platform Terms 3.2.3(e) forbids.
require(openFreeMapBuildTypes.intersect(googleBuildTypes.toSet()).isEmpty()) {
    "a build type cannot render with both map providers: " +
        openFreeMapBuildTypes.intersect(googleBuildTypes.toSet())
}

private val googlePocMissingKeySentinel = "TRAILVEIL_GOOGLE_MAPS_POC_MISSING_KEY"
private val googlePocKeyPattern = Regex("^AIza[A-Za-z0-9_-]{35}$")
private val googlePocKeyFingerprintPattern = Regex("^[a-f0-9]{64}$")
private val defaultGooglePocPropertiesFile = File(
    System.getProperty("user.home"),
    ".trailveil/maps/google-maps.properties",
)
private val googlePocPropertiesOverride = providers
    .environmentVariable("TRAILVEIL_GOOGLE_MAPS_PROPERTIES")
    .orNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private enum class GooglePocKeyBuildReason {
    VALID,
    MISSING_KEY,
    INVALID_KEY,
    CONFIG_PATH_NOT_ABSOLUTE,
    CONFIG_FILE_INSIDE_REPOSITORY,
    CONFIG_FILE_UNREADABLE,
}

private data class GooglePocKeyBuildConfiguration(
    val key: String?,
    val reason: GooglePocKeyBuildReason,
)

private fun readGooglePocKeyConfiguration(): GooglePocKeyBuildConfiguration {
    val configuredPath = googlePocPropertiesOverride
    if (configuredPath != null && !File(configuredPath).isAbsolute) {
        return GooglePocKeyBuildConfiguration(
            key = null,
            reason = GooglePocKeyBuildReason.CONFIG_PATH_NOT_ABSOLUTE,
        )
    }

    val propertiesFile = configuredPath?.let(::File) ?: defaultGooglePocPropertiesFile
    if (propertiesFile.isInsideRepository()) {
        return GooglePocKeyBuildConfiguration(
            key = null,
            reason = GooglePocKeyBuildReason.CONFIG_FILE_INSIDE_REPOSITORY,
        )
    }
    if (!propertiesFile.isFile) {
        return GooglePocKeyBuildConfiguration(
            key = null,
            reason = GooglePocKeyBuildReason.MISSING_KEY,
        )
    }

    return try {
        val properties = Properties().apply {
            propertiesFile.inputStream().use(::load)
        }
        val key = properties.getProperty("debugApiKey")?.trim()
        // An empty value is not a configured fingerprint. `Properties.load` returns "" for a
        // `debugApiKeySha256=` line, which the README's own template shows, so without this the
        // documented file would resolve INVALID_KEY and compile the sentinel over a good key.
        val expectedFingerprint = properties
            .getProperty("debugApiKeySha256")
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
        when {
            key.isNullOrEmpty() -> GooglePocKeyBuildConfiguration(
                key = null,
                reason = GooglePocKeyBuildReason.MISSING_KEY,
            )

            !googlePocKeyPattern.matches(key) -> GooglePocKeyBuildConfiguration(
                key = null,
                reason = GooglePocKeyBuildReason.INVALID_KEY,
            )

            // `V02-008`: the fingerprint is a typo self-check for whoever wrote the file, not a
            // second secret. Every builder supplies their own key, so requiring each of them to
            // hash it would be friction without a safety gain; it is enforced only when present.
            expectedFingerprint != null && (
                !googlePocKeyFingerprintPattern.matches(expectedFingerprint) ||
                    key.sha256Hex() != expectedFingerprint
                ) -> GooglePocKeyBuildConfiguration(
                key = null,
                reason = GooglePocKeyBuildReason.INVALID_KEY,
            )

            else -> GooglePocKeyBuildConfiguration(
                key = key,
                reason = GooglePocKeyBuildReason.VALID,
            )
        }
    } catch (_: Exception) {
        GooglePocKeyBuildConfiguration(
            key = null,
            reason = GooglePocKeyBuildReason.CONFIG_FILE_UNREADABLE,
        )
    }
}

private val googlePocKeyConfiguration = readGooglePocKeyConfiguration()

private fun String.sha256Hex(): String = MessageDigest
    .getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

/** The Google key inputs, identical on every Google build type. */
private fun com.android.build.api.dsl.VariantDimension.applyGoogleMapsKey() {
    buildConfigField(
        "boolean",
        "GOOGLE_MAPS_POC_KEY_CONFIGURED",
        (googlePocKeyConfiguration.reason == GooglePocKeyBuildReason.VALID).toString(),
    )
    buildConfigField(
        "String",
        "GOOGLE_MAPS_POC_KEY_REASON",
        googlePocKeyConfiguration.reason.name.toBuildConfigString(),
    )
    buildConfigField(
        "String",
        "GOOGLE_MAPS_POC_KEY_GUIDANCE",
        googlePocKeyGuidance(googlePocKeyConfiguration).toBuildConfigString(),
    )
    resValue(
        "string",
        "trailveil_google_maps_poc_api_key",
        googlePocKeyConfiguration.key ?: googlePocMissingKeySentinel,
    )
}

private fun String.toBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

private fun googlePocKeyGuidance(configuration: GooglePocKeyBuildConfiguration): String = when (
    configuration.reason
) {
    GooglePocKeyBuildReason.VALID -> ""
    GooglePocKeyBuildReason.MISSING_KEY ->
        "Add debugApiKey to ~/.trailveil/maps/google-maps.properties (debugApiKeySha256 is " +
            "optional), or set TRAILVEIL_GOOGLE_MAPS_PROPERTIES to an absolute external properties file."

    GooglePocKeyBuildReason.INVALID_KEY ->
        "debugApiKey is not a Google API key, or it does not match the debugApiKeySha256 you " +
            "configured. Fix the external file and rebuild the googlePoc variant."

    GooglePocKeyBuildReason.CONFIG_PATH_NOT_ABSOLUTE ->
        "TRAILVEIL_GOOGLE_MAPS_PROPERTIES must be an absolute path outside this repository."

    GooglePocKeyBuildReason.CONFIG_FILE_INSIDE_REPOSITORY ->
        "Move the Google Maps properties file outside this repository and rebuild the googlePoc variant."

    GooglePocKeyBuildReason.CONFIG_FILE_UNREADABLE ->
        "The external Google Maps properties file could not be read; check its permissions and rebuild."
}

/**
 * `P5-002`: the commit an installed build was made from, so a field report can name it.
 *
 * A version name alone cannot answer "which build is this?" during an internal test - the same
 * `0.1.0-internal` will be installed many times from different commits. This asks git, through
 * `providers.exec` so the answer participates in Gradle's configuration cache rather than being
 * read eagerly at configuration time.
 *
 * `unknown` is a real answer, not a failure: a source archive without `.git` still has to build.
 * The dirty marker matters more than it looks - an internal APK built from uncommitted work is
 * exactly the build whose field evidence cannot be reproduced later, so it says so on screen.
 */
val gitCommit: Provider<String> = providers.exec {
    workingDir = repositoryRoot
    commandLine("git", "rev-parse", "--short=12", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.zip(
    providers.exec {
        workingDir = repositoryRoot
        commandLine("git", "status", "--porcelain")
        isIgnoreExitValue = true
    }.standardOutput.asText,
) { head, status ->
    val sha = head.trim().ifEmpty { "unknown" }
    if (sha != "unknown" && status.isNotBlank()) "$sha-dirty" else sha
}

fun File.isInsideRepository(): Boolean {
    var candidate: File? = canonicalFile
    while (candidate != null) {
        val sameAsRepository = try {
            Files.isSameFile(candidate.toPath(), repositoryRoot.toPath())
        } catch (_: Exception) {
            false
        }
        if (sameAsRepository) {
            return true
        }
        candidate = candidate.parentFile
    }
    return false
}

fun missingInternalSigningConfiguration(): List<String> = buildList {
    if (internalSigningPropertiesOverride != null && !File(internalSigningPropertiesOverride).isAbsolute) {
        add("TRAILVEIL_INTERNAL_SIGNING_PROPERTIES must be an absolute path")
    }
    if (!internalSigningPropertiesFile.isFile) {
        add("properties file ${internalSigningPropertiesFile.absolutePath}")
        return@buildList
    }
    if (internalSigningPropertiesFile.isInsideRepository()) {
        add("properties file must be outside the repository: ${internalSigningPropertiesFile.absolutePath}")
    }

    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach { key ->
        if (internalSigningProperties.getProperty(key).isNullOrBlank()) {
            add("property $key")
        }
    }
    if (internalStoreFile != null) {
        if (!internalStoreFile.isFile) {
            add("keystore file ${internalStoreFile.absolutePath}")
        } else if (internalStoreFile.isInsideRepository()) {
            add("keystore file must be outside the repository: ${internalStoreFile.absolutePath}")
        }
    }
}

fun requireInternalSigningConfiguration() {
    val missing = missingInternalSigningConfiguration()
    if (missing.isNotEmpty()) {
        throw GradleException(
            """
            Internal signing is not configured.
            Missing: ${missing.joinToString()}.
            Create ${defaultInternalSigningPropertiesFile.absolutePath} or set
            TRAILVEIL_INTERNAL_SIGNING_PROPERTIES to an absolute external properties file.
            Required keys: storeFile, storePassword, keyAlias, keyPassword.
            Keep the properties file and keystore outside this repository.
            See README.md#app-signing.
            """.trimIndent(),
        )
    }
}

val signedDistributionTaskNames = buildSet {
    listOf("Internal", "Release").forEach { variant ->
        add("assemble$variant")
        add("bundle$variant")
        add("install$variant")
        add("package$variant")
        add("package${variant}Bundle")
        add("package${variant}UniversalApk")
        add("sign${variant}Bundle")
        add("signingConfigWriter$variant")
        add("validateSigning$variant")
    }
}

fun requiresDistributionSigning(taskName: String): Boolean =
    signedDistributionTaskNames.any { candidate -> candidate.equals(taskName, ignoreCase = true) }

val explicitlyRequestsSignedDistribution = gradle.startParameter.taskNames.any { requestedTask ->
    requiresDistributionSigning(requestedTask.substringAfterLast(':'))
}
if (explicitlyRequestsSignedDistribution) {
    requireInternalSigningConfiguration()
}

tasks.configureEach {
    if (requiresDistributionSigning(name)) {
        doFirst {
            requireInternalSigningConfiguration()
        }
    }
}

android {
    namespace = "app.trailveil"
    compileSdk = 37
    // Keep the ordinary instrumentation graph on debug. The Google PoC test source set is only
    // selected when an operator explicitly opts into the isolated googlePoc test variant.
    testBuildType = androidTestBuildType

    defaultConfig {
        applicationId = "app.trailveil"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // P5-002. Read on screen by the About row; also the only way a field report can name the
        // exact tree an installed internal APK came from.
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("internal") {
            if (missingInternalSigningConfiguration().isEmpty()) {
                storeFile = requireNotNull(internalStoreFile)
                storePassword = internalSigningProperties.getProperty("storePassword")
                keyAlias = internalSigningProperties.getProperty("keyAlias")
                keyPassword = internalSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // GitHub distributes this APK directly, so Android never re-signs it for us. Reuse the
            // fixed certificate already installed by internal field builds: a different key would
            // force an uninstall, which permanently deletes TrailVeil's non-backed-up history.
            signingConfig = signingConfigs.getByName("internal")
            isDebuggable = false
            isMinifyEnabled = false
        }
        create("internal") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("internal")
            versionNameSuffix = "-internal"
            matchingFallbacks += listOf("debug")
        }
        create("googlePoc") {
            initWith(getByName("debug"))
            versionNameSuffix = "-googlePoc"
            matchingFallbacks += listOf("debug")
            applyGoogleMapsKey()
        }
        // `V02-008`: the Google variant a key holder builds. Release-configured - not debuggable,
        // signed by whatever key its builder configured - and never published as a prebuilt APK,
        // because the key it compiles in is uncompressed in `resources.arsc`. It reads the key by
        // exactly the same path as the PoC build type, including the missing-key sentinel, so a
        // key-less build of it fails closed onto the provider-unavailable surface rather than
        // failing to build.
        create("googleRelease") {
            initWith(getByName("release"))
            versionNameSuffix = "-google"
            matchingFallbacks += listOf("release")
            applyGoogleMapsKey()
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        // `V02-005` stage 1: the MapLibre map surface lives in its own source tree so the
        // googlePoc variant can bind a Google implementation of the same neutral contract.
        // debug/internal/release compile exactly the sources they always did.
        openFreeMapBuildTypes.forEach { variant ->
            getByName(variant).kotlin.srcDir("src/mapLibre/java")
            getByName(variant).res.srcDir("src/mapLibre/res")
            // `V02-008`: the notices resource and the meta-data that points at it are MapLibre's,
            // so they merge in from this provider's own manifest rather than from `src/main`.
            // These build types declare no manifest of their own, so this is purely additive.
            getByName(variant).manifest.srcFile("src/mapLibre/AndroidManifest.xml")
            // Unit tests that name this provider, for the same reason.
            getByName("test${variant.replaceFirstChar(Char::uppercase)}")
                .kotlin.srcDir("src/testMapLibre/java")
        }
        // `V02-008`: the Google variants share one source tree and one manifest overlay. The
        // release-configured one is a second build type rather than a copy of the sources, so the
        // build that ships and the build the instrumentation suites drive cannot drift apart.
        googleBuildTypes.forEach { variant ->
            getByName(variant).kotlin.srcDir("src/googlePoc/java")
            getByName(variant).res.srcDir("src/googlePoc/res")
            getByName(variant).manifest.srcFile("src/googlePoc/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkTestSources = true
        warningsAsErrors = true
        disable += setOf(
            // Deliberate pins and target choice from docs/ANDROID_BASELINE.md. GradleDependency
            // joined the list when a Compose BOM release broke CI with zero code change: with
            // warningsAsErrors, "a newer version exists" turns every upstream publish into a
            // build failure, while upgrades here are a deliberate baseline decision, not lint's.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable",
            "OldTargetApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val mergedManifestFiles = mapOf(
    "debug" to layout.buildDirectory.file(
        "intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
    ),
    "internal" to layout.buildDirectory.file(
        "intermediates/merged_manifests/internal/processInternalManifest/AndroidManifest.xml",
    ),
    "release" to layout.buildDirectory.file(
        "intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
    ),
    "googlePoc" to layout.buildDirectory.file(
        "intermediates/merged_manifests/googlePoc/processGooglePocManifest/AndroidManifest.xml",
    ),
    "googleRelease" to layout.buildDirectory.file(
        "intermediates/merged_manifests/googleRelease/processGoogleReleaseManifest/AndroidManifest.xml",
    ),
)
// The key's one sink. The manifest keeps the `@string` reference; this generated resource holds
// the value aapt links into resources.arsc, so it is the file that proves what a build resolved.
/**
 * The key's one sink, per Google build type. The manifest keeps the `@string` reference; this
 * generated resource holds the value aapt links into `resources.arsc`, so it is the file that
 * proves what a build resolved.
 */
fun googleKeyResValues(variant: String) = layout.buildDirectory.file(
    "generated/res/resValues/$variant/values/gradleResValues.xml",
)
val verifyGooglePocMergedManifest = tasks.register("verifyGooglePocMergedManifest") {
    group = "verification"
    description = "Proves Google Maps markers exist only in the Google variants' manifests"
    dependsOn(
        openFreeMapBuildTypes.map { "process${it.replaceFirstChar(Char::uppercase)}Manifest" } +
            googleBuildTypes.flatMap {
                val capitalised = it.replaceFirstChar(Char::uppercase)
                listOf("process${capitalised}Manifest", "generate${capitalised}ResValues")
            },
    )
    inputs.files(mergedManifestFiles.values)
    inputs.files(googleBuildTypes.map(::googleKeyResValues))
    outputs.upToDateWhen { false }
    val keyReason = googlePocKeyConfiguration.reason
    doLast {
        googleBuildTypes.forEach { googleVariant ->
            val googleManifest = mergedManifestFiles.getValue(googleVariant).get().asFile.readText()
            check("com.google.android.geo.API_KEY" in googleManifest) {
                "$googleVariant merged manifest is missing the Google Maps API-key marker"
            }
            check("android:value=\"@string/trailveil_google_maps_poc_api_key\"" in googleManifest) {
                "$googleVariant merged manifest does not bind the key marker to the key resource"
            }
            // `V02-008` (c): assert the VALUE the build resolved, so a keyless build is proven
            // keyless rather than assumed keyless because the runner happened to hold no key. The
            // sentinel must be there when no valid key was configured, and a key-shaped,
            // non-sentinel value when one was. The value itself is never printed, on either branch.
            val resValues = googleKeyResValues(googleVariant).get().asFile.readText()
            val keyResource = Regex(
                """<string\b[^>]*\bname="trailveil_google_maps_poc_api_key"[^>]*>([^<]*)</string>""",
            ).find(resValues)
            checkNotNull(keyResource) {
                "$googleVariant resValues do not define trailveil_google_maps_poc_api_key"
            }
            val resolvedValue = keyResource.groupValues[1]
            // The reason is safe to print and worth printing: a CI log then shows that the keyless
            // job really resolved MISSING_KEY. The value never is, on any branch.
            logger.lifecycle(
                "$googleVariant key resolved as ${keyReason.name} " +
                    (if (resolvedValue == googlePocMissingKeySentinel) "(sentinel)" else "(key-shaped)"),
            )
            if (keyReason == GooglePocKeyBuildReason.VALID) {
                check(
                    resolvedValue != googlePocMissingKeySentinel &&
                        googlePocKeyPattern.matches(resolvedValue),
                ) {
                    "$googleVariant key resource does not hold a key although the reason is VALID"
                }
            } else {
                check(resolvedValue == googlePocMissingKeySentinel) {
                    "$googleVariant key resource is not the missing-key sentinel although the key " +
                        "reason is ${keyReason.name}"
                }
            }
            check("org.apache.http.legacy" in googleManifest) {
                "$googleVariant merged manifest is missing the Maps SDK 20 legacy-renderer shim"
            }
            // An <activity> either self-closes its opening tag or runs to </activity>; matching the
            // first "/>" unconditionally would truncate a block at its first self-closing CHILD
            // (<action .../>) and lose the intent-filter this verifier exists to check.
            val activityBlocks = Regex("""<activity\b[^>]*?(?:/>|>[\s\S]*?</activity>)""")
                .findAll(googleManifest).map(MatchResult::value).toList()
            // `V02-005` stage 2 inverted this block: the production MainActivity IS the Google
            // launcher now, and the PoC Activity must remain present but never launchable.
            val mainActivity = activityBlocks.singleOrNull { "app.trailveil.MainActivity" in it }
            checkNotNull(mainActivity) {
                "$googleVariant merged manifest is missing the production MainActivity"
            }
            check("android.intent.category.LAUNCHER" in mainActivity) {
                "$googleVariant merged manifest does not make MainActivity the launcher"
            }
            val pocActivity = activityBlocks.singleOrNull {
                "app.trailveil.googlepoc.GoogleMapsPocActivity" in it
            }
            checkNotNull(pocActivity) {
                "$googleVariant merged manifest lost the retained PoC engineering harness Activity"
            }
            check("android.intent.category.LAUNCHER" !in pocActivity) {
                "the de-launchered PoC Activity regained a launcher intent-filter in $googleVariant"
            }
            check("android:exported=\"false\"" in pocActivity) {
                "the retained PoC Activity must stay unexported in $googleVariant"
            }
            check("app.trailveil.MAPLIBRE_THIRD_PARTY_NOTICES" !in googleManifest) {
                "$googleVariant merged manifest unexpectedly exposes the other provider's notices"
            }
            check("app.trailveil.map.GoogleMapWarmup" in googleManifest) {
                "$googleVariant merged manifest is missing the map warmup initializer"
            }
        }

        mergedManifestFiles
            .filterKeys { variant -> variant !in googleBuildTypes }
            .forEach { (variant, manifestProvider) ->
                val manifest = manifestProvider.get().asFile.readText()
                check("com.google.android.geo.API_KEY" !in manifest) {
                    "$variant merged manifest leaked the Google Maps API-key marker"
                }
                check("app.trailveil.googlepoc.GoogleMapsPocActivity" !in manifest) {
                    "$variant merged manifest leaked the Google PoC launcher"
                }
                check("org.apache.http.legacy" !in manifest) {
                    "$variant merged manifest leaked the Google PoC legacy-renderer shim"
                }
                check("app.trailveil.MainActivity" in manifest) {
                    "$variant merged manifest lost the production launcher"
                }
                check("app.trailveil.MAPLIBRE_THIRD_PARTY_NOTICES" in manifest) {
                    "$variant merged manifest lost the production MapLibre notices"
                }
            }
    }
}

tasks.configureEach {
    // Every Google variant is gated, not just the PoC one: the release-configured build is
    // the one that would carry a real key to a stranger.
    if (name in googleBuildTypes.map { "assemble${it.replaceFirstChar(Char::uppercase)}" }) {
        dependsOn(verifyGooglePocMergedManifest)
    }
}

val instrumentationTestManifest = layout.projectDirectory.file(
    "src/androidTest/instrumentation-test-manifest.txt",
)
val connectedDebugAndroidTestResults = layout.buildDirectory.dir(
    "outputs/androidTest-results/connected/debug",
)
val instrumentationRunnerArgumentPrefix = "android.testInstrumentationRunnerArguments."
val instrumentationSelectionArgumentNames = setOf(
    "annotation",
    "class",
    "notAnnotation",
    "notClass",
    "notPackage",
    "notTestFile",
    "numShards",
    "package",
    "shardIndex",
    "size",
    "testFile",
    "tests_regex",
)
val activeInstrumentationSelectionArguments = gradle.startParameter.projectProperties.keys
    .mapNotNull { key -> key.takeIf { it.startsWith(instrumentationRunnerArgumentPrefix) } }
    .map { key -> key.removePrefix(instrumentationRunnerArgumentPrefix) }
    .filter { name -> name in instrumentationSelectionArgumentNames }
    .toSet()
var connectedDebugRunStartedAtMillis: Long? = null
// P4-039: the JVM drift test reads the instrumentation manifest and the androidTest sources at
// runtime. Declared as inputs so the unit-test task re-runs when they change - without this, adding
// an undeclared @Test left the task UP-TO-DATE and the detector silently never looked.
tasks.withType<Test>().configureEach {
    inputs.file("$projectDir/src/androidTest/instrumentation-test-manifest.txt")
        .withPropertyName("instrumentationTestManifestForDriftCheck")
    inputs.dir("$projectDir/src/androidTest/java")
        .withPropertyName("androidTestSourcesForDriftCheck")
    inputs.dir("$projectDir/src/androidTestDebug/java")
        .withPropertyName("androidTestDebugSourcesForDriftCheck")
    inputs.file("$projectDir/src/androidTestGooglePoc/instrumentation-test-manifest.txt")
        .withPropertyName("googleInstrumentationTestManifestForDriftCheck")
    inputs.dir("$projectDir/src/androidTestGooglePoc/java")
        .withPropertyName("googleInstrumentationSourcesForDriftCheck")
}

val verifyDebugAndroidTestManifest = tasks.register("verifyDebugAndroidTestManifest") {
    group = "verification"
    description = "Fails when the unfiltered debug instrumentation run omits or adds a declared test"
    inputs.file(instrumentationTestManifest)
    outputs.upToDateWhen { false }
    onlyIf {
        if (activeInstrumentationSelectionArguments.isNotEmpty()) {
            logger.lifecycle(
                "Skipping the full instrumentation manifest check because these runner arguments " +
                    "select a subset: ${activeInstrumentationSelectionArguments.sorted()}.",
            )
        }
        activeInstrumentationSelectionArguments.isEmpty()
    }
    doLast {
        val runStartedAtMillis = checkNotNull(connectedDebugRunStartedAtMillis) {
            "The instrumentation manifest must verify results from connectedDebugAndroidTest " +
                "in the same Gradle invocation; standalone or stale XML is not accepted"
        }
        val expectedEntries = instrumentationTestManifest.asFile.readLines()
            .map(String::trim)
            .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
        val expected = expectedEntries.toSet()
        check(expected.isNotEmpty()) { "The instrumentation test manifest is empty" }
        check(expected.size == expectedEntries.size) {
            "The instrumentation test manifest contains duplicate case entries"
        }

        val resultRoot = connectedDebugAndroidTestResults.get().asFile
        val resultFiles = resultRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
            .toList()
        check(resultFiles.isNotEmpty()) {
            "No connected debug instrumentation XML results were found under ${resultRoot.absolutePath}"
        }
        val staleResultFiles = resultFiles.filter { resultFile ->
            resultFile.lastModified() < runStartedAtMillis
        }
        check(staleResultFiles.isEmpty()) {
            "Connected instrumentation XML predates this run: " +
                staleResultFiles.joinToString { file -> file.name }
        }

        val parserFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        resultFiles.forEach { resultFile ->
            val document = parserFactory.newDocumentBuilder().parse(resultFile)
            val cases = document.getElementsByTagName("testcase")
            val actual = linkedSetOf<String>()
            var resultCaseCount = 0
            repeat(cases.length) { index ->
                val case = cases.item(index)
                val attributes = case.attributes
                val className = attributes.getNamedItem("classname")?.nodeValue.orEmpty()
                val methodName = attributes.getNamedItem("name")?.nodeValue.orEmpty()
                if (className.isNotBlank() && methodName.isNotBlank()) {
                    resultCaseCount += 1
                    actual += "$className#$methodName"
                }
            }

            val missing = (expected - actual).sorted()
            val unexpected = (actual - expected).sorted()
            check(
                missing.isEmpty() &&
                    unexpected.isEmpty() &&
                    resultCaseCount == expected.size &&
                    actual.size == resultCaseCount,
            ) {
                buildString {
                    append(
                        "Connected instrumentation results in ${resultFile.name} do not match " +
                            "the declared manifest.",
                    )
                    append(
                        "\nExpected ${expected.size} unique cases; XML contained $resultCaseCount " +
                            "cases and ${actual.size} unique cases.",
                    )
                    if (missing.isNotEmpty()) {
                        append("\nMissing (${missing.size}):\n${missing.joinToString("\n")}")
                    }
                    if (unexpected.isNotEmpty()) {
                        append("\nUnexpected (${unexpected.size}):\n${unexpected.joinToString("\n")}")
                    }
                }
            }
        }
        logger.lifecycle(
            "Verified ${expected.size} connected debug instrumentation cases across " +
                "${expected.map { entry -> entry.substringBefore('#') }.toSet().size} classes " +
                "in ${resultFiles.size} device result file(s).",
        )
    }
}

tasks.configureEach {
    if (name == "connectedDebugAndroidTest") {
        doFirst {
            connectedDebugRunStartedAtMillis = System.currentTimeMillis()
        }
        finalizedBy(verifyDebugAndroidTestManifest)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.core)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    // `V02-008`: scoped, not shared. See [openFreeMapBuildTypes] for why this is a compliance
    // boundary rather than a size optimisation, and section 6 of the task evidence for the
    // measurement that found it unscoped.
    openFreeMapBuildTypes.forEach { variant -> add("${variant}Implementation", libs.maplibre.opengl) }
    // Room 2.8.4 migration bundles require serialization 1.8.1 at runtime.
    // Pin the tested app so its parent class loader cannot supply the older 1.7.3 API.
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    googleBuildTypes.forEach { variant ->
        add("${variant}Implementation", "com.google.android.gms:play-services-maps:20.0.0")
        add("${variant}Implementation", "androidx.startup:startup-runtime:1.2.0")
    }
}
