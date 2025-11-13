from datetime import datetime
from typing import Any

import requests
from bs4 import BeautifulSoup

MCU_PARAM = "?mcu="

supported_ports = ("esp32", "esp8266", "rp2", "samd", "stm32")

session = requests.Session()
session.headers.update({
    'User-Agent': 'MicroPython-Tools-Plugin-Firmware-Index-Scraper/1.0 (jetbrains-ide-plugin)'
})


def retrieve_page_links(url: str) -> list:
    # Get raw response
    response = session.get(url)

    # Parse into html
    soup = BeautifulSoup(response.text, 'html.parser')

    # Collect all "a" tags
    a_tags = soup.find_all("a")

    # Filter for href links only
    links = [a_tag.get("href", "") for a_tag in a_tags]

    return links


def retrieve_page_headings(url: str) -> list:
    # Get raw response
    response = session.get(url)

    # Parse into html
    soup = BeautifulSoup(response.text, 'html.parser')

    # Collect all "h" tags
    h_tags = soup.find_all('h2')

    # Collect text of the "h" tags
    headings = [h_tag.text for h_tag in h_tags]

    return headings


def retrieve_offset(url: str) -> str:
    # Get raw response
    response = session.get(url)

    # Parse into html
    soup = BeautifulSoup(response.text, 'html.parser')

    # Collect all "p" tags
    p_tags = soup.find_all('p')

    # Collect text of the "p" tags
    texts = [p_tag.text for p_tag in p_tags]

    # This is the first part of the text in the p tag which contains the offset
    address_tag_text = "Then deploy the firmware to the board, starting at address "

    # Iterate over text of all "p" tags
    for text in texts:
        # Look for the offset text
        if text.startswith(address_tag_text):
            # Remove the trailing ":" and return the offset that now remains
            return text.removeprefix(address_tag_text).removesuffix(":")

    # If no offset is found return an empty string
    return ""


def get_port_from_mcu(mcu_name: str) -> str | None:
    # Find the port this mcu belongs to
    for port in supported_ports:
        if mcu_name.startswith(port):
            return port

    # If no port is found, return None (as not supported)
    return None


def main():
    micropython_board_map: dict[str, Any] = {
        "timestamp": "",  # Filled out at the end
        "skimmed_ports": list(supported_ports),
        "boards": []
    }

    # Initial URL on which to start searching for MicroPython firmware and boards
    download_url = "https://micropython.org/download/"

    # Collect links of the downloads page
    download_page_links = retrieve_page_links(download_url)

    for download_page_link in download_page_links:
        # Filter only for the links of mcu subgroups
        if download_page_link.startswith(MCU_PARAM):
            # Retrieve mcu name from the page link
            mcu_name = download_page_link.removeprefix(MCU_PARAM)

            # Determine the port from the mcu name
            port = get_port_from_mcu(mcu_name)

            # Only continue if the port was found (unsupported ports return None)
            if port:
                # Build the url of the mcu's page
                mcu_page_url = download_url + download_page_link

                # Grab the links of the mcu's page
                mcu_page_links = retrieve_page_links(mcu_page_url)

                for mcu_page_link in mcu_page_links:
                    # Filter these characters out, as the board page links don't contain them
                    if not any(char in mcu_page_link for char in ("?", "/")):
                        # Build the url of the board's page
                        board_page_url = download_url + mcu_page_link

                        # Collect all headings of the board's page
                        headings = retrieve_page_headings(board_page_url)

                        # Remove the "Installation Instructions" heading
                        headings.pop(1)

                        # Index 0 is the board name
                        name = headings[0].strip()

                        board = {
                            "id": mcu_page_link,
                            "name": name,
                            "port": port,
                            "mcu": mcu_name,
                            "offset": retrieve_offset(board_page_url),
                            "firmwareNames": headings[1:],  # 1+ on is only used for Firmware headings
                            "firmware": []
                        }

                        # Collect all links of the board's page
                        board_page_links = retrieve_page_links(board_page_url)

                        for board_page_link in board_page_links:
                            # Only grab firmware links
                            if "/resources/firmware/" in board_page_link:
                                if mcu_name.startswith(("esp32", "esp8266")) and ".bin" in board_page_link:
                                    board["firmware"].append(board_page_link)
                                elif mcu_name.startswith(("rp2", "samd")) and ".uf2" in board_page_link:
                                    board["firmware"].append(board_page_link)
                                elif mcu_name.startswith("stm32") and ".dfu" in board_page_link:
                                    board["firmware"].append(board_page_link)

                        # Save the scraped board
                        micropython_board_map["boards"].append(board)

                        print(f"Scraped \"{name}\"")

    # Save the timestamp at the end
    micropython_board_map["timestamp"] = datetime.now().isoformat()
    print(micropython_board_map)


if __name__ == "__main__":
    main()
