try:
    import os

    try:
        import binascii

        a = True
    except ImportError:
        a = False

    b = %s

    c = [
        %s
    ]

    d = [
        %s
    ]

    e = set()
    g = set()

    def m(dir_path) -> None:
        for h in os.ilistdir(dir_path):
            i, j = h[0], h[1]

            k = f"{dir_path}/{i}" if dir_path != "/" else f"/{i}"

            if any(k == l or k.startswith(l + '/') for l in d):
                continue

            if j == 0x8000:  # file
                e.add(k)
            elif j == 0x4000:  # dir
                g.add(k)
                m(k)

    if b:
        m("/")

    try:
        o = bytearray(1024)
        p = []

        if not a and not b:
            raise Exception

        for q in c:
            r, s, t = q

            if not r.startswith("/"):
                r = "/" + r

            try:
                u = os.stat(r)[6]

                if b:
                    e.remove(r)
            except OSError:
                continue

            if not a or s != u:
                continue

            v = 0
            with open(r, 'rb') as f:
                while True:
                    n = f.readinto(o)
                    if n == 0:
                        break

                    if n < 1024:
                        v = binascii.crc32(o[:n], v)
                    else:
                        v = binascii.crc32(o, v)

                w = "%%08x" %% (v & 0xffffffff)

                if w == t:
                    p.append(r)

        if b:
            for x in e:
                os.remove(x)

            for y in g:
                try:
                    os.rmdir(y)
                except OSError:
                    pass

        if not p:
            raise Exception

        z = "&".join(p)
        print(z)

    except Exception:
        print("NO MATCHES")

except Exception as e:
    print(f"ERROR: {e}")