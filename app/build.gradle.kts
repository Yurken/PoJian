import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// The upstream Android AAR is published as a GitHub release asset. Pin and verify it
// rather than depending on a moving JitPack build or committing native binaries.
val sherpaAar = layout.buildDirectory.file("asr-dependencies/sherpa-onnx-1.13.8.aar")
val prepareSherpa by tasks.registering {
    outputs.file(sherpaAar)
    outputs.upToDateWhen { false }
    doLast {
        val destination = sherpaAar.get().asFile
        val expected = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").let { hash ->
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (!destination.exists() || digest(destination) != expected) {
            destination.parentFile.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.part")
            try {
                val connection = URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                check(temporary.length() == 50_129_134L && digest(temporary) == expected) { "sherpa-onnx AAR verification failed" }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
    }
}
android {
    namespace = "com.emerge.app"
    compileSdk = 34
    defaultConfig {
        // Stable install identity lets existing users keep their local records on upgrade.
        applicationId = "com.zhiheng.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 9
        versionName = "0.4.2"
        // Optional smaller package for a known device; default APK keeps all upstream ABIs.
        providers.gradleProperty("emergeAbi").orNull?.let { selectedAbi ->
            require(selectedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "Unsupported emergeAbi: $selectedAbi" }
            ndk { abiFilters += selectedAbi }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(files(sherpaAar).builtBy(prepareSherpa))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Uses the public input service on recent Android versions instead of removed reflection APIs.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
