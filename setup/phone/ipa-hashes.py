"""ipa-hashes.py SCRIPTS APP OUT: the CDHashes of every Mach-O of an app for the trust cache.

SCRIPTS is the folder with cdhash.py (the PC installer's), APP the placed .app, OUT the list (one 40-digit hash a
line, the form mktc.py reads). Install-Ipa.ps1 does the same with cdhash.py over the extracted files. A Mach-O
with no SHA-256 code directory (unsigned, or signed with SHA-1 only: Angry Birds 2015) gets no hash; AMFI would
kill it, so each is named: "ASOFT: ipa-nosig PATH". The count line is "ASOFT: ipa-hashes N MACHOS".
"""
import os
import sys

scripts, app, out = sys.argv[1], sys.argv[2], sys.argv[3]
sys.path.insert(0, scripts)
import cdhash  # noqa: E402  (the PC installer's, from SCRIPTS)

MAGICS = (b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf', b'\xcf\xfa\xed\xfe')
hashes = set()
machos = 0
for d, _, files in os.walk(app):
    for n in sorted(files):
        p = os.path.join(d, n)
        if os.path.islink(p):
            continue
        with open(p, 'rb') as f:
            head = f.read(4)
            if head not in MAGICS:
                continue
            f.seek(0)
            buf = f.read()
        machos += 1
        got = [h for h, _flags in cdhash.cdhashes(buf)]
        if not got:
            print('ASOFT: ipa-nosig ' + os.path.relpath(p, app))
        hashes.update(got)
with open(out, 'w') as f:
    f.write('\n'.join(sorted(hashes)) + ('\n' if hashes else ''))
print('ASOFT: ipa-hashes %d %d' % (len(hashes), machos))
