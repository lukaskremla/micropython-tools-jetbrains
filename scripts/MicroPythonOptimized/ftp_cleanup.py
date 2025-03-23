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


import network as ___A,gc
def ___B():
	for C in[___A.STA_IF,___A.AP_IF]:
		___B=___A.WLAN(C)
		if not ___B.active():continue
		try:___B.disconnect()
		except:pass
		___B.active(False)
	try:___stop()
	except:pass
___B()
del ___B
gc.collect()