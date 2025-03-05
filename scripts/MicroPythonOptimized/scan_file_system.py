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


import os, gc
def ___l(p):
    for r in os.ilistdir(p):
        f = f"{p}/{r[0]}" if p != "/" else f"/{r[0]}"
        print(r[1], r[3] if len(r) > 3 else -1, f, 0, 0, sep="&")
        if r[1] & 0x4000:
            ___l(f)
___l("/")
del ___l
gc.collect()