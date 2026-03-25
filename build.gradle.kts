plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.3.0"
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
    compileOnly("com.hypixel.hytale:Server:2026.03.23-338988e70")
    compileOnly(files("../DynamicTooltipsLib/build/libs/DynamicTooltipsLib-1.5.1.jar"))
    compileOnly(files("../dungeon-gen/build/libs/dungeon-gen-1.0.0-SNAPSHOT.jar"))
    
    // SQLite JDBC driver
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")

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
        mergeServiceFiles()
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
