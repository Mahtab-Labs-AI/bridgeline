import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "0.1-mockup"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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
