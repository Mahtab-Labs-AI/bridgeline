import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- Version (brand-version brief section A) ------------------------------------------------
//
// major/minor/patch come from version.properties at the repo root (bumped by hand per release);
// BUILD is the commit count, auto-counted and identical locally and on CI. Read via
// providers.fileContents/ValueSource (not plain java.io at configuration time) so both stay
// configuration-cache compatible: Gradle reruns each provider only when the file or git's answer
// could have changed, rather than treating this script's configuration as always-stale.
abstract class GitCommitCountSource : org.gradle.api.provider.ValueSource<Int, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): Int = try {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            standardOutput = output
            isIgnoreExitValue = true
        }
        output.toString().trim().toIntOrNull() ?: 0
    } catch (e: Exception) {
        // No git on PATH, not a git checkout, etc: fall back rather than fail the build.
        0
    }
}

val versionPropsMap = providers.fileContents(
    rootProject.layout.projectDirectory.file("version.properties"),
).asText.orElse("major=0\nminor=0\npatch=0\n").map { text ->
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to value.trim()
        }
}
val gitBuildNumber: Int = providers.of(GitCommitCountSource::class) {}.get()
val verMajor = versionPropsMap.map { it["major"]?.toIntOrNull() ?: 0 }.get()
val verMinor = versionPropsMap.map { it["minor"]?.toIntOrNull() ?: 0 }.get()
val verPatch = versionPropsMap.map { it["patch"]?.toIntOrNull() ?: 0 }.get()
val appVersionName = "$verMajor.$verMinor.$verPatch.$gitBuildNumber"

// --- Laptop kit assets (design spec section 8a / plan Task 7) ----------------------------------
//
// connect-usb.ps1's source of truth stays in tools/usb-client/ (also used directly from there by
// a developer at the laptop's PowerShell prompt); connect-usb.cmd and README.txt have no other
// use, so they live next to this build file instead of under src/main. Both get copied into a
// generated assets directory, which LaptopKitBuilder reads at runtime (`assets.open("laptop-kit/...")`)
// to bundle them into the Download/WiFiProxy-LaptopKit kit alongside the downloaded platform-tools.
//
// Declared as plain Provider-based paths (no Project/Task reference captured by a task action), so
// this task is configuration-cache compatible.
val laptopKitAssetsDir = layout.buildDirectory.dir("generated/laptopKitAssets")

val copyLaptopKitAssets = tasks.register<Copy>("copyLaptopKitAssets") {
    group = "build"
    description = "Copies the laptop-kit scripts (connect-usb.ps1/.cmd, README.txt) into a generated assets dir."
    from(rootProject.layout.projectDirectory.dir("tools/usb-client")) {
        include("connect-usb.ps1")
    }
    from(project.layout.projectDirectory.dir("laptop-kit-static")) {
        include("connect-usb.cmd", "README.txt")
    }
    into(laptopKitAssetsDir.map { it.dir("laptop-kit") })
}

tasks.named("preBuild") {
    dependsOn(copyLaptopKitAssets)
}

android {
    namespace = "com.wifiproxy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wifiproxy.app"
        minSdk = 29
        targetSdk = 36
        // versionCode must only increase between releases: the commit count does, since commits
        // are never removed from develop/main history. Android requires versionCode >= 1, so the
        // (git-unavailable) fallback of 0 is floored here, not in appVersionName/BUILD itself.
        versionCode = gitBuildNumber.coerceAtLeast(1)
        versionName = appVersionName
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            all { it.useJUnitPlatform() }
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            assets.srcDir(laptopKitAssetsDir)
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

dependencies {
    implementation(project(":proxy-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    // Pure-JVM org.xmlpull.v1.XmlPullParser implementation, so LaptopKitBuilder's repository-index
    // parsing (which takes an XmlPullParser, not an Android type) can be unit-tested without
    // Android/Robolectric. Never shipped in the app itself (android.util.Xml.newPullParser()
    // supplies the real implementation there).
    testImplementation(libs.kxml2)
}
