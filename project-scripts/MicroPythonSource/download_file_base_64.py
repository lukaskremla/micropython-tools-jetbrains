"""
* Copyright 2025 Lukas Kremla
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

import binascii
import gc


def m():
    with open("%s", "rb") as f:
        b = bytearray(384)
        mw = memoryview(b)
        while True:
            n = f.readinto(b)
            if n == 0:
                break
            if n < 384:
                print(binascii.b2a_base64(b[:n]), end="")
            else:
                print(binascii.b2a_base64(b), end="")


m()
del m
gc.collect()
