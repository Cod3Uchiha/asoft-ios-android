# An .ipa into iOS 26's user disk (Ipa26): the PC's Install-Ipa.ps1 on the phone. The disks: d0 the user
# disk (qcow2, read and written), d1 the .ipa (a raw file, a zip followed by zeros to a whole sector); in the
# job: base.tc (the trust cache the disk boots with now), scripts/{noget,cdhash,mktc}.py, phone/ipa-*.py.
# Hands back ipa.tc: base.tc and the CDHash of every Mach-O of the app (get-task-allow switched off first).
# The lines "ASOFT: ipa-*" tell Ipa26 what happened; a failure says why in "ASOFT: ipa-fail ..." and the exit code.
progress 5 "Opening iOS 26's disk"
disk_tools
in_root user.qcow2 <<'IPA'
echo "ASOFT: progress 15 Putting the app on iOS 26's disk"
python3 -I $A/phone/ipa-place.py $A/app.ipa $R
APP=$(cat $A/ipa.name)
NEW=$(cat $A/ipa.new)
DST=$R/Applications/$APP
# a developer-signed app has get-task-allow, which iOS 26 refuses while Developer Mode is off
echo "ASOFT: progress 70 Preparing the app's signatures"
python3 -I $S/noget.py "$DST" > $A/noget.txt || true
echo "ASOFT: ipa-noget $(grep -c '^get-task-allow off' $A/noget.txt || true)"
grep '^FAILED' $A/noget.txt | sed 's/^/ASOFT: ipa-warn /' || true
python3 -I $A/phone/ipa-hashes.py $S "$DST" $A/ipa.hashes
if [ ! -s $A/ipa.hashes ]; then
	echo "ASOFT: ipa-fail no file of this app has a signature iOS 26 can use"
	exit 3
fi
echo "ASOFT: progress 85 Making the trust cache"
python3 -I $S/mktc.py -2 $A/ipa.tc $A/base.tc $A/ipa.hashes
# a new app: LaunchServices builds its list again at the next start (an app already there is found as it is)
if [ "$NEW" = 1 ]; then
	find "$R/private/var/mobile/Containers/Data/InternalDaemon" -maxdepth 4 -name 'com.apple.LaunchServices-*.csstore' 2> /dev/null |
		while read -r f; do mv "$f" "$f.stale"; done
fi
IPA
echo "ASOFT: progress 98 Done"
