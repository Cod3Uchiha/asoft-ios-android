package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * How many vCPUs iOS gets and which of the phone's CPUs run them. Four vCPUs are used on every
 * phone. When at least four cores have 60% or more of the best core's capacity, pinning the vCPU
 * and native EL0 worker threads to those cores avoids migrating the emulation workload onto the
 * little cores. Phones with fewer than four fast cores keep normal Android scheduling: pinning
 * four vCPUs onto only one or two big cores can starve the guest's parallel TCG work.
 *
 * A saved state keeps the count it was saved with (Guest.savedCpus).
 * cpu.txt in the app's external folder overrides it for tests: "smp=2 pin=6,7" ("pin=" none).
 */
final class CpuPlan {
    final int smp;
    /** The host CPUs for the vCPU threads and the stubs ("6,7"), empty for any. */
    final String pin;
    final String why;

    private CpuPlan(int smp, String pin, String why) {
        this.smp = smp;
        this.pin = pin;
        this.why = why;
    }

    static File overrideFile(Context c) {
        return new File(c.getExternalFilesDir(null), "cpu.txt");
    }

    static CpuPlan forThisPhone(Context c) {
        CpuPlan auto = auto();
        try {
            String t = new String(Files.readAllBytes(overrideFile(c).toPath())).trim();
            int smp = auto.smp;
            String pin = auto.pin;
            for (String w : t.split("\\s+")) {
                if (w.matches("smp=[1-8]")) {
                    smp = Integer.parseInt(w.substring(4));
                } else if (w.matches("pin=[0-9,]*")) {
                    pin = w.substring(4);
                }
            }
            return new CpuPlan(smp, pin, "cpu.txt \"" + t + "\" (automatic: " + auto.smp + " vCPUs, pin "
                    + (auto.pin.isEmpty() ? "none" : auto.pin) + ")");
        } catch (IOException e) {
            return auto;        // no test file
        }
    }

    private static CpuPlan auto() {
        List<Integer> cap = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            File d = new File("/sys/devices/system/cpu/cpu" + i);
            if (!d.isDirectory()) {
                break;
            }
            cap.add(readInt(new File(d, "cpu_capacity")));
        }
        int best = 0;
        for (int v : cap) {
            best = Math.max(best, v);
        }
        if (cap.isEmpty() || best <= 0) {
            return new CpuPlan(4, "", "no cpu_capacity: 4 vCPUs, any core");
        }

        StringBuilder fast = new StringBuilder();
        int n = 0;
        for (int i = 0; i < cap.size(); i++) {
            if (cap.get(i) > 0 && cap.get(i) * 10 >= best * 6) {
                fast.append(n++ > 0 ? "," : "").append(i);
            }
        }

        // Pin only when every vCPU can have its own fast host core. Otherwise let Android's
        // scheduler distribute the TCG and native EL0 work across the whole CPU set.
        String pin = n >= 4 ? fast.toString() : "";
        String reason = n + " of " + cap.size() + " cores fast (capacity " + cap + "), fast: "
                + (fast.length() == 0 ? "none" : fast);
        return new CpuPlan(4, pin, reason + (pin.isEmpty() ? "; scheduler chooses cores" : "; pinned to fast cores"));
    }

    private static int readInt(File f) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(f.toPath())).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
