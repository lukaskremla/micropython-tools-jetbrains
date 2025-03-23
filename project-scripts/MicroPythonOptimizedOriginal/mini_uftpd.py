import errno, gc, network, socket, sys, uos
from micropython import alloc_emergency_exception_buf, const

class ___ftp:
    CHUNK_SIZE = const(1024)
    SO_REGISTER_HANDLER = const(20)
    COMMAND_TIMEOUT = const(300)
    DATA_TIMEOUT = const(100)
    DATA_PORT = const(13333)

    def __init__(self):
        self.ftp_sockets = []
        self.data_socket = None
        self.client_list = []
        self.verbose_l = 0
        self.client_busy = False

    class FTPClient:
        def __init__(self, ftp_socket, local_addr, ftp):
            self.ftp = ftp
            self.command_client, self.remote_addr = ftp_socket.accept()
            self.remote_addr = self.remote_addr[0]
            self.command_client.settimeout(self.ftp.COMMAND_TIMEOUT)
            self.ftp.log_msg(1, "FTP Command connection from:", self.remote_addr)
            self.command_client.setsockopt(socket.SOL_SOCKET,
                                           self.ftp.SO_REGISTER_HANDLER,
                                           self.exec_ftp_command)
            self.command_client.sendall("220 Hello, this is the {}.\r\n".format(sys.platform))
            self.cwd = '/'
            self.act_data_addr = self.remote_addr
            self.DATA_PORT = 20
            self.active = True
            self.pasv_data_addr = local_addr

        def send_file_data(self, path, data_client):
            buffer = bytearray(self.ftp.CHUNK_SIZE)
            mv = memoryview(buffer)
            with open(path, "rb") as file:
                bytes_read = file.readinto(buffer)
                while bytes_read > 0:
                    data_client.write(mv[0:bytes_read])
                    bytes_read = file.readinto(buffer)
                data_client.close()

        def save_file_data(self, path, data_client, mode):
            buffer = bytearray(self.ftp.CHUNK_SIZE)
            mv = memoryview(buffer)
            with open(path, mode) as file:
                bytes_read = data_client.readinto(buffer)
                while bytes_read > 0:
                    file.write(mv[0:bytes_read])
                    bytes_read = data_client.readinto(buffer)
                data_client.close()

        def get_absolute_path(self, cwd, payload):
            if payload.startswith('/'):
                cwd = "/"
            for token in payload.split("/"):
                if token == '..':
                    cwd = self.split_path(cwd)[0]
                elif token != '.' and token != '':
                    if cwd == '/':
                        cwd += token
                    else:
                        cwd = cwd + '/' + token
            return cwd

        @staticmethod
        def split_path(path):
            tail = path.split('/')[-1]
            head = path[:-(len(tail) + 1)]
            return '/' if head == '' else head, tail

        def open_dataclient(self):
            if self.active:  # active mode
                data_client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                data_client.settimeout(self.ftp.DATA_TIMEOUT)
                data_client.connect((self.act_data_addr, self.DATA_PORT))
                self.ftp.log_msg(1, "FTP Data connection with:", self.act_data_addr)
            else:
                data_client, data_addr = self.ftp.data_socket.accept()
                self.ftp.log_msg(1, "FTP Data connection with:", data_addr[0])
            return data_client

        def exec_ftp_command(self, cl):
            try:
                gc.collect()

                try:
                    data = cl.readline().decode("utf-8").rstrip("\r\n")
                except OSError:
                    data = ""

                if len(data) <= 0:
                    self.ftp.log_msg(1, "*** No data, assume QUIT")
                    self.ftp.close_client(cl)
                    return

                if self.ftp.client_busy:
                    cl.sendall("400 Device busy.\r\n")
                    return
                self.ftp.client_busy = True

                command = data.split()[0].upper()
                payload = data[len(command):].lstrip()
                path = self.get_absolute_path(self.cwd, payload)
                self.ftp.log_msg(1, "Command={}, Payload={}".format(command, payload))

                if command == "USER":
                    cl.sendall("230 Logged in.\r\n")
                elif command == "PASS":
                    cl.sendall("230 Logged in.\r\n")
                elif command == "SYST":
                    cl.sendall("215 UNIX Type: L8\r\n")
                elif command in ("TYPE", "NOOP", "ABOR"):
                    cl.sendall('200 OK\r\n')
                elif command == "QUIT":
                    cl.sendall('221 Bye.\r\n')
                    self.ftp.close_client(cl)
                elif command == "PWD" or command == "XPWD":
                    cl.sendall('257 "{}"\r\n'.format(self.cwd))
                elif command == "CWD" or command == "XCWD":
                    try:
                        if (uos.stat(path)[0] & 0o170000) == 0o040000:
                            self.cwd = path
                            cl.sendall('250 OK\r\n')
                        else:
                            cl.sendall('550 Fail\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "PASV":
                    cl.sendall('227 Entering Passive Mode ({},{},{}).\r\n'.format(
                        self.pasv_data_addr.replace('.', ','),
                        self.ftp.DATA_PORT >> 8, self.ftp.DATA_PORT % 256))
                    self.active = False
                elif command == "PORT":
                    items = payload.split(",")
                    if len(items) >= 6:
                        self.act_data_addr = '.'.join(items[:4])
                        if self.act_data_addr == "127.0.1.1":
                            self.act_data_addr = self.remote_addr
                        self.DATA_PORT = int(items[4]) * 256 + int(items[5])
                        cl.sendall('200 OK\r\n')
                        self.active = True
                    else:
                        cl.sendall('504 Fail\r\n')
                elif command == "RETR":
                    try:
                        self.ftp.data_client = self.open_dataclient()
                        cl.sendall("150 Opened data connection.\r\n")
                        self.send_file_data(path, self.ftp.data_client)
                        self.ftp.data_client = None
                        cl.sendall("226 Done.\r\n")
                    except:
                        cl.sendall('550 Fail\r\n')
                        if self.ftp.data_client is not None:
                            self.ftp.data_client.close()
                elif command == "STOR":
                    try:
                        self.ftp.data_client = self.open_dataclient()
                        cl.sendall("150 Opened data connection.\r\n")
                        self.save_file_data(path, self.ftp.data_client, "wb")
                        self.ftp.data_client = None
                        cl.sendall("226 Done.\r\n")
                    except:
                        cl.sendall('550 Fail\r\n')
                        if self.ftp.data_client is not None:
                            self.ftp.data_client.close()
                elif command == "SIZE":
                    try:
                        cl.sendall('213 {}\r\n'.format(uos.stat(path)[6]))
                    except:
                        cl.sendall('550 Fail\r\n')
                else:
                    cl.sendall("502 Unsupported command.\r\n")
            except OSError as err:
                if self.ftp.verbose_l > 0:
                    self.ftp.log_msg(1, "Exception in exec_ftp_command:")
                    sys.print_exception(err)
                if err.errno in (errno.ECONNABORTED, errno.ENOTCONN):
                    self.ftp.close_client(cl)
            except Exception as err:
                self.ftp.log_msg(1, "Exception in exec_ftp_command: {}".format(err))
            self.ftp.client_busy = False

    def log_msg(self, level, *args):
        if self.verbose_l >= level:
            print(*args)

    def close_client(self, cl):
        cl.setsockopt(socket.SOL_SOCKET, self.SO_REGISTER_HANDLER, None)
        cl.close()
        for i, client in enumerate(self.client_list):
            if client.command_client == cl:
                del self.client_list[i]
                break

    def accept_ftp_connect(self, ftp_socket, local_addr):
        try:
            self.client_list.append(self.FTPClient(ftp_socket, local_addr, self))
        except:
            self.log_msg(1, "Attempt to connect failed")
            try:
                temp_client, temp_addr = ftp_socket.accept()
                temp_client.close()
            except:
                pass

    def stop(self):
        for client in self.client_list:
            client.command_client.setsockopt(socket.SOL_SOCKET,
                                             self.SO_REGISTER_HANDLER, None)
            client.command_client.close()
        del self.client_list
        self.client_list = []
        self.client_busy = False
        for sock in self.ftp_sockets:
            sock.setsockopt(socket.SOL_SOCKET, self.SO_REGISTER_HANDLER, None)
            sock.close()
        self.ftp_sockets = []
        if self.data_socket is not None:
            self.data_socket.close()
            self.data_socket = None

    def start(self, port=21, verbose=0, splash=True):
        alloc_emergency_exception_buf(100)
        self.verbose_l = verbose
        self.client_list = []
        self.client_busy = False

        for interface in [network.AP_IF, network.STA_IF]:
            wlan = network.WLAN(interface)
            if not wlan.active():
                continue

            ifconfig = wlan.ifconfig()
            addr = socket.getaddrinfo(ifconfig[0], port)
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(addr[0][4])
            sock.listen(1)
            sock.setsockopt(socket.SOL_SOCKET,
                            self.SO_REGISTER_HANDLER,
                            lambda s: self.accept_ftp_connect(s, ifconfig[0]))
            self.ftp_sockets.append(sock)
            if splash:
                print("FTP server started on {}:{}".format(ifconfig[0], port))

        self.data_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.data_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.data_socket.bind(('0.0.0.0', self.DATA_PORT))
        self.data_socket.listen(1)
        self.data_socket.settimeout(10)