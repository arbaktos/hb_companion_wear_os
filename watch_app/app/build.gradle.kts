import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// BASE_URL + BEARER_TOKEN come from secrets.properties (gitignored — the repo
// is public). See secrets.properties.example.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    require(f.exists()) { "secrets.properties missing — copy secrets.properties.example and fill it in" }
    f.inputStream().use { load(it) }
}

android {
    namespace = "com.arbaktos.babywatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arbaktos.babywatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "BASE_URL", "\"${secrets["BASE_URL"]}\"")
        buildConfigField("String", "BEARER_TOKEN", "\"${secrets["BEARER_TOKEN"]}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)
    implementation(libs.kotlinx.serialization.json)
}
