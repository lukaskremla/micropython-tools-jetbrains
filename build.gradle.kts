import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }
}

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

repositories {
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/maven-central")
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
        snapshots()
    }
}

@Suppress("unused")
sourceSets {
    val main by getting {
        kotlin.srcDir("src/main/kotlin")
        resources.srcDir("src/main/resources")
    }
}

configurations {
    implementation {
        // Exclude dependencies already bundled by the IDE
        exclude(group = "org.jetbrains", module = "annotations")
    }
}

dependencies {
    intellijPlatform {
        val type = providers.gradleProperty("platformType").get()
        val version = providers.gradleProperty("platformVersion").get()
        val pythonPlugin = providers.gradleProperty("pythonPlugin").get()

        create(type, version) {
            useInstaller.set(false)
        }

        jetbrainsRuntime()

        bundledPlugin("org.jetbrains.plugins.terminal")

        when (type) {
            "PC" -> bundledPlugin("PythonCore")
            "PY" -> bundledPlugin("Pythonid")
            else -> plugin(pythonPlugin)
        }
    }

    // Ensure the code completion sees this library, but don't bundle it, the IDE will have it
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // JSerialComm for serial communication
    implementation("com.fazecast:jSerialComm:2.11.4")
    // JZlib for ZLIB compression with configurable window size
    implementation("com.jcraft:jzlib:1.1.3")
    // Relies on a custom fork of the Java-Websocket library made for this plugin
    // https://github.com/lukaskremla/Java-WebSocket
    implementation(files("libs/Java-WebSocket-1.6.1-CUSTOM_FIX_ver2.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild").get()
        }
    }

    instrumentCode = false
    buildSearchableOptions = false

    pluginVerification {
        ides {
            select {
                types = listOf(
                    IntelliJPlatformType.PyCharmCommunity
                )
                channels = listOf(
                    ProductRelease.Channel.RELEASE,
                    ProductRelease.Channel.EAP
                )
                sinceBuild = providers.gradleProperty("sinceBuild").get()
            }
        }
    }
}

@Suppress("unused")
intellijPlatformTesting {
    val testPlatformVersion = providers.gradleProperty("testPlatformVersion")

    val runPyCharm by runIde.registering {
        type = IntelliJPlatformType.PyCharm
        version = testPlatformVersion

        val testProjectPath = file("$rootProject/test-projects/TestFileSet").absolutePath

        task {
            args = listOf(testProjectPath)
            systemProperty("idea.trust.all.projects", "true")
        }
    }

    val runCLion by runIde.registering {
        type = IntelliJPlatformType.CLion
        version = testPlatformVersion
        plugins {
            plugin(providers.gradleProperty("pythonPlugin"))
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            languageVersion = KotlinVersion.DEFAULT
            apiVersion = KotlinVersion.KOTLIN_2_2
        }
    }

    register<Exec>("runMpyScriptMinification") {
        description = "Minify MicroPython scripts and put them in the resources directory"
        group = "build"

        val pythonExecutable = if (System.getProperty("os.name").lowercase().contains("win")) {
            ".venv/Scripts/python.exe"
        } else {
            ".venv/bin/python3"
        }

        executable = file(pythonExecutable).absolutePath
        args = listOf("project-scripts/minify_scripts.py")
        workingDir = rootProject
    }

    register<DefaultTask>("processBundledResources") {
        description = "Process bundled resources from data directory"
        group = "build"

        val boardsJson = file("$rootProject/data/micropython_boards.json")
        val stubsJson = file("$rootProject/data/micropython_stubs.json")
        val bundledDir = file("$rootProject/src/main/resources/bundledInfo")

        val flashingInfoFile = bundledDir.resolve("bundled_flashing_info.json")
        val stubsInfoFile = bundledDir.resolve("bundled_stubs_index_info.json")

        inputs.files(boardsJson, stubsJson)
        outputs.files(flashingInfoFile, stubsInfoFile)

        doLast {
            println("Processing bundled resources...")

            // Clean and recreate bundled directory
            bundledDir.deleteRecursively()
            bundledDir.mkdirs()

            println("Bundling flashing info")

            // Process boards JSON
            val boardsContent = boardsJson.readText()
            val boardsData = groovy.json.JsonSlurper().parseText(boardsContent) as Map<*, *>

            val flashingInfo = mapOf(
                "compatibleIndexVersion" to boardsData["version"],
                "supportedPorts" to boardsData["supportedPorts"],
                "portToExtension" to boardsData["portToExtension"],
                "espMcuToOffset" to boardsData["espMcuToOffset"]
            )

            flashingInfoFile.writeText(
                groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(flashingInfo))
            )

            println("Bundling stub info")

            // Process stubs JSON
            val stubsContent = stubsJson.readText()
            val stubsData = groovy.json.JsonSlurper().parseText(stubsContent) as Map<*, *>

            val stubsInfo = mapOf(
                "compatibleIndexVersion" to stubsData["version"]
            )

            stubsInfoFile.writeText(
                groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(stubsInfo))
            )

            println("Bundled resources processed successfully")
        }
    }

    named<ProcessResources>("processResources") {
        dependsOn("runMpyScriptMinification")
        dependsOn("processBundledResources")

        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        // Include EULA.txt in the archive
        from(rootProject.toString()) {
            into("license")
            include("EULA.txt")
        }
    }

    test {
        testLogging.showExceptions = true
        useJUnitPlatform()
    }
}