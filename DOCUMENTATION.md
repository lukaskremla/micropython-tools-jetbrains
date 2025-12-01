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
- [File System Widget](#file-system-widget)
    - [Drag and Drop](#drag-and-drop-in-file-system-widget)
    - [Volume/SD card Support](#volume-support)
    - [File System Context Menu](#file-system-context-menu)
    - [Opening And Editing on-device files](#opening-and-editing-on-device-files)
- [REPL Widget](#repl-widget)
- [Uploads](#uploads)
    - [Run Configurations](#run-configurations)
        - [Project](#project)
        - [Selected MicroPython Sources Roots](#selected-micropython-sources-roots)
        - [Custom Path](#custom-path)
    - [Drag and Drop](#drag-and-drop-uploads)
    - [Context Menu Actions](#context-menu-actions)
- [Execute File in REPL](#execute-file-in-repl)
    - [Run Configuration](#run-configuration)
    - [Context menu Action](#context-menu-action)
- [Pro Features](#pro-features)
    - [Background Uploads and Downloads](#background-uploads-and-downloads)
    - [Automatic File Compression](#automatic-file-compression)
    - [mpy-cross Compilation](#mpy-cross-compilation)
    - [.mpy File Analyzer](#mpy-file-analyzer)

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

Both official ways (Serial, WebREPL) to communicate with MicroPython devices are supported. The plugin utilizes a highly
optimized implementation of MicroPython's raw-paste mode with flow control. This ensures the fastest and most reliable
communication that REPL can support.

### Serial

Serial communication is the best and most common way to work with MicroPython devices, it's the fastest and most
reliable. It should be preferred over WebREPL whenever possible.

By default, the plugin's port-select dropdown menu filters out serial ports without a detectable manufacturer entry -
these ports often aren't hardware ports, but virtual ones (such as the default macOS "
/dev/tty.Bluetooth-Incoming-Port").

Some microcontrollers might not have the device manufacturer entry of their port populated and thus get filtered out
when they shouldn't. If you can't find the port you want to connect to in the dropdown menu, but your computer does see
it, try to disable this setting as it might be falsely filtering out this port.

### WebREPL

WebREPL is MicroPython's custom communication protocol meant to facilitate remote development on MicroPython devices. It
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

Supporting more than one connected device at a time would require a complete rewrite and only offer diminishing returns
due to the modal nature of the dialog.

If you need to work with multiple devices simultaneously, you can either create a separate project for each device and
then have them open simultaneously. Alternatively, you can combine this plugin with a command line tool such as mpremote
or rshell and use the plugin for uploading code and the command line tool as a REPL monitor. More info can be found
[here](https://github.com/lukaskremla/micropython-tools-jetbrains/discussions/24).

## Stubs/Typehints

MicroPython stubs make the IDE recognize MicroPython specific modules (machine, network) and the MicroPythons stdlib
modules (asyncio, time). This brings auto-completion, code checking and allows you to see what methods are available.

### Built-in stub package manager

The plugin includes a stub package manager for the community-maintained MicroPython stubs
by [Jos Verlinde](https://github.com/Josverl/micropython-stubs).

The stub packages are shown in a table with a toolbar that lets you install, update, select, deselect, delete, and
refresh them. A package must be installed before it can be selected. By default, no packages are installed (all entries
appear grayed out).

Table highlights:

- Installed packages are shown in white and pinned to the top.
- Update available packages are highlighted in blue.
- Selected (active) packages are highlighted in green.

If a selected package has an update pending, the IDE will display an in-editor inspection notice.

A working internet connection is required to view, install, or update stub packages. Once installed, they remain
available offline.

### Custom stub package

You can also use your own custom stub packages like this:

1. Disable the plugin's "Enable MicroPython stubs" option, so that it doesn't interfere with your customs stubs.
2. Create a folder in your project, it can be called anything, `.stubs` for example.
3. Mark the created folder as a `Sources Root` via the right click `Mark Directory as` action
4. Put the `.pyi` files and directories containing them in the created folder. If your stubs also have an `stdlib`
   folder, make sure to explicitly mark it as a `Sources Root` as well, otherwise it will be ignored.
5. You may need to restart the IDE to trigger a typehint re-scan.

## File System Widget

The File System widget is one of the most useful features of this plugin. Being able to see the state of the file
system and manage it just like on your computer’s OS is priceless.

Due to the constrained nature of MicroPython, file system scans cannot occur in the background, they must interrupt code
running on the device. In order for the file information that the plugin displays to be accurate, a scan is
automatically carried out after every file system operation (uploads, deletions, creating directories, etc.)

If this automatic refresh is cancelled, the plugin will disconnect, as it can no longer trust that the data it has
reflects the true state of the device's file system.

You can also trigger a refresh manually via the toolbar action, this is useful for when you want to see changes your
code has made.

### File System Context Menu

The file system context menu (right-click) provides the core actions you would expect from a fully featured OS file
manager:

- Create new files (.py or any other type) and directories
- Copy / Cut / Paste (limited to project → device and device → device, never device → project)
- Rename files or directories
- Copy file or directory name / absolute path

You can also download files from the device.

⚠️ Note: Downloads are potentially destructive. Ensure the destination directory does not contain items that would
conflict with the files being downloaded.

#### Opening And Editing on-device files

On-device files can be opened directly in the IDE in read-only mode, with the option to enter edit mode.

- Edits persist as long as the editor tab remains open.
- You can save the modified file back to the device (if connected), or discard the changes to restore the original
  version.
- The opened file can be refreshed if a device is connected

### Drag and Drop in File System Widget

The File System widget's tree items fully support drag and drop for both uploads and re-arranging the file system. More
info on drag and drop uploads can be found [here](#drag-and-drop-uploads).

### Volume Support

The File System widget also supports mounted volumes (SD cards and more). This support works automatically for
MicroPython versions 1.25+, which introduced an efficient way to query the device's mount points.

The plugin will display SD cards and other mounted volumes on the top level similarly to the FS root "/". It will also
display the stats of how much storage is used up, how much is available and some action descriptions will change to
reflect that a volume is going to be affected.

Volume support is also available for MicroPython versions below 1.25, it can be enabled by checking the "legacy volume
support" checkbox in the settings.

NOTE: Legacy support will slow down refresh operations anytime you connect a device with MicroPython version below 1.25,
because the check is more comprehensive and demanding.

## REPL Widget

The REPL Widget of this plugin lets you directly access REPL as it is. This means that all MicroPython REPL keyboard
shortcuts (Raw REPL, Paste mode, Reset) will get passed through to the device. For your comfort the plugin also exposes
some commonly used REPL actions (reset and interrupt) into toolbar buttons.

Enabling Auto Clear REPL will clear the terminal after every major action (FS refresh, upload, download, reset). This is
useful to prevent cluttering of the terminal.

## Uploads

There are several options for uploading items. All of them will skip already uploaded files if the connected device is
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

### Drag and Drop Uploads

You can quickly upload items by dragging them from the project file tree to the File System tab in the plugin's tool  
window. The items will get uploaded where they are dropped.

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.

### Context Menu Actions

You can manually upload files by right-clicking them in the project file tree or in the editor tab of open files. They  
can be uploaded directly to the device root `/`, relative to the project root, or relative to the parent-most  
MicroPython Sources Root (if applicable).

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.

## Execute File in REPL

Executing a file in REPL is a handy feature for a wide array of scenarios, from running test scripts, executing code
fragments or wanting to avoid blocking a device off by a bug in your `main.py`. This plugin offers two ways to execute
code directly in REPL without it ever touching the file system of your device.

### Run Configuration

Setting up an Execute File in REPL run configuration will allow you to easily run a file with one click or run a set of
test files programmatically as a part of some larger run configuration chain. Only `.py` and `.mpy` are accepted.

### Context menu Action

The execute code in REPL action is available in multiple menus. It's available for `.py` and `.mpy` files when you
right-click them in the project tree, and it's also available for files open in the editor, both in the file's editor
tab and when right-clicking anywhere in the open editor.

While you're in the file's editor you can also select code and execute just the selected Fragment in REPL.

## Pro Features

The following features require an active Pro license.

### Background Uploads and Downloads

Upload and download operations run in the background without blocking the IDE. Progress is shown in the IDE's background tasks manager (bottom-right corner). You can continue coding while transfers are in progress.

Enable in `Settings → Languages & Frameworks → MicroPython Tools` with the "Enable background uploads/downloads" checkbox.

### Automatic File Compression

Files are automatically compressed before upload and decompressed on the device. Compression is applied to files larger than 4KB that achieve at least 12% size reduction. Already-compressed files (images, archives, `.mpy`) are skipped. The upload preview dialog shows compression savings.

Enable in `Settings → Languages & Frameworks → MicroPython Tools` with the "Enable upload compression" checkbox.

### mpy-cross Compilation

Compile Python files to MicroPython bytecode (`.mpy`) directly from the IDE. Bytecode files load faster and use less memory on the device.

Create a new `MicroPython Tools → mpy-cross Compilation` run configuration. Select what to compile (Project, Selected Sources Roots, or Custom Path), choose output location, and configure compilation options.

The Project/Selected/Custom path source selecting logic is the same for this run configuration is it is for the [upload run configuration](#run-configurations).

**Auto-Detection:** Click the Auto-Detect button to automatically detect your device's bytecode version and architecture.

**Emitter options:**
- Bytecode: Standard bytecode, compatible with all devices (Choose this unless you know the implications of Native/Viper and how to use them)
- Native/Viper: Faster machine code, architecture-specific (Strict requirements, Viper requires strict typing of variables, Native doesn't work well with exception handlers)

**Optimization levels:** O0 (no optimization) through O3 (maximum optimization)

**Embed modes:**
- Filename only: Shortest (Slight obfuscation)
- Relative to project root: Full paths in stack traces (best for debugging
- Mapping file: Custom shortened paths (Allows manually setting up obfuscation of source files for stack traces

**Special handling:**
- `boot.py` and `main.py` are always copied as `.py` (not compiled)
- Non-Python files can optionally be copied alongside `.mpy` files

More information on mpy-cross compilation can be found on the [official micropython.org website](https://docs.micropython.org/en/latest/reference/mpyfiles.html).

#### Mapping Files

Mapping files customize the paths embedded in `.mpy` files. Format: one line per file with source path and embedded path separated by space.

Example:
```
src/network/wifi.py wifi
src/utils/helpers.py utils/hlp
```

Click the "Generate" link in the run configuration to auto-generate a mapping file.

#### Bytecode version to used mpy-cross binary mapping

The plugin's selection of mpy-cross binaries deviates from what the mpy-cross python wrapper does. The official mpy-cross python library's compat parameter uses the earliest available mpy-cross binary of a give Bytecode/MicroPython version. 

This means that if you use --compat 1.26.1, it won't use the mpy-cross version 1.26.1, but the earliest available mpy-cross version compatible with the 1.26.1 MicroPython bytecode is mpy-cross of version 1.23.0.

This means you'll be missing out on several improvements and optimizations. For example multi line f strings (which mpy-cross 1.26.1 can handle) might not compile at all with mpy-cross 1.23.0.

This plugin solves this issue by internally using the latest available mpy-cross binary for a given version. So if you select -compat 1.23.0, 1.24.0 etc. it will default to the latest available mpy-cross binary for this version (at the time of writing this documentation it is 1.26.1) meaning you get the latest available mpy-cross optimizations and bug fixes.

NOTE: This should not affect backwards compatibility (bytecode from mpy-cross 1.26.1 should be perfectly compatible with MPY version 1.23.0), if you suspect you've ran into some compatibility issue caused by this plugin's mpy-cross version selection behavior, pleae open an issue.

The mapping of BytecodeVersions to used mpy-cross binaries differes on Windows/macOS due to the availability of pre-compiled binaries. To see what MicroPython version range a given MicroPython Bytecode version corresponds to, visit the official [micropython.org website](https://docs.micropython.org/en/latest/reference/mpyfiles.html). Below is the mapping of Bytecode version to used mpy-cross binary of this plugin:



### .mpy File Analyzer

When you open a `.mpy` file in the IDE, a metadata panel displays:
- File size
- Embedded path
- Emitter type (Bytecode/Native/Viper)
- Architecture
- Bytecode version
- Small int bits

Useful for verifying compilation settings and checking bytecode version compatibility before uploading.
