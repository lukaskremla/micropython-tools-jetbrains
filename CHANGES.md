The Changelog
=============

0.1.4 - 1-1-2025
------------------
* Fixed several unhandled exceptions related to connection state transitions
* Fixed several cases where connection related actions wouldn't work as intended

There should no longer be any odd errors when cancelling any of the plugin's dialogs. The displayed information/error messages when cancelling a dialog should now be more accurate and the board should be properly refreshed() after each action if required.

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
