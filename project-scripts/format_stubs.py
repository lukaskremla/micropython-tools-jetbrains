import os

with os.scandir("../stubs") as stub_packages:
    for stub_package in stub_packages:
        print(stub_package.path)

        if stub_package.is_dir():
            with os.scandir(stub_package.path) as stub_package_items:
                found_asyncio = False

                for stub_package_item in stub_package_items:
                    if stub_package_item.is_file() and not stub_package_item.path.endswith(".pyi"):
                        os.remove(stub_package_item)

                    if stub_package_item.name in ("asyncio", "uasyncio"):
                        found_asyncio = True

                if not found_asyncio:
                    os.mkdir(f"{stub_package.path}/asyncio")
                    os.mkdir(f"{stub_package.path}/uasyncio")

                    for asyncio_folders in ("asyncio", "uasyncio"):
                        with os.scandir(asyncio_folders) as asyncio:
                            for item in asyncio:
                                with open(item.path, "r") as f:
                                    bytes = f.read()

                                    with open(f"{stub_package.path}/{asyncio_folders}/{item.name}", "w") as f2:
                                        f2.write(bytes)