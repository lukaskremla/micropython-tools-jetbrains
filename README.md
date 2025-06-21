# MicroPython Tools Plugin for PyCharm, CLion, IntelliJ and other JetBrains IDEs

[![JetBrains IntelliJ Downloads](https://img.shields.io/jetbrains/plugin/d/26227-micropython-tools?label=Downloads)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![JetBrains IntelliJ Rating](https://img.shields.io/jetbrains/plugin/r/rating/26227-micropython-tools?label=Rating)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)

This is a fork of the [JetBrains IntelliJ MicroPython plugin](https://github.com/JetBrains/intellij-micropython).
Credits to [Andrey Vlasovskikh](https://github.com/vlasovskikh) for creating the original plugin and
to [Ilia Motornyi](https://github.com/elmot) for his original work on the communication layer and file system view.

Many thanks also go to [Jos Verlinde](https://github.com/Josverl/micropython-stubs) for creating and maintaining the
MicroPython stubs that this plugin uses.

I have decided fork the original jetbrains plugin as its development had slowed and I wanted to focus on implementing
more advanced features that I deem invaluable based on my professional experience working with MicroPython

I believe that the MicroPython community needs robust, actively maintained and developed tools adding MicroPython
support to modern industry-standard IDEs. My aim with this fork is to address this need.

There will be frequent updates, I'm actively working on developing new features and fixing bugs in the existing
code. If you run into any problems while using this plugin, please create an issue. For any suggestions or feature
requests, feel free to start a discussion.

Some of the features you can expect soon include:

- Reworked stubs manager, allowing on-demand downloading and updating of stub packages
- Integration with mpy-cross to allow compiling to bytecode

Long term plans:

- Built-in MicroPython firmware flashing support
- After the full-release of this plugin I might consider also developing MicroPython plugins for VSCode
  and possibly Visual Studio 2022

## Installation, Getting Started and Documentation

Usage tips and documentation are available
[here](https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/DOCUMENTATION.md)

## Features

### File System Widget

- Easily view and interact with the device's file system
- Upload to or reorganize the file system via drag and drop
- Supports mounted volumes (such as SD cards) and displays storage usage
  ![File System Widget](media/file_system.png)

### REPL Widget

- Interact with the MicroPython REPL
- All keyboard shortcuts are passed to the device as well (Raw REPL, Paste mode, etc.)
  ![REPL Widget](media/repl.png)

### Uploads

- Items can be uploaded via context menu actions, drag and drop, or run configurations
- Already uploaded files are automatically skipped using CRC32 calculations
- Upload preview dialog that shows how the file system will look after the upload operation
  ![Upload Preview](media/upload_preview.png)

### Run Configurations

- #### Upload
    - Comfortably select what gets uploaded
    - Synchronize device file system to only contain uploaded files and folders
    - Exclude on-device paths from synchronization
      ![Upload Run Configuration](media/run_configuration_upload.png)
- #### Execute in REPL
    - Execute a ".py", ".mpy" file or selected code selections in REPL without uploading anything to the device
      ![Execute in REPL Run Configuration](media/run_configuration_execute.png)
      ![Execute Code Fragment in REPL](media/execute_fragment.png)

### MicroPython Stubs

- Built-in stubs management Integrates all available MicroPython stubs packages
  by [Jos Verlinde](https://github.com/Josverl/micropython-stubs)

### Context Menu Actions

- Quickly upload or execute selected files
  ![Context Menu File Actions](media/file_actions.png)
- Custom "Mark as MicroPython Sources Root" action that allows compatibility with many different JetBrains IDEs
  ![Context Menu MicroPython Sources Actions](media/micropython_sources.png)

### Settings

![Settings](media/settings.png)

## Requirements

* A valid Python interpreter 3.10+
* Python Community plugin (For non-PyCharm IDEs)
* A development board with MicroPython installed (version 1.20+)

This plugin is licensed under the terms of the Apache 2 license.
