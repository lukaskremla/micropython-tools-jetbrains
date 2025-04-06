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

import binascii
import gc
import os


class t:
    def __init__(self):
        self.s_to_id = {}
        self.b = bytearray(1024)
        self.i = 0

    def m(self, p):
        for result in os.ilistdir(p):
            file_path = f"{p}/{result[0]}" if p != "/" else f"/{result[0]}"
            stats = os.statvfs(file_path)

            if stats in self.s_to_id:
                volume_id = self.s_to_id[stats]
            else:
                volume_id = self.s_to_id[stats] = self.i
                self.i += 1

            crc32 = 0
            if not result[1] & 0x4000:
                with open(file_path, "rb") as f:
                    while True:
                        n = f.readinto(self.b)
                        if n == 0:
                            break
                        if n < 1024:
                            crc32 = binascii.crc32(self.b[:n], crc32)
                        else:
                            crc32 = binascii.crc32(self.b, crc32)
                    crc32 = "%08x" % (crc32 & 0xffffffff)

            print(result[1], result[3] if len(result) > 3 else -1, file_path, volume_id, crc32, sep="&")
            if result[1] & 0x4000:
                self.m(file_path)


t().m("/")
del t
gc.collect()
