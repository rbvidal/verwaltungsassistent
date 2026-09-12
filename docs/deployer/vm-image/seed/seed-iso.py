#!/usr/bin/env python3
"""Builds the cloud-init NoCloud seed ISO (label cidata) for the Verwaltungsassistent
appliance provisioning boot. Pure-python ISO9660 writer (pycdlib).

Usage: python seed/seed-iso.py <user-data> <meta-data> <ssh-pubkey-file> <out.iso>
"""
import sys

import pycdlib

def main():
    user_data_path, meta_data_path, pubkey_path, out_iso = sys.argv[1:5]
    user_data = open(user_data_path, encoding="utf-8").read()
    meta_data = open(meta_data_path, encoding="utf-8").read()
    pubkey = open(pubkey_path, encoding="utf-8").read().strip()
    user_data = user_data.replace("BUILDKEY_PLACEHOLDER", pubkey)

    iso = pycdlib.PyCdlib()
    iso.new(interchange_level=3, joliet=3, rock_ridge="1.09", vol_ident="cidata")
    iso.add_fp(
        _BytesIO(user_data.encode("utf-8")),
        len(user_data.encode("utf-8")),
        "/USER_DATA.;1",
        joliet_path="/user-data",
        rr_name="user-data",
    )
    iso.add_fp(
        _BytesIO(meta_data.encode("utf-8")),
        len(meta_data.encode("utf-8")),
        "/META_DATA.;1",
        joliet_path="/meta-data",
        rr_name="meta-data",
    )
    iso.write(out_iso)
    iso.close()
    print(f"seed ISO written: {out_iso}")


class _BytesIO:
    mode = "rb"
    name = "<seed>"

    def __init__(self, data):
        self._data = data
        self._pos = 0

    def read(self, size=None):
        if size is None:
            size = len(self._data) - self._pos
        chunk = self._data[self._pos : self._pos + size]
        self._pos += len(chunk)
        return chunk

    def seek(self, offset, whence=0):
        if whence == 0:
            self._pos = offset
        elif whence == 1:
            self._pos += offset
        elif whence == 2:
            self._pos = len(self._data) + offset
        return self._pos

    def tell(self):
        return self._pos


if __name__ == "__main__":
    main()
