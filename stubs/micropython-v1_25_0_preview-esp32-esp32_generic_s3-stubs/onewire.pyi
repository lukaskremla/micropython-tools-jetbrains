from _typeshed import Incomplete

class OneWireError(Exception): ...

class OneWire:
    SEARCH_ROM: int
    MATCH_ROM: int
    SKIP_ROM: int
    pin: Incomplete
    def __init__(self, pin) -> None: ...
    def reset(self, required: bool = False): ...
    def readbit(self): ...
    def readbyte(self): ...
    def readinto(self, buf) -> None: ...
    def writebit(self, value): ...
    def writebyte(self, value): ...
    def write(self, buf) -> None: ...
    def select_rom(self, rom) -> None: ...
    def scan(self): ...
    def _search_rom(self, l_rom, diff): ...
    def crc8(self, data): ...
