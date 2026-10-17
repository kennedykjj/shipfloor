import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("shipfloor.kotlin-jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unixsocket)
}

compose.desktop {
    application {
        mainClass = "shipfloor.app.MainKt"
        // The window is opaque and stays on OpenGL: no skiko.renderApi=SOFTWARE (spike S0-07).

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "shipfloor"
            // jpackage requires MAJOR.MINOR.PATCH.
            packageVersion = "1.0.0"
            description = "Local software factory: agents refine, implement and validate; the owner decides."
            vendor = "shipfloor"
        }
    }
}
