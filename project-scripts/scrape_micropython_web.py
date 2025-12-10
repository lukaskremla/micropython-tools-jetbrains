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
from datetime import datetime
from typing import Any, Dict

import requests
from bs4 import BeautifulSoup

MCU_PARAM = "?mcu="

path = os.path.abspath(__file__)
current_dir = os.path.dirname(path)
project_dir = os.path.dirname(current_dir)

board_json_path = os.path.join(project_dir, "data/micropython_boards.json")

supported_ports = ("esp32", "esp8266", "rp2", "samd")

port_to_extension = {
    "esp32": ".bin",
    "esp8266": ".bin",
    "rp2": ".uf2",
    "samd": "uf2"
}

esp_mcu_to_offset = {
    # ESP8266
    "esp8266": 0x0,

    # ESP32
    "esp32": 0x1000,

    # ESP32-S series
    "esp32s2": 0x1000,
    "esp32s3": 0x0,

    # ESP32-C series
    "esp32c2": 0x0,
    "esp32c3": 0x0,
    "esp32c5": 0x0,
    "esp32c6": 0x0,

    # ESP32-P series
    "esp32p4": 0x2000,
}

session = requests.Session()
session.headers.update({
    'User-Agent': 'MicroPython-Tools-Plugin-Firmware-Index-Scraper/1.0 (jetbrains-ide-plugin)'
})


def retrieve_page_links_from_url(url: str) -> list:
    # Get raw response
    response = session.get(url)

    # Parse into html
    beautiful_soup = BeautifulSoup(response.text, 'html.parser')

    # Collect all "a" tags
    a_tags = beautiful_soup.find_all("a")

    # Filter for href links only
    links = [a_tag.get("href", "") for a_tag in a_tags]

    return links


def retrieve_page_links_from_beautiful_soup(beautiful_soup: BeautifulSoup) -> list:
    # Collect all "a" tags
    a_tags = beautiful_soup.find_all("a")

    # Filter for href links only
    links = [a_tag.get("href", "") for a_tag in a_tags]

    return links


def retrieve_page_headings(url: str) -> list:
    # Get raw response
    response = session.get(url)

    # Parse into html
    beautiful_soup = BeautifulSoup(response.text, 'html.parser')

    # Collect all "h" tags
    h_tags = beautiful_soup.find_all('h2')

    # Collect text of the "h" tags
    headings = [h_tag.text for h_tag in h_tags]

    return headings


def get_port_from_mcu(mcu_name: str) -> str | None:
    # Find the port this mcu belongs to
    for port in supported_ports:
        if mcu_name.startswith(port):
            return port

    # If no port is found, return None (as not supported)
    return None


def main():
    micropython_board_map: dict[str, Any] = {
        "version": "1.0.0",
        "timestamp": "",  # Filled out at the end
        "supportedPorts": list(supported_ports),
        "portToExtension": port_to_extension,
        "espMcuToOffset": esp_mcu_to_offset,
        "boards": []
    }

    # Initial URL on which to start searching for MicroPython firmware and boards
    download_url = "https://micropython.org/download/"

    # Collect links of the downloads page
    download_page_links = retrieve_page_links_from_url(download_url)

    for download_page_link in download_page_links:
        # Filter only for the links of mcu subgroups
        if download_page_link.startswith(MCU_PARAM):
            # Retrieve mcu name from the page link
            mcu_name = download_page_link.removeprefix(MCU_PARAM)

            if mcu_name.startswith("esp") and mcu_name not in esp_mcu_to_offset:
                raise RuntimeError(f"Mcu \"{mcu_name}\" has no mapped offset")

            # Determine the port from the mcu name
            port = get_port_from_mcu(mcu_name)

            # Only continue if the port was found (unsupported ports return None)
            if port:
                # Build the url of the mcu's page
                mcu_page_url = download_url + download_page_link

                # Grab the links of the mcu's page
                mcu_page_links = retrieve_page_links_from_url(mcu_page_url)

                for mcu_page_link in mcu_page_links:
                    # Filter these characters out, as the board page links don't contain them
                    if not any(char in mcu_page_link for char in ("?", "/")):
                        # Build the url of the board's page
                        board_page_url = download_url + mcu_page_link

                        # Retrieve the html response of this page
                        board_page_response = session.get(board_page_url)

                        # Parse the board page HTML
                        board_page_beautiful_soup = BeautifulSoup(board_page_response.text, 'html.parser')

                        # List of firmware names (tags) to the links of all available binaries for it
                        firmware_name_to_link_parts: Dict[str, list] = {}

                        # The board name heading text and vendor will be saved here
                        board_name = None
                        vendor = None

                        # Iterate over all html tags on the page
                        for html_element in board_page_beautiful_soup.descendants:
                            # Ensure a valid tag that can be parsed
                            if hasattr(html_element, "name"):
                                # Handle h2 tags
                                if html_element.name == "h2":
                                    # Get the text
                                    tag_text = html_element.text

                                    # If no board name was set, set it, the first heading is it
                                    if not board_name:
                                        board_name = tag_text.strip()
                                    # If the heading contains "Firmware", the following link tags will contain the binary links
                                    elif tag_text.startswith("Firmware"):
                                        # Parse out the specific name of this heading
                                        firmware_name = tag_text.removeprefix("Firmware (").removesuffix(")").strip()

                                        if firmware_name == "Firmware":
                                            firmware_name = "Standard"

                                        # Prepare an empty list for the heading
                                        firmware_name_to_link_parts[firmware_name] = []
                                # Handle a tags
                                elif html_element.name == "a":
                                    # Get only href a tags
                                    # noinspection PyUnresolvedReferences
                                    board_page_link = html_element.get("href", "")

                                    resources_path = "/resources/firmware/"

                                    # Ensure a valid firmware link
                                    if resources_path in board_page_link:
                                        required_extension = port_to_extension[port]

                                        if required_extension in board_page_link:
                                            # Find the latest firmware name (key) to append to
                                            last_key = list(firmware_name_to_link_parts.keys())[-1]

                                            # Remove the remaining resources path
                                            link_part = board_page_link.removeprefix(resources_path)

                                            # Remove the board ID
                                            link_part = link_part.removeprefix(mcu_page_link)

                                            # Append the newly found link
                                            firmware_name_to_link_parts[last_key].append(link_part)
                                elif html_element.name == "strong":
                                    if html_element.text == "Vendor:":
                                        vendor = html_element.next_sibling.strip()

                        # Normal sort priority
                        sort_priority = 2

                        # Add (Generic) to Espressif boards
                        if vendor == "Espressif" and port.startswith("esp"):
                            board_name = board_name + " (Generic)"
                            # Espressif boards get higher sort priority to appear first
                            sort_priority = 1
                        # SparkFun has a board with a name duplicit to the Espressif "ESP32 / WROOM" board
                        elif board_name == "ESP32 / WROOM" and vendor == "SparkFun":
                            board_name = board_name + " (SparkFun)"
                        # Pico boards are used most commonly, sort them first for convenience
                        elif board_name in ("Pico", "Pico W", "Pico 2", "Pico 2 W"):
                            sort_priority = 1

                        # Format the board dictionary with its info
                        board = {
                            "id": mcu_page_link,
                            "name": board_name,
                            "vendor": vendor,
                            "port": port,
                            "mcu": mcu_name,
                            "firmwareNameToLinkParts": firmware_name_to_link_parts,
                            "sortPriority": sort_priority
                        }

                        # Save the scraped board
                        micropython_board_map["boards"].append(board)

                        print(f"Scraped \"{board_name}\"")

    # Sort the boards to ensure generic ESP32 boards come first
    micropython_board_map["boards"].sort(key=lambda b: (b["mcu"], b["sortPriority"]))

    # Remove the "sortPriority" param to avoid cluttering the final json
    for board in micropython_board_map["boards"]:
        board.pop("sortPriority", None)

    # Save the timestamp at the end
    micropython_board_map["timestamp"] = datetime.now().isoformat()

    # Collect all IDs of the new boards
    new_board_ids = [board["id"] for board in micropython_board_map["boards"]]

    try:
        with open(board_json_path, "r") as f:
            print("Testing for regression...")

            old_json_map = json.loads(f.read())

            for old_board in old_json_map["boards"]:
                old_board_id = old_board["id"]

                if old_board_id not in new_board_ids:
                    raise RuntimeError(
                        f"A previously scraped board is missing in the newly scraped map: \"{old_board_id}\"")

            print("No regression found")
    except FileNotFoundError:
        pass

    print("Saving the newly scraped data...")
    with open(board_json_path, "w") as f:
        json.dump(micropython_board_map, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
