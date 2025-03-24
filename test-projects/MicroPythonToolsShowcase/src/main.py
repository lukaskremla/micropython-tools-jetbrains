# Main script to run drivers and libraries

from drivers.sensor import Sensor
from drivers.motor import Motor
from lib.math_utils import average
from lib.logger import Logger
import time

def main():
    sensor = Sensor(32)
    motor = Motor(14)
    logger = Logger('system.log')

    logger.log("Starting system...")

    # Read sensor data
    temp = sensor.read_temperature()
    humidity = sensor.read_humidity()

    # Compute and log average
    avg = average([temp, humidity])
    logger.log(f"Average sensor value: {avg:.2f}")

    # Control motor based on sensor input
    speed = int(avg * 10)
    motor.set_speed(speed)
    time.sleep(2)
    motor.stop()

    logger.log("System finished")

if __name__ == '__main__':
    main()
