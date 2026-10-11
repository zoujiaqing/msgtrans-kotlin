plugins { kotlin("multiplatform") }
repositories { mavenCentral() }

// Its own version line, starting at 0.1.0 (the root sets msgtrans's).
version = "0.1.0"

// msgtrans over WebSocket: a separate artifact because it brings com.netonstream:websocket (and its HTTP), which TCP
// users do not need. The targets msgtrans and websocket both provide: native, and the JVM (desktop and Android apps),
// bytecode 1.8 / API 2.2 like msgtrans.
kotlin {
    coreLibrariesVersion = "2.2.21"
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
    }
    macosArm64(); macosX64(); linuxX64(); linuxArm64()
    iosArm64(); iosSimulatorArm64(); iosX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":msgtrans"))
            api("com.netonstream:websocket:0.2.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // wss in the tests: TLS on both sides.
            implementation("com.netonstream:tls-websocket:0.2.0")
        }
        nativeTest.dependencies {
            // Certificates generated at test time (the JVM uses fixed ones).
            implementation("com.netonstream:quic-testkit:0.2.0")
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

