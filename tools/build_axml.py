#!/usr/bin/env python3
"""Сборка бинарного AndroidManifest.xml (AXML) без aapt — прямой энкодер."""
import struct

NS_ANDROID = "http://schemas.android.com/apk/res/android"

TYPE_STRING = 0x03
TYPE_INT = 0x10
TYPE_BOOL = 0x12

def u32(v): return struct.pack('<I', v & 0xFFFFFFFF)
def i32(v): return struct.pack('<i', v)
def u16(v): return struct.pack('<H', v)

class StringPool:
    def __init__(self):
        self.strings = []
        self.index = {}
    def add(self, s):
        if s in self.index: return self.index[s]
        self.index[s] = len(self.strings)
        self.strings.append(s)
        return self.index[s]

def build_string_pool(strings, utf8=False):
    """UTF-16LE пул строк с 4-байтовым выравниванием каждой строки."""
    offsets = []
    data = bytearray()
    for s in strings:
        offsets.append(len(data))
        b = s.encode('utf-16-le')
        data += u16(len(s))          # длина в символах
        data += b
        data += u16(0)               # терминатор
        while len(data) % 4 != 0:    # выравнивание до 4
            data += b'\x00'
    header_size = 28
    offsets_size = 4 * len(strings)
    strings_start = header_size + offsets_size
    chunk_size = strings_start + len(data)
    out = bytearray()
    out += u16(0x0001)               # RES_STRING_POOL_TYPE
    out += u16(header_size)
    out += u32(chunk_size)
    out += u32(len(strings))         # stringCount
    out += u32(0)                    # styleCount
    out += u32(0x100 if utf8 else 0) # flags: UTF-16
    out += u32(strings_start)
    out += u32(0)                    # stylesStart
    for off in offsets:
        out += u32(off)
    out += data
    assert len(out) == chunk_size
    return bytes(out)

def res_value(data_type, data):
    return u16(8) + bytes([0, data_type]) + u32(data)

def xml_node(chunk_type, size, line, body):
    out = bytearray()
    out += u16(chunk_type)
    out += u16(16)                   # headerSize
    out += u32(size)
    out += i32(line)
    out += u32(0xFFFFFFFF)           # comment
    out += body
    return bytes(out)

def start_ns(pool, line=0):
    p = pool.add("android"); u = pool.add(NS_ANDROID)
    return xml_node(0x0100, 24, line, u32(p) + u32(u))

def end_ns(pool, line=0):
    p = pool.add("android"); u = pool.add(NS_ANDROID)
    return xml_node(0x0101, 24, line, u32(p) + u32(u))

def start_el(pool, name, attrs, line=0):
    """attrs: list of (ns_or_None, name, raw_or_None, type, data)"""
    body = bytearray()
    n = pool.add(name)
    body += u32(0xFFFFFFFF)          # ns (у элемента нет)
    body += u32(n)
    body += u16(0x14)                # attributeStart
    body += u16(0x14)                # attributeSize
    body += u16(len(attrs))
    body += u16(0); body += u16(0); body += u16(0)  # idIndex, classIndex, styleIndex
    for (ns, aname, raw, t, data) in attrs:
        ns_idx = pool.add(NS_ANDROID) if ns == 'android' else 0xFFFFFFFF
        an_idx = pool.add(aname)
        raw_idx = pool.add(raw) if (raw is not None and t == TYPE_STRING) else 0xFFFFFFFF
        body += u32(ns_idx)
        body += u32(an_idx)
        body += u32(raw_idx)
        body += res_value(t, data)
    size = 16 + 20 + 20 * len(attrs)
    return xml_node(0x0102, size, line, bytes(body))

def end_el(pool, name, line=0):
    n = pool.add(name)
    return xml_node(0x0103, 24, line, u32(0xFFFFFFFF) + u32(n))

def build_manifest():
    pool = StringPool()
    chunks = bytearray()

    # <manifest package=...>
    pkg = "com.arena.redradar"
    chunks += start_ns(pool)
    chunks += start_el(pool, "manifest", [
        (None, "package", pkg, TYPE_STRING, pool.add(pkg)),
    ])
    # <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28"/>
    chunks += start_el(pool, "uses-sdk", [
        ('android', "minSdkVersion", None, TYPE_INT, 24),
        ('android', "targetSdkVersion", None, TYPE_INT, 28),
    ])
    chunks += end_el(pool, "uses-sdk")
    # <application ...>
    chunks += start_el(pool, "application", [
        ('android', "label", "Красный радар", TYPE_STRING, pool.add("Красный радар")),
        ('android', "allowBackup", None, TYPE_BOOL, 0xFFFFFFFF),
        ('android', "hardwareAccelerated", None, TYPE_BOOL, 0xFFFFFFFF),
    ])
        # <activity ...>
    chunks += start_el(pool, "activity", [
        ('android', "name", "com.arena.redradar.MainActivity", TYPE_STRING, pool.add("com.arena.redradar.MainActivity")),
        ('android', "exported", None, TYPE_BOOL, 0xFFFFFFFF),
        ('android', "screenOrientation", None, TYPE_INT, 6),      # sensorLandscape
        ('android', "configChanges", None, TYPE_INT, 0x5C0),      # orientation|keyboardHidden|screenLayout|screenSize
    ])
        # <intent-filter><action .../><category .../></intent-filter>
    chunks += start_el(pool, "intent-filter", [])
    chunks += start_el(pool, "action", [
        ('android', "name", "android.intent.action.MAIN", TYPE_STRING, pool.add("android.intent.action.MAIN")),
    ])
    chunks += end_el(pool, "action")
    chunks += start_el(pool, "category", [
        ('android', "name", "android.intent.category.LAUNCHER", TYPE_STRING, pool.add("android.intent.category.LAUNCHER")),
    ])
    chunks += end_el(pool, "category")
    chunks += end_el(pool, "intent-filter")
    chunks += end_el(pool, "activity")
    chunks += end_el(pool, "application")
    chunks += end_el(pool, "manifest")
    chunks += end_ns(pool)

    sp = build_string_pool(pool.strings, utf8=False)
    total = 8 + len(sp) + len(chunks)
    out = bytearray()
    out += u16(0x0003)      # RES_XML_TYPE
    out += u16(8)           # headerSize
    out += u32(total)
    out += sp
    out += chunks
    assert len(out) == total
    return bytes(out)

if __name__ == '__main__':
    import sys
    open(sys.argv[1] if len(sys.argv) > 1 else 'AndroidManifest.bin', 'wb').write(build_manifest())
    print('AXML готов:', len(build_manifest()), 'байт')
