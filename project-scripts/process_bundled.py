"""
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
"""

import json
import os
import shutil

path = os.path.abspath(__file__)
current_dir = os.path.dirname(path)
project_dir = os.path.dirname(current_dir)

to_bundle_dir = os.path.join(project_dir, "toBundle")
bundled_dir = os.path.join(project_dir, "bundled")

boards_json_path = os.path.join(project_dir, "data/micropython_boards.json")
stubs_json_path = os.path.join(project_dir, "data/micropython_stubs.json")

MCU_JSON_NAME = "bundled_flashing_info.json"
RP2_NUKE_NAME = "universal_flash_nuke.uf2"
STUBS_JSON_INFO = "bundled_stubs_index_info.json"

print(__file__)

try:
    shutil.rmtree(bundled_dir)
except FileNotFoundError:
    pass

os.mkdir(bundled_dir)

with open(boards_json_path, "r") as bf:
    parsed_boards_json = json.loads(bf.read())
    bundled_info = {
        "compatibleIndexVersion": parsed_boards_json["version"]
        , "supportedPorts": parsed_boards_json["supportedPorts"]
        , "portToExtension": parsed_boards_json["portToExtension"]
        , "espMcuToOffset": parsed_boards_json["espMcuToOffset"]
    }

    with open(os.path.join(bundled_dir, MCU_JSON_NAME), "w") as mf:
        json.dump(bundled_info, mf, indent=2, ensure_ascii=False)

shutil.copyfile(
    os.path.join(to_bundle_dir, RP2_NUKE_NAME),
    os.path.join(bundled_dir, RP2_NUKE_NAME)
)

with open(stubs_json_path, "r") as sf:
    parsed_stubs_json = json.loads(sf.read())

    bundled_info = {
        "compatibleIndexVersion": parsed_stubs_json["version"]
    }

    with open(os.path.join(bundled_dir, STUBS_JSON_INFO), "w") as tf:
        json.dump(bundled_info, tf, indent=2, ensure_ascii=False)
