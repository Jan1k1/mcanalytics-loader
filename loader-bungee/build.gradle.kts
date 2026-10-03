plugins {
    `java-library`
    id("com.gradleup.shadow")
}

// BungeeCord publishes its API releases on Maven Central. 1.20-R0.2 is the oldest one the
// connector bundle supports, and Waterfall implements the same API.
val bungeeApiVersion = "1.20-R0.2"

dependencies {
    implementation(project(":loader-common"))

    compileOnly("net.md-5:bungeecord-api:$bungeeApiVersion")
    testCompileOnly("net.md-5:bungeecord-api:$bungeeApiVersion")
}

tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("bungee.yml") {
        expand(tokens)
    }
}

tasks.shadowJar {
    archiveBaseName.set("mcanalytics-loader-bungee")
    archiveClassifier.set("")
}

tasks.test {
    systemProperty("loader.version", project.version.toString())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier.set("plain")
}
