plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val nativeSources = layout.buildDirectory.dir("generated/series-tracker")
val requestedAbis = providers.environmentVariable("ANDROID_ABIS")
    .orElse("armeabi-v7a arm64-v8a x86_64")

android {
    namespace = "org.seriestracker.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.seriestracker.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.4"
        resourceConfigurations += listOf("en", "cs", "eo", "ko", "uk", "b+tlh")
        ndk.abiFilters += requestedAbis.get().split(Regex("\\s+"))
    }

    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.named("main") {
        java.srcDir(nativeSources.map { it.dir("kotlin") })
        jniLibs.srcDir(nativeSources.map { it.dir("jniLibs") })
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

val prepareRust by tasks.registering(Exec::class) {
    val projectRoot = rootProject.projectDir.parentFile
    val buildScript = projectRoot.resolve("scripts/build-rust-android.sh")
    inputs.file(buildScript)
    inputs.file(projectRoot.resolve("rust-toolchain.toml"))
    inputs.files(fileTree(projectRoot.resolve("core-rust")) {
        exclude("target/**")
    })
    inputs.property("abis", requestedAbis)
    outputs.dir(nativeSources)
    environment("ANDROID_HOME", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
    commandLine("bash", buildScript.absolutePath, nativeSources.get().asFile.absolutePath)
}
tasks.named("preBuild").configure { dependsOn(prepareRust) }

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
