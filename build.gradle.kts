import groovy.json.JsonSlurper

plugins {
    id("java-library")
    id("com.gradleup.shadow") version "9.3.0"
}

group = "com.duntale"
version = "0.1.0"

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
    compileOnly("com.hypixel.hytale:Server:0.5.0-pre.8")
    compileOnly(files("../DynamicTooltipsLib/build/libs/DynamicTooltipsLib-1.5.1.jar"))
    compileOnly(files("../dungeon-gen/build/libs/dungeon-gen-1.0.0-SNAPSHOT.jar"))
    
    // SQLite JDBC driver
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.hypixel.hytale:Server:0.5.0-pre.8")
    testImplementation(files("../dungeon-gen/build/libs/dungeon-gen-1.0.0-SNAPSHOT.jar"))
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        // Java 25 preview features might be needed
        // options.compilerArgs.add("--enable-preview") 
    }

    test {
        useJUnitPlatform()
        jvmArgs("-Djava.util.logging.manager=com.hypixel.hytale.logger.backend.HytaleLogManager")
    }

    shadowJar {
        val manifest = file("src/main/resources/manifest.json")
        val pluginName = (JsonSlurper().parse(manifest) as Map<*, *>)["Name"] as String
        archiveFileName.set("${pluginName}.jar")
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
