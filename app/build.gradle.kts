import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is configured EXTERNALLY via an untracked, gitignored
// `keystore.properties` file in the project root (see keystore.properties.example).
// If the file is absent or incomplete, the release build is left unsigned —
// it never silently falls back to the development debug keystore.
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { stream -> load(stream) }
    }
}

fun zeaHasReleaseSigning(props: Properties): Boolean =
    props["storeFile"] != null &&
            props["storePassword"] != null &&
            props["keyAlias"] != null &&
            props["keyPassword"] != null

android {
    namespace = "com.raomuhammadnoman.zea"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raomuhammadnoman.zea"
        minSdk = 26
        targetSdk = 36
    versionCode = 108
    versionName = "1.39-phase1-stability"
    }

    signingConfigs {
        if (zeaHasReleaseSigning(keystoreProps)) {
            create("externalRelease") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Signed ONLY when an external keystore.properties supplies all
            // four fields. Otherwise the release APK stays unsigned — an
            // honest failure, not a debug-keystore masquerading as release.
            if (zeaHasReleaseSigning(keystoreProps)) {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
            // Phase 2 (P1): the developer surface lives only in the debug
            // source set; release compiles a no-op stub instead.
        }
        getByName("debug") {
            // Debug builds use the standard development debug signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
