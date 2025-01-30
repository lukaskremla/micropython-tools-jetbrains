try:
    import machine
    import os

    d = %s

    try:
        r = machine.RTC()
        r.datetime(d)
    except Exception as e:
        pass

    b = True

    s = os.uname()
    v = os.version
    d = os.machine

    try:
        import binascii
    except ImportError:
        v = False

    print(v, d, b, sep="&")
except Exception:
    print("ERROR")