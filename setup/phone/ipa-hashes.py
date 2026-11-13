"""ipa-hashes.py SCRIPTS APP OUT: the CDHashes of every Mach-O of an app for the trust cache, after making each usable.

SCRIPTS is the folder with cdhash.py (the PC installer's), APP the staged .app, OUT the list (one 40-digit hash a
line, the form mktc.py reads). A 64-bit slice without a SHA-256 code directory that matches its pages (unsigned,
SHA-1 only, a decrypted binary's changed pages) is re-signed ad hoc first (adhoc.py, next to this file); each such file
is named: "ASOFT: ipa-signed PATH REASON". A file adhoc.py cannot help makes the job fail ("ASOFT: ipa-fail PATH: why",
exit 4), and so does a slice that still has no hash afterwards (exit 3): an app with one such file does not start, so
none is installed. The count line is "ASOFT: ipa-hashes N MACHOS".
"""
import os
import struct
import sys

scripts, app, out = sys.argv[1], sys.argv[2], sys.argv[3]
sys.path.insert(0, scripts)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import adhoc  # noqa: E402  (next to this file)
import cdhash  # noqa: E402  (the PC installer's, from SCRIPTS)


def fail(rel, why, code):
    print('ASOFT: ipa-fail %s: %s' % (rel, why))
    sys.exit(code)


hashes = set()
machos = 0
for p in adhoc.machos(app):
    rel = os.path.relpath(p, app)
    try:
        for why, got in adhoc.resign(p, app):
            print('ASOFT: ipa-signed %s %s' % (rel, why.replace(' ', '_')))
    except (adhoc.Unsupported, struct.error, OSError) as e:
        fail(rel, 'cannot be given a signature iOS 26 accepts: %s' % e, 4)
    with open(p, 'rb') as f:
        buf = f.read()
    try:
        arches = adhoc.fat_arches(buf)
    except adhoc.Unsupported:
        continue                                    # not a Mach-O (a Java class has a fat magic)
    slices = [buf] if arches is None and adhoc.is_64(buf, 0) else \
        [buf[o:o + s] for _, _, o, s, _ in (arches or []) if adhoc.is_64(buf, o)]
    if not slices:
        print('ASOFT: ipa-warn %s has no 64-bit code: iOS 26 cannot run it' % rel)
        continue
    machos += 1
    for sl in slices:
        got = [h for h, _flags in cdhash.cdhashes(sl)]
        ok, why = adhoc.usable(sl)
        if not got or not ok:
            fail(rel, 'still has no usable code signature (%s)' % (why or 'no hash'), 3)
        hashes.update(got)
with open(out, 'w') as f:
    f.write('\n'.join(sorted(hashes)) + ('\n' if hashes else ''))
print('ASOFT: ipa-hashes %d %d' % (len(hashes), machos))
