The Changelog
=============

0.2.0 - 13-1-2025
------------------

* Added built-in stubs support
* Scrapped the planned pyserial rewrite and removed the dependency on the pyserial python library
* Reworked the device connection management in the settings
* Improved plugin's state management (the plugin features will react to the disabled/disconnected states better)
* Icon improvements (run configurations now have a new icon and the bug with darkening tool window icon was fixed)
* Complete backend rewrite of run configuration and settings handling to use the latest plugin API for better maintainability (fixed the problems with settings reverting after applying them)

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
