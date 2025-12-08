# MicroPython Tools Plugin for PyCharm, CLion, IntelliJ and other JetBrains IDEs

[![JetBrains IntelliJ Downloads](https://img.shields.io/jetbrains/plugin/d/26227-micropython-tools?label=Downloads)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![JetBrains IntelliJ Rating](https://img.shields.io/jetbrains/plugin/r/rating/26227-micropython-tools?label=Rating)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)

This plugin brings MicroPython support into JetBrains IDEs in Free and Pro editions.
It provides reliable device file system integration, REPL support, stub package management, and smooth workflows for
developing both hobbyist and professional MicroPython projects.

The Free edition covers all essential MicroPython development needs, while the Pro edition adds advanced tools like
background transfers, compression, mpy-cross compilation, and bytecode analysis.

Originally inspired by JetBrains’ MicroPython plugin, this project has since been fully reworked and expanded into a
standalone tool. Credit to [Jos Verlinde](https://github.com/Josverl/micropython-stubs) for creating and maintaining the
stubs used here, and to [Ilia Motornyi](https://github.com/elmot) and
[Andrey Vlasovskikh](https://github.com/vlasovskikh) for their work on the original plugin.

Usage tips, setup guide and documentation are available [here](DOCUMENTATION.md).

## Features

### Free Features

#### File System Widget

- Easily view and interact with the device's file system in a dedicated tool window
- Upload to or reorganize the file system via drag and drop
- Supports mounted volumes (such as SD cards) and displays storage usage for each mount point
- Create new files or directories directly on the device
- Full copy/cut/paste support (project to device and device to device)
- Download files from the device to your project
- Open and edit on-device files with save-to-device functionality

<details>
<summary>View File System Widget screenshot</summary>

![File System Widget](media/file_system.png)
</details>

<details>
<summary>View File System Context Menu screenshot</summary>

![File System Widget Context Menu](media/file_system_context_menu.png)
</details>

<details>
<summary>View File System Edit File screenshot</summary>

![File System Widget Edit File](media/file_system_edit_file.png)
</details>

#### REPL Widget

- Full MicroPython REPL integration in a dedicated tool window
- All keyboard shortcuts are passed through to the device (Raw REPL, Paste mode, soft reset, interrupt)
- Toolbar actions for common operations (reset, interrupt, clear)
- Optional auto-clear to prevent terminal clutter

<details>
<summary>View REPL Widget screenshot</summary>

![REPL Widget](media/repl.png)
</details>

#### Uploads and Synchronization

- Multiple upload methods: context menu actions, drag and drop, or run configurations
- Smart upload: already uploaded files are automatically skipped using CRC32 calculations
- Upload preview dialog shows exactly how the file system will look after the upload operation
- Synchronization mode to mirror your project structure on the device (removes extra files)

<details>
<summary>View Upload Preview screenshot</summary>

![Upload Preview](media/upload_preview.png)
</details>

#### Run Configurations

Multiple run configuration types for different workflows:

- **Upload**: Select what gets uploaded (entire project, specific MicroPython Sources Roots, or custom paths)
    - Synchronize device file system to only contain uploaded files and folders
    - Exclude on-device paths from synchronization (e.g., logs, OTA directories, persistent config files)
    - Reset device and switch to REPL tab on success

<details>
<summary>View Upload Run Configuration screenshot</summary>

![Upload Run Configuration](media/run_configuration_upload.png)
</details>

- **Execute in REPL**: Run `.py` or `.mpy` files directly in REPL without uploading to the device
    - Execute entire files or just selected code fragments
    - Perfect for testing without modifying the device file system

<details>
<summary>View Execute in REPL Run Configuration screenshot</summary>

![Execute in REPL Run Configuration](media/run_configuration_execute.png)
</details>

<details>
<summary>View Execute Code Fragment screenshot</summary>

![Execute Code Fragment in REPL](media/execute_fragment.png)
</details>

#### MicroPython Stubs Package Manager

- Built-in package manager for [Jos Verlinde's MicroPython stubs](https://github.com/Josverl/micropython-stubs)
- Install and apply stubs on-demand for your specific board and MicroPython version
- Automatic update notifications when newer stub versions are available
- Visual table showing installed, available, and selected packages

<details>
<summary>View Settings screenshot</summary>

![Settings](media/settings.png)
</details>

#### Context Menu Actions

- Quick upload actions: upload to device root, relative to project, or relative to MicroPython Sources Root
- Execute file or code fragment in REPL directly from the editor
- Custom "Mark as MicroPython Sources Root" action for better IDE compatibility across JetBrains products

<details>
<summary>View File Context Menu Actions screenshot</summary>

![Context Menu File Actions](media/file_actions.png)
</details>

<details>
<summary>View MicroPython Sources Root Action screenshot</summary>

![Context Menu MicroPython Sources Actions](media/micropython_sources.png)
</details>

### Pro Features

#### Background Uploads and Downloads

- Upload and download operations run in the background without blocking the IDE
- Continue coding while large file transfers are in progress
- Progress reporting in the IDE's background tasks manager

<details>
<summary>View Background Progress screenshot</summary>

![Background Progress](media/background_progress.png)
</details>

#### Automatic File Compression

- Files are automatically compressed before upload to significantly speed up transfers
- Improves communication stability by reducing the amount of data transmitted
- Transparent compression/decompression on both sides

<details>
<summary>View Upload Preview with Compression screenshot</summary>

![Upload Preview with Compression](media/upload_preview_compression.png)
</details>

#### mpy-cross Run Configuration

- Compile Python files to MicroPython bytecode (`.mpy`) directly from the IDE
- Automatic detection of bytecode version and architecture for the connected board
- Supports custom compilation flags and options

<details>
<summary>View mpy-cross Run Configuration screenshot</summary>

![mpy-cross Run Configuration](media/run_configuration_compile.png)
</details>

#### .mpy File Analyzer

- View detailed information about compiled `.mpy` files
- Inspect bytecode version, architecture, and other metadata
- Helps troubleshoot compatibility issues

<details>
<summary>View .mpy File Analyzer screenshot</summary>

![.mpy File Analyzer](media/mpy_file_analyzer.png)
</details>

## Requirements

* A valid Python interpreter 3.10+
* Python Community plugin (For non-PyCharm IDEs)
* A development board with MicroPython installed (version 1.20+ is recommended)

## License

The plugin distributed via the JetBrains Marketplace or this repository's Releases is licensed under
the [End-User License Agreement (EULA)](EULA.txt).

The open-source components of this plugin are licensed under the [Apache License 2.0](LICENSE.txt).

## Third-Party Notices

This plugin includes the following third-party components:

- **MicroPython Stubs** - Copyright 2020-2024 Jos Verlinde, MIT License  
  https://github.com/Josverl/micropython-stubs  
  See [licenses/stubs/LICENSE.md](licenses/stubs/LICENSE.md) for the full license.

- **Pico Universal Flash Nuke** - Copyright 2024 Phil Howard, BSD 3-Clause License  
  https://github.com/Gadgetoid/pico-universal-flash-nuke  
  See [licenses/rp2UniversalFlashNuke/LICENSE.txt](licenses/rp2UniversalFlashNuke/LICENSE.txt) for the full license.
