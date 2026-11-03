#!/usr/bin/env python3
"""为 laimanhua-mihon 生成 Mihon 扩展仓库索引。

输出（与 Mihon 源码解析模型一致）：
  index.min.json  —— Mihon <0.16 的 legacy 数组格式（会读 repo.json 后跳到 index.pb）
  repo.json       —— legacy 入口，有 index_v2 时会重定向
  index.pb        —— Mihon 0.16+ 的 protobuf 格式（真正生效的那个）
  apk/<name>.apk  —— legacy 路径下 Mihon 会拼 {base}/apk/{apk}
  icon/<pkg>.png  —— legacy 路径下 Mihon 会拼 {base}/icon/{pkg}.png

用法:
  python gen_index.py --apk path/to/app.apk --version-code 1 --version-name 1.4.1 \
      --repo-base https://example.com/repo --out dist
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile

# ---------------------------------------------------------------- protobuf 编码

def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tag(field, wire):
    return varint((field << 3) | wire)


def pb_string(field, s):
    if s is None:
        return b''
    b = s.encode('utf-8')
    return tag(field, 2) + varint(len(b)) + b


def pb_bytes(field, b):
    return tag(field, 2) + varint(len(b)) + b


def pb_int(field, v):
    return tag(field, 0) + varint(int(v))


def pb_message(field, body):
    return tag(field, 2) + varint(len(body)) + body


# ---------------------------------------------------------------- APK 解析

def read_manifest_from_apk(apk_path):
    """用 aapt2 若可用，否则从 AndroidManifest.xml 二进制里粗解析。"""
    for tool in ('aapt2', 'aapt'):
        exe = shutil.which(tool)
        if exe:
            try:
                out = subprocess.run(
                    [exe, 'dump', 'badging', apk_path],
                    capture_output=True, text=True, timeout=60
                ).stdout
                m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
                if m:
                    return m.group(1), int(m.group(2)), m.group(3)
            except Exception:
                pass
    return None, None, None


def signing_cert_sha256(apk_path):
    """Mihon 用 证书DER 的 SHA-256 小写十六进制作为 signingKey。"""
    with zipfile.ZipFile(apk_path) as z:
        certs = [n for n in z.namelist() if re.match(r'META-INF/.*\.(RSA|DSA|EC)$', n, re.I)]
        if not certs:
            return None
        # 取第一个证书块（PKCS#7），用 openssl 解出证书再哈希
        with z.open(certs[0]) as f:
            blob = f.read()
    for exe_name in ('openssl',):
        exe = shutil.which(exe_name)
        if not exe:
            continue
        try:
            p1 = subprocess.run([exe, 'pkcs7', '-inform', 'DER', '-print_certs'],
                                input=blob, capture_output=True, timeout=60)
            p2 = subprocess.run([exe, 'x509', '-inform', 'PEM', '-outform', 'DER'],
                                input=p1.stdout, capture_output=True, timeout=60)
            if p2.returncode == 0 and p2.stdout:
                return hashlib.sha256(p2.stdout).hexdigest()
        except Exception:
            pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', required=True)
    ap.add_argument('--pkg', default='eu.kanade.tachiyomi.extension.zh.laimanhua')
    ap.add_argument('--name', default='来漫画')
    ap.add_argument('--source-name', default='来漫画')
    ap.add_argument('--source-id', default='8642197532516')
    ap.add_argument('--lang', default='zh')
    ap.add_argument('--base-url', default='https://www.laimanhua88.com')
    ap.add_argument('--version-code', type=int, required=True)
    ap.add_argument('--version-name', required=True)
    ap.add_argument('--lib-version', default='1.4')
    ap.add_argument('--repo-base', required=True, help='仓库根 URL，如 https://host/repo')
    ap.add_argument('--store-name', default='LaiManHua')
    ap.add_argument('--badge', default='LMH')
    ap.add_argument('--website', default='https://github.com/fanhongchang01-hub/laimanhua-mihon')
    ap.add_argument('--icon', default='', help='源图标 PNG 路径，会缩放为 256x256')
    ap.add_argument('--out', default='dist')
    args = ap.parse_args()

    pkg, vcode, vname = read_manifest_from_apk(args.apk)
    pkg = pkg or args.pkg
    vcode = vcode or args.version_code
    vname = vname or args.version_name
    signing = signing_cert_sha256(args.apk)
    if not signing:
        raise SystemExit('无法读取 APK 签名证书，请确认已签名且环境有 openssl')

    apk_filename = 'laimanhua-mihon-release.apk'
    base = args.repo_base.rstrip('/')

    os.makedirs(os.path.join(args.out, 'apk'), exist_ok=True)
    os.makedirs(os.path.join(args.out, 'icon'), exist_ok=True)
    shutil.copy2(args.apk, os.path.join(args.out, 'apk', apk_filename))

    sources = [{
        'id': int(args.source_id),
        'name': args.source_name,
        'language': args.lang,
        'homeUrl': args.base_url,
        'mirrorUrls': [],
    }]

    # ---------------- index.pb（Mihon 0.16+ 实际使用）
    extension = b''.join([
        pb_string(1, args.name),
        pb_string(2, pkg),
        pb_message(3, pb_string(1, f'{base}/apk/{apk_filename}') + pb_string(2, f'{base}/icon/{pkg}.png')),
        pb_string(4, args.lib_version),
        pb_int(5, vcode),
        pb_string(6, vname),
        pb_int(7, 1),  # ContentWarning.SAFE
        b''.join(pb_message(8, b''.join([
            pb_int(1, s['id']),
            pb_string(2, s['name']),
            pb_string(3, s['language']),
            pb_string(4, s['homeUrl']),
        ])) for s in sources),
    ])
    store = b''.join([
        pb_string(1, args.store_name),
        pb_string(2, args.badge),
        pb_string(3, signing),
        pb_message(4, pb_string(1, args.website)),
        pb_message(101, pb_message(1, extension)),
    ])
    with open(os.path.join(args.out, 'index.pb'), 'wb') as f:
        f.write(store)

    # ---------------- repo.json（legacy 入口 → 指向 index.pb）
    repo_json = {
        'index_v2': f'{base}/index.pb',
        'meta': {
            'name': args.store_name,
            'shortName': args.badge,
            'website': args.website,
            'signingKeyFingerprint': signing,
        },
    }
    with open(os.path.join(args.out, 'repo.json'), 'w', encoding='utf-8') as f:
        json.dump(repo_json, f, ensure_ascii=False, indent=2)

    # ---------------- index.min.json（legacy 数组，保留兼容）
    legacy = [{
        'name': args.name,
        'pkg': pkg,
        'apk': apk_filename,
        'lang': args.lang,
        'code': vcode,
        'version': vname,
        'nsfw': 0,
        'sources': [{
            'id': sources[0]['id'],
            'lang': args.lang,
            'name': args.source_name,
            'baseUrl': args.base_url,
        }],
    }]
    with open(os.path.join(args.out, 'index.min.json'), 'w', encoding='utf-8') as f:
        json.dump(legacy, f, ensure_ascii=False, separators=(',', ':'))

    # 扩展图标：Mihon 从 {base}/icon/{pkg}.png 拉取。
    # 若提供了 --icon，则缩放成 256x256 输出；否则写入 1x1 占位（列表会显示空白）。
    icon_path = os.path.join(args.out, 'icon', f'{pkg}.png')
    if args.icon and os.path.exists(args.icon):
        try:
            from PIL import Image
            img = Image.open(args.icon).convert('RGBA')
            img = img.resize((256, 256), Image.LANCZOS)
            img.save(icon_path, 'PNG')
            print('icon ->', icon_path, '(256x256 from', args.icon, ')')
        except Exception as e:
            print('WARN: 图标处理失败，改用占位:', e)
            with open(icon_path, 'wb') as f:
                f.write(PLACEHOLDER_PNG)
    elif not os.path.exists(icon_path):
        with open(icon_path, 'wb') as f:
            f.write(PLACEHOLDER_PNG)

    print(json.dumps({
        'pkg': pkg, 'versionCode': vcode, 'versionName': vname,
        'signingKey': signing, 'apkUrl': f'{base}/apk/{apk_filename}',
        'index.pb': os.path.getsize(os.path.join(args.out, 'index.pb')),
    }, ensure_ascii=False, indent=2))


# 1x1 透明 PNG，仅作占位
PLACEHOLDER_PNG = bytes.fromhex(
    '89504e470d0a1a0a0000000d494844520000000100000001080600000'
    '01f15c4890000000d49444154789c6360000002000001e221bc33000'
    '0000049454e44ae426082'.replace('\n', '')
)

if __name__ == '__main__':
    main()
