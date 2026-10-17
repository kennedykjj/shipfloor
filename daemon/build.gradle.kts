plugins {
    id("shipfloor.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

application {
    mainClass = "shipfloor.daemon.MainKt"
    applicationName = "shipfloor-daemon"
    applicationDefaultJvmArgs = listOf("-Dshipfloor.launcher=__APP_HOME__/bin/shipfloor-daemon")
}

// Lets the launcher know its own absolute path: the start script expands APP_HOME at run time.
tasks.startScripts {
    doLast {
        unixScript.writeText(unixScript.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":store"))
    implementation(project(":ports"))
    implementation(project(":adapters"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
}
