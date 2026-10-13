plugins {
    id("com.android.application")
}

android {
    namespace = "com.lenerd.lyricsnative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lenerd.lyricsnative"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources { noCompress += "onnx" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.13.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    compileOnly(files("$rootDir/lib/spotifyplus-sdk.aar"))
    compileOnly("org.luckypray:dexkit:2.0.0")
    compileOnly("androidx.core:core:1.15.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.github.wendykierp:JTransforms:3.1")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.google.code.gson:gson:2.13.1")
}
