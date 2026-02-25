plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.duntale"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
    maven { url = uri("https://maven.hytale.com/release") }
    maven { url = uri("https://maven.hytale.com/pre-release") }
}

dependencies {
    compileOnly("com.hypixel.hytale:Server:2026.02.18-f3b8fff95")
    
    // SQLite JDBC driver for persistence (if needed later)
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.9")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Java 25 preview features might be needed
        // options.compilerArgs.add("--enable-preview") 
    }

    shadowJar {
        archiveBaseName.set("ZSquad")
        archiveClassifier.set("")
    }

    register<Copy>("deploy") {
        dependsOn(shadowJar)
        from(shadowJar.get().archiveFile)
        into("${layout.projectDirectory.dir("../server/Server/mods")}")
        
        doLast {
            println("Deployed ${shadowJar.get().archiveFile.get().asFile.name} to server/Server/mods")
        }
    }
}
