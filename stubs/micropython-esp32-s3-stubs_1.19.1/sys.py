"""
system specific functions. See: https://docs.micropython.org/en/v1.19.1/library/sys.html

|see_cpython_module| :mod:`python:sys` https://docs.python.org/3/library/sys.html .
"""
# MCU: {'ver': 'v1.19.1', 'build': '', 'platform': 'esp32', 'port': 'esp32', 'machine': 'ESP32S3 module with ESP32S3', 'release': '1.19.1', 'nodename': 'esp32', 'name': 'micropython', 'family': 'micropython', 'sysname': 'esp32', 'version': '1.19.1'}
# Stubber: 1.11.2
from typing import Dict, List, Tuple, Any

platform = "esp32"  # type: str
version_info = ()  # type: tuple
path = []  # type: list
version = "3.4.0; MicroPython v1.19.1 on 2022-06-18"  # type: str
ps1 = ">>> "  # type: str
ps2 = "... "  # type: str
byteorder = "little"  # type: str
modules = {}  # type: dict
argv = []  # type: list
implementation = ()  # type: tuple
maxsize = 2147483647  # type: int


def print_exception(exc, file=stdout, /) -> None:
    """
    Print exception with a traceback to a file-like object *file* (or
    `sys.stdout` by default).
    """
    ...


def exit(retval=0, /) -> Any:
    """
    Terminate current program with a given exit code. Underlyingly, this
    function raise as `SystemExit` exception. If an argument is given, its
    value given as an argument to `SystemExit`.
    """
    ...


stderr: Any  ## <class 'FileIO'> = <io.FileIO 2>
stdout: Any  ## <class 'FileIO'> = <io.FileIO 1>
stdin: Any  ## <class 'FileIO'> = <io.FileIO 0>
