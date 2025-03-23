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


import os
import shutil
import re

import python_minifier

mpy_source_dir = "MicroPythonSource"
target_dir = "../scripts/MicroPythonOptimized"

try:
    shutil.rmtree(target_dir)
except FileNotFoundError:
    pass

os.mkdir(target_dir)

for file in os.listdir(mpy_source_dir):
    print(f"Processing {file}")

    source_path = os.path.join(mpy_source_dir, file)
    target_path = os.path.join(target_dir, file)

    # Temporarily avoid minifying uftpd, there are too many edge cases that must be handled
    if file.startswith("uftpd") or file.startswith("mini_uftpd"):
        with open(source_path) as f:
            with open(target_path, "w") as t:
                t.write(f.read())
        continue

    with open(source_path) as s:
        all_lines = s.readlines()

        # Separate the license header
        license_header = "".join(all_lines[:17])
        # Separate the script's code
        script_content = "".join(all_lines[17:])

        # Conduct minification
        minified_code = python_minifier.minify(
            script_content,
            rename_locals=True,
            rename_globals=True,
            remove_annotations=True,
            remove_pass=True,
            remove_literal_statements=True,
            combine_imports=True,
        )

        # Patterns for matching the corresponding statements
        global_pattern = r"^([a-zA-Z0-9_]+)\s*="
        def_pattern = r"\bdef\s+([a-zA-Z0-9_]+)"
        class_pattern = r"\bclass\s+([a-zA-Z0-9_]+)"
        import_as_pattern = r"\bas\s+([a-zA-Z0-9_]+)"


        # Collect the patterns
        found_global_patterns = re.findall(global_pattern, minified_code, re.MULTILINE)
        found_def_patterns = re.findall(def_pattern, minified_code)
        found_class_patterns = re.findall(class_pattern, minified_code)
        found_as_patterns = re.findall(import_as_pattern, minified_code)

        # Avoid mangling start() and stop() uftpd methods as they need to be consistent for other parts of the code
        #if file.startswith("uftpd") or file.startswith("mini_uftpd"):
        #    found_def_patterns.remove("start")
        #    found_def_patterns.remove("stop")

        # Join the collected patterns to a list
        names_to_mangle = (
            found_global_patterns +
            found_def_patterns +
            found_class_patterns +
            found_as_patterns
        )

        # Prepare a list for saving global names that should be deleted at the end
        global_names_to_del = []

        for name in names_to_mangle:
            # Skip __init__ methods of classes
            if not name.startswith("__init__"):
                # Strip leading underscores and then mangle the name, this ensures three underscores
                mangled_name = '___' + name.lstrip("_")
                # Substitute the original names with the mangled ones
                minified_code = re.sub(r'\b' + name + r'\b', mangled_name, minified_code)

                # Append global patterns to the del set
                if name in found_global_patterns:
                    global_names_to_del.append(mangled_name)

        # De-stringify templates, these are later replaced with real values during command execution
        minified_code = minified_code.replace("\'%s\'", "%s")

        # Map the global name del statements
        del_statements = [f"del {name}" for name in global_names_to_del]
        del_statements = "\n".join(del_statements)

        with open(target_path, "w") as t:
            t.write(license_header)
            t.write(minified_code)
            # Only write additional del statements if there are any
            if len(del_statements) > 0:
                t.write("\n" + del_statements)
            # Append gc.collect() at the end to assure proper garbage collection
            t.write("\n" + "gc.collect()")