plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}

group = "io.github.jiangyuyi.lightnovel"
version = providers.environmentVariable("APP_VERSION_NAME").orNull ?: "1.16.0"

base {
    archivesName.set("Mixn")
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "Mixn"
    mainClass.set("io.github.jiangyuyi.lightnovel.desktop.MainKt")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test-junit"))
}

tasks.test {
    useJUnit()
}

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "Mixn Windows"
        attributes["Implementation-Version"] = project.version.toString()
    }
}
