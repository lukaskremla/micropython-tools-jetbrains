# MicroPython Tools Documentation

## Table of Contents

- [Getting Started](#getting-started)
    - [Installation](#installation)
    - [Setting Up a Run Configuration](#setting-up-a-run-configuration)
- [Communication Types](#communication-types)
    - [Serial](#serial)
    - [WebREPL](#webrepl)
    - [Multiple Simultaneous Connections](#multiple-simultaneous-connections)
- [Stubs/Typehints](#stubstypehints)
    - [Built-in stub package manager](#built-in-stub-package-manager)
    - [Custom stub package](#custom-stub-package)
- [File System Widget]()
    - [File System Actions]()
    - [Volume Support]()
- [REPL Widget]()
- [Uploads](#uploads)
    - [Run Configurations](#run-configurations)
        - [Project](#project)
        - [Selected MicroPython Sources Roots](#selected-micropython-sources-roots)
        - [Custom Path](#custom-path)
    - [Drag and Drop](#drag-and-drop)
    - [Context Menu Actions](#context-menu-actions)
- [Execute File in REPL]()
    - [Run Configuration]()
    - [Context menu Action]()

## Getting Started

### Installation

1. Ensure that you have the necessary drivers for communicating with your microcontroller installed.
2. Ensure you have a JetBrains IDE version **2024.3 or newer** with the Python plugin installed.
3. Go to the **Plugin Marketplace**, search for *MicroPython Tools*, and install the plugin.
4. Open a project or create a new one.
5. Go to `Settings → Languages & Frameworks → MicroPython Tools`.
6. Enable **MicroPython support**.
7. Select an appropriate **stub package**.
8. Choose and configure your preferred **communication type**, then apply the settings.

### Setting Up a Run Configuration

To upload files to the device efficiently, you need to set up a run configuration:

1. Go to the **Run Configurations** menu near the top right corner of your IDE.
2. Create a new `MicroPython Tools → Upload` run configuration.
3. Choose a run configuration type (*Project* is best for most use cases).
4. Enable:
    - "Reset on success"
    - "Switch to REPL tab on success"
    - "Synchronize"
5. Configure paths to exclude from synchronization if needed (e.g. logs, OTA directories, persistent config files,
   etc.).
6. Create a new folder, right-click it, and mark it as a **MicroPython Sources Root**.

You can now treat this folder as the file system root `/` of your device and structure your code accordingly.  
When you make changes to your project, you can simply execute this run configuration — all new and modified items will
be uploaded, and any items deleted from your project will also be deleted from the device.

## Communication Types

Both official ways (Serial, WebREPL) to communicate with MicroPython boards are supported. The plugin utilizes a highly
optimized implementation of MicroPython's raw-paste mode with flow control. This ensures the fastest and most reliable
communication that REPL can support.

### Serial

Serial communication is the best and most common way to work with MicroPython boards, it's the fastest and most
reliable. It should be preferred over WebREPL whenever possible.

By default, the plugin's port-select dropdown menu filters out serial ports without a detectable manufacturer entry -
these ports often aren't hardware ports, but virtual ones (such as the default macOS "
/dev/tty.Bluetooth-Incoming-Port").

Some microcontrollers might not have the device manufacturer entry of their port populated and thus get filtered out
when they shouldn't. If you can't find the port you want to connect to in the dropdown menu, but your computer does see
it, try to disable this setting as it might be falsely filtering out this port.

### WebREPL

WebREPL is MicroPython's custom communication protocol meant to facilitate remote development on MicroPython boards. It
was primarily intended for use by browsers, and for this reason it uses WebSockets instead of pure TCP connections
(those are prohibited by browsers for security reasons).

The protocol is unfinished, riddled with many bugs, highly-unoptimized and difficult to implement right in a tool such
as this plugin due to being WebSocket based.

The plugin's current WebREPL implementation is as fast as the protocol permits, however, despite that it's incredibly
slow. Even simple scripts (such as File System scan) take long to execute, and uploads take ages.

It's advisable to avoid WebREPL for any projects that involve uploads with sizes of hundreds of kilobytes and to explore
custom remote development solutions if it is a necessity for your project.

If you do want to use or try WebREPL even with all of its constraints, it's recommended to add a delay after
starting a WebREPL server on the device to ensure that debug output isn't missed while the plugin is re-establishing a
WebREPL connection after a device reset. Some more useful information can also be found in
[this issue](https://github.com/lukaskremla/micropython-tools-jetbrains/issues/26#issuecomment-2843503240).

### Multiple Simultaneous Connections

At this time the plugin only supports a single active connection, supporting multiple simultaneous connections would be
overly complex and ambiguous. Additionally, the plugin utilizes a modal (blocking) progress dialog while communicating
with the device. This ensures files can't be modified during an upload and that no more than one action happens at once.

Supporting more than one connected device at a time would require a complete rewrite and only offer diminishing results
due to the modal nature of the dialog.

If you need to work with multiple devices simultaneously, you can either create a separate project for each device and
then have them open simultaneously. Alternatively, you can combine this plugin with a command line tool such as mpremote
or rshell and use the plugin for uploading code and the command line tool as a REPL monitor. More info can be found
[here](https://github.com/lukaskremla/micropython-tools-jetbrains/discussions/24).

# Stubs/Typehints

MicroPython stubs make the IDE recognize MicroPython specific modules (machine, network) and the MicroPythons stdlib
modules (asyncio, time). This brings auto-completion, code checking and allows you to see what methods are available.

## Built-in stub package manager

The plugin has a built-in MicroPython stub package manager. It utilizes MicroPython stubs by
[Jos Verlinde](https://github.com/Josverl/micropython-stubs). The packages come bundled with the plugin, and you can
select between them via the auto-completion text field.

Just start typing "micropython" and you'll be able to browse the available packages.

## Custom stub package

You can also use your own custom stub packages like this:

1. Disable the plugin's "Enable MicroPython stubs" option, so that it doesn't interfere with your customs stubs.
2. Create a folder in your project, it can be called anything, `.stubs` for example.
3. Mark the created folder as a `Sources Root` via the right click `Mark Directory as` action
4. Put the `.pyi` files and directories containing them in the created folder. If your stubs also have an `stdlib`
   folder, make sure to explicitly mark it as a `Sources Root` as well, otherwise it will be ignored.
5. You may need to restart the IDE to trigger a typehint re-scan.

## Uploads

There are several options for uploading items. All of them will skip already uploaded files if the connected board is
capable of calculating CRC32 hashes.

### Run Configurations

Run configurations are the main way to upload files to a MicroPython device. They allow you to upload and synchronize  
the files on the target device with just a single click.

There are three types of run configurations:

1. Project
2. Selected MicroPython Sources Roots
3. Custom Path

They differ in how files and folders to upload are selected. The other features (reset on success, switch to REPL tab,  
and synchronize) behave the same across all of them.

#### Project

This is the run configuration that is going to be the best fit for most use cases. It can work in two ways, depending
on  
whether at least one MicroPython Sources Root is marked.

##### No MicroPython Sources Roots Are Marked

All files and folders in your project will be uploaded with target (on-device) paths relative to the project root. For  
example:

```MyProjectName/Folder1/SubFolder/Script.py``` will be uploaded as ```Folder1/SubFolder/Script.py```

The algorithm will ignore and avoid uploading all items with a leading dot in their name, test source roots, and all  
excluded folders and their children.

##### One or More MicroPython Sources Roots Are Marked

If one or more MicroPython Sources Roots are marked, then only children of the top-most MicroPython Sources Roots will
be  
uploaded to the device relative to them. The files will get uploaded like this:

```MyProjectName/SomeFolder/MpySourcesRootMarkedFolder/Folder1/SubFolder/Script.py``` will be uploaded as  
```Folder1/SubFolder/Script.py```

Similarly to the project run configuration, all items with a leading dot in their name, test source roots, and all  
excluded folders and their children will be skipped.

#### Selected MicroPython Sources Roots

This run configuration works similarly to the project run configuration when at least one MicroPython Sources Root is  
selected. But instead of uploading all top-most MicroPython Sources Roots, you get to select which ones get uploaded.

This is useful in scenarios where you have a folder with ```.py``` files and another with ```.mpy``` files. You can
mark  
each folder as a MicroPython Sources Root and then have two separate `Selected MPY sources roots` run  
configurations — one for uploading ```.py``` files and another for uploading ```.mpy``` files.

Test folders, leading dot items, and excluded folders are ignored the same way as the Project run configuration does it.

#### Custom Path

The last upload run configuration type is Custom Path. This allows you to select a specific folder or file and
manually  
configure where it gets uploaded. This can be useful for uploading `secrets.py` and similar files, or other cases  
where the previous two types aren't appropriate for some reason.

When a file is selected, it gets uploaded with the displayed target path. When a folder gets uploaded, its contents  
will be uploaded to the `Upload to` path. So if you want to upload the whole folder and not just its contents, you  
must specify it in the `Upload to` field.

This run configuration type can also be useful if you want to upload a test source root, which would be ignored by the  
previous two types. Excluded and leading dot items are still skipped.

### Drag and Drop

You can quickly upload items by dragging them from the project file tree to the File System tab in the plugin's tool  
window. The items will get uploaded where they are dropped.

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.

### Context Menu Actions

You can manually upload files by right-clicking them in the project file tree or in the editor tab of open files. They  
can be uploaded directly to the device root `/`, relative to the project root, or relative to the parent-most  
MicroPython Sources Root (if applicable).

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.