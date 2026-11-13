"""ipa-place.py IPA ROOT [NAME]: put the .app of an .ipa into /Applications of the iOS 26 disk mounted at ROOT.

The phone's counterpart of the PC's Install-Ipa.ps1 placing step (copy the .app, owner root, the folder
root:wheel 0775, every file 0777 as the PC's WSL copy leaves them). The .ipa is read straight from the disk the
host handed over (a zip followed by zeros up to a whole sector, which zipfile accepts), so nothing is copied
through RAM. Prints "ASOFT: ipa-app NAME BUNDLEID" and "ASOFT: ipa-new 1|0" (0: that app was there already: its
files are replaced, LaunchServices knows it), and writes NAME to /work/ipa.name and 1|0 to /work/ipa.new.

A reinstall replaces an app this placing made (it leaves .asoft-ipa in it), never a system app of the same name.
Refused with a plain message: no Payload/*.app, a name that leaves the app folder, not enough room on the disk.
"""
import os
import plistlib
import shutil
import stat
import sys
import zipfile


# a file in the placed app: what an earlier placing here made (a reinstall may replace it)
MARK = '.asoft-ipa'


# the app folder being made: a failure takes the half-made one away (it has no mark, so a retry would be refused)
partial = []


def fail(msg):
    print('ASOFT: ipa-fail ' + msg)
    for p in partial:
        shutil.rmtree(p, ignore_errors=True)
    sys.exit(2)


def main():
    ipa, root = sys.argv[1], sys.argv[2]
    ipa_name = sys.argv[3] if len(sys.argv) > 3 else ''
    try:
        z = zipfile.ZipFile(ipa)
    except (zipfile.BadZipFile, OSError) as e:
        fail('this file is not a readable .ipa (%s)' % e)
    names = z.namelist()
    # the first Payload/NAME.app
    app = None
    for n in names:
        parts = n.split('/')
        if len(parts) >= 3 and parts[0] == 'Payload' and parts[1].endswith('.app'):
            app = parts[1]
            break
    if app is None:
        fail('there is no app (Payload/NAME.app) in this .ipa')
    prefix = 'Payload/' + app + '/'
    members = [i for i in z.infolist() if i.filename.startswith(prefix) and i.filename != prefix]
    total = sum(i.file_size for i in members)
    st = os.statvfs(root)
    free = st.f_bavail * st.f_frsize
    # (the app's own files, then what the guest writes on first start)
    if total + (256 << 20) > free:
        fail('iOS 26\'s disk has %d MB free and this app needs %d MB' % (free >> 20, total >> 20))

    bundle_id = ''
    info = prefix + 'Info.plist'
    if info in names:
        try:
            bundle_id = str(plistlib.loads(z.read(info)).get('CFBundleIdentifier', ''))
        except Exception:
            bundle_id = ''
    print('ASOFT: ipa-app %s %s' % (app, bundle_id or '?'))

    dst = os.path.join(root, 'Applications', app)
    new = 0 if os.path.exists(dst) else 1
    print('ASOFT: ipa-new %d' % new)
    with open('/work/ipa.new', 'w') as f:
        f.write(str(new))
    if os.path.lexists(dst):
        # only an app this placing made may be replaced: not a system app that has the same name
        if not os.path.exists(os.path.join(dst, MARK)):
            fail('iOS 26 has an app called %s of its own; this .ipa cannot replace it' % app)
        shutil.rmtree(dst)
    partial.append(dst)
    # the mark first: a placing that dies half way leaves a folder a retry may replace
    os.makedirs(dst)
    with open(os.path.join(dst, MARK), 'w') as f:
        f.write(ipa_name + '\n')
    base = os.path.realpath(dst)
    done = 0
    for i in members:
        rel = i.filename[len(prefix):]
        target = os.path.realpath(os.path.join(dst, rel))
        if target != base and not target.startswith(base + os.sep):
            fail('a file name in this .ipa leaves the app folder: ' + i.filename)
        if i.is_dir():
            os.makedirs(target, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        mode = (i.external_attr >> 16) & 0xFFFF
        if stat.S_ISLNK(mode):
            os.symlink(z.read(i).decode('utf-8'), os.path.join(dst, rel))
        else:
            with z.open(i) as src, open(target, 'wb') as out:
                shutil.copyfileobj(src, out, 1 << 20)
            os.chmod(target, 0o777)
        done += i.file_size
    # the same owner and modes as the PC's placing: root, the app folder root:wheel (group 80) 0775
    for d, dirs, files in os.walk(dst):
        for n in dirs + files:
            p = os.path.join(d, n)
            os.lchown(p, 0, 0)
            if os.path.isdir(p) and not os.path.islink(p):
                os.chmod(p, 0o777)
    os.chown(dst, 0, 80)
    os.chmod(dst, 0o775)
    partial.clear()
    with open('/work/ipa.name', 'w') as f:
        f.write(app)
    print('ASOFT: progress 55 placed %s (%d MB)' % (app, done >> 20))


try:
    main()
except OSError as e:
    fail('could not place the app: %s' % e)
