try:
    import machine
    import os

    b = True

    s = os.uname()
    v = s[3]
    d = s[4]

    try:
        import binascii
    except ImportError:
        b = False

    print(v, d, b, sep="&")
except Exception as e:
    print(f"ERROR: {e}")
