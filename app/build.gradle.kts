plugins {
    id("com.android.application") version "8.7.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {

    namespace = "com.continuum.navigator.core"

    // Always compile against latest stable SDK
    compileSdk = 36

    defaultConfig {

        applicationId = "com.continuum.navigator.core"

        // Runtime compatibility
        minSdk = 26        // Android 8 support
        targetSdk = 36     // Required for Play Store compliance

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps API key from local.properties
        manifestPlaceholders["MAPS_API_KEY"] =
            project.findProperty("MAPS_API_KEY") as String? ?: ""
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

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {

        jvmTarget = "17"
    }

    // JNI libraries (for native GNSS / navigation engine etc.)
    sourceSets {

        getByName("main") {

            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Location Services
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Google Maps SDK
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // Offline Maps (OSMDroid)
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
