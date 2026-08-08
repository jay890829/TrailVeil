import java.io.File
import java.nio.file.Files
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

fun File.isInsideRepository(): Boolean {
    var candidate: File? = canonicalFile
    while (candidate != null) {
        if (Files.isSameFile(candidate.toPath(), repositoryRoot.toPath())) {
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
            See README.md#internal-signing.
            """.trimIndent(),
        )
    }
}

val explicitlyRequestsInternal = gradle.startParameter.taskNames.any { requestedTask ->
    requestedTask.substringAfterLast(':').contains("Internal", ignoreCase = true)
}
if (explicitlyRequestsInternal) {
    requireInternalSigningConfiguration()
}

tasks.configureEach {
    if (name.contains("Internal", ignoreCase = true)) {
        doFirst {
            requireInternalSigningConfiguration()
        }
    }
}

android {
    namespace = "app.trailveil"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.trailveil"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

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
        create("internal") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("internal")
            versionNameSuffix = "-internal"
            matchingFallbacks += listOf("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
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
            // Deliberate pins and target choice from docs/ANDROID_BASELINE.md.
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            "OldTargetApi",
            // Assigned before sensitive storage and production branding land.
            "MissingApplicationIcon",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.maplibre.opengl)
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
}
