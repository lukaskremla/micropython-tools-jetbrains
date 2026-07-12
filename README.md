# MicroPython Tools Plugin for PyCharm, CLion, IntelliJ and other JetBrains IDEs

[![JetBrains IntelliJ Downloads](https://img.shields.io/jetbrains/plugin/d/26227-micropython-tools?label=Downloads)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![JetBrains IntelliJ Rating](https://img.shields.io/jetbrains/plugin/r/rating/26227-micropython-tools?label=Rating)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)

This plugin brings MicroPython support into JetBrains IDEs in Free and Pro editions.
It provides reliable device file system integration, REPL support, stub package management, firmware flashing, run
configurations and smooth workflows for developing both hobbyist and professional MicroPython projects.

The Free edition covers all essential MicroPython development needs, while the Pro edition adds advanced tools like
background transfers, compression, mpy-cross compilation, and bytecode analysis. Pro is just $2.90 a month, with a free
trial available on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/26227-micropython-tools/pricing).

Usage tips, setup guide and documentation are available [here](DOCUMENTATION.md).

## Features

Forever Free features:

- Upload and synchronize files with MicroPython devices, automatically skipping already uploaded files (Serial and
  WebREPL supported)
- File System view with drag and drop, create/rename/delete, copy/cut/paste, and download
- Type Hint/Stub package manager for MicroPython stubs
- On-device file editing
- REPL integration with soft reset, hard reset, interrupt, and clear actions
- Automatically detect MicroPython device's serial port and connect
- Firmware flashing, automatically download MicroPython and flash ESP, RP2 and SAMD devices
- Execute files or code selections in REPL
- Run configurations for uploading and executing files in REPL
- Mounted volume support (e.g. SD cards)

Pro (Paid) features:

- Background uploads and downloads that let you continue working
- Automatic file compression to significantly speed up uploads
- mpy-cross run configuration with auto-detection of bytecode version and architecture
- .mpy file analyzer

<details>
<summary>Showcase video</summary>

[![Watch the video](https://img.youtube.com/vi/gCzkCB5NNa8/maxresdefault.jpg)](https://www.youtube.com/watch?v=gCzkCB5NNa8)
</details>

For a full list of features visit [FEATURES.md](FEATURES.md)

## Requirements

#### System Requirements

* A valid Python interpreter 3.10+
* Python Community plugin (for non-PyCharm IDEs)

#### MicroPython Device Requirements

* Official MicroPython firmware version 1.20.0 or newer from micropython.org/download
* Device must support standard MicroPython REPL features (REPL, Raw REPL and Raw Paste Mode)
* Some features require additional libraries (e.g., `binascii.crc32` for skipping already uploaded files). The plugin
  will warn if these are unavailable.

#### Compatibility Notice:

This plugin requires standard MicroPython REPL features (REPL, Raw REPL and Raw Paste Mode). Custom or
manufacturer-modified ports (such as micro:bit) that do not include these features are **not compatible** and will not
work and **no support** will be provided for such devices.

If you're using a custom port that preserves standard REPL functionality, the plugin should work. However, if you
encounter issues, please open an issue with firmware details - we'll investigate, but cannot guarantee support for
non-standard configurations.

MicroPython versions older than 1.20.0 are not officially supported but may work.

## License

This plugin is distributed via the JetBrains Marketplace or this repository's Releases and is licensed under the
[End-User License Agreement (EULA)](EULA.txt).

This repository does not contain the plugin's source code. It is a companion repository used for issue tracking,
discussions, and managing public-facing text and scripts.

## Credits

Originally inspired by JetBrains’ MicroPython plugin, this project has since been fully reworked and expanded into a
standalone tool. Credit to [Jos Verlinde](https://github.com/Josverl/micropython-stubs) for creating and maintaining the
stubs used here, and to [Ilia Motornyi](https://github.com/elmot) and
[Andrey Vlasovskikh](https://github.com/vlasovskikh) for their work on the original plugin.

## Third-Party Notices

This plugin includes the following third-party components:

- **MicroPython Stubs** - Copyright 2020-2026 Jos Verlinde, MIT License  
  https://github.com/Josverl/micropython-stubs  
  See [licenses/stubs/LICENSE.md](licenses/stubs/LICENSE.md) for the full license.

- **Pico Universal Flash Nuke** - Copyright 2024 Phil Howard, BSD 3-Clause License  
  https://github.com/Gadgetoid/pico-universal-flash-nuke  
  See [licenses/rp2UniversalFlashNuke/LICENSE.txt](licenses/rp2UniversalFlashNuke/LICENSE.txt) for the full license.

- **Espflash** - Copyright (c) 2022-2025 The Espflash Project Developers  
  https://github.com/esp-rs/espflash  
  See [licenses/espflash/LICENSE.txt](licenses/espflash/LICENSE.txt) for the full license.

- **Java-WebSocket** - Copyright (c) 2010-2020 Nathan Rajlich, MIT License  
  https://github.com/TooTallNate/Java-WebSocket  
  See [licenses/java-WebSocket/LICENSE.txt](licenses/java-WebSocket/LICENSE.txt) for the full license.  