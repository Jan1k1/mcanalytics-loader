plugins {
    `java-library`
    id("com.gradleup.shadow")
}

// Paper publishes its API only as a snapshot. Pin the exact snapshot line and
// refuse to cache it as a moving target so a build is repeatable.
val paperApiVersion = "1.20.4-R0.1-SNAPSHOT"

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(24, "hours")
}

dependencies {
    implementation(project(":loader-common"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testCompileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

tasks.processResources {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(tokens)
    }
}

tasks.shadowJar {
    archiveBaseName.set("mcanalytics-loader-paper")
    archiveClassifier.set("")
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    systemProperty("loader.version", project.version.toString())
}
