import os

import python_minifier

# Doesn't work yet

with os.scandir("MicroPythonSource") as it:
    for entry in it:
        print(entry.path)
    if entry.is_file():
        file_path = entry.path
        with open(file_path, "r") as f:
            code = f.read()

        minified_code = python_minifier.minify(
            code,
            rename_globals=True
        )

        try:
            os.remove(f"../scripts/{entry.name}")
        except Exception:
            pass

        with open(file_path, "w") as f:
            f.write(minified_code)
