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
def ___c():
    for i in [network.STA_IF, network.AP_IF]:
        w = network.WLAN(i)

        if not w.active():
            continue

        try:
            w.disconnect()
        except:
            pass

        w.active(False)

    try:
        uftpd.stop()
    except:
        try:
            ___ftp.stop()
            del ___ftp
        except:
            pass
___c()
del ___c
gc.collect()