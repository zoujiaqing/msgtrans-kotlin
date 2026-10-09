import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// One artifact: the wire codec (msgtrans.core) and the session/transport layer (msgtrans.transport)
// ship together, the way the Rust crate does. Additional protocols (WebSocket, QUIC) and the RPC
// layer are separate modules that depend on this one; nothing needs the codec without the session.
kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()
    // Android Native builds and links, but has not run on a device yet, and Android apps use the JVM
    // artifact. Off by default, so a release does not publish it; -Pmsgtrans.androidNative=true
    // turns it on (it needs an NDK).
    val android = if (providers.gradleProperty("msgtrans.androidNative").orNull == "true") {
        listOf(androidNativeArm64(), androidNativeArm32(), androidNativeX64(), androidNativeX86())
    } else {
        emptyList()
    }
    // The JVM (and Android apps through it): the transport runs on neton-io's NIO reactor. Bytecode
    // 1.8 so an Android library can depend on it.
    // The JVM artifact must work in Android apps built with an older Kotlin (KuiklyUI pins hosts to
    // 2.1). Such a host never compiles against these classes, but Gradle aligns its whole classpath
    // to the highest kotlin-stdlib anything asks for, and its compiler reads stdlib metadata at most
    // one version ahead. So the JVM build asks for stdlib 2.2.21 and uses no stdlib API newer than
    // 2.2 (API 2.1 is deprecated); native klibs are unaffected (they follow the compiler version).
    coreLibrariesVersion = "2.2.21"
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
    }

    // zstd comes from zstd-kmp wherever it publishes a variant (Apple, Linux, JVM). It publishes none
    // for Android Native, so those targets compile the vendored upstream source instead; the codec on
    // top of either is the same streaming API with the same limits.
    applyDefaultHierarchyTemplate {
        common {
            group("zstdKmp") { group("apple"); group("linux"); withJvm() }
        }
    }

    android.forEach { target ->
        val staticLib = vendoredStaticLibrary(target, "zstd", file("src/nativeInterop/zstd/zstd.c"))
        target.compilations.getByName("main").cinterops.create("zstd") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/zstd.def"))
            includeDirs(project.file("src/nativeInterop/zstd"))
            // Embedded in the klib, so a consumer links zstd without knowing it exists.
            extraOpts("-staticLibrary", staticLib.get().asFile.name, "-libraryPath", staticLib.get().asFile.parent)
            // The library's bytes are copied into the klib, so they are an input, not just an ordering.
            tasks.named(interopProcessingTaskName) { inputs.file(staticLib) }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("com.netonstream:io:0.3.3")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        getByName("zstdKmpMain").dependencies {
            implementation("com.squareup.zstd:zstd-kmp:0.4.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

/**
 * Compile one vendored C file into a static library for an Android Native target, with the NDK's
 * clang. Kotlin/Native's cinterop can only compile C sources in a legacy call mode that builds them
 * as C++, so the library is built the ordinary way and embedded with `-staticLibrary` instead.
 *
 * API 21 matches the sysroot Kotlin/Native links Android binaries against. Hidden visibility keeps
 * the library's symbols out of a consumer's exported table, where they could collide with a host
 * app's own copy.
 */
fun vendoredStaticLibrary(target: KotlinNativeTarget, name: String, source: File): Provider<RegularFile> {
    val triple = when (target.konanTarget) {
        KonanTarget.ANDROID_ARM64 -> "aarch64-linux-android21"
        KonanTarget.ANDROID_ARM32 -> "armv7a-linux-androideabi21"
        KonanTarget.ANDROID_X64 -> "x86_64-linux-android21"
        KonanTarget.ANDROID_X86 -> "i686-linux-android21"
        else -> error("${target.name} is not an Android Native target")
    }
    val outDir = layout.buildDirectory.dir("vendored/$name/${target.name}")
    val flags = listOf(
        "-O2", "-fPIC", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections",
        // zstd's headers mark the API visibility("default"), which would override
        // -fvisibility=hidden; empty overrides keep it out of a consumer's exports.
        "-DZSTDLIB_VISIBLE=", "-DZSTDERRORLIB_VISIBLE=", "-DZDICTLIB_VISIBLE=",
    )
    val task = tasks.register("build${name.replaceFirstChar(Char::uppercase)}${target.name.replaceFirstChar(Char::uppercase)}") {
        inputs.file(source)
        inputs.property("flags", flags)
        outputs.dir(outDir)
        doLast {
            val bin = ndkToolchainBin()
            val dir = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
            val obj = dir.resolve("$name.o")
            providers.exec {
                commandLine(
                    listOf(bin.resolve("clang").absolutePath, "--target=$triple", "-c", source.absolutePath, "-o", obj.absolutePath) +
                        flags + "-I${source.parentFile.absolutePath}",
                )
            }.result.get().assertNormalExitValue()
            providers.exec {
                commandLine(bin.resolve("llvm-ar").absolutePath, "rcs", dir.resolve("lib$name.a").absolutePath, obj.absolutePath)
            }.result.get().assertNormalExitValue()
            obj.delete()
        }
    }
    return outDir.flatMap { dir -> task.map { dir.file("lib$name.a") } }
}

/** The newest installed NDK: ANDROID_NDK_HOME / ANDROID_NDK_ROOT, else `<sdk>/ndk/<version>`. */
fun ndkToolchainBin(): File {
    val env = { key: String -> providers.environmentVariable(key).orNull?.takeIf(String::isNotBlank) }
    val explicit = listOfNotNull(env("ANDROID_NDK_HOME"), env("ANDROID_NDK_ROOT")).map(::File)
    val sdks = listOfNotNull(env("ANDROID_HOME"), env("ANDROID_SDK_ROOT"), "${System.getProperty("user.home")}/Library/Android/sdk")
    val installed = sdks.flatMap { sdk ->
        File(sdk, "ndk").listFiles().orEmpty().sortedWith(
            compareByDescending<File> { it.name.substringBefore('.').toIntOrNull() ?: 0 }.thenByDescending { it.name },
        )
    }
    val ndk = (explicit + installed).firstOrNull { it.resolve("toolchains/llvm/prebuilt").isDirectory }
        ?: error("Android Native targets need an NDK to compile vendored C: set ANDROID_NDK_HOME")
    val host = ndk.resolve("toolchains/llvm/prebuilt").listFiles().orEmpty().first { it.isDirectory }
    return host.resolve("bin")
}
