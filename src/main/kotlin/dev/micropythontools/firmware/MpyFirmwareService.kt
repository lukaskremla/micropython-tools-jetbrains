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

package dev.micropythontools.firmware

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import dev.micropythontools.core.MpyPaths
import dev.micropythontools.core.MpyScripts
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.pathString

internal data class Board(
    val id: String,
    val name: String,
    val port: String,
    val mcu: String,
    val offset: String,
    val firmwareNameToLink: Map<String, List<String>>
)

@Service(Service.Level.PROJECT)
internal class MpyFirmwareService(private val project: Project) {
    private val client: HttpClient = HttpClient.newHttpClient()

    fun getCachedBoards(): List<Board> {
        val boardJsonFilePath = "${MpyPaths.globalAppDataBase()}/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Try to find the existing cached board list, extract the bundled one if it can't be found
        val boardJsonFile = LocalFileSystem.getInstance().findFileByPath(boardJsonFilePath) ?: extractBundledBoardJson()

        return parseBoardsFromJson(boardJsonFile.readText())
    }

    fun updateCachedBoards() {
        val url = "https://raw.githubusercontent.com/Josverl/micropython-stubs/main/data/stub-packages.json"
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: Throwable) {
            return
        }

        val text = response.body()

        writeCachedBoardJson(text)
    }

    private fun parseBoardsFromJson(jsonString: String): List<Board> {
        val root = JSONObject(jsonString)
        val boardsArray = root.optJSONArray("boards") ?: return emptyList()

        val boards = mutableListOf<Board>()
        for (i in 0 until boardsArray.length()) {
            val boardJson = boardsArray.optJSONObject(i) ?: continue

            // Extract fields from each board object
            val id = boardJson.optString("id", "")
            val name = boardJson.optString("name", "")
            val port = boardJson.optString("port", "")
            val mcu = boardJson.optString("mcu", "")
            val offset = boardJson.optString("offset", "")

            // Handle the firmwareNameToLink map
            val firmwareMap = mutableMapOf<String, List<String>>()
            val firmwareJson = boardJson.optJSONObject("firmwareNameToLink")
            if (firmwareJson != null) {
                for (key in firmwareJson.keys()) {
                    val linksArray = firmwareJson.optJSONArray(key)
                    if (linksArray != null) {
                        val links = mutableListOf<String>()
                        for (j in 0 until linksArray.length()) {
                            links.add(linksArray.optString(j, ""))
                        }
                        firmwareMap[key] = links
                    }
                }
            }

            boards.add(Board(id, name, port, mcu, offset, firmwareMap))
        }

        return boards
    }

    private fun extractBundledBoardJson(): VirtualFile {
        // Path to the bundled json file
        val resourcePath = "/data/${MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME}"

        // Extract the stream
        val stream = MpyScripts::class.java.getResourceAsStream(resourcePath)
            ?: error("Bundled board json file not found: $resourcePath")

        // Read the file's text
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

        return writeCachedBoardJson(text)
    }

    private fun writeCachedBoardJson(text: String): VirtualFile {
        var boardJsonFile: VirtualFile? = null

        // Retrieve the app data dir
        val appDataDir = LocalFileSystem
            .getInstance()
            .refreshAndFindFileByPath(MpyPaths.globalAppDataBase().pathString)
            ?: throw RuntimeException("Failed to find the plugin's data directory")

        // Ensure synchronization on EDT
        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                // Find the existing file or create it
                boardJsonFile = appDataDir.findChild(MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME)
                    ?: appDataDir.createChildData(this, MpyPaths.MICROPYTHON_BOARD_JSON_FILE_NAME)

                // Write the new content, flushing the old one
                VfsUtil.saveText(boardJsonFile, text)

                // Refresh the file in VFS
                boardJsonFile.refresh(false, false)
            }
        }

        // Ensure the file was created
        if (boardJsonFile == null) throw RuntimeException("Failed to write cached board JSON")

        return boardJsonFile
    }
}