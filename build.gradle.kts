plugins {
    java
    `maven-publish`
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

group = "com.github.ALLAY-XD-20"
version = "2.4.4"

lavalinkPlugin {
    name = "instaxlava"
    path = "dev.pawan.instaxlava"
    apiVersion = "4.0.8"
    serverVersion = "4.0.8"
    configurePublishing = true
}

base {
    archivesName.set("instaxlava")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven("https://maven.lavalink.dev/releases")
    maven("https://maven.lavalink.dev/snapshots")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("dev.arbjerg.lavalink:plugin-api:4.0.8")
    compileOnly("dev.arbjerg:lavaplayer:2.2.2")
    compileOnly("org.springframework.boot:spring-boot:3.2.5")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
