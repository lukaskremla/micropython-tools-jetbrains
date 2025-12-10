import json
import os

import requests
from packaging.requirements import Requirement
from packaging.version import Version

path = os.path.abspath(__file__)
current_dir = os.path.dirname(path)
project_dir = os.path.dirname(current_dir)

output_json_path = os.path.join(project_dir, "data/micropython_stubs.json")

session = requests.Session()
session.headers.update({
    'User-Agent': 'MicroPython-Tools-Plugin-Stub-Scraper/1.0 (jetbrains-ide-plugin)'
})

# Get original micropython-stubs GitHub repo JSON list
stubs_json_response = session.get(
    "https://raw.githubusercontent.com/Josverl/micropython-stubs/refs/heads/main/data/stub-packages.json"
)

# Retrieve content
original_content = stubs_json_response.content.decode()

# Parse to a dict
parsed_json = json.loads(original_content)

# Ignore the schema and any other uninteresting tags
original_packages = parsed_json["packages"]

# Get unique package names
package_names = list(set([package[0] for package in original_packages]))

# Ensure stdlib versions info is available
package_names = package_names + ["micropython-stdlib-stubs"]

# Fetch all available versions for each package
package_name_to_pypi_versions = {}

for package_name in package_names:
    # Get packages page response
    response = session.get(f"https://pypi.org/pypi/{package_name}/json")

    data = response.json()

    # Retrieve all versions of the package name
    all_versions = list(data["releases"].keys())

    package_name_to_pypi_versions[package_name] = all_versions

    print(f"Fetched versions for \"{package_name}\"")

# Build index with dependency information
index_packages = []

for package in original_packages:
    package_name = package[0]
    mpy_version = package[1]
    port = package[2]
    board = package[3]
    variant = package[4]

    # Stdlib is handled separately
    if package_name == "micropython-stdlib-stubs":
        continue

    # Get versions matching this MicroPython version and find the latest
    available_versions = package_name_to_pypi_versions[package_name]
    matching_versions = [v for v in available_versions if v.startswith(mpy_version)]
    exact_package_version = max(matching_versions, key=Version)

    if not exact_package_version:
        raise RuntimeError(f"No exact package info found for \"{package_name}\"")

    # Get dependency info for this specific package and MicroPython version
    response = session.get(f"https://pypi.org/pypi/{package_name}/{exact_package_version}/json")
    data = response.json()
    requires_dist = data["info"]["requires_dist"]

    # Determine micropython-stdlib-stubs dependency
    exact_stdlib_version = None

    # Match for exact MicroPython version first to try and achieve the best accuracy
    stdlib_versions = package_name_to_pypi_versions["micropython-stdlib-stubs"]
    exact_match_candidates = [v for v in stdlib_versions if v.startswith(mpy_version)]

    if exact_match_candidates:
        exact_stdlib_version = max(exact_match_candidates, key=Version)
    else:
        # SFallback to PyPI dependency resolution for older versions that don't have an equivalent stdlib package
        for dependency in requires_dist:
            if "micropython-stdlib-stubs" in dependency.lower():
                requirement = Requirement(dependency)
                specifier = requirement.specifier

                exact_stdlib_version = max(
                    (v for v in stdlib_versions if Version(v) in specifier),
                    key=Version,
                    default=None
                )

                break

    if not exact_stdlib_version:
        raise RuntimeError(f"No stdlib dependency info found for \"{package_name} {mpy_version}\"")

    # Build package entry
    package_entry = {
        "name": package_name,
        "mpyVersion": mpy_version,
        "port": port,
        "board": board,
        "variant": variant,
        "exactPackageVersion": exact_package_version,
        "exactStdlibVersion": exact_stdlib_version
    }

    index_packages.append(package_entry)

    print(f"Indexed exact versions for \"{package_name} {mpy_version}\"")

# Build final output structure
micropython_stubs = {
    "version": "1.0.0",
    "packages": index_packages
}

print("Saving the newly built stubs index...")
with open(output_json_path, "w") as f:
    json.dump(micropython_stubs, f, indent=2, ensure_ascii=False)
