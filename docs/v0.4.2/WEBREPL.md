# MicroPython Tools WebREPL support in version 0.4.2

WebREPL support in MicroPython Tools is currently experimental. This document outlines the known
limitations, and tips for successful usage.

## Known Limitations

### Performance Issues

- WebREPL connections are significantly slower than serial connections
- Large file uploads are throttled to prevent buffer overflows (255-byte chunks with 200ms delays)
- Command execution may time out with complex or large scripts

### Connection Stability

- Connections may drop unexpectedly during long operations
- Error handling and reconnection logic is still being improved

### Buffer Limitations

- The WebREPL protocol has an inherent buffer size limitation which necessitates chunking data
- This limitation affects upload speeds and reliability

This will be further addressed in future versions of the plugin. In the meantime it is strongly recommended to enable
the FTP uploads option whenever using WebREPL if your device can handle it.

Using FTP uploads will take a significant load off the WebREPL communication increasing reliability and improving
performance.
With WebREPL communication it's sufficient to just enable the FTP uploads option, credentials are not required as the
existing WebREPL wi-fi connection is used. It is also recommended to enable caching of the FTP script and disable the
minimum upload size threshold to take as much load off of the WebREPL communication as possible.