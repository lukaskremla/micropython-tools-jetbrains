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
import sys


def m():
    try:
        system_info = os.uname()

        version = system_info[3]
        description = system_info[4]

        has_crc32 = hasattr(binascii, "crc32")
        can_encode_base64 = hasattr(binascii, "b2a_base64")
        can_decode_base64 = hasattr(binascii, "a2b_base64")

        platform = sys.platform
        byteorder = sys.byteorder

        mpy_version = None
        mpy_subversion = None
        mpy_arch = None
        arch_name = None

        word_size = 32
        small_int_bits = 31
        maxsize = 0
        if hasattr(sys, "maxsize"):
            maxsize = sys.maxsize

            bits = 0
            v = maxsize
            while v:
                bits += 1
                v >>= 1

            word_size = 64 if bits > 32 else 32

            small_int_bits = bits - 1

        if hasattr(sys, "implementation") and hasattr(sys.implementation, "_mpy"):
            sys_mpy = sys.implementation._mpy

            mpy_version = sys_mpy & 0xff
            mpy_subversion = (sys_mpy >> 8) & 3

            arch_idx = sys_mpy >> 10
            if arch_idx > 0:
                arch_names = [None, 'x86', 'x64',
                              'armv6', 'armv6m', 'armv7m', 'armv7em', 'armv7emsp', 'armv7emdp',
                              'xtensa', 'xtensawin', 'rv32imc']
                if arch_idx < len(arch_names):
                    arch_name = arch_names[arch_idx]
                    mpy_arch = arch_idx

        print(version, description, has_crc32, can_encode_base64, can_decode_base64, platform, byteorder, maxsize,
              mpy_version, mpy_subversion,
              mpy_arch, arch_name, word_size, small_int_bits, sep="&")

    except Exception as e:
        print(f"ERROR: {e}")


m()
del m
gc.collect()
