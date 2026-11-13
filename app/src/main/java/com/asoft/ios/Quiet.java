package com.asoft.ios;

import java.util.function.Consumer;

/**
 * Calls into Android's system services (notifications, channels) that must never take a long-running thread down.
 * When system_server dies the call throws DeadSystemRuntimeException (a RuntimeException) although the setup or the
 * guest is fine (asoft-ios-android issue 43): one log line, then carry on.
 */
final class Quiet {
    private Quiet() {
    }

    /** Runs @r; a RuntimeException is told to @log as one line and swallowed. Returns whether @r finished. */
    static boolean run(String what, Consumer<String> log, Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            try {
                log.accept(what + " failed: " + e);
            } catch (RuntimeException ignored) {
                // nowhere to say it
            }
            return false;
        }
    }

    static boolean run(android.content.Context c, String what, Runnable r) {
        return run(what, s -> AppLog.note(c, s), r);
    }
}
