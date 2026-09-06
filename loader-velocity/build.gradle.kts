plugins {
    `java-library`
    id("com.gradleup.shadow")
}

// Velocity publishes its API only as a snapshot. Pin the exact snapshot line and
// refuse to cache it as a moving target so a build is repeatable.
val velocityApiVersion = "3.3.0-SNAPSHOT"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

configurations.all {
    resolutionStrategy.cacheChangingModulesFor(24, "hours")
}

dependencies {
    implementation(project(":loader-common"))

    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
    testCompileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
}

tasks.shadowJar {
    archiveBaseName.set("mcanalytics-loader-velocity")
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
