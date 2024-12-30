The Changelog
=============

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
