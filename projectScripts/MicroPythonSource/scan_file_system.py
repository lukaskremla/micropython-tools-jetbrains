import os


class ___FSScan(object):

    def fld(self, name):
        for r in os.ilistdir(name):
            print(r[1], r[3] if len(r) > 3 else -1, name + r[0], sep='&')
            if r[1] & 0x4000:
                self.fld(name + r[0] + "/")


___FSScan().fld("/")
del ___FSScan
try:
    import gc

    gc.collect()
except Exception:
    pass
