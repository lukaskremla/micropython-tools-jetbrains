# Simple Logger Library

import time

class Logger:
    def __init__(self, filename):
        self.filename = filename

    def log(self, message):
        timestamp = time.time()
        formatted = f"[{timestamp}] {message}"
        print(formatted)
        with open(self.filename, 'a') as file:
            file.write(formatted + '\n')

# Example usage:
# logger = Logger('log.txt')
# logger.log("System initialized")
