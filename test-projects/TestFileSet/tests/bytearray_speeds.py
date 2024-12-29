try :
    import os, hashlib, binascii, gc
    from time import ticks_ms

    local_files = set()
    calculated_hashes_sha256 = []

    def save_all_items_on_path(dir_path) -> None:
        for entry in os.ilistdir(dir_path):
            name, kind = entry[0], entry[1]
            file_path = f"{dir_path}/{name}" if dir_path != "/" else f"/{name}"
            if kind == 0x8000:  # file
                local_files.add(file_path)
            elif kind == 0x4000:  # dir
                save_all_items_on_path(file_path)

    save_all_items_on_path("/")

    bytearray_sizes = [32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 12288, 16384]

    print("SHA256: Initiating the test")

    for size in bytearray_sizes:
        calculated_hashes_sha256 = []

        print(f"SHA256: Testing: {size}")
        start_time = ticks_ms()

        buffer = bytearray(size)

        for file in local_files:
            with open(file, 'rb') as f:
                hash = hashlib.sha256()

                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break
                    if n < size:
                        hash.update(buffer[:n])
                    else:
                        hash.update(buffer)
                calculated_hash = binascii.hexlify(hash.digest()).decode()
                calculated_hashes_sha256.append(calculated_hash)

        end_time = ticks_ms()
        total_time = end_time - start_time

        print(f"SHA256: Finished testing {size} byte array in: {total_time}ms")

    print("CRC32: Initiating the test")

    for size in bytearray_sizes:
        calculated_hashes_crc32 = []

        print(f"CRC32: Testing: {size}")
        start_time = ticks_ms()

        buffer = bytearray(size)

        for file in local_files:
            crc = 0

            with open(file, 'rb') as f:
                while True:
                    n = f.readinto(buffer)
                    if n == 0:
                        break
                    if n < size:
                        crc = binascii.crc32(buffer[:n], crc)
                    else:
                        crc = binascii.crc32(buffer, crc)
                calculated_hash = '%08x' % (crc & 0xffffffff)
                calculated_hashes_crc32.append(calculated_hash)

        end_time = ticks_ms()
        total_time = end_time - start_time

        print(f"CRC32: Finished testing {size} byte array in: {total_time}ms")

    print(calculated_hashes_crc32)

except Exception as e:
    print(e)