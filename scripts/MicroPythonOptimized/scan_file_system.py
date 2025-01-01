import os


class ___f(object):

    def l(self, n):
        for r in os.ilistdir(n):
            print(r[1], r[3] if len(r) > 3 else -1, n + r[0], sep='&')
            if r[1] & 0x4000:
                self.l(n + r[0] + "/")


___f().l("/")
del ___f
try:
    import gc

    gc.collect()
except Exception:
    pass
