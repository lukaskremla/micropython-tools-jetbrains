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
import os

import binascii

import vfs

ba = bytearray(1024)
mv = memoryview(ba)
___l = False


def s():
    try:
        mount_points = [mount_tuple[1] for mount_tuple in vfs.mount()]
    except TypeError:
        if ___l:
            path_to_stat_tuple = {"/": os.statvfs("/")}

            for result in os.ilistdir("/"):
                if result[1] & 0x4000:
                    path = f"/{result[0]}"
                    stats = os.statvfs(path)
                    if stats not in path_to_stat_tuple.values():
                        path_to_stat_tuple[path] = stats

            mount_points = path_to_stat_tuple.keys()
        else:
            mount_points = ["/"]

    for mount_point in mount_points:
        fs_stats = os.statvfs(mount_point)
        total_bytes = fs_stats[0] * fs_stats[2]
        free_bytes = fs_stats[0] * fs_stats[3]

        print(mount_point, free_bytes, total_bytes, sep="&")

    m("/")


def m(p):
    for result in os.ilistdir(p):
        file_path = f"{p}/{result[0]}" if p != "/" else f"/{result[0]}"
        # Utilize the fact that 0 evaluates to False and other integers to True
        file_type = 1 if result[1] & 0x4000 else 0

        crc32 = 0
        if not file_type:
            with open(file_path, "rb") as f:
                while True:
                    n = f.readinto(ba)
                    if n == 0:
                        break
                    crc32 = binascii.crc32(mv[0:n], crc32)

                crc32 = "%08x" % (crc32 & 0xffffffff)

        print(file_path, file_type, result[3] if len(result) > 3 else -1, crc32, sep="&")

        if file_type:
            m(file_path)


s()
del ba
del mv
del s
del m
gc.collect()
