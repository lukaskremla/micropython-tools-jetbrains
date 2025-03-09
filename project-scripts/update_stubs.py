import json
import os
import shutil
import subprocess

import requests

try:
    url = "https://raw.githubusercontent.com/Josverl/micropython-stubs/main/data/stub-packages.json"

    subprocess.run(["pip", "install", "--upgrade", "pip"])

    stubs_dir = "../stubs"

    if os.path.exists(stubs_dir):
        shutil.rmtree(stubs_dir)

    os.mkdir(stubs_dir)

    response = requests.get(url)
    response.raise_for_status()

    data = json.loads(response.text)

    if "packages" in data:
        packages = data["packages"]
        print(f"Loaded {len(packages)} packages from the JSON file")

        for package in packages:
            package_name = package[0]
            version = package[1]

            print(f"Installing {package_name} version {version}...")

            stub_package_path = os.path.join(stubs_dir, f"{package_name}_{version}")
            os.mkdir(stub_package_path)

            subprocess.run(
                ["pip", "install", f"{package_name}~={version}", "--no-user", "--target", stub_package_path]
            )

    else:
        raise KeyError("JSON structure doesn't contain required 'packages' key")

except requests.exceptions.RequestException as e:
    raise ConnectionError(f"Failed to fetch the JSON file: {e}")
except json.JSONDecodeError as e:
    raise ValueError(f"Invalid JSON format: {e}")
