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

import gc
import network
import time


def m():
    ssid = "%s1"
    password = "%s2"
    wifi_timeout = "%s3"
    sta = network.WLAN(network.STA_IF)
    sta.active(True)
    sta.connect(ssid, password)
    i = 0
    while not sta.isconnected():
        time.sleep_ms(100)
        i += 1
        if i > wifi_timeout * 10:
            print(f"ERROR: Connecting to \"{ssid}\" failed, connection timed out. Check your network settings.")
            break


m()
del m
gc.collect()
