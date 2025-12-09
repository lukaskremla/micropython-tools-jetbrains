import json

PATH_TO_BOARDS_JSON = "../data/micropython_boards.json"
PATH_TO_MCU_JSON = "../bundled/bundled_flashing_info.json"

with open(PATH_TO_BOARDS_JSON, "r") as bf:
    parsed_boards_json = json.loads(bf.read())

    bundled_info = {
        "compatibleIndexVersion": parsed_boards_json["version"],
        "supportedPorts": parsed_boards_json["supportedPorts"],
        "portToExtension": parsed_boards_json["portToExtension"],
        "espMcuToOffset": parsed_boards_json["espMcuToOffset"]
    }

    with open(PATH_TO_MCU_JSON, "w") as mf:
        json.dump(bundled_info, mf, indent=2, ensure_ascii=False)
