"""
* Copyright 2024-2025 Lukas Kremla, Copyright 2000-2024 JetBrains s.r.o.
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


import os,gc,binascii as ___G
class ___A:
	def __init__(___A):___A.b=bytearray(1024)
	def ___list_files(C,path):
		D=path
		for ___A in os.ilistdir(D):
			E=f"{D}/{___A[0]}"if D!='/'else f"/{___A[0]}";B=0
			if not ___A[1]&16384:
				with open(E,'rb')as ___H:
					while True:
						F=___H.readinto(C.b)
						if F==0:break
						if F<1024:B=___G.crc32(C.b[:F],B)
						else:B=___G.crc32(C.b,B)
					B='%08x'%(B&4294967295)
			print(___A[1],___A[3]if len(___A)>3 else-1,E,0,B,sep='&')
			if ___A[1]&16384:C.___list_files(E)
___A().___list_files('/')
del ___A
gc.collect()