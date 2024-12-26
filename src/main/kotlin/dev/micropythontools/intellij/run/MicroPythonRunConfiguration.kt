/*
 * Copyright 2000-2024 JetBrains s.r.o.
 * Copyright 2024 Lukas Kremla
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
 */

package dev.micropythontools.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.configuration.AbstractRunConfiguration
import com.intellij.execution.configuration.EmptyRunProfileState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.facet.ui.ValidationResult
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.util.progress.reportProgress
import com.intellij.project.stateStore
import com.intellij.util.PathUtil
import com.intellij.util.PlatformUtils
import com.jetbrains.python.sdk.PythonSdkUtil
import dev.micropythontools.intellij.nova.*
import dev.micropythontools.intellij.settings.MicroPythonProjectConfigurable
import dev.micropythontools.intellij.settings.microPythonFacet
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.jdom.Element
import java.io.ByteArrayInputStream

private const val FTP_SERVER_CLEANUP = """
    
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
    
"""

private const val START_FTP_SERVER = """
    
try:
    ssid = %s
    password = %s
    wifi_timeout = %s

    #
    # Small ftp server for ESP8266 Micropython
    # Based on the work of chrisgp - Christopher Popp and pfalcon - Paul Sokolovsky
    #
    # The server accepts passive mode only. It runs in background.
    # Start the server with:
    #
    # import uftpd
    # uftpd.start([port = 21][, verbose = level])
    #
    # port is the port number (default 21)
    # verbose controls the level of printed activity messages, values 0, 1, 2
    #
    # Copyright (c) 2016 Christopher Popp (initial ftp server framework)
    # Copyright (c) 2016 Paul Sokolovsky (background execution control structure)
    # Copyright (c) 2016 Robert Hammelrath (putting the pieces together and a
    # few extensions)
    # Copyright (c) 2020 Jan Wieck Use separate FTP servers per socket for STA + AP mode
    # Copyright (c) 2021 JD Smith Use a preallocated buffer and improve error handling.
    # Distributed under MIT License
    #
    import time
    import socket
    import network
    import uos
    import gc
    import sys
    import errno
    from time import sleep_ms, localtime
    from micropython import alloc_emergency_exception_buf
    from micropython import const

    # constant definitions
    _CHUNK_SIZE = const(1024)
    _SO_REGISTER_HANDLER = const(20)
    _COMMAND_TIMEOUT = const(300)
    _DATA_TIMEOUT = const(100)
    _DATA_PORT = const(13333)

    # Global variables
    ftpsockets = []
    datasocket = None
    client_list = []
    verbose_l = 0
    client_busy = False
    # Interfaces: (IP-Address (string), IP-Address (integer), Netmask (integer))

    _month_name = ("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                   "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


    class FTP_client:
        def __init__(self, ftpsocket, local_addr):
            self.command_client, self.remote_addr = ftpsocket.accept()
            self.remote_addr = self.remote_addr[0]
            self.command_client.settimeout(_COMMAND_TIMEOUT)
            log_msg(1, "FTP Command connection from:", self.remote_addr)
            self.command_client.setsockopt(socket.SOL_SOCKET,
                                           _SO_REGISTER_HANDLER,
                                           self.exec_ftp_command)
            self.command_client.sendall("220 Hello, this is the {}.\r\n".format(sys.platform))
            self.cwd = '/'
            self.fromname = None
            #self.logged_in = False
            self.act_data_addr = self.remote_addr
            self.DATA_PORT = 20
            self.active = True
            self.pasv_data_addr = local_addr

        def send_list_data(self, path, data_client, full):
            try:
                for fname in uos.listdir(path):
                    data_client.sendall(self.make_description(path, fname, full))
            except Exception as e:  # path may be a file name or pattern
                path, pattern = self.split_path(path)
                try:
                    for fname in uos.listdir(path):
                        if self.fncmp(fname, pattern):
                            data_client.sendall(
                                self.make_description(path, fname, full))
                except:
                    pass

        def make_description(self, path, fname, full):
            global _month_name
            if full:
                stat = uos.stat(self.get_absolute_path(path, fname))
                file_permissions = ("drwxr-xr-x"
                                    if (stat[0] & 0o170000 == 0o040000)
                                    else "-rw-r--r--")
                file_size = stat[6]
                tm = stat[7] & 0xffffffff
                tm = localtime(tm if tm < 0x80000000 else tm - 0x100000000)
                if tm[0] != localtime()[0]:
                    description = "{} 1 owner group {:>10} {} {:2} {:>5} {}\r\n".\
                        format(file_permissions, file_size,
                            _month_name[tm[1]], tm[2], tm[0], fname)
                else:
                    description = "{} 1 owner group {:>10} {} {:2} {:02}:{:02} {}\r\n".\
                        format(file_permissions, file_size,
                            _month_name[tm[1]], tm[2], tm[3], tm[4], fname)
            else:
                description = fname + "\r\n"
            return description

        def send_file_data(self, path, data_client):
            buffer = bytearray(_CHUNK_SIZE)
            mv = memoryview(buffer)
            with open(path, "rb") as file:
                bytes_read = file.readinto(buffer)
                while bytes_read > 0:
                    data_client.write(mv[0:bytes_read])
                    bytes_read = file.readinto(buffer)
                data_client.close()

        def save_file_data(self, path, data_client, mode):
            buffer = bytearray(_CHUNK_SIZE)
            mv = memoryview(buffer)
            with open(path, mode) as file:
                bytes_read = data_client.readinto(buffer)
                while bytes_read > 0:
                    file.write(mv[0:bytes_read])
                    bytes_read = data_client.readinto(buffer)
                data_client.close()

        def get_absolute_path(self, cwd, payload):
            # Just a few special cases "..", "." and ""
            # If payload start's with /, set cwd to /
            # and consider the remainder a relative path
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

        def split_path(self, path):  # instead of path.rpartition('/')
            tail = path.split('/')[-1]
            head = path[:-(len(tail) + 1)]
            return ('/' if head == '' else head, tail)

        # compare fname against pattern. Pattern may contain
        # the wildcards ? and *.
        def fncmp(self, fname, pattern):
            pi = 0
            si = 0
            while pi < len(pattern) and si < len(fname):
                if (fname[si] == pattern[pi]) or (pattern[pi] == '?'):
                    si += 1
                    pi += 1
                else:
                    if pattern[pi] == '*':  # recurse
                        if pi == len(pattern.rstrip("*?")):  # only wildcards left
                            return True
                        while si < len(fname):
                            if self.fncmp(fname[si:], pattern[pi + 1:]):
                                return True
                            else:
                                si += 1
                        return False
                    else:
                        return False
            if pi == len(pattern.rstrip("*")) and si == len(fname):
                return True
            else:
                return False

        def open_dataclient(self):
            if self.active:  # active mode
                data_client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                data_client.settimeout(_DATA_TIMEOUT)
                data_client.connect((self.act_data_addr, self.DATA_PORT))
                log_msg(1, "FTP Data connection with:", self.act_data_addr)
            else:  # passive mode
                data_client, data_addr = datasocket.accept()
                log_msg(1, "FTP Data connection with:", data_addr[0])
            return data_client

        def exec_ftp_command(self, cl):
            global datasocket
            global client_busy
            global my_ip_addr

            try:
                gc.collect()

                try:
                    data = cl.readline().decode("utf-8").rstrip("\r\n")
                except OSError:
                    # treat an error as QUIT situation.
                    data = ""

                if len(data) <= 0:
                    # No data, close
                    # This part is NOT CLEAN; there is still a chance that a
                    # closing data connection will be signalled as closing
                    # command connection
                    log_msg(1, "*** No data, assume QUIT")
                    close_client(cl)
                    return

                if client_busy:  # check if another client is busy
                    cl.sendall("400 Device busy.\r\n")  # tell so the remote client
                    return  # and quit
                client_busy = True  # now it's my turn

                # check for log-in state may done here, like
                # if self.logged_in == False and not command in\
                #    ("USER", "PASS", "QUIT"):
                #    cl.sendall("530 Not logged in.\r\n")
                #    return

                command = data.split()[0].upper()
                payload = data[len(command):].lstrip()  # partition is missing
                path = self.get_absolute_path(self.cwd, payload)
                log_msg(1, "Command={}, Payload={}".format(command, payload))

                if command == "USER":
                    # self.logged_in = True
                    cl.sendall("230 Logged in.\r\n")
                    # If you want to see a password,return
                    #   "331 Need password.\r\n" instead
                    # If you want to reject an user, return
                    #   "530 Not logged in.\r\n"
                elif command == "PASS":
                    # you may check here for a valid password and return
                    # "530 Not logged in.\r\n" in case it's wrong
                    # self.logged_in = True
                    cl.sendall("230 Logged in.\r\n")
                elif command == "SYST":
                    cl.sendall("215 UNIX Type: L8\r\n")
                elif command in ("TYPE", "NOOP", "ABOR"):  # just accept & ignore
                    cl.sendall('200 OK\r\n')
                elif command == "QUIT":
                    cl.sendall('221 Bye.\r\n')
                    close_client(cl)
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
                        _DATA_PORT >> 8, _DATA_PORT %% 256))
                    self.active = False
                elif command == "PORT":
                    items = payload.split(",")
                    if len(items) >= 6:
                        self.act_data_addr = '.'.join(items[:4])
                        if self.act_data_addr == "127.0.1.1":
                            # replace by command session addr
                            self.act_data_addr = self.remote_addr
                        self.DATA_PORT = int(items[4]) * 256 + int(items[5])
                        cl.sendall('200 OK\r\n')
                        self.active = True
                    else:
                        cl.sendall('504 Fail\r\n')
                elif command == "LIST" or command == "NLST":
                    if payload.startswith("-"):
                        option = payload.split()[0].lower()
                        path = self.get_absolute_path(
                                self.cwd, payload[len(option):].lstrip())
                    else:
                        option = ""
                    try:
                        data_client = self.open_dataclient()
                        cl.sendall("150 Directory listing:\r\n")
                        self.send_list_data(path, data_client,
                                            command == "LIST" or 'l' in option)
                        cl.sendall("226 Done.\r\n")
                        data_client.close()
                    except:
                        cl.sendall('550 Fail\r\n')
                        if data_client is not None:
                            data_client.close()
                elif command == "RETR":
                    try:
                        data_client = self.open_dataclient()
                        cl.sendall("150 Opened data connection.\r\n")
                        self.send_file_data(path, data_client)
                        # if the next statement is reached,
                        # the data_client was closed.
                        data_client = None
                        cl.sendall("226 Done.\r\n")
                    except:
                        cl.sendall('550 Fail\r\n')
                        if data_client is not None:
                            data_client.close()
                elif command == "STOR" or command == "APPE":
                    try:
                        data_client = self.open_dataclient()
                        cl.sendall("150 Opened data connection.\r\n")
                        self.save_file_data(path, data_client,
                                            "wb" if command == "STOR" else "ab")
                        # if the next statement is reached,
                        # the data_client was closed.
                        data_client = None
                        cl.sendall("226 Done.\r\n")
                    except:
                        cl.sendall('550 Fail\r\n')
                        if data_client is not None:
                            data_client.close()
                elif command == "SIZE":
                    try:
                        cl.sendall('213 {}\r\n'.format(uos.stat(path)[6]))
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "MDTM":
                    try:
                        tm=localtime(uos.stat(path)[8])
                        cl.sendall('213 {:04d}{:02d}{:02d}{:02d}{:02d}{:02d}\r\n'.format(*tm[0:6]))
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "STAT":
                    if payload == "":
                        cl.sendall("211-Connected to ({})\r\n"
                                   "    Data address ({})\r\n"
                                   "    TYPE: Binary STRU: File MODE: Stream\r\n"
                                   "    Session timeout {}\r\n"
                                   "211 Client count is {}\r\n".format(
                                    self.remote_addr, self.pasv_data_addr,
                                    _COMMAND_TIMEOUT, len(client_list)))
                    else:
                        cl.sendall("213-Directory listing:\r\n")
                        self.send_list_data(path, cl, True)
                        cl.sendall("213 Done.\r\n")
                elif command == "DELE":
                    try:
                        uos.remove(path)
                        cl.sendall('250 OK\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "RNFR":
                    try:
                        # just test if the name exists, exception if not
                        uos.stat(path)
                        self.fromname = path
                        cl.sendall("350 Rename from\r\n")
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "RNTO":
                    try:
                        uos.rename(self.fromname, path)
                        cl.sendall('250 OK\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                    self.fromname = None
                elif command == "CDUP" or command == "XCUP":
                    self.cwd = self.get_absolute_path(self.cwd, "..")
                    cl.sendall('250 OK\r\n')
                elif command == "RMD" or command == "XRMD":
                    try:
                        uos.rmdir(path)
                        cl.sendall('250 OK\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "MKD" or command == "XMKD":
                    try:
                        uos.mkdir(path)
                        cl.sendall('250 OK\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                elif command == "SITE":
                    try:
                        exec(payload.replace('\0' ,'\n'))
                        cl.sendall('250 OK\r\n')
                    except:
                        cl.sendall('550 Fail\r\n')
                else:
                    cl.sendall("502 Unsupported command.\r\n")
                    # log_msg(2,
                    #  "Unsupported command {} with payload {}".format(command,
                    #  payload))
            except OSError as err:
                if verbose_l > 0:
                    log_msg(1, "Exception in exec_ftp_command:")
                    sys.print_exception(err)
                if err.errno in (errno.ECONNABORTED, errno.ENOTCONN):
                    close_client(cl)
            # handle unexpected errors
            except Exception as err:
                log_msg(1, "Exception in exec_ftp_command: {}".format(err))
            # tidy up before leaving
            client_busy = False


    def log_msg(level, *args):
        global verbose_l
        if verbose_l >= level:
            print(*args)


    # close client and remove it from the list
    def close_client(cl):
        cl.setsockopt(socket.SOL_SOCKET, _SO_REGISTER_HANDLER, None)
        cl.close()
        for i, client in enumerate(client_list):
            if client.command_client == cl:
                del client_list[i]
                break


    def accept_ftp_connect(ftpsocket, local_addr):
        # Accept new calls for the server
        try:
            client_list.append(FTP_client(ftpsocket, local_addr))
        except:
            log_msg(1, "Attempt to connect failed")
            # try at least to reject
            try:
                temp_client, temp_addr = ftpsocket.accept()
                temp_client.close()
            except:
                pass


    def num_ip(ip):
        items = ip.split(".")
        return (int(items[0]) << 24 | int(items[1]) << 16 |
                int(items[2]) << 8 | int(items[3]))


    def stop():
        global ftpsockets, datasocket
        global client_list
        global client_busy

        for client in client_list:
            client.command_client.setsockopt(socket.SOL_SOCKET,
                                             _SO_REGISTER_HANDLER, None)
            client.command_client.close()
        del client_list
        client_list = []
        client_busy = False
        for sock in ftpsockets:
            sock.setsockopt(socket.SOL_SOCKET, _SO_REGISTER_HANDLER, None)
            sock.close()
        ftpsockets = []
        if datasocket is not None:
            datasocket.close()
            datasocket = None


    # start listening for ftp connections on port 21
    def start(port=21, verbose=0, splash=True):
        global ftpsockets, datasocket
        global verbose_l
        global client_list
        global client_busy

        alloc_emergency_exception_buf(100)
        verbose_l = verbose
        client_list = []
        client_busy = False

        for interface in [network.STA_IF]:
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
                            _SO_REGISTER_HANDLER,
                            lambda s : accept_ftp_connect(s, ifconfig[0]))
            ftpsockets.append(sock)
            if splash:
                print(f"IP: {ifconfig[0]}")

        datasocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        datasocket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        datasocket.bind(('0.0.0.0', _DATA_PORT))
        datasocket.listen(1)
        datasocket.settimeout(10)

    def restart(port=21, verbose=0, splash=True):
        stop()
        sleep_ms(200)
        start(port, verbose, splash)

    for interface in [network.STA_IF, network.AP_IF]:
        wlan = network.WLAN(interface)

        if not wlan.active():
            continue

        try:
            wlan.disconnect()
        except Exception as e:
            pass

        wlan.active(False)

    import gc
    gc.collect()

    sta = network.WLAN(network.STA_IF)
    sta.active(True)
    sta.connect(ssid, password)

    i = 0
    while not sta.isconnected():
        time.sleep(1)
        i+= 1

        if (i > wifi_timeout):
            print(f"ERROR: Connecting to \"{ssid}\" failed, connection timed out. Check your network settings.")
            break

    start()
except Exception as e:
    print(f"Error: {e}")


"""

private class FTPUploadClient {
    private val ftpClient: FTPClient = FTPClient()

    fun connect(ip: String, ftpUsername: String, ftpPassword: String) {
        ftpClient.connect(ip)
        ftpClient.login(ftpUsername, ftpPassword)
        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
    }

    fun disconnect() {
        ftpClient.logout()
        ftpClient.disconnect()
    }

    fun uploadFile(bytes: ByteArray, path: String) {
        val unixPath = if (!path.startsWith("/")) {
            "/$path"
        } else {
            path
        }

        val fileName = unixPath.substringAfterLast("/")
        val filePath = unixPath.substringBeforeLast("/")

        if (filePath != "/") {
            val dirs = filePath.split("/").filter { it.isNotEmpty() }
            var currentPath = ""

            for (dir in dirs) {
                currentPath += "/$dir"
                try {
                    ftpClient.makeDirectory(currentPath)
                } catch (e: Exception) {
                    // Directory might already exist, continue
                }
            }
        }

        ftpClient.changeWorkingDirectory(filePath)

        ByteArrayInputStream(bytes).use { inputStream ->
            ftpClient.storeFile(fileName, inputStream)
        }
    }
}

/**
 * @author Mikhail Golubev, Lukas Kremla
 */
class MicroPythonRunConfiguration(project: Project, factory: ConfigurationFactory) : AbstractRunConfiguration(project, factory), RunConfigurationWithSuppressedDefaultDebugAction {
    var path: String = ""
    var runReplOnSuccess: Boolean = false
    var resetOnSuccess: Boolean = true
    var useFTP: Boolean = false
    var synchronize: Boolean = false
    var excludePaths: Boolean = false
    var excludedPaths: MutableList<String> = mutableListOf()

    override fun getValidModules() =
        allModules.filter { it.microPythonFacet != null }.toMutableList()

    override fun getConfigurationEditor() = MicroPythonRunConfigurationEditor(this)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val success: Boolean
        val projectDir = project.guessProjectDir()
        val projectPath = projectDir?.path

        val facet = module?.microPythonFacet

        val ssid = facet?.configuration?.ssid ?: ""
        var wifiPassword: String = ""

        runWithModalProgressBlocking(project, "Retrieving Password...") {
            wifiPassword = project.service<MpySupportService>().retrieveWifiPassword()
        }

        if (path.isBlank() || (projectPath != null && path == projectPath)) {
            success = uploadProject(project, excludedPaths, synchronize, excludePaths, useFTP, ssid, wifiPassword)
        } else {
            val toUpload = StandardFileSystems.local().findFileByPath(path) ?: return null
            success = uploadFileOrFolder(project, toUpload, excludedPaths, synchronize, excludePaths, useFTP, ssid, wifiPassword)
        }
        if (success) {
            val fileSystemWidget = fileSystemWidget(project)
            if (resetOnSuccess) fileSystemWidget?.reset()
            if (runReplOnSuccess) fileSystemWidget?.activateRepl()
            return EmptyRunProfileState.INSTANCE
        } else {
            return null
        }
    }

    override fun checkConfiguration() {
        super.checkConfiguration()
        val m = module ?: throw RuntimeConfigurationError("Module for path was not found")
        val showSettings = Runnable {
            when {
                PlatformUtils.isPyCharm() ->
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MicroPythonProjectConfigurable::class.java)

                PlatformUtils.isIntelliJ() ->
                    ProjectSettingsService.getInstance(project).openModuleSettings(module)

                else ->
                    ShowSettingsUtil.getInstance().showSettingsDialog(project)
            }
        }
        val facet = m.microPythonFacet ?: throw RuntimeConfigurationError(
            "MicroPython support was not enabled for selected module in IDE settings",
            showSettings
        )
        val validationResult = facet.checkValid()
        if (validationResult != ValidationResult.OK) {
            val runQuickFix = Runnable {
                validationResult.quickFix.run(null)
            }
            throw RuntimeConfigurationError(validationResult.errorMessage, runQuickFix)
        }
        facet.pythonPath ?: throw RuntimeConfigurationError("Python interpreter is not found")
    }

    override fun suggestedName() = "Flash ${PathUtil.getFileName(path)}"

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("path", path)
        element.setAttribute("run-repl-on-success", if (runReplOnSuccess) "yes" else "no")
        element.setAttribute("reset-on-success", if (resetOnSuccess) "yes" else "no")
        element.setAttribute("synchronize", if (synchronize) "yes" else "no")
        element.setAttribute("exclude-paths", if (excludePaths) "yes" else "no")
        element.setAttribute("ftp", if (useFTP) "yes" else "no")

        if (excludedPaths.isNotEmpty()) {
            val excludedPathsElement = Element("excluded-paths")
            excludedPaths.forEach { path ->
                val pathElement = Element("path")
                pathElement.setAttribute("value", path)
                excludedPathsElement.addContent(pathElement)
            }
            element.addContent(excludedPathsElement)
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        configurationModule.readExternal(element)
        element.getAttributeValue("path")?.let {
            path = it
        }
        element.getAttributeValue("run-repl-on-success")?.let {
            runReplOnSuccess = it == "yes"
        }
        element.getAttributeValue("reset-on-success")?.let {
            resetOnSuccess = it == "yes"
        }
        element.getAttributeValue("synchronize")?.let {
            synchronize = it == "yes"
        }
        element.getAttributeValue("exclude-paths")?.let {
            excludePaths = it == "yes"
        }
        element.getAttributeValue("ftp")?.let {
            useFTP = it == "yes"
        }

        excludedPaths.clear()

        excludedPaths.clear()
        element.getChild("excluded-paths")?.let { excludedPathsElement ->
            excludedPathsElement.getChildren("path").forEach { pathElement ->
                pathElement.getAttributeValue("value")?.let { path ->
                    excludedPaths.add(path)
                }
            }
        }
    }

    val module: Module?
        get() {
            if (path.isEmpty()) {
                val projectDir = project.guessProjectDir()
                if (projectDir != null) return ModuleUtil.findModuleForFile(projectDir, project)
            }
            val file = StandardFileSystems.local().findFileByPath(path) ?: return null
            return ModuleUtil.findModuleForFile(file, project)
        }

    companion object {
        private fun VirtualFile.leadingDot() = this.name.startsWith(".")

        private fun collectProjectUploadables(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .mapNotNull { it.file }
                    .flatMap { it.children.toList() }
                    .filter { !it.leadingDot() }
                    .toMutableList()
            }.toSet()
        }

        private fun collectExcluded(project: Project): Set<VirtualFile> {
            val ideaDir = project.stateStore.directoryStorePath?.let { VfsUtil.findFile(it, false) }
            val excludes = if (ideaDir == null) mutableSetOf() else mutableSetOf(ideaDir)
            project.modules.forEach { module ->
                PythonSdkUtil.findPythonSdk(module)?.homeDirectory?.apply { excludes.add(this) }
                module.rootManager.contentEntries.forEach { entry ->
                    excludes.addAll(entry.excludeFolderFiles)
                }
            }
            return excludes
        }

        private fun collectSourceRoots(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .flatMap { entry -> entry.sourceFolders.toList() }
                    .filter { sourceFolder ->
                        !sourceFolder.isTestSource && sourceFolder.file?.let { !it.leadingDot() } ?: false
                    }
                    .mapNotNull { it.file }
            }.toSet()
        }

        private fun collectTestRoots(project: Project): Set<VirtualFile> {
            return project.modules.flatMap { module ->
                module.rootManager.contentEntries
                    .flatMap { entry -> entry.sourceFolders.toList() }
                    .filter { sourceFolder -> sourceFolder.isTestSource }
                    .mapNotNull { it.file }
            }.toSet()
        }

        fun uploadProject(
            project: Project,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            wifiPassword: String = ""
        ): Boolean {

            FileDocumentManager.getInstance().saveAllDocuments()
            val filesToUpload = collectProjectUploadables(project)
            return performUpload(project, filesToUpload, true, excludedPaths, shouldSynchronize, shouldExcludePaths, useFTP, ssid, wifiPassword)
        }

        fun uploadFileOrFolder(
            project: Project,
            toUpload: VirtualFile,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            wifiPassword: String = ""
        ): Boolean {

            FileDocumentManager.getInstance().saveAllDocuments()
            return performUpload(project, setOf(toUpload), false, excludedPaths, shouldSynchronize, shouldExcludePaths, useFTP, ssid, wifiPassword)
        }

        fun uploadItems(
            project: Project,
            toUpload: Set<VirtualFile>,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            wifiPassword: String = ""
        ): Boolean {

            FileDocumentManager.getInstance().saveAllDocuments()
            return performUpload(project, toUpload, false, excludedPaths, shouldSynchronize, shouldExcludePaths, useFTP, ssid, wifiPassword)
        }

        private fun performUpload(
            project: Project,
            toUpload: Set<VirtualFile>,
            initialIsProjectUpload: Boolean,
            excludedPaths: List<String> = emptyList(),
            shouldSynchronize: Boolean = false,
            shouldExcludePaths: Boolean = false,
            useFTP: Boolean = false,
            ssid: String = "",
            password: String = ""
        ): Boolean {

            var isProjectUpload = initialIsProjectUpload
            var filesToUpload = toUpload.toMutableList()
            val excludedFolders = collectExcluded(project)
            val sourceFolders = collectSourceRoots(project)
            val testFolders = collectTestRoots(project)
            val projectDir = project.guessProjectDir()

            var ftpUploadClient: FTPUploadClient? = null

            try {
                performReplAction(project, true, "Upload") { fileSystemWidget ->
                    var i = 0
                    while (i < filesToUpload.size) {
                        val file = filesToUpload[i]

                        val shouldSkip = !file.isValid ||
                                (file.leadingDot() && file != projectDir) ||
                                FileTypeRegistry.getInstance().isFileIgnored(file) ||
                                excludedFolders.any { VfsUtil.isAncestor(it, file, true) } ||
                                (isProjectUpload && testFolders.any { VfsUtil.isAncestor(it, file, true) }) ||
                                (isProjectUpload && sourceFolders.isNotEmpty() &&
                                        !sourceFolders.any { VfsUtil.isAncestor(it, file, false) })

                        when {
                            shouldSkip -> {
                                filesToUpload.removeAt(i)
                            }

                            file == projectDir -> {
                                i = 0
                                filesToUpload.clear()
                                filesToUpload = collectProjectUploadables(project).toMutableList()
                                isProjectUpload = true
                            }

                            file.isDirectory -> {
                                filesToUpload.addAll(file.children)
                                filesToUpload.removeAt(i)
                            }

                            else -> i++
                        }

                        checkCanceled()
                    }
                    val uniqueFilesToUpload = filesToUpload.distinct()
                    val fileToTargetPath = mutableMapOf<VirtualFile, String>()

                    uniqueFilesToUpload.forEach { file ->
                        val path = when {
                            sourceFolders.find { VfsUtil.isAncestor(it, file, false) }?.let { sourceRoot ->
                                VfsUtil.getRelativePath(file, sourceRoot) ?: file.name
                            } != null -> VfsUtil.getRelativePath(file, sourceFolders.find { VfsUtil.isAncestor(it, file, false) }!!) ?: file.name

                            testFolders.find { VfsUtil.isAncestor(it, file, false) }?.let { sourceRoot ->
                                VfsUtil.getRelativePath(file, sourceRoot) ?: file.name
                            } != null -> VfsUtil.getRelativePath(file, testFolders.find { VfsUtil.isAncestor(it, file, false) }!!) ?: file.name

                            else -> projectDir?.let { VfsUtil.getRelativePath(file, it) } ?: file.name
                        }

                        fileToTargetPath[file] = if (path.startsWith("/")) path else "/$path"
                    }

                    val scriptProgressText = if (shouldSynchronize) {
                        "Syncing and skipping already uploaded files..."
                    } else {
                        "Detecting already uploaded files..."
                    }

                    reportProgress(10000) { reporter ->
                        reporter.sizedStep(0, scriptProgressText) {
                            val alreadyUploadedFiles = fileSystemWidget.synchronizeAndGetAlreadyUploadedFiles(fileToTargetPath, excludedPaths, shouldSynchronize, shouldExcludePaths)
                            fileToTargetPath.keys.removeAll(alreadyUploadedFiles.toSet())
                        }

                        if (useFTP && fileToTargetPath.isNotEmpty()) {
                            if (ssid.isEmpty()) {
                                Notifications.Bus.notify(
                                    Notification(
                                        NOTIFICATION_GROUP,
                                        "Cannot upload over FTP, no SSID was provided in settings! Falling back to serial communication.",
                                        NotificationType.ERROR
                                    ), project
                                )
                            } else {
                                reporter.sizedStep(0, "Establishing an FTP server connection...") {
                                    val formattedScript = START_FTP_SERVER.format(
                                        """"$ssid"""",
                                        """"$password"""",
                                        10 // Wi-Fi connection timeout
                                    )

                                    println(formattedScript)

                                    val scriptResponse = fileSystemWidget.blindExecute(LONG_TIMEOUT, formattedScript).extractSingleResponse().trim()

                                    print(scriptResponse)

                                    if (scriptResponse.contains("ERROR") || !scriptResponse.startsWith("IP: ")) {
                                        Notifications.Bus.notify(
                                            Notification(
                                                NOTIFICATION_GROUP,
                                                "Ran into an error establishing an FTP connection, falling back to serial communication: $scriptResponse",
                                                NotificationType.ERROR
                                            ), project
                                        )
                                    } else {
                                        try {
                                            val ip = scriptResponse.removePrefix("IP: ")

                                            ftpUploadClient = FTPUploadClient()
                                            ftpUploadClient?.connect(ip, "", "") // No credentials are used
                                        } catch (e: Exception) {
                                            Notifications.Bus.notify(
                                                Notification(
                                                    NOTIFICATION_GROUP,
                                                    "Connecting to FTP server failed: $e",
                                                    NotificationType.ERROR
                                                ), project
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val totalBytes = fileToTargetPath.keys.sumOf { it.length }

                        fileToTargetPath.forEach { (file, path) ->
                            val fileProgress = ((file.length.toDouble() / totalBytes) * 10000).toInt()
                            reporter.sizedStep(fileProgress, path) {
                                if (ftpUploadClient != null) {
                                    ftpUploadClient?.uploadFile(file.contentsToByteArray(), path)
                                } else {
                                    fileSystemWidget.upload(path, file.contentsToByteArray())
                                }
                            }
                        }
                    }
                }
            } finally {
                ftpUploadClient?.disconnect()

                runWithModalProgressBlocking(project, "Cleaning up after FTP upload...") {
                    if (useFTP) {
                        fileSystemWidget(project)?.blindExecute(TIMEOUT, FTP_SERVER_CLEANUP)
                    }
                }

                runWithModalProgressBlocking(project, "Updating file system view...") {
                    fileSystemWidget(project)?.refresh()
                }
            }
            return true
        }
    }
}
