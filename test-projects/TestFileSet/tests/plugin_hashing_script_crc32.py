try:
    import os

    try:
        import binascii

        imported_successfully = True
    except ImportError:
        imported_successfully = False

    # these three variables will be replaced with actual data by the calling plugin
    should_synchronize = False

    files_to_upload = [
        ("path", 1234, "hash")
    ]

    paths_to_ignore = [
        ""
    ]

    # Use sets for better performance when removing elements
    local_files = set()
    local_directories = set()

    def save_all_items_on_path(dir_path) -> None:
        for entry in os.ilistdir(dir_path):
            name, kind = entry[0], entry[1]

            file_path = f"{dir_path}/{name}" if dir_path != "/" else f"/{name}"

            # Avoid saving ignored paths to prevent their removal when synchronizing
            if any(ignored_path in file_path for ignored_path in paths_to_ignore):
                continue

            if kind == 0x8000:  # file
                local_files.add(file_path)
            elif kind == 0x4000:  # dir
                # Repeatedly scan until there are no subdirectories.
                local_directories.add(file_path)
                save_all_items_on_path(file_path)

    # The entire file system is only scanned if synchronization is enabled
    # Already uploaded files will be removed from the local_files set
    # At the all remaining members of local_files will be deleted alongside all empty folders in local_directories
    if should_synchronize:
        save_all_items_on_path("/")

    try:
        # Allocate a bytearray buffer once to be used for every file to increase memory efficiency
        chunk_size = 1024
        buffer = bytearray(chunk_size)
        # Store paths of all matching files
        already_uploaded_paths = []

        # Early break - performing synchronization isn't required
        # and hashing algorithms couldn't have been imported successfully
        # Raise an exception to be caught and printed as "NO MATCHES"
        if not imported_successfully and not should_synchronize:
            raise Exception

        for remote_file_tuple in files_to_upload:
            path, remote_size, remote_hash = remote_file_tuple

            # MicroPython file system expects a leading /
            if not path.startswith("/"):
                path = "/" + path

            # os.stat raises an OSError for files that don't exist
            # This checks if a file exists and gets its size if it does
            try:
                local_size = os.stat(path)[6]

                # Remove from the list of existing scanned files (remaining files will be deleted at the end)
                if should_synchronize:
                    local_files.remove(path)
            except OSError:
                continue

            # Compare sizes and continue if hash comparisons can't be executed
            # The loop gets here to ensure that at least synchronization can be performed if it was enabled
            if not imported_successfully or remote_size != local_size:
                continue

            crc = 0
            with open(path, 'rb') as f:
                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break

                    # n size checks to prevent reading into previous readinto call contents
                    # for when the remaining file size is less than the chunk size
                    if n < chunk_size:
                        crc = binascii.crc32(buffer[:n], crc)
                    else:
                        crc = binascii.crc32(buffer, crc)

                calculated_hash = "%08x" % (crc & 0xffffffff)

                if calculated_hash == remote_hash:
                    # Remove leading / to match what the calling script expects
                    if path.startswith("/"):
                        path = path[1:]
                    already_uploaded_paths.append(path)

        # Remove scanned local files that weren't uploaded already
        if should_synchronize:
            for file in local_files:
                os.remove(file)

        # Remove empty directories (non-empty directories will raise an OSError)
        if should_synchronize:
            for directory in local_directories:
                try:
                    os.rmdir(directory)
                except OSError:
                    pass

        # Raise an error to be caught and interpreted as "NO MATCHES" if no matching paths were saved
        if not already_uploaded_paths:
            raise Exception

        output = "&".join(already_uploaded_paths)
        print(output)

    except Exception:
        print("NO MATCHES")

except Exception as e:
    print(f"ERROR: {e}")