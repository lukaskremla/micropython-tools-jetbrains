import network
import webrepl
import time
from secrets import WIFI_SSID, WIFI_PASSWORD

def connect_wifi_and_start_webrepl(ssid, password, webrepl_password=None):
    wlan = network.WLAN(network.STA_IF)

    wlan.active(True)

    if not wlan.isconnected():
        print('Connecting to WiFi network "{}"...'.format(ssid))
        wlan.connect(ssid, password)

        max_wait = 10
        while max_wait > 0:
            if wlan.isconnected():
                break
            max_wait -= 1
            print('Waiting for connection...')
            time.sleep(1)

    if wlan.isconnected():
        network_info = wlan.ifconfig()
        print('WiFi connected!')
        print('IP address:', network_info[0])

        if webrepl_password:
            webrepl.start(password=webrepl_password)
        else:
            webrepl.start()
        print('WebREPL started')
        print('Connect to WebREPL at ws://{}:8266/'.format(network_info[0]))
    else:
        print('Failed to connect to WiFi')

if __name__ == '__main__':
    WEBREPL_PASSWORD = "password"

    connect_wifi_and_start_webrepl(WIFI_SSID, WIFI_PASSWORD, WEBREPL_PASSWORD)