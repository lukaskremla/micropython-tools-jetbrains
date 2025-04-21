"""
* Copyright 2025 Lukas Kremla
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
"""

import os
import socket

ds = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
addr = socket.getaddrinfo("%s1", "%s2")[0][-1]
ds.connect(addr)
ba = bytearray(1024)
mv = memoryview(ba)


def l():
    global ds

    line = ds.recv(1024).decode("utf-8")
    if (line == "PING"):
        print("Sending PONG")
        ds.sendall("PONG\r\n")

    while True:
        line = ds.recv(1024).decode("utf-8")
        print("Received data")

        if line.startswith("UPLOAD"):
            print(f"Starting upload \"{line}\"")
            parts = line.split("\"")
            path = parts[1]
            size = int(parts[3])
            ds.sendall("CONTINUE\r\n")
            w(path, size)
        elif line.startswith("DOWNLOAD"):
            print(f"Starting download \"{line}\"")
            parts = line.split("\"")
            path = parts[1]
            d(path)
        elif line == "FINISHED":
            print(f"Finished")
            ds.close()
            break


def w(p, s):
    global ds, ba, mv

    i = 0
    try:
        dp = "/".join(p.split("/")[:-1])
        if dp:
            parts = dp.split("/")
            curr = ""
            for part in parts:
                if not part:
                    continue
                curr += "/" + part
                try:
                    os.mkdir(curr)
                except OSError:
                    pass

        with open(p, "wb") as f:
            while True:
                if s - i < 1024:
                    print(f"Breaking remainder is {s - i}")
                    break

                n = ds.readinto(ba)
                f.write(mv[0:n])
                ds.sendall(f"WROTE {n}\r\n")
                i += n

            remainder = s - i
            if remainder == 0:
                return

            # Read specifically the remainder from the bytearray,
            # Otherwise the socket would keep waiting for data that will never arrive
            sba = bytearray(remainder)
            sma = memoryview(sba)

            n = ds.readinto(sba)
            f.write(sma[0:n])
            ds.sendall(f"WROTE {n}\r\n")

            print("Sending DONE")
            ds.sendall("DONE\r\n")
            print("After sending DONE")

    except Exception as e:
        ds.send(f"ERROR: {e}".encode())


def d(p):
    global ds, ba, mv

    try:
        size = os.stat(p)[6]
        ds.sendall(f"SIZE:{size}\r\n")
    except Exception as e:
        ds.send(f"ERROR: {e}".encode())
        return

    line = ds.recv(1024).decode("utf-8")

    if not line == "CONTINUE":
        return

    try:
        with open(p, "rb") as f:
            n = f.readinto(ba)
            while n > 0:
                print(f"Sending {n} bytes")
                ds.write(mv[0:n])
                n = f.readinto(ba)

    except Exception as e:
        ds.send(f"ERROR: {e}".encode())


l()

# This will be split into a separate script during minification, global names will be added automatically
try:
    del l
    del w
    ds.close()
except:
    pass
try:
    ds = None
    del ds
except:
    pass
import network


def cl():
    clean_wifi = "%s"
    if clean_wifi:
        for interface in [network.STA_IF, network.AP_IF]:
            wlan = network.WLAN(interface)
            if not wlan.active():
                continue
            try:
                wlan.disconnect()
            except:
                pass
            wlan.active(False)


cl()
del cl
# Import gc here, gc.collect() call will be added during minification
import gc
