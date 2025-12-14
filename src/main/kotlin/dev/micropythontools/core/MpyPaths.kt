/*
 * Copyright 2025 Lukas Kremla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.micropythontools.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal object MpyPaths {
    val stubBaseDir: Path by lazy {
        val base = globalAppDataBase()
        val dir = base.resolve("stubs")
        Files.createDirectories(dir)
        dir
    }

    val mpyCrossBaseDir: Path by lazy {
        val base = globalAppDataBase()
        val dir = base.resolve("mpy-cross")
        Files.createDirectories(dir)
        dir
    }

    val packagesBaseDir: Path by lazy {
        val base = globalAppDataBase()
        val dir = base.resolve("packages")
        Files.createDirectories(dir)
        dir
    }

    // Bundled resource paths
    const val BUNDLED_STUB_PACKAGE_INFO_PATH = "bundledInfo/bundled_stubs_index_info.json"
    const val BUNDLED_FIRMWARE_FLASHING_INFO_PATH = "bundledInfo/bundled_flashing_info.json"
    const val BUNDLED_RP2_NUKE_PATH = "RP2Nuke/universal_flash_nuke.uf2"

    // Stub package management constants
    const val STUB_PACKAGE_METADATA_FILE_NAME = "micropython-tools-stub-metadata.json"
    const val STDLIB_STUB_PACKAGE_NAME = "micropython-stdlib-stubs"

    // Package management constants
    const val PYTHON_PACKAGE_DIST_INFO_SUFFIX = ".dist-info"
    const val ESPTOOL_PACKAGE_NAME = "esptool"
    const val ESPTOOL_VERSION = "5.1.0"

    fun globalAppDataBase(): Path {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val appDataDir = when {
            os.contains("win") -> {
                // Prefer LOCALAPPDATA (machine-local); fallback to APPDATA; last resort: user.home
                val local = System.getenv("LOCALAPPDATA")
                val roaming = System.getenv("APPDATA")
                Paths.get((local ?: roaming) ?: System.getProperty("user.home")).resolve(MpyPluginInfo.PLUGIN_ID)
            }

            os.contains("mac") || os.contains("darwin") -> {
                Paths.get(System.getProperty("user.home"), "Library", "Application Support", MpyPluginInfo.PLUGIN_ID)
            }

            else -> {
                // Linux/Unix – XDG first, then ~/.local/share
                val xdg = System.getenv("XDG_DATA_HOME")
                val base = if (!xdg.isNullOrBlank()) Paths.get(xdg)
                else Paths.get(System.getProperty("user.home"), ".local", "share")
                base.resolve(MpyPluginInfo.PLUGIN_ID.lowercase())
            }
        }

        Files.createDirectories(appDataDir)

        return appDataDir
    }
}