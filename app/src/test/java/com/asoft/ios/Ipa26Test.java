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
    public void signedFilesAreListedNotWarnedAbout() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ipa-signed Frameworks/libX.dylib SHA-1_only", r, fail);
        Ipa26.line26("ipa-signed Frameworks/U.framework/U its_code_pages_do_not_match_its_signature", r, fail);
        r.app = "A.app";
        String text = Ipa26.summary(r);
        assertTrue(text.contains("2 files of this app had no usable signature and was signed again on this phone: "
                + "Frameworks/libX.dylib (SHA-1 only), Frameworks/U.framework/U (its code pages do not match its signature)."));
        assertTrue(!text.contains("will refuse"));
    }

    @Test
    public void unsignableFileFailsWithItsName() {
        Ipa26.Result r = new Ipa26.Result();
        String[] fail = {null};
        Ipa26.line26("ipa-fail Frameworks/libX.dylib: cannot be given a signature iOS 26 accepts: it is still encrypted", r, fail);
        assertTrue(fail[0].startsWith("Frameworks/libX.dylib: cannot be given"));
    }
}
