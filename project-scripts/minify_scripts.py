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
import python_minifier
import re
import shutil

path = os.path.abspath(__file__)
current_dir = os.path.dirname(path)
project_dir = os.path.dirname(current_dir)
pro_project_dir = os.path.dirname(project_dir)

pro_source_dir = os.path.join(pro_project_dir, "proScripts/MicroPythonSource")
mpy_source_dir = os.path.join(project_dir, "project-scripts/MicroPythonSource")
target_dir = os.path.join(project_dir, "src/main/resources/scripts")

try:
    shutil.rmtree(target_dir)
except FileNotFoundError:
    pass

os.mkdir(target_dir)

print("Running MicroPython script minifier...")


def do_minification(source_directory, target_directory):
    for file in os.listdir(source_directory):
        print(f"Minifying {file}")

        source_path = os.path.join(source_directory, file)
        target_path = os.path.join(target_directory, file)

        if os.path.isdir(source_path):
            os.mkdir(target_path)
            do_minification(source_path, target_path)
            continue

        with open(source_path) as s:
            all_lines = s.readlines()

            # Separate the license header
            license_header = "".join(all_lines[:15])
            # Separate the script's code
            script_content = "".join(all_lines[15:])

            # Conduct minification, don't mangle globals to avoid naming collisions
            # the minifier might rename local and global variables to the same name
            minified_code = python_minifier.minify(
                script_content,
                rename_locals=True,
                rename_globals=False,
                remove_annotations=True,
                remove_pass=True,
                remove_literal_statements=True,
                combine_imports=True,
            )

            # Patterns for matching the corresponding statements
            global_pattern = r"^([a-zA-Z0-9_]+)\s*="
            def_pattern = r"\bdef\s+([a-zA-Z0-9_]+)"
            class_pattern = r"\bclass\s+([a-zA-Z0-9_]+)"

            # Collect the patterns
            found_global_patterns = re.findall(global_pattern, minified_code, re.MULTILINE)
            found_def_patterns = re.findall(def_pattern, minified_code)
            found_class_patterns = re.findall(class_pattern, minified_code)

            # Join the collected patterns to a list
            names_to_mangle = (
                    found_global_patterns +
                    found_def_patterns +
                    found_class_patterns
            )

            for name in names_to_mangle:
                if name in ["s", "d"]:
                    raise RuntimeError(
                        "Class, function and variable names can't be \"d\" or \"s\" as they're string format specifiers")
                elif name in ["f"]:
                    raise RuntimeError(
                        "Class, function and variable names can't be \"f\" as it is a protected f string specifier")

            for name in names_to_mangle:
                # Skip __init__ methods of classes
                if not name.startswith("__init__"):
                    # Strip leading underscores and then mangle the name, this ensures three underscores
                    mangled_name = '___' + name.lstrip("_")
                    # Substitute the original names with the mangled ones
                    minified_code = re.sub(r'\b' + name + r'\b', mangled_name, minified_code)

            # De-stringify templates, these are later replaced with real values during command execution
            # Each string template has a custom integer value in the source code
            # This is to prevent the minifier from only defining one variable as "%s" and then defining all other variables
            # To be this variable
            for i in range(10):
                minified_code = minified_code.replace(f"\'%s{i}\'", "%s")

            # Also handle the case where just one template is used without an index
            minified_code = minified_code.replace("\'%s\'", "%s")

            # If the template is used as a variable, it needs to be replaced as well
            minified_code = minified_code.replace("\'%___s\'", " %s")

            with open(target_path, "w") as t:
                t.write(license_header)
                t.write(minified_code)


do_minification(mpy_source_dir, target_dir)
try:
    do_minification(pro_source_dir, target_dir)
except:
    pass
