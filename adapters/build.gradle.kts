plugins {
    id("shipfloor.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ports"))
    implementation(libs.kotlinx.serialization.json)
}
