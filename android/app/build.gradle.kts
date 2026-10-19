plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aoede.twa"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    buildFeatures {
        buildConfig = true   // needed for the BuildConfig.DEBUG gate in MainActivity
    }

    defaultConfig {
        applicationId = "com.aoede.twa"
        minSdk = 21
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.1"
    }

    buildTypes {
        getByName("debug") { }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        create("distribution") {
            val keyPath = System.getenv("AOEDE_ANDROID_KEYSTORE")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storeType = "PKCS12"
                storePassword = System.getenv("AOEDE_ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("AOEDE_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("AOEDE_ANDROID_KEY_PASSWORD")
            }
        }
    }
    if (!System.getenv("AOEDE_ANDROID_KEYSTORE").isNullOrBlank()) {
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

val bundleFrontend by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val localPython = rootProject.projectDir.resolve("../.venv/bin/python")
    commandLine(if (localPython.exists()) localPython.absolutePath else "python3", "build_frontend.py")
}
tasks.named("preBuild") { dependsOn(bundleFrontend) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    // MediaSessionCompat — exposes media metadata + transport controls to
    // the system so the OS shows the rich media notification with album art,
    // play/pause, next/prev — same as a real PWA in Chrome.
    implementation("androidx.media:media:1.7.0")
    // WebViewCompat.addDocumentStartJavaScript — injects our MediaSession shim
    // BEFORE any page JS runs, so the web app's player picks up our overridden
    // navigator.mediaSession instead of the WebView's native (and uncaptured) one.
    implementation("androidx.webkit:webkit:1.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
