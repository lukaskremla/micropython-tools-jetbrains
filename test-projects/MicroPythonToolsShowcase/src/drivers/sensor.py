# Sensor Driver for a hypothetical temperature and humidity sensor

import machine

class Sensor:
    def __init__(self, pin):
        self.pin = machine.Pin(pin)
        self.adc = machine.ADC(self.pin)

    def read_temperature(self):
        raw = self.adc.read()
        temp = (raw / 4095) * 100  # Example conversion
        print(f"Temperature reading: {temp:.2f} °C")
        return temp

    def read_humidity(self):
        raw = self.adc.read()
        humidity = (raw / 4095) * 100  # Example conversion
        print(f"Humidity reading: {humidity:.2f} %")
        return humidity

# Example usage:
# sensor = Sensor(32)
# sensor.read_temperature()
# sensor.read_humidity()
