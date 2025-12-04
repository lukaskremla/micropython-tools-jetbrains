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

import com.intellij.openapi.project.Project

/**
 * Flasher for SAMD21/SAMD51 boards using UF2 bootloader.
 * Matches boards with Board-ID containing "SAMD" (e.g., "SAMD21", "SAMD51").
 */
internal class MpySamdFlasher(project: Project) : MpyUf2Flasher(project) {
    override val boardIdPrefix = "SAMD"
}