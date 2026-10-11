plugins { kotlin("jvm") version "2.1.21" apply false }

// Downstream projects on Kotlin 2.1, one per way an application takes the stack, each resolving its dependencies on its
// own: quic alone, msgtrans-quic alone, msgtrans-websocket alone (ws), msgtrans-websocket with tls-websocket (wss).
// A combined project would let one artifact's newer pins silently lift another's (Gradle picks the highest version),
// hiding a module whose own declared dependencies are too old; checkPins fails any project where a com.netonstream
// version was lifted, so each artifact must work with exactly what it declares. `run` runs all four.
val versions = mapOf(
    "openssl" to "4.0.2-3", "quic" to "0.3.0", "msgtrans-quic" to "0.2.0", "msgtrans-websocket" to "0.2.0", "tls-websocket" to "0.3.0",
).mapValues { (name, default) -> providers.gradleProperty("version.$name").getOrElse(default) }
val artifactsOf = mapOf(
    "quic" to listOf("quic"),
    "msgtrans-quic" to listOf("msgtrans-quic"),
    "ws" to listOf("msgtrans-websocket"),
    "wss" to listOf("msgtrans-websocket", "tls-websocket"),
)

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "application")
    dependencies {
        for (artifact in artifactsOf.getValue(project.name)) add("implementation", "com.netonstream:$artifact:${versions.getValue(artifact)}")
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
        sourceSets.getByName("main").kotlin.srcDir(rootProject.file("shared"))
        if (project.name != "quic") sourceSets.getByName("main").kotlin.srcDir(rootProject.file("shared-msgtrans"))
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    extensions.configure<JavaApplication> { mainClass.set("consumer.MainKt") }

    val checkPins = tasks.register("checkPins") {
        val result = configurations.getByName("runtimeClasspath").incoming.resolutionResult.rootComponent
        doLast {
            val lifted = mutableListOf<String>()
            fun walk(component: org.gradle.api.artifacts.result.ResolvedComponentResult, seen: MutableSet<Any>) {
                if (!seen.add(component.id)) return
                for (dep in component.dependencies) {
                    if (dep !is org.gradle.api.artifacts.result.ResolvedDependencyResult) continue
                    val requested = dep.requested
                    val selected = dep.selected.moduleVersion
                    if (requested is org.gradle.api.artifacts.component.ModuleComponentSelector && requested.group == "com.netonstream" &&
                        selected != null && requested.version.isNotEmpty() && requested.version != selected.version
                    ) lifted += "${dep.from.id.displayName} asks ${requested.module}:${requested.version}, gets ${selected.version}"
                    walk(dep.selected, seen)
                }
            }
            walk(result.get(), mutableSetOf())
            check(lifted.isEmpty()) { "com.netonstream versions lifted by conflict resolution:\n  " + lifted.joinToString("\n  ") }
            println("${project.name}: every com.netonstream dependency resolves to the version asked for")
        }
    }
    tasks.named<JavaExec>("run") {
        dependsOn(checkPins)
        // -PrunJavaHome=<JDK>: run on that JDK (CI: 8, the oldest the bytecode targets, and 21).
        providers.gradleProperty("runJavaHome").orNull?.let { jdk ->
            executable = File(jdk, if (System.getProperty("os.name").startsWith("Windows")) "bin/java.exe" else "bin/java").absolutePath
        }
    }
}
