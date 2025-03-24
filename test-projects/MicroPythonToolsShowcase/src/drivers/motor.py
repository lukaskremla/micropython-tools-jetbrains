# Motor Driver for a simple DC motor using PWM

import machine
import time

class Motor:
    def __init__(self, pin):
        self.pin = machine.Pin(pin)
        self.pwm = machine.PWM(self.pin)
        self.pwm.freq(1000)

    def set_speed(self, speed):
        if 0 <= speed <= 1023:
            self.pwm.duty(speed)
            print(f"Motor speed set to: {speed}")
        else:
            print("Speed out of range (0-1023)")

    def stop(self):
        self.pwm.duty(0)
        print("Motor stopped")

# Example usage:
# motor = Motor(14)
# motor.set_speed(512)
# motor.stop()
