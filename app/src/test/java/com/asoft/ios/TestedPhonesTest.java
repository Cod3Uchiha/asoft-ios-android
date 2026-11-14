package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class TestedPhonesTest {
    /** The seed of devices.json at the repository root, as the app fetches it. */
    static final String SEED = "{\"version\":1,\"devices\":["
            + "{\"soc\":\"SM8750\",\"gpu\":\"Adreno (TM) 830\",\"os\":[\"ios26\",\"ios16\",\"watch\"],\"state\":\"works\",\"phone\":\"Samsung SM-S938B\",\"note\":\"\"},"
            + "{\"soc\":\"\",\"gpu\":\"Mali\",\"os\":[\"ios26\",\"watch\"],\"state\":\"fails\",\"phone\":\"\",\"note\":\"no host memory\"},"
            + "{\"soc\":\"\",\"gpu\":\"PowerVR\",\"os\":[\"ios26\",\"watch\"],\"state\":\"fails\",\"phone\":\"\",\"note\":\"\"},"
            + "{\"soc\":\"\",\"gpu\":\"Xclipse\",\"os\":[\"ios26\",\"watch\"],\"state\":\"fails\",\"phone\":\"\",\"note\":\"\"}]}";

    @Test
    public void normIgnoresCaseTmAndSpaces() {
        assertEquals(TestedPhones.norm("adreno 830"), TestedPhones.norm("Adreno (TM) 830"));
        assertEquals("adreno830", TestedPhones.norm(" ADRENO(TM)  830 "));
        assertEquals("", TestedPhones.norm(null));
    }

    @Test
    public void parsesAGoodFile() {
        List<TestedPhones.Entry> l = TestedPhones.parse(SEED);
        assertNotNull(l);
        assertEquals(4, l.size());
        assertEquals("SM8750", l.get(0).soc);
        assertTrue(l.get(0).works);
        assertEquals(3, l.get(0).os.size());
        assertEquals("Samsung SM-S938B", l.get(0).phone);
    }

    @Test
    public void aPartialFileKeepsWhatIsUsableAndIgnoresUnknownFields() {
        String json = "{\"version\":2,\"extra\":{\"a\":[1,2]},\"devices\":["
                + "{\"soc\":\"SM8650\",\"gpu\":\"Adreno 750\",\"state\":\"works\",\"new\":true},"
                + "5, \"x\", {\"soc\":\"\",\"gpu\":\"\",\"state\":\"works\"},"
                + "{\"soc\":\"X1\",\"state\":\"maybe\"},"
                + "{\"gpu\":\"Mali\",\"state\":\"FAILS\",\"os\":[\"iOS26\", 3]}]}";
        List<TestedPhones.Entry> l = TestedPhones.parse(json);
        assertNotNull(l);
        assertEquals(2, l.size());
        assertTrue(l.get(0).os.isEmpty());
        assertEquals("ios26", l.get(1).os.get(0));
        assertEquals(1, l.get(1).os.size());
    }

    @Test
    public void aBrokenFileIsNoList() {
        assertNull(TestedPhones.parse(null));
        assertNull(TestedPhones.parse(""));
        assertNull(TestedPhones.parse("<html>404</html>"));
        assertNull(TestedPhones.parse(SEED.substring(0, SEED.length() - 7)));
        assertNull(TestedPhones.parse("[]"));
        assertNull(TestedPhones.parse("{\"devices\":{}}"));
        assertNull(TestedPhones.parse("{\"devices\":[]} trailing"));
        // an empty list is a list
        assertEquals(0, TestedPhones.parse("{\"devices\":[]}").size());
    }

    @Test
    public void exactRowMatchesIgnoringSpellingOfTheGpu() {
        List<TestedPhones.Entry> l = TestedPhones.parse(SEED);
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "sm8750", "adreno 830", "ios26"));
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "SM8750", "Adreno (TM) 830", "watch"));
        // a neighbour chip is not that row
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "SM8650", "Adreno (TM) 750", "ios26"));
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "SM8750", "Adreno (TM) 8300", "ios26"));
    }

    @Test
    public void ios16KeyIsTheSocAlone() {
        List<TestedPhones.Entry> l = TestedPhones.parse(SEED);
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "SM8750", "", "ios16"));
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "SM8750", "something else", "ios16"));
        // a GPU family row does not speak for iOS 16: it uses no GPU
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "MT6985", "Mali-G720", "ios16"));
    }

    @Test
    public void familyRowsMatchAnyGpuOfTheFamily() {
        List<TestedPhones.Entry> l = TestedPhones.parse(SEED);
        assertEquals(TestedPhones.Verdict.FAILS, TestedPhones.lookup(l, "MT6985", "Mali-G720 Immortalis", "ios26"));
        assertEquals(TestedPhones.Verdict.FAILS, TestedPhones.lookup(l, "MT6985", "ARM Mali-G715", "watch"));
        assertEquals(TestedPhones.Verdict.FAILS, TestedPhones.lookup(l, "GS201", "Imagination PowerVR BXM-8-256", "ios26"));
        assertEquals(TestedPhones.Verdict.FAILS, TestedPhones.lookup(l, "S5E9945", "Samsung Xclipse 940", "ios26"));
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "MT6985", "", "ios26"));
    }

    @Test
    public void aRowOfOtherGuestsDoesNotCount() {
        List<TestedPhones.Entry> l = TestedPhones.parse(
                "{\"devices\":[{\"soc\":\"SM8650\",\"gpu\":\"Adreno 750\",\"os\":[\"ios16\"],\"state\":\"works\"}]}");
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "SM8650", "", "ios16"));
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "SM8650", "Adreno 750", "ios26"));
    }

    @Test
    public void unknownSocNeverMatchesASocRow() {
        List<TestedPhones.Entry> l = TestedPhones.parse(SEED);
        assertEquals(TestedPhones.Verdict.NONE, TestedPhones.lookup(l, "", "Adreno (TM) 830", "ios26"));
    }

    @Test
    public void worksWinsOverFails() {
        List<TestedPhones.Entry> l = TestedPhones.parse("{\"devices\":["
                + "{\"gpu\":\"Mali\",\"state\":\"fails\"},{\"soc\":\"MT1\",\"gpu\":\"Mali-G1\",\"state\":\"works\"}]}");
        assertEquals(TestedPhones.Verdict.WORKS, TestedPhones.lookup(l, "MT1", "Mali-G1", "ios26"));
    }
}
