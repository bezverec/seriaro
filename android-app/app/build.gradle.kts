import java.nio.file.Files
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val minimumAndroidSdk = 26
val requestedAbis = providers.environmentVariable("ANDROID_ABIS")
    .orElse("")
    .map { it.trim() }

android {
    namespace = "org.seriestracker.android"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "28.2.13676358"
    ndkPath = providers.environmentVariable("ANDROID_NDK_HOME").orNull?.takeIf { it.isNotBlank() }

    defaultConfig {
        applicationId = "org.seriestracker.android"
        minSdk = minimumAndroidSdk
        targetSdk = 37
        versionCode = 6
        versionName = "0.2.4"
        ndk.abiFilters += requestedAbis.get().takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))
            ?: listOf("armeabi-v7a", "arm64-v8a", "x86_64")
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += listOf("en", "cs", "eo", "ko", "uk", "b+tlh")
    }
    @Suppress("UnstableApiUsage")
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val rustDirectory = rootProject.layout.projectDirectory.dir("../core-rust")
val ndkInstallation: Provider<Directory> = androidComponents.sdkComponents.ndkDirectory

val prepareRust = tasks.register<PrepareRust>("prepareRust") {
    description = "Builds the Rust libraries and generates Kotlin UniFFI bindings."
    crateDirectory.set(rustDirectory)
    inputs.file(rootProject.layout.projectDirectory.file("../rust-toolchain.toml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree(rustDirectory) { exclude("target/**") })
        .withPathSensitivity(PathSensitivity.RELATIVE)
    ndkDirectory.set(ndkInstallation)
    inputs.file(ndkInstallation.map { it.file("source.properties") })
        .withPathSensitivity(PathSensitivity.NONE)
    ndkHostTag.set(providers.environmentVariable("ANDROID_NDK_HOST_TAG"))
    androidAbis.set(android.defaultConfig.ndk.abiFilters.toList())
    minimumSdk.set(minimumAndroidSdk)
    cargoTarget.set(layout.buildDirectory.dir("rust-target"))
    kotlinOutput.set(layout.buildDirectory.dir("generated/series-tracker/kotlin"))
    jniOutput.set(layout.buildDirectory.dir("generated/series-tracker/jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(prepareRust, PrepareRust::kotlinOutput)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareRust, PrepareRust::jniOutput)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    // Compress JNI libraries to fit the universal APK within the F-Droid host's asset limit.
    variant.packaging.jniLibs.useLegacyPackaging.set(true)
}

abstract class PrepareRust @Inject constructor(
    private val processes: ExecOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Internal abstract val crateDirectory: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Input @get:Optional abstract val ndkHostTag: Property<String>
    @get:Input abstract val androidAbis: ListProperty<String>
    @get:Input abstract val minimumSdk: Property<Int>
    @get:LocalState abstract val cargoTarget: DirectoryProperty
    @get:OutputDirectory abstract val kotlinOutput: DirectoryProperty
    @get:OutputDirectory abstract val jniOutput: DirectoryProperty

    @TaskAction
    fun build() {
        val host = System.getProperty("os.name")
        val windows = host.startsWith("Windows")
        val (defaultTag, hostLibrary) = when {
            windows -> "windows-x86_64" to "core_rust.dll"
            host == "Mac OS X" -> "darwin-x86_64" to "libcore_rust.dylib"
            host == "Linux" -> "linux-x86_64" to "libcore_rust.so"
            else -> throw GradleException("Unsupported Rust build host: $host")
        }
        val tag = ndkHostTag.orNull?.takeIf { it.isNotBlank() } ?: defaultTag
        val bin = ndkDirectory.get().dir("toolchains/llvm/prebuilt/$tag/bin").asFile
        val archiver = bin.resolve(if (windows) "llvm-ar.exe" else "llvm-ar")
        check(archiver.isFile) { "Android NDK tools for $tag were not found at $bin" }

        val kotlinDirectory = kotlinOutput.get().asFile
        val jniDirectory = jniOutput.get().asFile
        val targetDirectory = cargoTarget.get().asFile
        files.delete { delete(kotlinDirectory, jniDirectory) }
        Files.createDirectories(kotlinDirectory.toPath())
        cargo("build", "--locked", "--lib")
        cargo(
            "run", "--locked", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
            "generate", "--library", targetDirectory.resolve("debug/$hostLibrary").absolutePath,
            "--config", "uniffi.toml", "--language", "kotlin", "--no-format",
            "--out-dir", kotlinDirectory.absolutePath,
        )
        // UniFFI 0.29.5 emits a redundant Long conversion with Kotlin 2.4.
        kotlinDirectory.walkTopDown().filter { it.isFile && (it.extension == "kt") }.forEach { bindings ->
            bindings.writeText(
                bindings.readText().replaceFirst(
                    "@file:Suppress(\"NAME_SHADOWING\")",
                    "@file:Suppress(\"NAME_SHADOWING\", \"REDUNDANT_CALL_OF_CONVERSION_METHOD\")",
                ),
            )
        }

        for (abi in androidAbis.get()) {
            val (rustTarget, clangTarget) = when (abi) {
                "armeabi-v7a" -> "armv7-linux-androideabi" to "armv7a-linux-androideabi"
                "arm64-v8a" -> "aarch64-linux-android" to "aarch64-linux-android"
                "x86_64" -> "x86_64-linux-android" to "x86_64-linux-android"
                else -> throw GradleException("Unsupported Android ABI: $abi")
            }
            val linker = bin.resolve("$clangTarget${minimumSdk.get()}-clang" + if (windows) ".cmd" else "")
            val targetEnv = rustTarget.replace('-', '_')
            cargo(
                "build", "--locked", "--lib", "--release", "--target", rustTarget,
                toolEnvironment = mapOf(
                    "CARGO_TARGET_${targetEnv.uppercase()}_LINKER" to linker.absolutePath,
                    "CC_$targetEnv" to linker.absolutePath,
                    "AR_$targetEnv" to archiver.absolutePath,
                ),
            )
            targetDirectory.resolve("$rustTarget/release/libcore_rust.so")
                .copyTo(jniDirectory.resolve("$abi/libcore_rust.so"))
        }
    }

    private fun cargo(vararg arguments: String, toolEnvironment: Map<String, String> = emptyMap()) {
        processes.exec {
            workingDir(crateDirectory.get().asFile)
            environment("CARGO_TARGET_DIR", cargoTarget.get().asFile.absolutePath)
            environment(toolEnvironment)
            commandLine(listOf("cargo") + arguments)
        }
    }
}

dependencies {
    constraints {
        implementation("androidx.annotation:annotation-experimental:1.6.0") {
            because("Uses the updated AndroidX opt-in lint checks with Kotlin 2.4.")
        }
    }
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
