for i in [network.STA_IF, network.AP_IF]:
    w = network.WLAN(i)

    if not w.active():
        continue

    try:
        w.disconnect()
    except Exception as e:
        pass

    w.active(False)

try:
    stop()
except Exception as e:
    pass

import gc

gc.collect()
