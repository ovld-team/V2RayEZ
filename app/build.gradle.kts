import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Firebase light: Crashlytics + Analytics only (see google-services.json in this module).
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics.plugin)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Sentry DSN: never hardcoded in committed source. Local dev reads `sentry.dsn` from the
 * gitignored `local.properties`; CI passes `-Psentry.dsn=...` instead. Defaults to empty —
 * [com.v2rayez.app.data.analytics.RemoteTelemetry] treats a blank DSN as "stay a no-op".
 */
val sentryDsn: String = (project.findProperty("sentry.dsn") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("sentry.dsn")?.takeIf { it.isNotBlank() }
    ?: ""

android {
    namespace = "com.v2rayez.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.v2rayez.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 80
        versionName = "0.9.80"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Configurable links sourced from gradle.properties (build-time "env").
        fun linkProp(key: String, default: String): String =
            (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() } ?: default
        buildConfigField("String", "LINK_WEBSITE", "\"${linkProp("v2rayez.link.website", "https://v2rayez.app")}\"")
        buildConfigField("String", "LINK_TELEGRAM", "\"${linkProp("v2rayez.link.telegram", "https://t.me/EzAccess1")}\"")
        buildConfigField("String", "LINK_YOUTUBE", "\"${linkProp("v2rayez.link.youtube", "https://youtube.com/@MacanDev")}\"")
        buildConfigField(
            "String",
            "ADDONS_GITHUB_REPO",
            "\"${linkProp("v2rayez.addons.githubRepo", "ovld-team/V2RayEZ")}\""
        )
        buildConfigField(
            "String",
            "ADDONS_RELEASE_TAG",
            "\"${linkProp("v2rayez.addons.releaseTag", "V2RayEZ-v0.9.80-rc-1")}\""
        )
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // Ship English + Persian + Russian; strip every other locale (incl. library ones).
        resourceConfigurations += listOf("en", "fa", "ru")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Per-ABI APKs keep packaging under the heap limit with huge core binaries.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Stability over size: plain (non-"optimize") default rules + -dontoptimize below
            // avoid R8's aggressive optimization passes that have caused release-only crashes
            // with the vendored gomobile core + Hilt reflection. Shrinking (unused-class removal)
            // still runs so dead code is dropped; resource shrinking stays off to avoid stripping
            // anything Firebase Crashlytics/Analytics or Compose resolve by name at runtime.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // No Robolectric in this project — let android.util.Log etc. return defaults
            // instead of throwing, so plain-JVM tests can exercise code that logs.
            isReturnDefaultValues = true
        }
    }
    androidResources {
        // v0.9.50 size wave: drop the full geo databases (~28 MB) from the APK — they are
        // baked into libv2ray.aar's assets too, and this pattern filters the merged asset
        // set from ALL sources. GeoAssetManager downloads them on demand into filesDir/assets;
        // the AAR's tiny geoip-only-cn-private.dat stays packaged as the offline fallback so
        // geoip:private / geoip:cn routing works out of the box.
        ignoreAssetsPatterns += listOf("!geoip.dat", "!geosite.dat")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle's bcprov/bcutil/bcpkix jars all ship duplicate META-INF license/notice
            // files under the same path; only the class files matter for packaging.
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            useLegacyPackaging = true
            // v0.9.71: bundle Xray (AAR) + Tor C core + lyrebird (obfs4) + hev (TUN bridge).
            // Other PTs / ByeDPI / sing-box / mihomo stay on-demand via Core manager.
            // Source .so remain in jniLibs for scripts/pack-addons.sh Release zips.
            excludes += listOf(
                "**/libsnowflake.so",
                "**/libwebtunnel.so",
                "**/libbyedpi.so",
                "**/libsingbox.so",
                "**/libmihomo.so"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Coroutines & serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking & QR
    implementation(libs.okhttp)
    // ZXing kept for QR *generation* only (sharing). Scanning uses CameraX + ML Kit below.
    implementation(libs.zxing.core)

    // Dedicated QR scanner: CameraX pipeline + ML Kit decode (QR format only)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Coin logos loaded from a CDN (PNG)
    implementation(libs.coil.compose)

    // WebView ProxyController (MITM Browser proxy override)
    implementation("androidx.webkit:webkit:1.12.1")

    // Vendored Xray core (Go-mobile AAR with in-core TUN handler)
    implementation(files("libs/libv2ray.aar"))

    // MITM CA generation (self-signed cert extensions unavailable on plain android.* APIs)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    // Firebase light: Crashlytics + Analytics ONLY. No Auth/Firestore/Messaging/Remote Config/etc.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // Sentry without NDK preload — SentryNdkPreloadProvider has crashed cold-start on
    // older Android (API 26) when libsentry.so fails to load. Pure-Java SDK is enough.
    implementation(libs.sentry.android) {
        exclude(group = "io.sentry", module = "sentry-android-ndk")
    }

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle.bcpkix)
    testImplementation(libs.bouncycastle.bcprov)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
