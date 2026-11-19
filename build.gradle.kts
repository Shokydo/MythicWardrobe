plugins {
    java
}

group = "dev.mythicwardrobe"
version = "1.11.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("io.lumine:Mythic-Dist:5.13.0")
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.10.2")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
    compileOnly("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

