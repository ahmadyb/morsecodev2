import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Signing material is never hard-coded. Precedence:
 *   1. keystore/keystore.properties (local, git-ignored)
 *   2. MC_KEYSTORE_* environment variables (CI secrets)
 *   3. the demo release keystore committed in keystore/ (documented password)
 *   4. the debug keystore, so an unsigned build is impossible
 *
 * Replace the demo keystore before shipping to a store: `python3 tools/offline_build.py --new-keystore`.
 */
fun keystoreProps(): Properties {
    val p = Properties()
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
    val env = mapOf(
        "storeFile" to "MC_KEYSTORE_FILE",
        "storePassword" to "MC_KEYSTORE_PASSWORD",
        "keyAlias" to "MC_KEY_ALIAS",
        "keyPassword" to "MC_KEY_PASSWORD"
    )
    for ((key, variable) in env) {
        val value = System.getenv(variable) ?: continue
        if (value.isNotBlank()) p.setProperty(key, value)
    }
    return p
}

android {
    namespace = "com.morsecode.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.morsecode.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            val props = keystoreProps()
            val demo = rootProject.file("keystore/morsecode-release.jks")
            val file = (props.getProperty("storeFile")?.let { file(it) })
                ?: (if (demo.exists()) demo else null)
            if (file != null && file.exists()) {
                storeFile = file
                storePassword = props.getProperty("storePassword") ?: "morsecode"
                keyAlias = props.getProperty("keyAlias") ?: "morsecode"
                keyPassword = props.getProperty("keyPassword") ?: "morsecode"
            } else {
                // Fall back to the debug keystore so `assembleRelease` always produces an APK.
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false          // tiny app, plain views: R8 only buys risk here
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // dx/d8 on the offline toolchain cannot translate default interface methods at minSdk 21.
        freeCompilerArgs += listOf("-Xjvm-default=disable")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
        compose = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// No dependencies block on purpose: the APK is built from the platform APIs only.
