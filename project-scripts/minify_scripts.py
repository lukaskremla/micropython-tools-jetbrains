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

mpy_source_dir = os.path.join(project_dir, "project-scripts/MicroPythonSource")
scripts_dir = os.path.join(project_dir, "scripts")
target_dir = os.path.join(scripts_dir, "MicroPythonMinified")
scripts_to_split = ["socket_transfer.py"]

try:
    shutil.rmtree(scripts_dir)
except FileNotFoundError:
    pass

os.mkdir(scripts_dir)
os.mkdir(target_dir)


def do_minification(source_directory, target_directory):
    for file in os.listdir(source_directory):
        print(f"Processing {file}")

        source_path = os.path.join(source_directory, file)
        target_path = os.path.join(target_directory, file)

        if os.path.isdir(source_path):
            os.mkdir(target_path)
            do_minification(source_path, target_path)
            continue

        # Temporarily avoid minifying uftpd, there are too many edge cases that must be handled
        if file.startswith("uftpd") or file.startswith("mini_uftpd") or file.startswith("ftp_cleanup"):
            with open(source_path) as f:
                with open(target_path, "w") as t:
                    t.write(f.read())
            continue

        with open(source_path) as s:
            all_lines = s.readlines()

            # Separate the license header
            license_header = "".join(all_lines[:15])
            # Separate the script's code
            script_content = "".join(all_lines[15:])

            # Conduct minification
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

            # Avoid mangling start() and stop() uftpd methods as they need to be consistent for other parts of the code
            # if file.startswith("uftpd") or file.startswith("mini_uftpd"):
            #    found_def_patterns.remove("start")
            #    found_def_patterns.remove("stop")

            # Join the collected patterns to a list
            names_to_mangle = (
                    found_global_patterns +
                    found_def_patterns +
                    found_class_patterns
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
            # Each string template has a custom integer value in the source code
            # This is to prevent the minifier from only defining one variable as "%s" and then defining all other variables
            # To be this variable
            for i in range(10):
                minified_code = minified_code.replace(f"\'%s{i}\'", "%s")

            # Also handle the case where just one template is used without an index
            minified_code = minified_code.replace(f"\'%s\'", "%s")

            # Map the global name del statements
            del_statements = [f"del {name}" for name in global_names_to_del]
            del_statements = "\n".join(del_statements)

            with open(target_path, "w") as t:
                t.write(license_header)
                t.write(minified_code)
                # Only write additional del statements if there are any
                if len(del_statements) > 0:
                    t.write("\n" + del_statements)
                if len(del_statements) > 0 or not minified_code.endswith("gc.collect()"):
                    # Append gc.collect() at the end to assure proper garbage collection
                    t.write("\n" + "gc.collect()")

            # Check for files that are meant to be split into the main and clean up scripts
            if file in scripts_to_split:
                with open(target_path, "r+") as ts:
                    all_lines = ts.readlines()

                    # The cleanup section always starts with a del statement
                    for line in all_lines:
                        if not line.startswith("del"):
                            continue

                        # Get index to split from and split
                        index = all_lines.index(line)
                        cleanup_script_content = "".join(all_lines[index:])
                        # Create the base to which the "_cleanup.py" will be appended
                        target_path_base = target_path.removesuffix(".py")

                        # Write the cleanup script
                        with open(f"{target_path_base}_cleanup.py", "w") as cs:
                            cs.write(license_header)
                            cs.write(cleanup_script_content)

                        # Remove the clean_up script from the original, also remove a redundant whitespace at the end
                        new_original_script_content = "".join(all_lines).removesuffix(
                            cleanup_script_content).removesuffix("\n")

                        ts.seek(0)
                        ts.truncate(0)
                        ts.write(new_original_script_content)

                        break


do_minification(mpy_source_dir, target_dir)
