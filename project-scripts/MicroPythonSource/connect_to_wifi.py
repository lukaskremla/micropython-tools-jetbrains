"""
* Copyright 2024-2025 Lukas Kremla
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


import network, time, gc

def connect_to_wifi():
    ssid = "%s"
    password = "%s"
    wifi_timeout = "%s"
    sta = network.WLAN(network.STA_IF)
    sta.active(True)
    sta.connect(ssid, password)
    i = 0
    while not sta.isconnected():
        time.sleep(1)
        i+= 1
        if i > wifi_timeout:
            print(f"ERROR: Connecting to \"{ssid}\" failed, connection timed out. Check your network settings.")
            break
    print(f"IP: {sta.ifconfig()[0]}")

connect_to_wifi()
del connect_to_wifi
