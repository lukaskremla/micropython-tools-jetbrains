# MicroPython Tools Plugin for PyCharm, CLion and other JetBrains IDEs

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

- Built-in MicroPython firmware flashing support
- Integration with mpy-cross to allow compiling to bytecode

Long term plans:

- After the full-release of this plugin I might consider also developing MicroPython plugins for VSCode
  and possibly Visual Studio 2022

## Quick start

To access all of the features this plugin offers I recommend always creating a Project upload configuration in every
project.

After enabling MicroPython support in the "Languages & Frameworks" section of settings you will be able to mark
directories as MicroPython Sources Roots. The upload project run configuration uploads contents of the top most
MicroPython Sources Roots marked folders. So you can treat these folders as the root "/" of the device's MicroPython
file system when structuring your project.

I also recommend enabling the synchronize run configuration option, that way you don't have to worry about cleaning up
the File System
if you make large changes to your project. When running the upload run configuration, all folders and files that were
not apart of the
upload will be deleted - you can also simply configure excluded target (MicroPython) paths, that will be
ignored by the synchronization feature.

Finally, don't forget to select the appropriate stubs package for your device. You can do so in the plugin's settings.
Start typing "micropython" in the "Stubs package" text field and you can browse all available packages via autocomplete.

## Features

### File System widget

- Easily view and interact with the MicroPython device file system
- Upload to or reorganize the file system via drag and drop
  ![File System Widget](media/file_system.png)

### REPL Widget

- Observe code execution via REPL
  ![REPL Widget](media/repl.png)

### Run Configurations

- #### Upload
    - Comfortably select what gets uploaded
    - Synchronize device file system to only contain uploaded files and folders
    - Exclude on-device paths from synchronization
      ![Upload Run Configuration](media/run_configuration_upload.png)
- #### Execute in REPL
    - Execute selected ".py", ".mpy" file or code in REPL without uploading it to the device
      ![Execute in REPL Run Configuration](media/run_configuration_execute.png)

### Context Menu Actions

- Quickly upload or execute selected files
  ![Context Menu File Actions](media/file_actions.png)
- Custom "Mark as MicroPython Sources Root" action that allows compatibility many different JetBrains IDEs
  ![Context Menu MicroPython Sources Actions](media/micropython_sources.png)

### Settings

![Settings](media/settings.png)

#### MicroPython Stubs

- Built-in stubs management Integrates all available MicroPython stubs packages
  by [Jos Verlinde](https://github.com/Josverl/micropython-stubs)

### Other

- Automatically skips already uploaded files (on boards with the required crc32 binascii capabilities)

## Requirements

* A valid Python interpreter 3.10+
* Python Community plugin (For non-PyCharm IDEs)
* A development board with MicroPython installed (1.20+ is recommended)

The plugin is licensed under the terms of the Apache 2 license.
