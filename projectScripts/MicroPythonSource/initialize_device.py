import machine, os

datetime = %s

try:
    rtc = machine.RTC()
    rtc.datetime(datetime)
except Exception as e:
    pass
