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

package persistence

import com.intellij.execution.configurations.RunConfigurationOptions

internal class MpyRunConfUploadOptions : RunConfigurationOptions() {
    var uploadMode by property(0)
    var selectedPaths by list<String>()
    var path by string("")
    var uploadToPath by string("/")
    var switchToReplOnSuccess by property(false)
    var resetOnSuccess by property(true)
    var synchronize by property(false)
    var excludePaths by property(false)
    var excludedPaths by list<String>()
}