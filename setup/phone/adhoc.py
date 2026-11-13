"""adhoc.py: give every Mach-O of an app a code signature the iOS 26 trust cache can carry.

    adhoc.py APP                  check every Mach-O under the folder; re-sign the unusable ones in place
    adhoc.py --check APP          only say which are unusable (exit 1 when there are some)
    adhoc.py --file FILE [APP]    the same for one file (APP: the folder its Info.plist / CodeResources belong to)

A 64-bit slice is "usable" when it has a SHA-256 code directory whose page hashes all match the file as it is. The
trust cache route (ipa-hashes.py -> mktc.py) carries the CDHash of such a directory, and the guest's kernel checks a
page against the directory when it is mapped. Three things make a slice unusable, and each is repaired the same way:
  unsigned                     no LC_CODE_SIGNATURE
  SHA-1 only                   (Angry Birds 2015-era builds, some third-party frameworks) no SHA-256 directory
  code pages that do not match a decrypted App Store binary: the encrypted pages were replaced after signing
Repair = one ad-hoc signature, like `codesign -s -`: a SHA-256 code directory (version 0x20400, flags adhoc, 4 KB pages,
page hashes over the file as it is, execSegBase/Limit/Flags from __TEXT), the old requirements are dropped (an empty
set, as codesign makes for ad hoc), the old entitlements are kept byte for byte (XML slot 5 and DER slot 7; a DER one is
made from the XML when only that was there), special slot 1 and 3 (Info.plist, CodeResources) are hashed again from the
files beside the binary when it is its bundle's executable, and an empty CMS blob closes it. The old identifier stays.
Prints "signed REL REASON CDHASH [CDHASH ...]" per changed file (CDHash = first 20 bytes of the directory's SHA-256).

Fat files are handled per slice: every 64-bit slice (arm64 and arm64e alike) is checked and, when needed, re-signed in
its place, and the fat header is rewritten (slices keep their alignment; 32-bit slices are copied untouched).

Handled signature layouts: a signature that is the last thing of __LINKEDIT and of the file (replaced in place, the
file shrinks or grows), and a file without one when the header has room for one more load command and __LINKEDIT is
the last segment of the file (the signature is added after it).

Everything else raises Unsupported with a message naming what is wrong, and the caller fails the install:
  a slice that is still encrypted (LC_ENCRYPTION_INFO cryptid != 0), a signature that is not at the end of __LINKEDIT,
  no room in the header for LC_CODE_SIGNATURE, __LINKEDIT not at the end of the file, a __TEXT or __LINKEDIT missing,
  a superblob that does not parse, an old signature with application-specific (slot 4) or rep-specific (slot 6)
  hashes, entitlements with values other than booleans, strings, integers, arrays and dictionaries of those.
Not handled on purpose: a 32-bit slice (iOS 26 cannot run it), alternate code directories (dropped when re-signing),
requirements (dropped), a team identifier (none). Untested on real files: arm64e slices, a fat file with several 64-bit
slices (tested on made-up files).
"""
import hashlib
import os
import plistlib
import struct
import sys

PAGE = 4096
LC_SEGMENT_64, LC_CODE_SIGNATURE, LC_ENCRYPTION_INFO_64 = 0x19, 0x1d, 0x2c
MH_MAGIC_64, MH_EXECUTE = 0xfeedfacf, 2
CSMAGIC_EMBEDDED_SIGNATURE, CSMAGIC_CODEDIRECTORY, CSMAGIC_REQUIREMENTS = 0xfade0cc0, 0xfade0c02, 0xfade0c01
CSMAGIC_ENTITLEMENTS, CSMAGIC_DER_ENTITLEMENTS, CSMAGIC_BLOBWRAPPER = 0xfade7171, 0xfade7172, 0xfade0b01
HASH_SHA1, HASH_SHA256 = 1, 2
MACHO_MAGICS = (b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf', b'\xcf\xfa\xed\xfe')


class Unsupported(Exception):
    """This file cannot be given a usable signature; the message says why (the caller names the file)."""


# -- reading

def fat_arches(buf):
    """None for a thin file; for a fat one [(cpu, sub, offset, size, align_log2)]. Unsupported when it does not parse
    (a Java class file has the same magic)."""
    if len(buf) < 8:
        return None
    magic = struct.unpack_from('>I', buf)[0]
    if magic not in (0xcafebabe, 0xcafebabf):
        return None
    n = struct.unpack_from('>I', buf, 4)[0]
    if n == 0 or n > 16:
        raise Unsupported('not a Mach-O (a fat header with %d architectures)' % n)
    out = []
    for i in range(n):
        if magic == 0xcafebabe:
            cpu, sub, off, size, al = struct.unpack_from('>iiIII', buf, 8 + 20 * i)
        else:
            cpu, sub, off, size, al, _ = struct.unpack_from('>iiQQII', buf, 8 + 32 * i)
        if off + size > len(buf) or al > 20:
            raise Unsupported('not a Mach-O (a fat header that does not fit the file)')
        out.append((cpu, sub, off, size, al))
    return out


def is_64(buf, off):
    return len(buf) >= off + 32 and struct.unpack_from('<I', buf, off)[0] == MH_MAGIC_64


class Info:
    """What a 64-bit slice's header says."""

    def __init__(self, m):
        self.ncmds, self.sizeofcmds = struct.unpack_from('<II', m, 16)
        self.filetype = struct.unpack_from('<I', m, 12)[0]
        self.cmds_end = 32 + self.sizeofcmds
        self.sig_cmd = self.sig_off = self.sig_size = None
        self.linkedit = self.text = None
        self.cryptid = 0
        self.first_section = None
        o = 32
        for _ in range(self.ncmds):
            if o + 8 > len(m):
                raise Unsupported('the load commands run past the end of the file')
            cmd, size = struct.unpack_from('<II', m, o)
            if size < 8 or o + size > len(m):
                raise Unsupported('a load command does not fit the file')
            if cmd == LC_CODE_SIGNATURE:
                self.sig_cmd = o
                self.sig_off, self.sig_size = struct.unpack_from('<II', m, o + 8)
            elif cmd == LC_ENCRYPTION_INFO_64:
                self.cryptid = struct.unpack_from('<I', m, o + 16)[0]
            elif cmd == LC_SEGMENT_64:
                name = bytes(m[o + 8:o + 24]).rstrip(b'\0')
                if name == b'__LINKEDIT':
                    self.linkedit = o
                elif name == b'__TEXT':
                    self.text = o
                for i in range(struct.unpack_from('<I', m, o + 64)[0]):
                    off = struct.unpack_from('<I', m, o + 72 + 80 * i + 48)[0]
                    if off and (self.first_section is None or off < self.first_section):
                        self.first_section = off
            o += size


def blobs(m, info):
    """The old signature's blobs as {slot: (offset in m, length, magic)}; {} when there is none. Unsupported when it
    does not parse."""
    if info.sig_cmd is None or not info.sig_size:
        return {}
    so = info.sig_off
    if so + info.sig_size > len(m) or info.sig_size < 12:
        raise Unsupported('the code signature does not fit the file')
    magic, length, count = struct.unpack_from('>III', m, so)
    if magic != CSMAGIC_EMBEDDED_SIGNATURE or length > info.sig_size or 12 + 8 * count > length:
        raise Unsupported('the code signature is not a superblob')
    out = {}
    for i in range(count):
        slot, off = struct.unpack_from('>II', m, so + 12 + 8 * i)
        if off + 8 > length:
            raise Unsupported('a blob of the code signature lies outside it')
        bm, bl = struct.unpack_from('>II', m, so + off)
        if off + bl > length:
            raise Unsupported('a blob of the code signature is cut short')
        out[slot] = (so + off, bl, bm)
    return out


def directories(m, bl):
    """[(slot, hash type, ok, identifier, specials)] of every code directory in blobs @bl; ok = the page hashes of
    the file as it is match. specials = {slot number: hash bytes} (only non-zero ones)."""
    out = []
    for slot, (at, ln, magic) in sorted(bl.items()):
        if magic != CSMAGIC_CODEDIRECTORY or ln < 44:
            continue
        ver, flags, hoff, ioff, nsp, ncode, limit, hsize, htype, plat, psize = struct.unpack_from('>IIIIIIIBBBB', m, at + 8)
        ident = bytes(m[at + ioff:m.find(b'\0', at + ioff)]).decode('utf-8', 'replace') if ioff < ln else ''
        hf = {HASH_SHA1: hashlib.sha1, HASH_SHA256: hashlib.sha256}.get(htype)
        ok = hf is not None and hsize == hf().digest_size and limit <= len(m) and hoff + hsize * ncode <= ln
        page = (1 << psize) if psize else 0
        if ok and page:
            ok = ncode == (limit + page - 1) // page
        elif ok:
            ok = ncode == 1 and limit > 0 or limit == 0 and ncode == 0
            page = max(limit, 1)
        if ok:
            for p in range(ncode):
                d = hf(m[p * page:min((p + 1) * page, limit)]).digest()
                if d != bytes(m[at + hoff + hsize * p:at + hoff + hsize * (p + 1)]):
                    ok = False
                    break
        specials = {}
        for s in range(1, nsp + 1):
            h = bytes(m[at + hoff - hsize * s:at + hoff - hsize * (s - 1)])
            if any(h):
                specials[s] = h
        out.append((slot, htype, ok, ident, specials))
    return out


def usable(m, info=None):
    """(usable, why not): is there a SHA-256 code directory whose pages all match this slice?"""
    info = info or Info(m)
    bl = blobs(m, info)
    if not bl:
        return False, 'unsigned'
    cds = directories(m, bl)
    if any(t == HASH_SHA256 and ok for _, t, ok, _, _ in cds):
        return True, ''
    if any(t == HASH_SHA256 for _, t, _, _, _ in cds):
        return False, 'its code pages do not match its signature'
    if cds:
        return False, 'SHA-1 only'
    return False, 'no code directory'


# -- writing

def der_len(n):
    if n < 0x80:
        return bytes([n])
    s = n.to_bytes((n.bit_length() + 7) // 8, 'big')
    return bytes([0x80 | len(s)]) + s


def der(tag, body):
    return bytes([tag]) + der_len(len(body)) + body


def der_value(v):
    if isinstance(v, bool):
        return der(0x01, b'\xff' if v else b'\x00')
    if isinstance(v, int):
        return der(0x02, v.to_bytes(max(1, (v.bit_length() + 8) // 8), 'big', signed=True))
    if isinstance(v, str):
        return der(0x0c, v.encode())
    if isinstance(v, (list, tuple)):
        return der(0x30, b''.join(der_value(x) for x in v))
    if isinstance(v, dict):
        return der_dict(v)
    raise Unsupported('an entitlement value of type %s cannot be written as DER' % type(v).__name__)


def der_dict(d):
    # CoreEntitlements: a context-specific constructed [16] of SEQUENCE { UTF8String key, value }, keys sorted
    items = b''.join(der(0x30, der(0x0c, k.encode()) + der_value(d[k])) for k in sorted(d, key=lambda k: k.encode()))
    return der(0xb0, items)


def blob(magic, body):
    return struct.pack('>II', magic, 8 + len(body)) + body


class Context:
    """What a slice's signature takes from the files around it: a default identifier and the two file hashes of the
    special slots 1 and 3, when the binary is its bundle's executable."""

    def __init__(self, path, app):
        self.default_ident = os.path.basename(path)
        self.info_hash = self.res_hash = bytes(32)
        here = os.path.dirname(path)
        plist = os.path.join(here, 'Info.plist')
        if os.path.isfile(plist):
            try:
                with open(plist, 'rb') as f:
                    data = f.read()
                d = plistlib.loads(data)
                if d.get('CFBundleExecutable') == os.path.basename(path):
                    self.info_hash = hashlib.sha256(data).digest()
                    self.default_ident = str(d.get('CFBundleIdentifier') or self.default_ident)
                    res = os.path.join(here, '_CodeSignature', 'CodeResources')
                    if os.path.isfile(res):
                        with open(res, 'rb') as f:
                            self.res_hash = hashlib.sha256(f.read()).digest()
            except Exception:
                pass


def sign_slice(m, ctx):
    """The slice @m (a bytes-like 64-bit Mach-O) with a new ad-hoc signature: (new bytearray, CDHash hex)."""
    info = Info(m)
    if info.cryptid:
        raise Unsupported('it is still encrypted (cryptid %d): decrypt it first' % info.cryptid)
    if info.linkedit is None or info.text is None:
        raise Unsupported('it has no __TEXT or no __LINKEDIT segment')
    le_vmaddr, le_vmsize, le_fileoff, le_filesize = struct.unpack_from('<QQQQ', m, info.linkedit + 24)
    text_fileoff, text_filesize = struct.unpack_from('<QQ', m, info.text + 40)
    b = bytearray(m)
    bl = blobs(m, info)
    ident, ents_xml, ents_der = None, None, None
    if info.sig_cmd is not None:
        # the old signature must be the last thing of __LINKEDIT and of the file (a 16-byte tail of zeros is allowed)
        end = info.sig_off + info.sig_size
        if end != le_fileoff + le_filesize or end < len(b) - 16 or len(b) > end and any(b[end:]):
            raise Unsupported('its signature is not the last thing in __LINKEDIT and in the file')
        for slot, ty, _, ident_, specials in directories(m, bl):
            if ident is None and ident_:
                ident = ident_
            if 4 in specials or 6 in specials:
                raise Unsupported('its signature has application-specific or rep-specific hashes, which are not kept')
        if 5 in bl and bl[5][2] == CSMAGIC_ENTITLEMENTS:
            ents_xml = bytes(m[bl[5][0]:bl[5][0] + bl[5][1]])
        if 7 in bl and bl[7][2] == CSMAGIC_DER_ENTITLEMENTS:
            ents_der = bytes(m[bl[7][0]:bl[7][0] + bl[7][1]])
        sig_cmd, sig_off = info.sig_cmd, info.sig_off
        del b[sig_off:]
    else:
        # unsigned: a new LC_CODE_SIGNATURE in the header's padding, the signature after a 16-byte aligned end
        if info.first_section is None or info.cmds_end + 16 > info.first_section:
            raise Unsupported('there is no room in its header for a code signature load command')
        if le_fileoff + le_filesize != len(b):
            raise Unsupported('__LINKEDIT is not at the end of the file')
        b += bytes(-len(b) % 16)
        sig_cmd, sig_off = info.cmds_end, len(b)
        if any(b[sig_cmd:sig_cmd + 16]):
            raise Unsupported('the padding after its load commands is not empty')
        struct.pack_into('<IIII', b, sig_cmd, LC_CODE_SIGNATURE, 16, sig_off, 0)
        struct.pack_into('<II', b, 16, info.ncmds + 1, info.sizeofcmds + 16)
    if ents_xml is not None and ents_der is None:
        try:
            ents = plistlib.loads(ents_xml[8:])
        except Exception as e:
            raise Unsupported('its entitlements do not parse (%s)' % e)
        ents_der = blob(CSMAGIC_DER_ENTITLEMENTS, der(0x70, der(0x02, b'\x01') + der_dict(ents)))
    ident = (ident or ctx.default_ident).encode()

    requirements = blob(CSMAGIC_REQUIREMENTS, struct.pack('>I', 0))
    cms = blob(CSMAGIC_BLOBWRAPPER, b'')
    special = {1: ctx.info_hash, 2: hashlib.sha256(requirements).digest(), 3: ctx.res_hash}
    n_special = 3
    if ents_xml is not None:
        special[5] = hashlib.sha256(ents_xml).digest()
        n_special = 5
    if ents_der is not None:
        special[7] = hashlib.sha256(ents_der).digest()
        n_special = 7
    parts = [(2, requirements)]
    if ents_xml is not None:
        parts.append((5, ents_xml))
    if ents_der is not None:
        parts.append((7, ents_der))
    parts.append((0x10000, cms))

    code_limit = sig_off
    n_code = (code_limit + PAGE - 1) // PAGE
    header_len, ident_off = 88, 88
    hash_off = ident_off + len(ident) + 1 + 32 * n_special
    cd_len = hash_off + 32 * n_code
    cd = bytearray(struct.pack('>IIIIIIIIIBBBBIIIIQQQQ', CSMAGIC_CODEDIRECTORY, cd_len, 0x20400, 0x2, hash_off, ident_off,
                               n_special, n_code, code_limit, 32, HASH_SHA256, 0, 12, 0, 0, 0, 0, 0,
                               text_fileoff, text_filesize, 1 if info.filetype == MH_EXECUTE else 0))
    assert len(cd) == header_len, len(cd)
    cd += ident + b'\0'
    for s in range(n_special, 0, -1):
        cd += special.get(s, bytes(32))
    new_size = (12 + 8 * (len(parts) + 1) + cd_len + sum(len(x) for _, x in parts) + 15) & ~15

    # the header says the new sizes before the pages are hashed
    struct.pack_into('<II', b, sig_cmd + 8, sig_off, new_size)
    new_filesize = sig_off + new_size - le_fileoff
    struct.pack_into('<QQQQ', b, info.linkedit + 24, le_vmaddr, (new_filesize + 0x3fff) & ~0x3fff, le_fileoff, new_filesize)
    for p in range(n_code):
        cd += hashlib.sha256(bytes(b[p * PAGE:min((p + 1) * PAGE, code_limit)])).digest()
    assert len(cd) == cd_len
    all_blobs = [(0, bytes(cd))] + parts
    sb = bytearray(struct.pack('>III', CSMAGIC_EMBEDDED_SIGNATURE, 0, len(all_blobs)))
    at = 12 + 8 * len(all_blobs)
    for slot, x in all_blobs:
        sb += struct.pack('>II', slot, at)
        at += len(x)
    for _, x in all_blobs:
        sb += x
    struct.pack_into('>I', sb, 4, len(sb))
    sb += bytes(new_size - len(sb))
    b += sb
    return b, hashlib.sha256(bytes(cd)).hexdigest()[:40]


def resign(path, app=None):
    """Re-sign what is unusable in the Mach-O @path in place. Returns [] when nothing was needed, else
    [(reason, [CDHash, ...])] (one entry per file). Raises Unsupported."""
    with open(path, 'rb') as f:
        buf = f.read()
    try:
        arches = fat_arches(buf)
    except Unsupported:
        return []                       # not a Mach-O after all (a Java class file has a fat file's magic)
    ctx = Context(path, app)
    if arches is None:
        if not is_64(buf, 0):
            return []
        ok, why = usable(buf)
        if ok:
            return []
        new, h = sign_slice(buf, ctx)
        write(path, new)
        return [(why, [h])]
    parts, changed, hashes, reasons = [], False, [], []
    for cpu, sub, off, size, al in arches:
        sl = buf[off:off + size]
        if is_64(sl, 0):
            ok, why = usable(sl)
            if not ok:
                new, h = sign_slice(sl, ctx)
                sl, changed = bytes(new), True
                hashes.append(h)
                reasons.append(why)
        parts.append((cpu, sub, sl, al))
    if not changed:
        return []
    # the fat header again: slices keep their alignment, the file is laid out in the old order
    big = any(len(sl) >= 1 << 32 for _, _, sl, _ in parts) or len(buf) >= 1 << 32
    hdr = 8 + (32 if big else 20) * len(parts)
    out = bytearray(struct.pack('>II', 0xcafebabf if big else 0xcafebabe, len(parts)))
    out += bytes(hdr - 8)
    pos = hdr
    for i, (cpu, sub, sl, al) in enumerate(parts):
        pos = (pos + (1 << al) - 1) & ~((1 << al) - 1)
        out += bytes(pos - len(out))
        out += sl
        if big:
            struct.pack_into('>iiQQII', out, 8 + 32 * i, cpu, sub, pos, len(sl), al, 0)
        else:
            struct.pack_into('>iiIII', out, 8 + 20 * i, cpu, sub, pos, len(sl), al)
        pos += len(sl)
    write(path, out)
    return [(', '.join(sorted(set(reasons))), hashes)]


def write(path, data):
    """Replaces @path with @data, keeping its mode and owner; a half-written file never takes its place."""
    st = os.stat(path)
    tmp = path + '.asoft-tmp'
    with open(tmp, 'wb') as f:
        f.write(data)
    os.chmod(tmp, st.st_mode & 0o7777)
    try:
        os.chown(tmp, st.st_uid, st.st_gid)  # (root only; the job is root)
    except (OSError, AttributeError):
        pass
    os.replace(tmp, path)


def machos(app):
    """Paths of the Mach-O files (by magic; symlinks skipped) under @app, sorted."""
    for d, _, files in os.walk(app):
        for n in sorted(files):
            p = os.path.join(d, n)
            if os.path.islink(p) or not os.path.isfile(p):
                continue
            try:
                with open(p, 'rb') as f:
                    if f.read(4) in MACHO_MAGICS:
                        yield p
            except OSError:
                continue


def main(argv):
    check = '--check' in argv
    args = [a for a in argv if not a.startswith('--') or a == '--file']
    if args and args[0] == '--file':
        files, app = [args[1]], (args[2] if len(args) > 2 else os.path.dirname(args[1]))
    elif args:
        app = args[0]
        files = list(machos(app))
    else:
        print(__doc__)
        return 2
    bad = 0
    for p in files:
        rel = os.path.relpath(p, app)
        try:
            if check:
                with open(p, 'rb') as f:
                    buf = f.read()
                try:
                    arches = fat_arches(buf)
                except Unsupported:
                    continue
                slices = [buf] if arches is None and is_64(buf, 0) else \
                    [buf[o:o + s] for _, _, o, s, _ in (arches or []) if is_64(buf, o)]
                for sl in slices:
                    ok, why = usable(sl)
                    if not ok:
                        print('unusable %s: %s' % (rel, why))
                        bad += 1
            else:
                for why, hashes in resign(p, app):
                    print('signed %s %s %s' % (rel, why.replace(' ', '_'), ' '.join(hashes)))
        except (Unsupported, struct.error, OSError) as e:
            print('FAILED %s: %s' % (rel, e))
            bad += 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
