import java.io.File
import java.nio.file.Files
import java.util.Properties

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
    namespace = "io.github.jay890829.trailveil"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jay890829.trailveil"
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
        create("mapLibreInternal") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("internal")
            versionNameSuffix = "-maplibre-internal"
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
    "mapLibreInternalImplementation"(libs.maplibre.opengl)
}
