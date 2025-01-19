try:
    import os

    try:
        import binascii

        imported_successfully = True
    except ImportError:
        imported_successfully = False

    should_synchronize = %s

    files_to_upload = [
        %s
    ]

    paths_to_exclude = [
        %s
    ]

    local_files = set()
    local_directories = set()

    def save_all_items_on_path(dir_path) -> None:
        for entry in os.ilistdir(dir_path):
            name, kind = entry[0], entry[1]

            file_path = f"{dir_path}/{name}" if dir_path != "/" else f"/{name}"

            if any(file_path == excluded_path or file_path.startswith(excluded_path + '/') for excluded_path in paths_to_exclude):
                continue

            if kind == 0x8000:  # file
                local_files.add(file_path)
            elif kind == 0x4000:  # dir
                local_directories.add(file_path)
                save_all_items_on_path(file_path)

    if should_synchronize:
        save_all_items_on_path("/")

    try:
        chunk_size = 1024
        buffer = bytearray(chunk_size)
        already_uploaded_paths = []

        if not imported_successfully and not should_synchronize:
            raise Exception

        for remote_file_tuple in files_to_upload:
            path, remote_size, remote_hash = remote_file_tuple

            if not path.startswith("/"):
                path = "/" + path

            try:
                local_size = os.stat(path)[6]

                if should_synchronize:
                    local_files.remove(path)
            except OSError:
                continue

            if not imported_successfully or remote_size != local_size:
                continue

            crc = 0
            with open(path, 'rb') as f:
                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break

                    if n < chunk_size:
                        crc = binascii.crc32(buffer[:n], crc)
                    else:
                        crc = binascii.crc32(buffer, crc)

                calculated_hash = "%%08x" %% (crc & 0xffffffff)

                if calculated_hash == remote_hash:
                    already_uploaded_paths.append(path)

        if should_synchronize:
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