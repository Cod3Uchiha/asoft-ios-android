package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Ipa26Test {
    @Test
    public void replacedLineNamesTheOldApp() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ASOFT: ipa-replaced AngryBirds2.app com.rovio.baba 25.0".substring(6), r, fail);
        Ipa26.line26("ipa-new 1", r, fail);
        assertEquals("AngryBirds2.app (was com.rovio.baba 25.0)", r.replaced);
        r.app = "AngryBirds2.app";
        assertTrue(Ipa26.summary(r).startsWith("Replaced AngryBirds2.app (was com.rovio.baba 25.0) on iOS 26's disk."));
    }

    @Test
    public void refusalMessageIsKept() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ipa-fail iOS 26 has an app called X.app of its own; this .ipa cannot replace it", r, fail);
        assertEquals("iOS 26 has an app called X.app of its own; this .ipa cannot replace it", fail[0]);
    }

    @Test
    public void plainInstallSaysSo() {
        Ipa26.Result r = new Ipa26.Result();
        r.app = "A.app";
        assertTrue(Ipa26.summary(r).startsWith("A.app is on iOS 26's disk."));
    }

    @Test
    public void signedFilesAreCountedInOneLine() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        for (int i = 0; i < 26; i++) {
            Ipa26.line26("ipa-signed Frameworks/F" + i + ".framework/F" + i + " its_code_pages_do_not_match_its_signature", r, fail);
        }
        Ipa26.line26("ipa-ents-dropped Frameworks/iGameGod.framework/iGameGod 40", r, fail);
        r.app = "A.app";
        String text = Ipa26.summary(r);
        assertEquals(26, r.signed.size());
        assertEquals("Frameworks/F0.framework/F0 (its code pages do not match its signature)", r.signed.get(0));
        assertTrue(text.contains("\n26 files of this app were signed again on this phone so that iOS 26 accepts them. "
                + "The entitlements of 1 library were left out"));
        assertTrue(!text.contains("Frameworks/F0"));
        assertTrue(!text.contains("will refuse"));
    }

    @Test
    public void unsignableFileFailsWithItsName() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ipa-fail Frameworks/libX.dylib: cannot be given a signature iOS 26 accepts: it is still encrypted", r, fail);
        assertTrue(fail[0].startsWith("Frameworks/libX.dylib: cannot be given"));
    }

    @Test
    public void addedEntitlementIsLogged() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ipa-ent Game com.apple.private.set-exception-port", r, fail);
        Ipa26.line26("ipa-ent PlugIns/Share.appex/Share com.apple.private.set-exception-port", r, fail);
        assertEquals(2, r.entsAdded.size());
        assertEquals("Game com.apple.private.set-exception-port", r.entsAdded.get(0));
        assertTrue(fail[0] == null);
    }

    @Test
    public void ipaEntLinesOfTheTestFile() {
        java.util.List<String> l = java.util.Arrays.asList("something=1", " ipa-ent=com.example.x ", "ipa-ent=", "ipa-ent-default=off");
        assertEquals("com.example.x\n-default\n", Ipa26.entLines(l));
        assertEquals("", Ipa26.entLines(java.util.Arrays.asList("other")));
    }
}
