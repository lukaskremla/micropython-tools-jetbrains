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

def ___initialize():
    try:
        has_binascii = True

        system_info = os.uname()
        version = system_info[3]
        description = system_info[4]

        try:
            import binascii
        except ImportError:
            has_binascii = False

        print(version, description, has_binascii, sep="&")
    except Exception as e:
        print(f"ERROR: {e}")

___initialize()
del ___initialize
gc.collect()