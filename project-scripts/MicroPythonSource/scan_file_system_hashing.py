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



import os, gc, binascii


class ___list_files_class:
    def __init__(self):
        self.stats_to_volume_id = {}
        self.buffer = bytearray(1024)
        self.i = 0

    def list_files(self, path):
        for result in os.ilistdir(path):
            file_path = f"{path}/{result[0]}" if path != "/" else f"/{result[0]}"
            stats = os.statvfs(file_path)

            if stats in self.stats_to_volume_id:
                volume_id = self.stats_to_volume_id[stats]
            else:
                volume_id = self.stats_to_volume_id[stats] = self.i
                self.i += 1

            hash = 0
            if not result[1] & 0x4000:
                with open(file_path, "rb") as f:
                    while True:
                        n = f.readinto(self.buffer)
                        if n == 0:
                            break
                        if n < 1024:
                            hash = binascii.crc32(self.buffer[:n], hash)
                        else:
                            hash = binascii.crc32(self.buffer, hash)
                    hash = "%08x" % (hash & 0xffffffff)

            print(result[1], result[3] if len(result) > 3 else -1, file_path, volume_id, hash, sep="&")
            if result[1] & 0x4000:
                self.list_files(file_path)

___list_files_class().list_files("/")
del ___list_files_class
gc.collect()