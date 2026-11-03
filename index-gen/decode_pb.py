"""解码生成的 index.pb，按 Mihon 的 NetworkExtensionStore 结构回读，验证编码正确。"""
import json
import sys


def read_varint(b, i):
    v = 0
    s = 0
    while True:
        x = b[i]
        i += 1
        v |= (x & 0x7F) << s
        if not (x & 0x80):
            return v, i
        s += 7


def parse(b, i=0, end=None):
    """返回 dict: field -> list of (wire, value)"""
    end = len(b) if end is None else end
    out = {}
    while i < end:
        key, i = read_varint(b, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            val, i = read_varint(b, i)
        elif wire == 2:
            ln, i = read_varint(b, i)
            val = b[i:i + ln]
            i += ln
        elif wire == 5:
            val = b[i:i + 4]
            i += 4
        elif wire == 1:
            val = b[i:i + 8]
            i += 8
        else:
            raise ValueError('wire %d' % wire)
        out.setdefault(field, []).append((wire, val))
    return out


def s(v):
    return v.decode('utf-8')


path = sys.argv[1] if len(sys.argv) > 1 else 'dist/index.pb'
data = open(path, 'rb').read()
top = parse(data)
print('index.pb 大小:', len(data), 'bytes')
print('顶层字段:', sorted(top.keys()))
print()
print('name        :', s(top[1][0][1]))
print('badgeLabel  :', s(top[2][0][1]))
print('signingKey  :', s(top[3][0][1]))
contact = parse(top[4][0][1])
print('contact.website:', s(contact[1][0][1]))
plist = parse(top[101][0][1])
exts = plist[1]
print('扩展条目数  :', len(exts))
e = parse(exts[0][1])
res = parse(e[3][0][1])
print()
print('-- 扩展条目 --')
print('  name        :', s(e[1][0][1]))
print('  packageName :', s(e[2][0][1]))
print('  apkUrl      :', s(res[1][0][1]))
print('  iconUrl     :', s(res[2][0][1]))
print('  extensionLib:', s(e[4][0][1]))
print('  versionCode :', e[5][0][1])
print('  versionName :', s(e[6][0][1]))
print('  contentWarn :', e[7][0][1], '(1=SAFE)')
src = parse(e[8][0][1])
print('  source.id   :', src[1][0][1])
print('  source.name :', s(src[2][0][1]))
print('  source.lang :', s(src[3][0][1]))
print('  source.home :', s(src[4][0][1]))
