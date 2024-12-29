try:
    import os

    try:
        import hashlib
        import binascii

        imported_successfully = True
    except ImportError:
        imported_successfully = False

    # these two variables will be replaced with actual data by the calling plugin
    should_synchronize = False

    files_to_upload = [
        ("path", 1234, "hash")
    ]

    local_files = set()
    local_directories = set()

    def save_all_items_on_path(dir_path) -> None:
        for entry in os.ilistdir(dir_path):
            name, kind = entry[0], entry[1]

            file_path = f"{dir_path}/{name}" if dir_path != "/" else f"/{name}"

            if kind == 0x8000:  # file
                local_files.add(file_path)
            elif kind == 0x4000:  # dir
                # Repeatedly scan until there are no subdirectories.
                local_directories.add(file_path)
                save_all_items_on_path(file_path)

    if should_synchronize:
        save_all_items_on_path("/")

    try:
        chunk_size = 1024
        already_uploaded_paths = []

        # Early break - performing synchronization isn't required
        # and hashing algorithms couldn't have been imported successfully
        # Raise an exception to be caught and printed as "NO MATCHES"
        if not imported_successfully and not should_synchronize:
            raise Exception

        for remote_file_tuple in files_to_upload:
            path, remote_size, remote_hash = remote_file_tuple

            if not path.startswith("/"):
                path = "/" + path

            try:
                local_size = os.stat(path)[6]

                # Remove from the list of existing scanned files (remaining files will be deleted at the end)
                if should_synchronize:
                    local_files.remove(path)
            except OSError:
                continue

            if not imported_successfully or remote_size != local_size:
                continue

            with open(path, 'rb') as f:
                buffer = bytearray(chunk_size)

                hash = hashlib.sha256()

                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break

                    if n < chunk_size:
                        hash.update(buffer[:n])
                    else:
                        hash.update(buffer)

                calculated_hash = binascii.hexlify(hash.digest()).decode()

                if calculated_hash == remote_hash:
                    if path.startswith("/"):
                        path = path[1:]
                    already_uploaded_paths.append(path)

        for file in local_files:
            os.remove(file)

        for directory in local_directories:
            try:
                os.rmdir(directory)
            except OSError:
                pass

        if not already_uploaded_paths:
            raise Exception

        output = "&".join(already_uploaded_paths)
        print(output)

    except Exception:
        print("NO MATCHES")

except Exception as e:
    print(f"ERROR: {e}")