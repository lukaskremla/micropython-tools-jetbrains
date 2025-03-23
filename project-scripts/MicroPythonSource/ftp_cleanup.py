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


import network, gc

def clean_up():
    for interface in [network.STA_IF, network.AP_IF]:
        wlan = network.WLAN(interface)

        if not wlan.active():
            continue

        try:
            wlan.disconnect()
        except:
            pass

        wlan.active(False)

    try:
        ___stop()
    except:
        pass

clean_up()
del clean_up
