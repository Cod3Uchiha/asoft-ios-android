"""Tests of adhoc.py and ipa-hashes.py on made-up Mach-O files, and (when ASOFT_AB2 names a folder holding the extracted
Angry Birds 2 .app, see below) on its two real files. No root needed:
    python3 -I adhoc_test.py            (WSL or Windows)
    ASOFT_AB2=/path/to/Payload/AngryBirds2.app python3 -I adhoc_test.py

The checker here (`slice_state`) is written apart from adhoc.py: it reads the signature again from the bytes and compares
every page hash, so it stands in for the guest's page validation (not for AMFI's own decision, which only a boot shows).
"""
import hashlib
import os
import plistlib
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..'))
import adhoc  # noqa: E402

PY = sys.executable
bad = 0


def check(name, cond):
    global bad
    print(('ok   ' if cond else 'FAIL ') + name)
    if not cond:
        bad += 1


def expect_unsupported(name, fn, word):
    try:
        fn()
    except adhoc.Unsupported as e:
        check(name + ' (%s)' % e, word in str(e))
        return
    check(name + ' (no error)', False)


# -- fixtures

def macho(filetype=6, sig_len=None, sig=None, cryptid=None, room=True, junk_after=b'', ncode=4):
    """A thin arm64 Mach-O: __TEXT over ncode pages (the load commands at the top, one section at 0x1000 when there is
    room, else right after the commands), __LINKEDIT, and when sig_len (or sig) is given a signature area at its end."""
    if sig is not None:
        sig_len = len(sig)
    text_len = 0x1000 * ncode
    text = bytearray(os.urandom(text_len))
    text[:0x200] = bytes(0x200)
    link = b'LINKEDIT-DATA' * 4
    sect = struct.pack('<16s16sQQIIIIIIII', b'__text', b'__TEXT', 0x1000, 0x100, 0x1000 if room else 256, 0, 0, 0, 0, 0, 0, 0)
    cmds = [struct.pack('<II16sQQQQiiII', 0x19, 72 + 80, b'__TEXT', 0, text_len, 0, text_len, 5, 5, 1, 0) + sect]
    sig_at = text_len + len(link)
    sig_at += -sig_at % 16
    le_size = (sig_at - text_len) + (sig_len or 0)
    cmds.append(struct.pack('<II16sQQQQiiII', 0x19, 72, b'__LINKEDIT', text_len, 0x4000, text_len, le_size if sig_len else len(link), 1, 1, 0, 0))
    if cryptid is not None:
        cmds.append(struct.pack('<IIIIII', 0x2c, 24, 0x1000, 0x1000, cryptid, 0))
    if sig_len:
        cmds.append(struct.pack('<IIII', 0x1d, 16, sig_at, sig_len))
    body = b''.join(cmds)
    text[:32 + len(body)] = struct.pack('<IiiIIIII', 0xfeedfacf, 0x100000c, 0, filetype, len(cmds), len(body), 0, 0) + body
    out = bytes(text) + link
    if sig_len:
        out += bytes(sig_at - len(out))
        out += sig if sig is not None else bytes(sig_len)
    return out + junk_after


def old_sig(m, htype, ents=None, ident=b'old.ident', specials4=False):
    """A signature made apart from adhoc.py: a code directory of @htype over @m (the file up to the signature)."""
    hf = hashlib.sha1 if htype == 1 else hashlib.sha256
    hs = hf().digest_size
    n = (len(m) + 4095) // 4096
    req = struct.pack('>III', 0xfade0c01, 12, 0)
    ent = struct.pack('>II', 0xfade7171, 8 + len(ents)) + ents if ents else None
    specials = {2: hf(req).digest()}
    nsp = 2
    if ent:
        specials[5] = hf(ent).digest()
        nsp = 5
    if specials4:
        specials[4] = hf(b'x').digest()
        nsp = max(nsp, 5)
    hoff = 44 + len(ident) + 1 + hs * nsp
    cd = struct.pack('>IIIIIIIIIBBBB', 0xfade0c02, hoff + hs * n, 0x20001, 0, hoff, 44, nsp, n, len(m), hs, htype, 0, 12)
    cd += struct.pack('>I', 0) + ident + b'\0'
    for s in range(nsp, 0, -1):
        cd += specials.get(s, bytes(hs))
    for p in range(n):
        cd += hf(m[p * 4096:min((p + 1) * 4096, len(m))]).digest()
    parts = [(0, cd), (2, req)] + ([(5, ent)] if ent else [])
    index, at = b'', 12 + 8 * len(parts)
    for slot, x in parts:
        index += struct.pack('>II', slot, at)
        at += len(x)
    body = index + b''.join(x for _, x in parts)
    return struct.pack('>III', 0xfade0cc0, 12 + len(body), len(parts)) + body


def signed(htype=1, ents=None, specials4=False, **kw):
    """A Mach-O with a signature that matches its pages (the header already counts the signature's size)."""
    L = len(old_sig(bytes(0x1000 * kw.get('ncode', 4) + 64), htype, ents, specials4=specials4))
    m = macho(sig_len=L, **{k: v for k, v in kw.items() if k != 'junk_after'})
    sig_at = len(m) - L
    return m[:sig_at] + old_sig(m[:sig_at], htype, ents, specials4=specials4) + kw.get('junk_after', b'')


def slice_state(m):
    """(has a SHA-256 CD, all its page hashes match, cdhash hex) read again from the bytes."""
    n = struct.unpack_from('<I', m, 16)[0]
    o = 32
    so = ss = None
    for _ in range(n):
        c, s = struct.unpack_from('<II', m, o)
        if c == 0x1d:
            so, ss = struct.unpack_from('<II', m, o + 8)
        o += s
    if so is None:
        return False, False, ''
    _, _, cnt = struct.unpack_from('>III', m, so)
    for i in range(cnt):
        _, off = struct.unpack_from('>II', m, so + 12 + 8 * i)
        at = so + off
        magic, ln = struct.unpack_from('>II', m, at)
        if magic != 0xfade0c02:
            continue
        ver, flags, hoff, ioff, nsp, ncode, limit, hsize, htype, plat, psize = struct.unpack_from('>IIIIIIIBBBB', m, at + 8)
        if htype != 2:
            continue
        ok = limit <= len(m) and ncode == (limit + (1 << psize) - 1) // (1 << psize)
        for p in range(ncode):
            d = hashlib.sha256(m[p * 4096:min((p + 1) * 4096, limit)]).digest()
            ok = ok and d == m[at + hoff + 32 * p:at + hoff + 32 * (p + 1)]
        return True, ok, hashlib.sha256(m[at:at + ln]).hexdigest()[:40]
    return False, False, ''


def blob_at(m, slot):
    n = struct.unpack_from('<I', m, 16)[0]
    o = 32
    for _ in range(n):
        c, s = struct.unpack_from('<II', m, o)
        if c == 0x1d:
            so, ss = struct.unpack_from('<II', m, o + 8)
            _, _, cnt = struct.unpack_from('>III', m, so)
            for i in range(cnt):
                sl, off = struct.unpack_from('>II', m, so + 12 + 8 * i)
                if sl == slot:
                    ln = struct.unpack_from('>I', m, so + off + 4)[0]
                    return bytes(m[so + off:so + off + ln])
        o += s
    return None


tmp = tempfile.mkdtemp()


def put(name, data, mode=0o755):
    p = os.path.join(tmp, name)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'wb') as f:
        f.write(data)
    os.chmod(p, mode)
    return p


def read(p):
    with open(p, 'rb') as f:
        return f.read()


# -- the fixtures themselves are what they say

fx = signed(1)
check('fixture: SHA-1 signed file reads as SHA-1 only', adhoc.usable(fx) == (False, 'SHA-1 only'))
check('fixture: unsigned reads as unsigned', adhoc.usable(macho()) == (False, 'unsigned'))

# -- unsigned
orig = macho()
p = put('a/unsigned.dylib', orig)
r = adhoc.resign(p, tmp)
m = read(p)
has, ok, h = slice_state(m)
check('unsigned: signed, SHA-256 directory, every page matches', has and ok and r and r[0][1] == [h] and r[0][0] == 'unsigned')
check('unsigned: the pages after the header are the original ones', m[0x1000:0x4000] == orig[0x1000:0x4000])
check('unsigned: a second run changes nothing', adhoc.resign(p, tmp) == [] and read(p) == m)

# -- SHA-1 only, with entitlements (XML only: the DER one is made) and an identifier to keep
ents = plistlib.dumps({'com.apple.security.app-sandbox': False, 'keychain-access-groups': ['a.b'], 'n': 3}, fmt=plistlib.FMT_XML)
p = put('a/sha1.dylib', signed(1, ents=ents))
old = read(p)
r = adhoc.resign(p, tmp)
m = read(p)
has, ok, h = slice_state(m)
check('SHA-1 only: re-signed with SHA-256, pages match', r and r[0][0] == 'SHA-1 only' and has and ok and r[0][1] == [h])
check('SHA-1 only: entitlements XML kept byte for byte', blob_at(m, 5) == blob_at(old, 5) and blob_at(m, 5) is not None)
check('SHA-1 only: a DER entitlements blob was made', (blob_at(m, 7) or b'')[:4] == bytes.fromhex('fade7172'))
cd0 = blob_at(m, 0)
io = struct.unpack_from('>I', cd0, 20)[0]
check('SHA-1 only: the identifier stays', cd0[io:cd0.index(b'\0', io)] == b'old.ident')
cdhash_cmd = subprocess.run([PY, '-I', os.path.join(HERE, '..', '..', '..', 'app', 'src', 'main', 'assets', 'setup', 'scripts', 'ios26', 'cdhash.py'), p],
                            capture_output=True, text=True).stdout if os.path.exists(os.path.join(HERE, '..', '..', '..', 'app', 'src', 'main', 'assets', 'setup', 'scripts', 'ios26', 'cdhash.py')) else None
if cdhash_cmd is not None:
    check('SHA-1 only: cdhash.py (the trust cache route) reads the same CDHash', cdhash_cmd.split()[0] == h)

# -- bad pages: a SHA-256 signature whose code pages no longer match (a decrypted binary)
good = bytearray(signed(2))
check('bad pages: a matching SHA-256 file is usable and left alone', adhoc.usable(bytes(good))[0])
good[0x2100] ^= 0xff
p = put('a/badpages.dylib', bytes(good))
check('bad pages: seen as unusable', adhoc.usable(bytes(good)) == (False, 'its code pages do not match its signature'))
r = adhoc.resign(p, tmp)
m = read(p)
has, ok, h = slice_state(m)
check('bad pages: re-signed over the file as it is', r and has and ok and m[0x2100] == good[0x2100])
p = put('a/usable.dylib', signed(2))
before = read(p)
check('usable file: untouched', adhoc.resign(p, tmp) == [] and read(p) == before)

# -- a bundle's executable: special slots 1 and 3 and the identifier come from its files
b = 'bundle/Fw.framework/'
put(b + 'Info.plist', plistlib.dumps({'CFBundleExecutable': 'Fw', 'CFBundleIdentifier': 'com.x.fw'}))
put(b + '_CodeSignature/CodeResources', b'resources')
p = put(b + 'Fw', macho())
adhoc.resign(p, os.path.join(tmp, 'bundle'))
m = read(p)
cd = blob_at(m, 0)
hoff, ioff = struct.unpack_from('>II', cd, 16)
nsp = struct.unpack_from('>I', cd, 24)[0]
s1 = cd[hoff - 32 * 1:hoff]
s3 = cd[hoff - 32 * 3:hoff - 32 * 2]
check('bundle executable: slot 1 = Info.plist, slot 3 = CodeResources, identifier from the plist',
      s1 == hashlib.sha256(read(os.path.join(tmp, b + 'Info.plist'))).digest() and s3 == hashlib.sha256(b'resources').digest()
      and cd[ioff:cd.index(b'\0', ioff)] == b'com.x.fw')

# -- fat: a 32-bit slice, an unusable arm64, a usable arm64e: each slice on its own
a32 = bytearray(os.urandom(0x2000))
a32[:4] = struct.pack('<I', 0xfeedface)
s64 = macho()
s64e = signed(2)
slices_ = [(12, 9, bytes(a32), 14), (0x100000c, 0, s64, 14), (0x100000c, 2, s64e, 14)]
hdr = 8 + 20 * 3
out = bytearray(struct.pack('>II', 0xcafebabe, 3)) + bytes(hdr - 8)
pos = hdr
for i, (cpu, sub, sl, al) in enumerate(slices_):
    pos = (pos + (1 << al) - 1) & ~((1 << al) - 1)
    out += bytes(pos - len(out)) + sl
    struct.pack_into('>iiIII', out, 8 + 20 * i, cpu, sub, pos, len(sl), al)
    pos += len(sl)
p = put('a/fat.dylib', bytes(out))
r = adhoc.resign(p, tmp)
m = read(p)
arches = adhoc.fat_arches(m)
check('fat: three slices stay, header rewritten and aligned', len(arches) == 3 and all(o % (1 << al) == 0 for _, _, o, _, al in arches))
check('fat: the 32-bit slice is byte for byte the same', m[arches[0][2]:arches[0][2] + arches[0][3]] == bytes(a32))
check('fat: the unsigned arm64 slice is signed, the usable arm64e one untouched',
      slice_state(m[arches[1][2]:arches[1][2] + arches[1][3]])[:2] == (True, True)
      and m[arches[2][2]:arches[2][2] + arches[2][3]] == s64e and r and len(r[0][1]) == 1)

# -- what it must refuse, naming the reason
expect_unsupported('encrypted slice refused', lambda: adhoc.sign_slice(macho(cryptid=1), adhoc.Context('x', tmp)), 'encrypted')
expect_unsupported('signature not at the end refused',
                   lambda: adhoc.sign_slice(signed(1, junk_after=b'tail-data-after-the-signature'), adhoc.Context('x', tmp)), 'not the last')
expect_unsupported('no room in the header refused', lambda: adhoc.sign_slice(macho(room=False), adhoc.Context('x', tmp)), 'no room')
expect_unsupported('LINKEDIT not at the end refused (unsigned)',
                   lambda: adhoc.sign_slice(macho() + b'extra-after-linkedit', adhoc.Context('x', tmp)), '__LINKEDIT is not at the end')
expect_unsupported('application-specific slots refused',
                   lambda: adhoc.sign_slice(signed(2, specials4=True), adhoc.Context('x', tmp)), 'application-specific')
weird = plistlib.dumps({'x': b'data-value'}, fmt=plistlib.FMT_XML)
expect_unsupported('entitlement value of an unsupported type refused', lambda: adhoc.sign_slice(signed(1, ents=weird), adhoc.Context('x', tmp)), 'DER')

# -- ipa-hashes.py on a made-up app: signs what it can, fails naming a file it cannot
app = os.path.join(tmp, 'App.app')
put('App.app/Main', signed(2))
put('App.app/Frameworks/Lib.dylib', macho())
put('App.app/Frameworks/Old.dylib', signed(1))
put('App.app/data.bin', os.urandom(100))
put('App.app/Classy.class', bytes.fromhex('cafebabe0000003400') + os.urandom(100))
scripts = os.path.join(HERE, '..', '..', '..', 'app', 'src', 'main', 'assets', 'setup', 'scripts', 'ios26')
if not os.path.exists(os.path.join(scripts, 'cdhash.py')):
    scripts = os.path.join(tmp, 'scripts')
    os.makedirs(scripts)
    shutil.copy(os.path.join(HERE, 'cdhash_for_test.py'), os.path.join(scripts, 'cdhash.py')) if os.path.exists(os.path.join(HERE, 'cdhash_for_test.py')) else None
if os.path.exists(os.path.join(scripts, 'cdhash.py')):
    r = subprocess.run([PY, '-I', os.path.join(HERE, '..', 'ipa-hashes.py'), scripts, app, os.path.join(tmp, 'h.txt')], capture_output=True, text=True)
    hs = [x for x in read(os.path.join(tmp, 'h.txt')).decode().split() if x]
    check('ipa-hashes: signs the two unusable, three machos, three hashes', r.returncode == 0 and 'ipa-hashes 3 3' in r.stdout
          and 'ipa-signed Frameworks/Lib.dylib unsigned' in r.stdout and 'ipa-signed Frameworks/Old.dylib SHA-1_only' in r.stdout
          and len(hs) == 3)
    check('ipa-hashes: every listed hash is the CDHash of a SHA-256 directory that matches its file',
          sorted(hs) == sorted(slice_state(read(os.path.join(app, n)))[2] for n in ('Main', 'Frameworks/Lib.dylib', 'Frameworks/Old.dylib')))
    put('App2.app/Main', signed(2))
    put('App2.app/Frameworks/Enc.dylib', macho(cryptid=1))
    r = subprocess.run([PY, '-I', os.path.join(HERE, '..', 'ipa-hashes.py'), scripts, os.path.join(tmp, 'App2.app'), os.path.join(tmp, 'h2.txt')],
                       capture_output=True, text=True)
    check('ipa-hashes: an encrypted file fails the job, named (exit 4)',
          r.returncode == 4 and 'ipa-fail Frameworks/Enc.dylib' in r.stdout and 'encrypted' in r.stdout)

# -- the real files of Angry Birds 2, when asked for
ab2 = os.environ.get('ASOFT_AB2')
if ab2:
    for rel in ('Frameworks/libAngryBirds2.dylib', 'Frameworks/UnityFramework.framework/UnityFramework'):
        src = os.path.join(ab2, rel)
        dst = os.path.join(tmp, 'ab2', rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy(src, dst)
        for extra in ('Info.plist', '_CodeSignature/CodeResources'):
            e = os.path.join(os.path.dirname(src), extra)
            if os.path.isfile(e):
                os.makedirs(os.path.dirname(os.path.join(os.path.dirname(dst), extra)), exist_ok=True)
                shutil.copy(e, os.path.join(os.path.dirname(dst), extra))
        before = read(dst)
        why = adhoc.usable(before)
        r = adhoc.resign(dst, os.path.join(tmp, 'ab2'))
        after = read(dst)
        has, ok, h = slice_state(after)
        check('Angry Birds 2 %s: was %s; now SHA-256 directory, all %d pages match, CDHash %s' % (rel, why[1], len(after) // 4096, h),
              not why[0] and r and has and ok and r[0][1] == [h])
        # everything before the signature is the original's but the header
        sig_old = adhoc.Info(before).sig_off
        hdr_end = 32 + adhoc.Info(before).sizeofcmds
        diff = [k for k in range(sig_old) if before[k] != after[k]]
        check('   before the old signature only the load commands differ (%d bytes: the signature size, __LINKEDIT sizes)' % len(diff),
              all(k < hdr_end for k in diff) and len(diff) < 32)

shutil.rmtree(tmp, ignore_errors=True)
print('failures:', bad)
sys.exit(1 if bad else 0)
