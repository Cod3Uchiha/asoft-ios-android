package com.asoft.ios;

import java.util.regex.Pattern;

/**
 * iOS 26's SystemAppMigrator plug-in and the setup (PhoneSetup26). The setup takes it out of the disk before
 * iOS's first full boot, as the PC installer does (asoft-ios 5369636, root26-staged.py): it installs the staged
 * system apps through installcoordinationd, which needs APFS clones, and the root is HFS+, so it only waited
 * on a lock (about 33 of the PC's 52 DataMigration minutes) and returned NO. apply-apps.sh already puts the
 * apps into /Applications.
 */
final class SystemAppMigrator {
    private SystemAppMigrator() {}

    /** Debug builds only: this file in files/setup/a26/phone/ keeps the plug-in in the disk (to compare times). */
    static final String KEEP_FLAG = "keep-system-app-migrator";

    /** The plug-in stays in the disk and the staged apps are copied: only a debug build with the flag file asks for it. */
    static boolean keep(boolean debuggable, boolean flagFile) {
        return debuggable && flagFile;
    }

    enum State { PRESENT, OUT, UNKNOWN }

    private static final Pattern NAME = Pattern.compile("(^|\\s)SystemAppMigrator\\.migrator(\\s|$)");

    /**
     * Whether the plug-in is in the booted iOS's /System/Library/DataClassMigrators, from the console's answer
     * (the listing, then the SpringBoard.migrator Info.plist dump: only the listing counts). No listing is UNKNOWN.
     */
    static State state(String info) {
        if (info == null) {
            return State.UNKNOWN;
        }
        int dump = info.indexOf(" /System/Library/DataClassMigrators/");
        String list = (dump >= 0 ? info.substring(0, dump) : info).trim();
        if (!list.contains(".migrator")) {
            return State.UNKNOWN;
        }
        return NAME.matcher(list).find() ? State.PRESENT : State.OUT;
    }

    /** The words for the log line. */
    static String describe(State s) {
        switch (s) {
            case PRESENT: return "SystemAppMigrator present (it runs; the old, slower variant)";
            case OUT: return "SystemAppMigrator out (the installer left it out)";
            default: return "SystemAppMigrator unknown (no migrator listing)";
        }
    }

    /** The PC's measured DataMigration minutes (33 without the plug-in, 52 with it), for the setup screen's "on a PC about N". */
    static int pcMinutes(State s) {
        return s == State.OUT ? 35 : 50;
    }
}
