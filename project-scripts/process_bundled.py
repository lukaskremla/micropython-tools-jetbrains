import json
import os
import shutil

path = os.path.abspath(__file__)
current_dir = os.path.dirname(path)
project_dir = os.path.dirname(current_dir)

to_bundle_dir = os.path.join(project_dir, "toBundle")
bundled_dir = os.path.join(project_dir, "bundled")

boards_json_path = os.path.join(project_dir, "data/micropython_boards.json")

MCU_JSON_NAME = "bundled_flashing_info.json"
RP2_NUKE_NAME = "universal_flash_nuke.uf2"

print(__file__)

try:
    shutil.rmtree(bundled_dir)
except FileNotFoundError:
    pass

os.mkdir(bundled_dir)

print("")
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

with open(os.path.join(to_bundle_dir, RP2_NUKE_NAME), "rb") as sf:
    with open(os.path.join(bundled_dir, RP2_NUKE_NAME), "wb") as tf:
        tf.write(sf.read())
