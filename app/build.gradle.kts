import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release CRM address: gradle.properties -> saascom.crmUrl (HTTPS). Empty means "ask on the login screen".
val releaseCrmUrl = providers.gradleProperty("saascom.crmUrl").orNull.orEmpty().trim().trimEnd('/')

// Release signing: keystore.properties at the project root (git-ignored), see phases/phase-1-android-mvp.md 1.16.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "com.saascom.caller"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.saascom.caller"
        minSdk = 29 // Android 10: needed for the call-screening role.
        targetSdk = 36
        // Bump for every release (or pass -Psaascom.versionCode=… / -Psaascom.versionName=…).
        versionCode = providers.gradleProperty("saascom.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("saascom.versionName").orNull ?: "1.0.0"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "CRM_URL", "\"http://10.0.2.2:8000\"")
        }
        release {
            buildConfigField("String", "CRM_URL", "\"$releaseCrmUrl\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            optimization {
                enable = false
            }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
