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


import os, gc
def ___i():
    try:
        c = True
        b = True
        s = os.uname()
        v = s[3]
        d = s[4]
        try:
            from binascii import crc32
        except ImportError:
            c = False
        try:
            from binascii import a2b_base64
        except ImportError:
            b = False
        print(v, d, c, b, sep="&")
    except Exception as e:
        print(f"ERROR: {e}")
___i()
del ___i
gc.collect()