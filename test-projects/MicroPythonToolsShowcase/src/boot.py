# Boot script to initialize system settings

import machine

def boot():
    print("Booting system...")
    machine.freq(160000000)  # Set CPU frequency to 160MHz
    print("CPU frequency set to 160MHz")

boot()
