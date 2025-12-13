The Changelog
=============

2025.3.4 - XX.12.2025
------------------

* Fix File System widget drag and drop handling to match the look and functionality of the project file tree
* Switch from JSSC to JSerialComm for serial communication, achieving up to over 20% upload speed improvements when not
  using compression
* Halved the distributed plugin archive size by optimizing the build process
* Ensured Install/Update dependency logic has exceptions checked and logged
* Improved cancellability of dependency/stub packages

2025.3.3 - 11.12.2025
------------------

* Added an option to Flash MicroPython firmware directly from the IDE with real-time progress output. Supports ESP, RP2
  and SAMD devices
* Added MicroPython detection when connecting to prevent confusing timeout errors on boards without MicroPython
  installed
* Centralized Python dependency handling with automatic esptool/mpy-cross installation and unified inspections
* Reworked the stub package management backend resulting in less http calls, better performance and ensured
  compatibility with all available stub packages
* Updated type hints terminology in stub package dialogs to clarify what stubs provide
* Improved settings "device already connected" dialog to only update after successful disconnection
* Improved upload run configuration layout - moved "Force blocking upload" checkbox after "Exclude paths" for better UX
* Adjusted File System volume size formatting
* Fixed port selector model not recognizing changes in settings

2025.3.2 - 18.11.2025
------------------

* Fixed the "Unexpected keyword argument 'sep'" error on very constrained ports

2025.3.1 - 5.11.2025
------------------

Free:

* Switched to release year based versioning
* Improved stub package installation logic to show details about exceptions and to support UV interpreters
* Reworked run configurations to show console views and be compatible with before launch tasks

Paid:

* Added mpy-cross run configuration with support for automatically detecting bytecode version/architecture for connected
  board
* Added upload compression which speeds up uploads and improves communication stability by reducing the amount of data
  that must be transferred
* Added background uploads/downloads progress reporting to reduce workflow disruptions during long-running data
  transfers
* Added .mpy file analyzer

0.6.3 - 3.10.2025
------------------

* Only check if the currently selected stub-package is up-to-date once an hour to improve performance
* Moved stub packages to a location that will survive IDE updates and cache resets

0.6.2 - 11.9.2025
------------------

* Improved no network state stub package manager handling
* Removed no network IDE notification when stub package validation couldn't check for updates

0.6.1 - 2.9.2025
------------------

* Ensure the file system is refreshed after an edited file is saved

0.6.0 - 28.8.2025
------------------

* Removed confirmation dialog from Clear REPL action
* Reworked and improved Open File action
* Added option to refresh and edit on-device files
* Added “New” action for creating files and folders on-device
* Added Copy, Cut, and Paste context menu actions to the File System view
* Increased command execution timeout to reduce failures on large uploads
* Fixed connection integrity monitoring on Windows
* Reworked stub package management with on-demand installation and updating
* Prevented file system refresh when a pre-upload scan is cancelled

0.5.12 - 28.7.2025
------------------

* Added a checkbox to enable manual editing of selected ports

0.5.11 - 22.7.2025
------------------

* Fixed a bug where uploads of files that can't fit into the device's memory always failed
* Improved error reporting, added memory fragmentation warnings

0.5.10 - 3.7.2025
------------------

* Fixed a bug where warnings about unavailable features of certain boards caused EDT errors
* Fixed a bug where cancelling an Upload Preview would leave the plugin in an unusable state
* Fixed a bug where run configurations kept getting renamed to default names
* Fixed duplicate "connection attempt cancelled" notifications
* Uploads now refresh the project dir each time to ensure no changes are missed
* Improved the upload run configuration's exclude paths checkbox and table labels

0.5.9 - 22.6.2025
------------------

* Updated stubs
* Remove "unknown" as default run configuration file name parameter
* Fixed a bug that caused uploads to incorrectly erase the file system
* Make the run configuration file choosers auto select the project directory by default
* Fixed a bug where before launch tasks would only execute after the run configurations

0.5.8 - 11.6.2025
------------------

* IntelliJ support
* Fixed a bug in the Execute in REPL run configuration validity check

0.5.7 - 2.6.2025
------------------

* Fixed duplicated MicroPython REPL banners in terminal which occurred after uploads
* Only enable delete action when at least one item is selected
* Added a "Clear REPL" action

0.5.6 - 28.5.2025
------------------

* Fixed "ImportError: no module named 'vfs'" errors when connecting to devices without the "vfs" module

0.5.5 - 20.5.2025
------------------

* Fixed "FormatFlagsConversionMismatchException" errors

0.5.4 - 20.5.2025
------------------

* Added support for mounted volumes (File System tab, Upload Preview, Drag and Drop, Delete action)
* File System tab now shows the amount of free and total storage space of every volume/mount point
* Changed colors used by the Upload Preview dialog to hold convention with GIT color schemes
* Stub package setting now accurately reflects the state of the attached MicroPython stubs
* Upload Preview dialog now also highlights items excluded from synchronization
* Added documentation for uploads and linked to it in the plugin
* Reworked and fixed custom path upload run configurations
* Optimized MicroPython scripts used by the plugin
* Fixed stub issues with MicroPython stdlib

0.5.3 - 29.4.2025
------------------

* Resolved issues related to receiving corrupted data over serial communication.

0.5.2 - 28.4.2025
------------------

* Fixed communication retry logic issues, including mishandled timeout exceptions that caused silent connection
  failures.
* Fixed a state transition bug in the interrupt action that could leave the plugin in an inconsistent state after an
  interrupt.
* Fixed WebREPL reset reconnection logic
* Improved WebREPL configuration settings UI
* Made the download action destination choice dialog open the project directory by default
* Optimized the communication speed

0.5.1 - 23.4.2025
------------------

* Fixed a bug where the upload preview dialog prevented modified files from getting uploaded

0.5.0 - 22.4.2025
------------------

* Added upload preview dialog
* Added available MicroPython 1.25.0 stubs
* Implemented raw paste mode for both WebREPL and Serial communication, WebREPL should now work reliably and serial
  communication should be significantly faster
* Improved the WebREPL / Serial communication download feature, it should be more reliable and faster
* Added support for .mpy files, they will now be recognized by the IDE, however, editing is not supported due to the
  nature of the MicroPython bytecode files
* Added serial connection integrity checks to catch and handle unexpected connection interruptions such as a cable
  getting
  unplugged
* Switched back to allowing project uploads without selected MicroPython Sources Roots
* Reworked and improved how visibility of REPL and context menu actions is handled
* Reworked and improved naming of several REPL and context menu actions
* Rewrote and optimized a significant portion of the code base, many small bugs and inconsistencies should be fixed
* Removed FTP uploads, will be replaced with a custom socket based implementation in a later minor version

0.4.3 - 16.4.2025
------------------

* 2025.1 IDE support

0.4.2 - 29.3.2025
------------------

* Added more help text and links to the documentation in our GitHub repo
* Fixed incorrect run configuration naming on Windows
* Fixed a bug with "path" upload configuration type
* Cleaned up port name displaying on Windows
* Fixed several FTP related issues, overall the resiliency and efficiency of FTP uploads is now increased, they also
  support WebREPL
* Re-enabled WebREPL, currently it will be slow and it might also be fragile, this should be further improved in future
  versions, it is recommended to use FTP uploads in combination with WebREPL to alleviate the slowness problems

0.4.1 - 22-3-2025
------------------

* Fixed several bugs in the 0.4.0-added upload algorithm
* Improved and cleaned up several dialogs, tool tips and descriptions
* New documentation and README.md
* Overall this is a polished and publicly releasable 0.4.0

0.4.0 - 18-3-2025
------------------

* Added "mark as MicroPython Sources Roots" functionality to the plugin, normal sources root are no longer considered
* Added a new "Execute in REPL" run configuration
* Added an icon provider for .mpy files
* Added new stubs, they are now downloaded directly from pip during the build process, meaning they should be more
  reliable
* Upload run configurations were heavily reworked, their UI is now more robust and they allow selecting specific
  MicroPython Sources Roots to be upload
* Fixed a bug where the plugin would disconnect from the board while uploading and stop working until the entire IDE is
  restarted
* Fixed a bug where the file system would be refreshed even after a drag and drop or a delete action was cancelled
* Fixed a bug where the plugin wouldn't properly detect missing CRC32 binascii support
* Added logic to make sure that the FTP wi-fi credentials don't appear in a notification when an exception occurs while
  connecting to the FTP server

0.3.0 - 14-2-2025
------------------

* Added support for drag and drop file system interactions (board to board and IDE to board)
* FTP parameters were fully moved to the plugin settings and extended to allow caching of the uftpd.py script, added a
  minimum upload size threshold setting
* Added a description with a disconnect button to the settings which appears when the "Connection" group is disabled due
  to an already connected board
* Modified the plugin's context menu actions to not be visible when MicroPython support is disabled
* Modified the execute in REPL context menu action to not be displayed at all for folders
* Rewrote and improved the internal MicroPython scripts to reduce on-device memory footprint and speed up execution

0.2.6 - 12-2-2025
------------------

* Added 1.24.1 and 1.25 preview stubs
* Fixed download action EDT errors
* Fixed a bug where the plugin enters the connected state even after a connection attempt was cancelled

0.2.5 - 6-2-2025
------------------

* Downgraded gradle dependencies to increase stability and fix errors of recent versions (0.2.3, 0.2.4)

0.2.4 - 4-2-2025
------------------

* Fixed index out of bound errors after connecting to a device
* Fixed threading errors when connecting via WebREPL

0.2.3 - 2-2-2025
------------------

* Improved compatibility with resource constrained boards such as the ESP8266
* Added an option to disable port listing filters (use this if your board's port isn't appearing)
* Fixed several bugs in the plugin settings editor (the editor prompting for applying changes when none were made,
  reverting unapplied changes not working etc.)

0.2.2 - 16-1-2025
------------------

* Fixed a bug with plugin settings not loading in a new project

0.2.1 - 14-1-2025
------------------

* Fixed issues with stubs package settings related changes
* Fixed issues with settings always being in modified state and enabling "apply" option as soon as they were open if the
  stub package text name field was empty

0.2.0 - 13-1-2025
------------------

* Added built-in stubs support
* Scrapped the planned pyserial rewrite and removed the dependency on the pyserial python library
* Reworked the device connection management in the settings
* Improved plugin's state management (the plugin features will react to the disabled/disconnected states better)
* Icon improvements (run configurations now have a new icon and the bug with darkening tool window icon was fixed)
* Complete backend rewrite of run configuration and settings handling to use the latest plugin API for better
  maintainability (fixed the problems with settings reverting after applying them)

0.1.5 - 6-1-2025
------------------

* Completely overhauled the progress reporting for all board communication actions
* Every action now provides more detailed explanation of steps that are happening
* Uploads now show the mount of files and KBs being uploaded alongside real-time updates as data gets transferred
* Reworked how clean-up actions work, the progress dialog titles are now unified
* Fixed several bugs: EDT related stack trace on start-up, improper clean-up, increased FTP wi-fi timeouts, delete
  action cancellation issues

0.1.4 - 1-1-2025
------------------

* Fixed several unhandled exceptions related to connection state transitions
* Fixed several cases where connection related actions wouldn't work as intended

There should no longer be any odd errors when cancelling any of the plugin's dialogs. The displayed information/error
messages when cancelling a dialog should now be more accurate and the board should be properly refreshed() after each
action if required.

0.1.3 - 31-12-2024
------------------

* Fixed incorrect python SDK retrieval

0.1.2 - 31-12-2024
------------------

* Improved the way credentials are stored by the plugin
* Fixed several errors related to un-configured Python interpreter IDE state
* Added more descriptive error messages and information texts for un-configured plugin state

0.1.1 - 30-12-2024
------------------

* Replaced internal annotated API usage

0.1.0 - 30-12-2024
------------------
Initial fork release:

* File system view, REPL, and kotlin communication layer from @elmot's version
* Automatically skip already uploaded files when flashing
* Synchronize the device file system to only contain what you're uploading
* Communication selector drop down directly in the tool window
* FTP uploads for wifi enabled boards - these save time and provide better reliability
* Switched to pyserial for serial port listing (removes junk ports on MacOS)
* Fixed several bugs, added and modified info notifications/status messages, removed deprecated code
