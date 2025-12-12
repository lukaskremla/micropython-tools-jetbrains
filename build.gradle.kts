import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven("https://cache-redirector.jetbrains.com/maven-central")
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
        snapshots()
    }
}

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.10.5"
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

    publishing {
        val tokenFile = file("publish-token.txt")

        if (tokenFile.exists()) {
            val tokenFileContents = tokenFile.readText()

            if (tokenFileContents.isNotBlank()) {
                token = tokenFileContents
            }
        }
    }

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

    val runPyCharmCommunity by runIde.registering {
        type = IntelliJPlatformType.PyCharm
        version = testPlatformVersion
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

    register<Exec>("runPythonBuildScripts") {
        description = "Run Python build scripts to prepare bundled resources"
        group = "build"

        // Use the virtual environment's Python
        val pythonExecutable = if (System.getProperty("os.name").lowercase().contains("win")) {
            ".venv/Scripts/python.exe"
        } else {
            ".venv/bin/python3"
        }

        executable = file("$ossDir/$pythonExecutable").absolutePath
        args = listOf("project-scripts/run_build_python_scripts.py")
        workingDir = projectDir
    }

    named<ProcessResources>("processResources") {
        dependsOn("runPythonBuildScripts")

        from("scripts") {
            into("scripts")
            include("**/*")
        }

        from("bundled") {
            into("bundled")
            include("**/*")
        }

        // Include EULA.txt in the archive
        from(".") {
            into("license")
            include("EULA.txt")
        }
    }
    test {
        testLogging.showExceptions = true
        useJUnitPlatform()
    }
}