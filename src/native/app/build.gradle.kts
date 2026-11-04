import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
}

android {
    namespace = "com.lenerd.lyricsnative"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    buildFeatures { prefab = true }

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
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += "onnx" }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libQnn*.so"
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("../../../assets/native/qnn")
}

val qnnAssets = tasks.register("exportQnnRuntime") {
    val runtime = configurations.getByName("debugRuntimeClasspath")
    val destination = rootProject.file("../../assets/native/qnn")
    inputs.files(runtime)
    outputs.dir(destination)
    doLast {
        val artifact = runtime.resolvedConfiguration.resolvedArtifacts.single { it.moduleVersion.id.group == "com.qualcomm.qti" && it.name == "qnn-runtime" }.file
        destination.mkdirs()
        val libraries = mutableListOf<Map<String, Any>>()
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        ZipFile(artifact).use { archive ->
            archive.entries().asSequence().filter { it.name.matches(Regex("jni/arm64-v8a/libQnn(Htp.*|System|Gpu)\\.so")) }.sortedBy { it.name }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                val compressed = ByteArrayOutputStream()
                GZIPOutputStream(compressed).use { it.write(bytes) }
                val gzip = compressed.toByteArray()
                val chunkSize = 4 * 1024 * 1024
                val chunks = (gzip.indices step chunkSize).mapIndexed { index, offset ->
                    val data = gzip.copyOfRange(offset, minOf(offset + chunkSize, gzip.size))
                    val path = "$name.gz.$index"
                    destination.resolve(path).writeBytes(data)
                    mapOf("path" to path, "size" to data.size, "sha256" to sha256(data))
                }
                libraries += mapOf("name" to name, "size" to bytes.size, "sha256" to sha256(bytes), "parts" to chunks)
            }
            archive.getInputStream(archive.getEntry("NOTICE.txt")).use { destination.resolve("NOTICE.txt").writeBytes(it.readBytes()) }
            archive.getInputStream(archive.getEntry("LICENSE.pdf")).use { destination.resolve("LICENSE.pdf").writeBytes(it.readBytes()) }
        }
        destination.resolve("runtime.json").writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("version" to "2.37.1", "libraries" to libraries))))
    }
}
tasks.named("preBuild").configure { dependsOn(qnnAssets) }

dependencies {

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.google.code.gson:gson:2.13.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    compileOnly(files("$rootDir/lib/spotifyplus-sdk.aar"))
    compileOnly("org.luckypray:dexkit:2.0.0")
    compileOnly("androidx.core:core:1.15.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.23.2")
    implementation("com.github.wendykierp:JTransforms:3.1")
    implementation("com.bytedance.android:shadowhook:2.0.0")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.google.code.gson:gson:2.13.1")
}
