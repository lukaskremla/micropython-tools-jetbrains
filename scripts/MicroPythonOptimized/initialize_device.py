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


___C=print
___B=ImportError
import os,gc
def ___A():
	F=False
	try:
		___A=True;D=True;E=os.uname();G=E[3];H=E[4]
		try:from binascii import crc32
		except ___B:___A=F
		try:from binascii import a2b_base64
		except ___B:D=F
		___C(G,H,___A,D,sep='&')
	except Exception as ___I:___C(f"ERROR: {___I}")
___A()
del ___A
del ___C
del ___B
gc.collect()