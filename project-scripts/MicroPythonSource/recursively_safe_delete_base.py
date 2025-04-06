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

import gc
import os


def ___d(p):
    try:
        os.stat(p)
    except:
        return
    try:
        os.remove(p)
        return
    except:
        pass
    for result in os.ilistdir(p):
        file_path = f'{p}/{result[0]}' if p != '/' else f'/{result[0]}'
        if result[1] & 0x4000:
            ___d(file_path)
        else:
            os.remove(file_path)
    try:
        os.rmdir(p)
    except:
        pass
