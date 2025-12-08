import json

PATH_TO_BOARDS_JSON = "../data/micropython_boards.json"
PATH_TO_MCU_JSON = "../bundled/esp_mcus.json"

with open(PATH_TO_BOARDS_JSON, "r") as bf:
    parsed_json = json.loads(bf.read())

    esp_mcu_to_offset = parsed_json["espMcuToOffset"]

    with open(PATH_TO_MCU_JSON, "w") as mf:
        json.dump(esp_mcu_to_offset, mf, indent=2, ensure_ascii=False)
