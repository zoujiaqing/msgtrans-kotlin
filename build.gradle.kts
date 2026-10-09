plugins { kotlin("multiplatform") version "2.4.20" apply false }

// Kotlin floor 2.4.20: anything below it (2.4.20 Beta / RC included) is refused. The stack and the Neton
// repositories share one Kotlin line; mixed versions break K/N klibs in ways that are hard to read.
run {
    val required = KotlinVersion(2, 4, 20)
    val actual = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    val parts = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val parsed = KotlinVersion(parts[0], parts[1], parts[2])
    require(parsed > required || (parsed == required && '-' !in actual)) {
        "Kotlin $actual is below the required minimum $required. Upgrade the Kotlin Gradle plugin."
    }
}
allprojects {
    group = "com.netonstream"
    version = "0.2.0"
}

// ---------- Maven Central publishing ----------
//
// Same convention as Neton (see its RELEASING.md): publications are laid out, signed, under
// build/staging-repo by `publishAllPublicationsToStagingLocalRepository`, and that directory is
// zipped and uploaded as one Central Portal bundle. Kotlin/Native artifacts are klibs; a consumer
// must compile with the same Kotlin version as the publisher.
val unpublished = setOf("msgtrans-bench")
val pomDescriptions = mapOf(
    "msgtrans" to "msgtrans for Kotlin/Native - the msgtrans wire protocol (Packet codec, zstd/zlib payload compression) and a request/response, one-way and server-push session over TCP on the neton-io reactor; wire-compatible with the Rust and TypeScript implementations"
)

subprojects {
    if (name in unpublished) return@subprojects
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    afterEvaluate {
        val sub = this@subprojects
        val publishing = sub.extensions.getByType<org.gradle.api.publish.PublishingExtension>()
        // Central requires a javadoc jar beside every jar. Dokka is not wired in; as in neton-io,
        // the jar carries the README, which is where the API is documented.
        val apiDocsJar = sub.tasks.register<Jar>("apiDocsJar") {
            archiveClassifier.set("javadoc")
            from(rootProject.file("README.md"))
        }

        // Only group / version / POM. The KMP plugin owns the artifactIds (one per target plus
        // the root metadata publication); overriding them would make the publications collide.
        publishing.publications.withType<MavenPublication>().configureEach {
            artifact(apiDocsJar)
            groupId = sub.group.toString()
            version = sub.version.toString()
            pom {
                name.set(sub.name)
                description.set(pomDescriptions[sub.name] ?: "msgtrans - ${sub.name}")
                url.set("https://github.com/zoujiaqing/msgtrans-kotlin")
                licenses { license { name.set("Apache-2.0"); url.set("https://opensource.org/licenses/Apache-2.0") } }
                developers {
                    developer {
                        id.set("zoujiaqing")
                        name.set("zoujiaqing")
                        email.set("zoujiaqing@gmail.com")
                        organization.set("Neton Stream")
                        organizationUrl.set("https://netonstream.com")
                    }
                }
                scm {
                    url.set("https://github.com/zoujiaqing/msgtrans-kotlin")
                    connection.set("scm:git:git://github.com/zoujiaqing/msgtrans-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/zoujiaqing/msgtrans-kotlin.git")
                }
            }
        }

        publishing.repositories {
            // Local file repository: the tree a Central Portal bundle is zipped from.
            maven {
                name = "stagingLocal"
                url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
            }
        }

        // Central rejects unsigned artifacts. In-memory key first (CI / no keyring), else keyring.
        val signing = sub.extensions.getByType<org.gradle.plugins.signing.SigningExtension>()
        val inMemoryKey = sub.findProperty("signingInMemoryKey") as String?
        when {
            !inMemoryKey.isNullOrBlank() -> {
                signing.useInMemoryPgpKeys(inMemoryKey, sub.findProperty("signingInMemoryKeyPassword") as String? ?: "")
                signing.sign(publishing.publications)
            }
            sub.hasProperty("signing.keyId") -> signing.sign(publishing.publications)
        }
        // KMP publications share sign tasks across targets; without this ordering Gradle reports
        // an implicit dependency between publishX and signY and fails the build.
        sub.tasks.withType<org.gradle.api.publish.maven.tasks.AbstractPublishToMaven>().configureEach {
            mustRunAfter(sub.tasks.withType<org.gradle.plugins.signing.Sign>())
        }
    }
}
