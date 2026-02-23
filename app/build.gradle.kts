plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.eqmusicplayer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.eqmusicplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val soundCloudClientId = providers.gradleProperty("SOUNDCLOUD_CLIENT_ID").orElse("").get()
        val soundCloudRedirectUri = providers.gradleProperty("SOUNDCLOUD_REDIRECT_URI")
            .orElse("eqmusicplayer://soundcloud-callback")
            .get()
        val soundCloudClientSecret = providers.gradleProperty("SOUNDCLOUD_CLIENT_SECRET").orElse("").get()
        resValue("string", "soundcloud_client_id", soundCloudClientId)
        resValue("string", "soundcloud_redirect_uri", soundCloudRedirectUri)
        resValue("string", "soundcloud_client_secret", soundCloudClientSecret)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
