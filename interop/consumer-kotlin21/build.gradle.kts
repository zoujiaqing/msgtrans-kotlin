plugins {
    kotlin("jvm") version "2.1.21"
    application
}

// The coordinates under test; override with -PmsgtransQuicVersion=… etc. when staging a release.
val msgtransQuic = providers.gradleProperty("msgtransQuicVersion").getOrElse("0.1.1")
val msgtransWebSocket = providers.gradleProperty("msgtransWebSocketVersion").getOrElse("0.1.0")
val tls = providers.gradleProperty("tlsVersion").getOrElse("0.2.0")

dependencies {
    implementation("com.netonstream:msgtrans-quic:$msgtransQuic")
    implementation("com.netonstream:msgtrans-websocket:$msgtransWebSocket")
    implementation("com.netonstream:tls-websocket:$tls")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

application { mainClass.set("consumer.MainKt") }
