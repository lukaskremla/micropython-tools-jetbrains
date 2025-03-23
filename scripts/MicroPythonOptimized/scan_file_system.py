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


import os,gc
def ___A(self,path):
	B=path
	for ___A in os.ilistdir(B):
		C=f"{B}/{___A[0]}"if B!='/'else f"/{___A[0]}";print(___A[1],___A[3]if len(___A)>3 else-1,C,0,0,sep='&')
		if ___A[1]&16384:self.list_files(C)
___A('/')
del ___A
gc.collect()