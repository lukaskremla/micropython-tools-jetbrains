"""
System specific functions.

MicroPython module: https://docs.micropython.org/en/v1.22.1/library/sys.html

CPython module: :mod:`python:sys` https://docs.python.org/3/library/sys.html .

---
Module: 'sys' on micropython-v1.22.1-samd-SEEED_WIO_TERMINAL
"""
# MCU: {'build': '', 'ver': '1.22.1', 'version': '1.22.1', 'port': 'samd', 'board': 'SEEED_WIO_TERMINAL', 'mpy': 'v6.2', 'family': 'micropython', 'cpu': 'SAMD51P19A', 'arch': 'armv7emsp'}
# Stubber: v1.16.3
from __future__ import annotations
from _typeshed import Incomplete
from typing import Dict, List, Tuple

platform: str = "samd"
version_info: tuple = ()
path: list = []
version: str = "3.4.0; MicroPython v1.22.1 on 2024-01-05"
ps1: str = ">>> "
ps2: str = "... "
byteorder: str = "little"
modules: dict = {}
argv: list = []
implementation: tuple = ()
maxsize: int = 2147483647

def print_exception(exc, file=stdout, /) -> None:
    """
    Print exception with a traceback to a file-like object *file* (or
    `sys.stdout` by default).

    Difference to CPython

       This is simplified version of a function which appears in the
       ``traceback`` module in CPython. Unlike ``traceback.print_exception()``,
       this function takes just exception value instead of exception type,
       exception value, and traceback object; *file* argument should be
       positional; further arguments are not supported. CPython-compatible
       ``traceback`` module can be found in `micropython-lib`.
    """
    ...

def exit(retval=0, /) -> Incomplete:
    """
    Terminate current program with a given exit code. Underlyingly, this
    function raise as `SystemExit` exception. If an argument is given, its
    value given as an argument to `SystemExit`.
    """
    ...

stderr: Incomplete  ## <class 'FileIO'> = <io.FileIO 2>
stdout: Incomplete  ## <class 'FileIO'> = <io.FileIO 1>
stdin: Incomplete  ## <class 'FileIO'> = <io.FileIO 0>
