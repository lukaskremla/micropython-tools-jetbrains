"""
Module: 'ds18x20' on micropython-v1.22.2-rp2-RPI_PICO
"""

# MCU: {'build': '', 'ver': '1.22.2', 'version': '1.22.2', 'port': 'rp2', 'board': 'RPI_PICO', 'mpy': 'v6.2', 'family': 'micropython', 'cpu': 'RP2040', 'arch': 'armv6m'}
# Stubber: v1.23.0
from __future__ import annotations
from _typeshed import Incomplete

def const(*args, **kwargs) -> Incomplete: ...

class DS18X20:
    def read_scratch(self, *args, **kwargs) -> Incomplete: ...
    def read_temp(self, *args, **kwargs) -> Incomplete: ...
    def write_scratch(self, *args, **kwargs) -> Incomplete: ...
    def convert_temp(self, *args, **kwargs) -> Incomplete: ...
    def scan(self, *args, **kwargs) -> Incomplete: ...
    def __init__(self, *argv, **kwargs) -> None: ...