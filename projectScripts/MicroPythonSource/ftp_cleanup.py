for interface in [network.STA_IF, network.AP_IF]:
    wlan = network.WLAN(interface)

    if not wlan.active():
        continue

    try:
        wlan.disconnect()
    except Exception as e:
        pass

    wlan.active(False)

try:
    stop()
except Exception as e:
    pass

import gc

gc.collect()
