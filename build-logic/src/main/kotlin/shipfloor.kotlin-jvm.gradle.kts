// Convention for every shipfloor module: Kotlin on the JVM, JUnit 5 with AssertJ, and test output the factory can read.
// Lives in build-logic/, which the factory's agents may not change (forbidden in .shipfloor/factory.yml).
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Compiles with a JDK 25 toolchain (downloaded if missing) into bytecode for Java 21: runs on 21 or newer.
val javaRelease = 21

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaRelease.toString())
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testImplementation"(libs.findLibrary("assertj-core").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Libraries such as Clikt load native code (JNA); without this the JVM prints a warning into every test run.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The factory's green gate looks for PASSED in the output to prove a test ran; Gradle prints nothing
    // for passing tests unless told to. FULL exceptions put the assertion message in the red gate's output.
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
