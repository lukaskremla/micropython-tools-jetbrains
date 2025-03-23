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


___D=print
import network as ___C,time,gc
def ___A():
	B=%s;E=B;G=B;H=B;___A=___C.WLAN(___C.STA_IF);___A.active(True);___A.connect(E,G);F=0
	while not ___A.isconnected():
		time.sleep(1);F+=1
		if F>H:___D(f'ERROR: Connecting to "{E}" failed, connection timed out. Check your network settings.');break
	___D(f"IP: {___A.ifconfig()[0]}")
___A()
del ___A
del ___D
gc.collect()