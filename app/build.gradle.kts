import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

// Both local builds and CI use the same private key; no temporary debug fallback.
val signingProperties = Properties().apply {
    val path = providers.environmentVariable("POJIAN_SIGNING_PROPERTIES").orNull
    (path?.let { rootProject.file(it) } ?: rootProject.file("keystore.properties"))
        .takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val verifySigningIdentity by tasks.registering {
    group = "verification"
    doLast {
        val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "storeType")
        check(required.all { !signingProperties.getProperty(it).isNullOrBlank() }) {
            "Fixed signing configuration missing. See docs/ANDROID_SIGNING.md; temporary signing is disabled."
        }
        val keyStore = KeyStore.getInstance(signingProperties.getProperty("storeType"))
        rootProject.file(signingProperties.getProperty("storeFile")).inputStream().use {
            keyStore.load(it, signingProperties.getProperty("storePassword").toCharArray())
        }
        val alias = signingProperties.getProperty("keyAlias")
        check(keyStore.getKey(alias, signingProperties.getProperty("keyPassword").toCharArray()) is java.security.PrivateKey) {
            "Signing alias does not contain a private key."
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate(alias).encoded)
            .joinToString("") { "%02x".format(it) }
        val expected = file("signing-certificate.sha256").readText().trim()
        check(expected.matches(Regex("[0-9a-f]{64}")) && actual == expected) {
            "Signing certificate does not match the project's fixed identity. Refusing to build."
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifySigningIdentity) }

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
    signingConfigs {
        create("pojian") {
            storeFile = signingProperties.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
            storeType = signingProperties.getProperty("storeType", "PKCS12")
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("pojian") }
        getByName("release") { signingConfig = signingConfigs.getByName("pojian") }
    }
    defaultConfig {
        // Stable install identity lets existing users keep their local records on upgrade.
        applicationId = "com.zhiheng.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 42
        versionName = "0.5.20"
        // Optional smaller package for a known device; default APK keeps all upstream ABIs.
        providers.gradleProperty("emergeAbi").orNull?.let { selectedAbi ->
            require(selectedAbi in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) { "Unsupported emergeAbi: $selectedAbi" }
            ndk { abiFilters += selectedAbi }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
        // Injected from Gradle properties or ignored local.properties; never commit real tokens.
        val localServiceProperties = Properties().apply {
            rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
        }
        val pojianServiceToken = providers.gradleProperty("pojian.serviceToken").orNull
            ?: localServiceProperties.getProperty("pojian.serviceToken", "")
        require(pojianServiceToken.length <= 512 && pojianServiceToken.all { it.code in 33..126 }) { "Invalid service token format" }
        val escapedServiceToken = pojianServiceToken.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "POJIAN_SERVICE_TOKEN", "\"$escapedServiceToken\"")
        // Public release metadata is readable without any download token.
        // Override with the pojian.updateManifestUrl Gradle property, or set it empty in
        // local.properties to disable update checks and network requests.
        val updateManifestUrl = providers.gradleProperty("pojian.updateManifestUrl").orNull
            ?: localServiceProperties.getProperty("pojian.updateManifestUrl", "https://github.com/baijiamin/PoJian-Releases/releases/latest/download/update.json")
        require(updateManifestUrl.length <= 300 && updateManifestUrl.all { it.code in 32..126 }) { "Invalid update manifest URL format" }
        val escapedUpdateManifestUrl = updateManifestUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$escapedUpdateManifestUrl\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("test").java.srcDir(rootProject.file("evaluation/kotlin"))
}

// Uses the normal unit-test runtime, but invokes a dataset evaluator rather than a model or Android device.
tasks.register<JavaExec>("evaluateHistoryQuality") {
    group = "verification"
    description = "Evaluate annotated structured viewpoints and historical increments entirely offline."
    dependsOn("compileDebugUnitTestKotlin")
    workingDir(rootProject.projectDir)
    mainClass.set("com.emerge.app.evaluation.HistoryQualityEvaluator")
    args("--dataset", providers.gradleProperty("quality.dataset").getOrElse(rootProject.file("evaluation/datasets/synthetic-v1").path),
        "--output", providers.gradleProperty("quality.output").getOrElse(layout.buildDirectory.dir("reports/history-quality").get().asFile.path),
        "--repository", rootProject.projectDir.path)
    doFirst { classpath = tasks.named<Test>("testDebugUnitTest").get().classpath }
}
tasks.register<JavaExec>("evaluateSyntheticTraces") {
    group = "verification"
    description = "Replay authored synthetic page hints through the real capture tracker; no device."
    dependsOn("compileDebugUnitTestKotlin")
    workingDir(rootProject.projectDir)
    mainClass.set("com.emerge.app.evaluation.SyntheticTraceEvaluator")
    args(providers.gradleProperty("research.dataset").getOrElse(layout.buildDirectory.dir("research-synthetic-v2").get().asFile.path),
        providers.gradleProperty("research.output").getOrElse(layout.buildDirectory.dir("reports/research-v2").get().asFile.path))
    doFirst { classpath = tasks.named<Test>("testDebugUnitTest").get().classpath }
}

tasks.register<JavaExec>("evaluateResearchDatabase") {
    group = "verification"
    description = "Check every virtual profile and statistics window against independently queried SQLite data."
    dependsOn("compileDebugUnitTestKotlin")
    workingDir(rootProject.projectDir)
    mainClass.set("com.emerge.app.evaluation.ResearchDatabaseEvaluator")
    args(layout.buildDirectory.dir("reports/project-research-database").get().asFile.path)
    doFirst { classpath = tasks.named<Test>("testDebugUnitTest").get().classpath }
}

dependencies {
    // Bundled Chinese OCR works without a first-run model download or Play Services.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
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
    // Run the production synthetic-data decoder on the JVM, without Android's JSON stubs.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Uses the public input service on recent Android versions instead of removed reflection APIs.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
