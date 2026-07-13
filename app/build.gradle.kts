import groovy.json.JsonSlurper
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun String.asBuildConfigStringLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val releaseVersionCode = providers.gradleProperty("ZEST_VERSION_CODE")
    .map(String::toInt)
    .orElse(3)
val releaseVersionName = providers.gradleProperty("ZEST_VERSION_NAME")
    .orElse("1.0.2")
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val usdaBootstrapApiKeyB64 = localProperties
    .getProperty("ZEST_USDA_BOOTSTRAP_API_KEY_B64")
    .orEmpty()
    .trim()
val proxyBaseUrl = localProperties
    .getProperty("ZEST_PROXY_BASE_URL")
    .orEmpty()
    .trim()
    .ifBlank { "https://ultraprocessed-ai-proxy-894254677159.us-east1.run.app" }
val analysisDiagnosticsEnabled = localProperties
    .getProperty("ZEST_ANALYSIS_DIAGNOSTICS_ENABLED")
    .orEmpty()
    .trim()
    .toBoolean()
val releaseStoreFile = providers.environmentVariable("ZEST_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ZEST_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ZEST_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ZEST_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.b2.ultraprocessed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.b2.ultraprocessed"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode.get()
        versionName = releaseVersionName.get()
        buildConfigField(
            "String",
            "USDA_BOOTSTRAP_API_KEY_B64",
            usdaBootstrapApiKeyB64.asBuildConfigStringLiteral(),
        )
        buildConfigField(
            "String",
            "PROXY_BASE_URL",
            proxyBaseUrl.asBuildConfigStringLiteral(),
        )
        buildConfigField(
            "boolean",
            "ANALYSIS_DIAGNOSTICS_ENABLED",
            analysisDiagnosticsEnabled.toString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails release artifact generation if signing material is missing."
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is required. Set ZEST_RELEASE_STORE_FILE, " +
                    "ZEST_RELEASE_STORE_PASSWORD, ZEST_RELEASE_KEY_ALIAS, and " +
                    "ZEST_RELEASE_KEY_PASSWORD before producing release artifacts."
            )
        }
    }
}

val retiredSourcePaths = listOf(
    "src/main/java/com/b2/ultraprocessed/ui/DemoImageSamples.kt",
    "src/main/java/com/b2/ultraprocessed/ui/DemoSamplePickerDialog.kt",
    "src/main/java/com/b2/ultraprocessed/classify/RulesClassifier.kt",
    "src/main/java/com/b2/ultraprocessed/classify/OnDeviceLLMClassifier.kt",
    "src/main/java/com/b2/ultraprocessed/storage/datastore/AppSettings.kt",
    "src/main/java/com/b2/ultraprocessed/storage/datastore/package-info.md",
    "src/test/java/com/b2/ultraprocessed/classify/ClassifierOrchestratorTest.kt",
    "src/test/java/com/b2/ultraprocessed/classify/RulesClassifierTest.kt",
    "src/test/java/com/b2/ultraprocessed/classify/NovaIngredientSampleFixturesTest.kt",
)

val verifyNoRetiredSourceFiles = tasks.register("verifyNoRetiredSourceFiles") {
    group = "verification"
    description = "Fails before Kotlin/KSP if retired demo or legacy source files reappear."
    doLast {
        val existing = retiredSourcePaths
            .map { layout.projectDirectory.file(it).asFile }
            .filter { it.exists() }

        if (existing.isNotEmpty()) {
            throw GradleException(
                "Retired source files reappeared and must not ship or enter KSP:\n" +
                    existing.joinToString(separator = "\n") { " - ${it.relativeTo(projectDir)}" } +
                    "\nDelete these files; they are intentionally ignored in .gitignore."
            )
        }
    }
}

val verifyNoDatalessSources = tasks.register("verifyNoDatalessSources") {
    group = "verification"
    description = "Fails before Kotlin/KSP if macOS dataless placeholders exist under app/src."
    doLast {
        if (!System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
            return@doLast
        }

        val appSrc = layout.projectDirectory.dir("src").asFile
        val process = ProcessBuilder(
            "/usr/bin/find",
            appSrc.absolutePath,
            "-flags",
            "+dataless",
            "-print",
        )
            .redirectErrorStream(true)
            .start()
        val datalessFiles = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw GradleException("Unable to check for macOS dataless placeholders under app/src.")
        }

        if (datalessFiles.isNotEmpty()) {
            throw GradleException(
                "macOS dataless source placeholders detected under app/src. " +
                    "These can make Gradle/KSP hang while hashing sources:\n$datalessFiles\n" +
                    "Hydrate or delete the listed files before building."
            )
        }
    }
}

val verifySessionOnlyStorage = tasks.register("verifySessionOnlyStorage") {
    group = "verification"
    description = "Fails if archived Room/history code or active persistence wiring returns."
    doLast {
        val sourceRoot = layout.projectDirectory.dir("src/main").asFile
        val forbidden = listOf(
            "NovaDatabase",
            "ScanResultDao",
            "androidx.room",
            "HistoryScreen",
            "AppDestination.History",
            "insertScanResult",
            "getAllScanResults",
        )
        val violations = sourceRoot.walkTopDown()
            .filter {
                it.isFile &&
                    (it.extension == "kt" || it.extension == "xml") &&
                    !it.relativeTo(sourceRoot).path.startsWith("archive/")
            }
            .flatMap { file ->
                val contents = file.readText()
                forbidden.filter { token -> contents.contains(token) }.map { token ->
                    "${file.relativeTo(projectDir)} contains $token"
                }
            }
            .toList()
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Session-only storage policy violation:\n" + violations.joinToString("\n")
            )
        }
    }
}

val verifyModuleVersionManifest = tasks.register("verifyModuleVersionManifest") {
    group = "verification"
    description = "Fails if a Zest module is missing from the production version manifest or docs."
    doLast {
        val manifestFile = rootProject.file("module_versions.json")
        val versionLogFile = rootProject.file("VERSION_LOG.md")
        val documentationFile = rootProject.file("documentation/12-module-versioning.md")
        if (!manifestFile.isFile) {
            throw GradleException("Missing module version manifest: ${manifestFile.relativeTo(rootDir)}")
        }
        if (!versionLogFile.isFile) {
            throw GradleException("Missing repo version log: ${versionLogFile.relativeTo(rootDir)}")
        }
        if (!documentationFile.isFile) {
            throw GradleException(
                "Missing module version documentation: ${documentationFile.relativeTo(rootDir)}"
            )
        }

        val manifest = JsonSlurper().parse(manifestFile) as Map<*, *>
        val releaseVersion = manifest["releaseVersion"] as? String
            ?: throw GradleException("module_versions.json must define releaseVersion.")
        val versionPattern = Regex("""^\d+\.\d+\.\d+([+-][0-9A-Za-z.-]+)?$""")
        if (!versionPattern.matches(releaseVersion)) {
            throw GradleException("releaseVersion must be semantic versioning: $releaseVersion")
        }

        val modules = manifest["modules"] as? List<*>
            ?: throw GradleException("module_versions.json must define modules[].")
        val moduleMaps = modules.mapIndexed { index, item ->
            item as? Map<*, *> ?: throw GradleException("modules[$index] must be a JSON object.")
        }
        val moduleIds = mutableSetOf<String>()
        val docsText = documentationFile.readText()
        val versionLogText = versionLogFile.readText()
        if (!versionLogText.contains("## $releaseVersion")) {
            throw GradleException("VERSION_LOG.md is missing release entry: $releaseVersion")
        }

        fun moduleValue(module: Map<*, *>, key: String): String =
            module[key] as? String
                ?: throw GradleException("Every module must define string field '$key': $module")

        moduleMaps.forEach { module ->
            val id = moduleValue(module, "id")
            val kind = moduleValue(module, "kind")
            val path = moduleValue(module, "path")
            val version = moduleValue(module, "version")

            if (!moduleIds.add(id)) {
                throw GradleException("Duplicate module version entry: $id")
            }
            if (!versionPattern.matches(version)) {
                throw GradleException("Module $id has non-semver version: $version")
            }
            if (!docsText.contains("`$id`") || !docsText.contains("`$version`")) {
                throw GradleException(
                    "Module version documentation is missing module $id version $version."
                )
            }
            if (!versionLogText.contains(version)) {
                throw GradleException("VERSION_LOG.md is missing module version $version for $id.")
            }
            if (kind != "android-gradle-module" && path.startsWith(":")) {
                throw GradleException("Only android-gradle-module entries can use Gradle paths: $id")
            }
            if (!path.startsWith(":") && !rootProject.file(path).exists()) {
                throw GradleException("Module $id points to missing path: $path")
            }
        }

        val manifestGradlePaths = moduleMaps
            .filter { moduleValue(it, "kind") == "android-gradle-module" }
            .map { moduleValue(it, "path") }
            .toSet()
        val actualGradlePaths = rootProject.allprojects
            .map { it.path }
            .filter { it != ":" }
            .toSet()
        val missingGradleModules = actualGradlePaths - manifestGradlePaths
        if (missingGradleModules.isNotEmpty()) {
            throw GradleException(
                "Gradle modules missing from module_versions.json: " +
                    missingGradleModules.joinToString()
            )
        }

        val packageRoot = layout.projectDirectory
            .dir("src/main/java/com/b2/ultraprocessed")
            .asFile
        val actualAndroidPackagePaths = packageRoot
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .map { it.relativeTo(rootDir).invariantSeparatorsPath }
            .toSet()
        val manifestAndroidPackagePaths = moduleMaps
            .filter { moduleValue(it, "kind") == "android-package" }
            .map { moduleValue(it, "path") }
            .toSet()
        val missingAndroidPackages = actualAndroidPackagePaths - manifestAndroidPackagePaths
        val staleAndroidPackages = manifestAndroidPackagePaths - actualAndroidPackagePaths
        if (missingAndroidPackages.isNotEmpty() || staleAndroidPackages.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("Android package module version manifest mismatch.")
                    if (missingAndroidPackages.isNotEmpty()) {
                        append("\nMissing package entries: ")
                        append(missingAndroidPackages.joinToString())
                    }
                    if (staleAndroidPackages.isNotEmpty()) {
                        append("\nStale package entries: ")
                        append(staleAndroidPackages.joinToString())
                    }
                }
            )
        }
    }
}

val verifySourceTreeForBuild = tasks.register("verifySourceTreeForBuild") {
    group = "verification"
    description = "Guards the Android source tree from retired files and macOS dataless placeholders."
    dependsOn(
        verifyNoRetiredSourceFiles,
        verifyNoDatalessSources,
        verifySessionOnlyStorage,
        verifyModuleVersionManifest,
    )
}

tasks.named("preBuild") {
    dependsOn(verifySourceTreeForBuild)
}

tasks.matching {
    it.path == ":app:assembleRelease" ||
        it.path == ":app:bundleRelease" ||
        it.path == ":app:packageRelease"
}.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
// Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
// Security Crypto (Keystore)
    implementation("androidx.security:security-crypto:1.0.0")
}
