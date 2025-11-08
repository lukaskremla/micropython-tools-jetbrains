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

import gc


def m():
    try:
        default_free_mem = gc.mem_free()

        try:
            import binascii
            has_crc32 = hasattr(binascii, "crc32")
            can_encode_base64 = hasattr(binascii, "b2a_base64")
            can_decode_base64 = hasattr(binascii, "a2b_base64")
        except:
            has_crc32, can_encode_base64, can_decode_base64 = False

        print(default_free_mem, has_crc32, can_encode_base64, can_decode_base64, sep="&")

    except Exception as e:
        print(f"ERROR: {e}")


m()
del m
gc.collect()
