plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

// JetBrains Runtime: the only JDK that ships WLToolkit, the native Wayland backend for AWT.
val jbr: Action<JavaToolchainSpec> = Action {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.JETBRAINS
}

kotlin {
    jvmToolchain(jbr)
}

dependencies {
    implementation(compose.desktop.currentOs)
}

// -Ptoolkit=wayland runs on WLToolkit (native Wayland); without it AWT uses XToolkit through XWayland.
// -Pseconds=N closes the spike after N seconds, for unattended fps runs.
val toolkit = providers.gradleProperty("toolkit").orElse("x11")
val seconds = providers.gradleProperty("seconds").orElse("0")
// -Pjvm="-Dskiko.renderApi=SOFTWARE -Dspike.transparent=false" passes extra JVM flags for the variants.
val extraJvm = providers.gradleProperty("jvm").orElse("")

compose.desktop {
    application {
        mainClass = "shipfloor.spike.MainKt"
        // `run` uses the JVM that runs Gradle unless told otherwise; this machine's system JDK is headless.
        javaHome = javaToolchains.launcherFor(jbr).get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Dspike.seconds=${seconds.get()}")
        jvmArgs += extraJvm.get().split(" ").filter { it.isNotBlank() }
        if (toolkit.get() == "wayland") {
            jvmArgs += listOf("-Dawt.toolkit.name=WLToolkit")
        }
    }
}
