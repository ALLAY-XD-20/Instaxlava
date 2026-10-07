plugins {
    java
    id("dev.arbjerg.lavalink.gradle-plugin") version "1.1.2"
}

group = "dev.pawan"
version = "1.0.0"

lavalinkPlugin {
    name = "instaxlava"
    path = "dev.pawan.instaxlava"
    apiVersion = "4.0.8"
    serverVersion = "4.0.8"
    configurePublishing = true
}

base {
    archivesName = "instaxlava"
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
    // plugin-api and the Lavalink server are added by the lavalinkPlugin block above.
    // These are provided by the server at runtime; declared only so the compiler sees them.
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
