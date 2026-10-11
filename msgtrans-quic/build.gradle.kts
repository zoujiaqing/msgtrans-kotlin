plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// Its own version line, starting at 0.1.0 (the root sets msgtrans's).
version = "0.2.0"

// msgtrans over QUIC: a separate artifact because it brings com.netonstream:quic and OpenSSL, which TCP users do not
// need. The targets msgtrans and quic both provide: native, and the JVM (desktop and Android apps; quic's TLS through
// openssl-kotlin's JNI library, its UDP through neton-io's NIO reactor), bytecode 1.8 / API 2.2 like msgtrans.
kotlin {
    coreLibrariesVersion = "2.2.21"
    // Language 2.2 for every source set the JVM compiles (KMP also requires a source set to be no older than the ones it
    // depends on, so native-only ones may stay newer): class metadata then says 2.2, which a Kotlin 2.1 compiler still
    // reads (one version ahead); with the 2.4 default it refuses to compile against these classes.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
        compilations.configureEach { allKotlinSourceSets.forAll { it.languageSettings.languageVersion = "2.2" } }
    }
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":msgtrans"))
            api("com.netonstream:quic:0.3.0")
            // lookupHost, used directly (quic 0.3.0 brings the same io).
            api("com.netonstream:io:0.4.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        nativeTest.dependencies {
            // Test certificates (a CA and leaves) generated with OpenSSL at test time.
            implementation("com.netonstream:quic-testkit:0.3.0")
        }
    }
}

// interop/android-smoke.sh: the JVM test classes with their runtime classpath, to dex for an Android device.
tasks.register("androidSmokeClasspath") {
    val test = kotlin.jvm().compilations.getByName("test")
    dependsOn(test.compileTaskProvider)
    val files = test.output.allOutputs + test.runtimeDependencyFiles!!
    val out = layout.buildDirectory.file("android-smoke/classpath.txt")
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs(); writeText(files.joinToString("\n") { it.absolutePath }) }
    }
}

