plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.testmusic"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.testmusic"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    implementation(libs.androidx.cardview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    val appcompat_version = "1.7.1"

    implementation("androidx.appcompat:appcompat:$appcompat_version")
    // For loading and tinting drawables on older versions of the platform
    implementation("androidx.appcompat:appcompat-resources:$appcompat_version")
}
dependencies {
    val media3_version = "1.8.0"

    // For media playback using ExoPlayer
    implementation("androidx.media3:media3-exoplayer:$media3_version")

    // For DASH playback support with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-dash:$media3_version")
    // For HLS playback support with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-hls:$media3_version")
    // For SmoothStreaming playback support with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:$media3_version")
    // For RTSP playback support with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3_version")
    // For MIDI playback support with ExoPlayer (see additional dependency requirements in
    // https://github.com/androidx/media/blob/release/libraries/decoder_midi/README.md)
    //implementation("androidx.media3:media3-exoplayer-midi:$media3_version")
    // For ad insertion using the Interactive Media Ads SDK with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-ima:$media3_version")

    // For loading data using the Cronet network stack
    implementation("androidx.media3:media3-datasource-cronet:$media3_version")
    // For loading data using the OkHttp network stack
    implementation("androidx.media3:media3-datasource-okhttp:$media3_version")
    // For loading data using librtmp
    implementation("androidx.media3:media3-datasource-rtmp:$media3_version")

    // For building media playback UIs using Compose
    implementation("androidx.media3:media3-ui-compose:$media3_version")
    // For building media playback UIs using Views
    implementation("androidx.media3:media3-ui:$media3_version")
    // For building media playback UIs using Jetpack Compose
    implementation("androidx.media3:media3-ui-compose:$media3_version")
    // For building media playback UIs for Android TV using the Jetpack Leanback library
    implementation("androidx.media3:media3-ui-leanback:$media3_version")

    // For exposing and controlling media sessions
    implementation("androidx.media3:media3-session:$media3_version")

    // For extracting data from media containers
    implementation("androidx.media3:media3-extractor:$media3_version")

    // For integrating with Cast
    implementation("androidx.media3:media3-cast:$media3_version")

    // For scheduling background operations using Jetpack Work's WorkManager with ExoPlayer
    implementation("androidx.media3:media3-exoplayer-workmanager:$media3_version")

    // For transforming media files
    implementation("androidx.media3:media3-transformer:$media3_version")

    // For applying effects on video frames
    implementation("androidx.media3:media3-effect:$media3_version")

    // For muxing media files
    implementation("androidx.media3:media3-muxer:$media3_version")

    // Utilities for testing media components (including ExoPlayer components)
    implementation("androidx.media3:media3-test-utils:$media3_version")
    // Utilities for testing media components (including ExoPlayer components) via Robolectric
    implementation("androidx.media3:media3-test-utils-robolectric:$media3_version")

    // Common functionality for reading and writing media containers
    implementation("androidx.media3:media3-container:$media3_version")
    // Common functionality for media database components
    implementation("androidx.media3:media3-database:$media3_version")
    // Common functionality for media decoders
    implementation("androidx.media3:media3-decoder:$media3_version")
    // Common functionality for loading data
    implementation("androidx.media3:media3-datasource:$media3_version")
    // Common functionality used across multiple media libraries
    implementation("androidx.media3:media3-common:$media3_version")
    // Common Kotlin-specific functionality
    implementation("androidx.media3:media3-common-ktx:$media3_version")
}