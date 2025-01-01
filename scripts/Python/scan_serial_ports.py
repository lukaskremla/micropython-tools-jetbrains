import serial.tools.list_ports as list_ports


def main():
    raw_ports = list_ports.comports()
    port_list = []

    for port in raw_ports:
        if port.hwid == "n/a":
            continue

        port_list.append(port.device)

    output = "&".join(port_list)
    print(output)


if __name__ == "__main__":
    main()
