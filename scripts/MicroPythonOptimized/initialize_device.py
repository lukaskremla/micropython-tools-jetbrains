import machine

d= %s

try:
    r = machine.RTC()
    r.datetime(d)
except Exception as e:
    pass
