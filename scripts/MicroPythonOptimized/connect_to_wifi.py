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


import network, time,gc
def ___c():
    s = %s
    p = %s
    t = %s
    w = network.WLAN(network.STA_IF)
    w.active(True)
    w.connect(s, p)
    i = 0
    while not w.isconnected():
        time.sleep(1)
        i+= 1
        if i > t:
            print(f"ERROR: Connecting to \"{s}\" failed, connection timed out. Check your network settings.")
            break
    print(f"IP: {w.ifconfig()[0]}")
___c()
del ___c
gc.collect()
