plugins {
    id("shipfloor.kotlin-jvm")
    alias(libs.plugins.sqldelight)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.sqldelight.sqlite.driver)
}

sqldelight {
    databases {
        create("ShipfloorDatabase") {
            packageName.set("shipfloor.store.db")
            verifyMigrations.set(true)
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}
