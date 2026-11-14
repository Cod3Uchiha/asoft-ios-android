package com.asoft.ios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The project's list of tested phones (devices.json at the root of the app's repository), and the question
 * "is this phone on it". Pure: no Android in here (TestedPhonesTest).
 *
 * A row has a SoC (empty = any), a GPU name or, in a row without a SoC, a GPU family ("Mali": every Mali), the
 * guests it is about (empty = all of them: "ios26", "ios16", "watch") and a state, "works" or "fails". The device key
 * of a phone is its SoC, plus its GPU for iOS 26 and the watch; iOS 16 does not use the GPU, so its key is the SoC.
 */
final class TestedPhones {
    private TestedPhones() {}

    static final String URL = "https://raw.githubusercontent.com/alpersamur3/asoft-ios-android/main/devices.json";

    static final String IOS26 = "ios26", IOS16 = "ios16", WATCH = "watch";

    enum Verdict { NONE, WORKS, FAILS }

    static final class Entry {
        final String soc, gpu, phone, note;
        final boolean works;
        final List<String> os;

        Entry(String soc, String gpu, List<String> os, boolean works, String phone, String note) {
            this.soc = soc;
            this.gpu = gpu;
            this.os = os;
            this.works = works;
            this.phone = phone;
            this.note = note;
        }
    }

    /** Case, "(TM)" and spaces do not count: "Adreno (TM) 830" is "adreno 830" is "Adreno830". */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replace("(tm)", "").replace("(r)", "").replaceAll("\\s+", "");
    }

    /**
     * The rows of @json; null when it is no list at all (not JSON, no "devices" array). Unknown fields are
     * ignored, and a row that is not an object, has no SoC and no GPU, or a state other than works/fails is skipped.
     */
    static List<Entry> parse(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) {
            return null;
        }
        Object d = ((Map<?, ?>) root).get("devices");
        if (!(d instanceof List)) {
            return null;
        }
        List<Entry> out = new ArrayList<>();
        for (Object o : (List<?>) d) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String state = MiniJson.str(m, "state").trim().toLowerCase(Locale.ROOT);
            String soc = MiniJson.str(m, "soc").trim(), gpu = MiniJson.str(m, "gpu").trim();
            if (!state.equals("works") && !state.equals("fails") || soc.isEmpty() && gpu.isEmpty()) {
                continue;
            }
            List<String> os = new ArrayList<>();
            if (m.get("os") instanceof List) {
                for (Object g : (List<?>) m.get("os")) {
                    if (g instanceof String) {
                        os.add(((String) g).trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            out.add(new Entry(soc, gpu, os, state.equals("works"), MiniJson.str(m, "phone"), MiniJson.str(m, "note")));
        }
        return out;
    }

    private static boolean matches(Entry e, String soc, String gpu, String guest) {
        if (!e.os.isEmpty() && !e.os.contains(guest)) {
            return false;
        }
        boolean needGpu = !IOS16.equals(guest);
        if (!e.soc.isEmpty()) {
            // a row with a SoC is about that SoC: its GPU only has to be the same name where the guest uses one
            return !norm(soc).isEmpty() && norm(e.soc).equals(norm(soc))
                    && (!needGpu || e.gpu.isEmpty() || norm(e.gpu).equals(norm(gpu)));
        }
        // a row without a SoC is a GPU family ("Mali", "PowerVR"): any GPU whose name has it in; iOS 16 has no GPU key
        return needGpu && !norm(gpu).isEmpty() && norm(gpu).contains(norm(e.gpu));
    }

    /** What the list says of this phone for @guest; works wins over fails when rows of both kinds match. */
    static Verdict lookup(List<Entry> list, String soc, String gpu, String guest) {
        Verdict v = Verdict.NONE;
        if (list == null) {
            return v;
        }
        for (Entry e : list) {
            if (matches(e, soc, gpu, guest)) {
                if (e.works) {
                    return Verdict.WORKS;
                }
                v = Verdict.FAILS;
            }
        }
        return v;
    }
}
