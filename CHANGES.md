The Changelog
=============

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
