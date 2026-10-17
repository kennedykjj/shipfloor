plugins {
    id("shipfloor.kotlin-jvm")
    application
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass = "shipfloor.cli.MainKt"
    applicationName = "shipfloor"
    // Clikt loads a native library (JNA) to read the terminal; without this the JVM warns on every error.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Opt-in native image: `./gradlew :cli:nativeCompile`. It is not wired into build/check, so machines
// without GraalVM keep building. Output: cli/build/native/nativeCompile/shipfloor.
graalvmNative {
    // Resolve the GraalVM toolchain only for the native tasks, never for the regular build.
    toolchainDetection = true
    binaries {
        named("main") {
            imageName = "shipfloor"
            mainClass = "shipfloor.cli.MainKt"
            buildArgs.add("--no-fallback")
            javaLauncher = javaToolchains.launcherFor {
                vendor = JvmVendorSpec.GRAAL_VM
            }
        }
    }
}

// The CLI reports the version it was built with; the build writes it into a resource.
tasks.processResources {
    // A local value: the filter below must not capture the build script (configuration cache).
    val cliVersion = project.version.toString()
    inputs.property("version", cliVersion)
    filesMatching("shipfloor/cli/build.properties") {
        expand("version" to cliVersion)
    }
}
