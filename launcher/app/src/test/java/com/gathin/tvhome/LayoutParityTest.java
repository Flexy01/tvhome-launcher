package com.gathin.tvhome;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * layout-iw must define every view MainActivity looks up on the activity itself. A missing one makes
 * findViewById return null, and the first use of it throws.
 */
public class LayoutParityTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String DEFAULT_LAYOUT = "src/main/res/layout/activity_main.xml";
    private static final String HEBREW_LAYOUT = "src/main/res/layout-iw/activity_main.xml";
    private static final String ACTIVITY = "src/main/java/com/gathin/tvhome/MainActivity.java";

    // A bare findViewById(R.id.x) searches the activity's content view. Qualified calls such as
    // panel.findViewById(...) or row.findViewById(...) search other layouts, so they are excluded.
    private static final Pattern ACTIVITY_LOOKUP = Pattern.compile("(?<![.\\w])findViewById\\(R\\.id\\.(\\w+)\\)");

    @Test public void activityLookupsExistInDefaultLayout() throws Exception {
        Set<String> required = activityLookups();
        assertTrue("lookup pattern found too few ids: " + required, required.contains("stats") && required.size() > 10);
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(viewsById(DEFAULT_LAYOUT).keySet());
        assertEquals("default layout lacks ids MainActivity uses", new TreeSet<String>(), missing);
    }

    @Test public void activityLookupsExistInHebrewLayout() throws Exception {
        Set<String> required = activityLookups();
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(viewsById(HEBREW_LAYOUT).keySet());
        assertEquals("layout-iw lacks ids MainActivity uses", new TreeSet<String>(), missing);
    }

    @Test public void statsMatchesDefaultLayoutInHebrew() throws Exception {
        Element def = viewsById(DEFAULT_LAYOUT).get("stats");
        Element iw = viewsById(HEBREW_LAYOUT).get("stats");
        assertNotNull("layout-iw has no stats view", iw);
        assertEquals("view type", def.getTagName(), iw.getTagName());
        assertEquals("start visibility", "gone", attr(iw, "visibility"));
        assertEquals("visibility", attr(def, "visibility"), attr(iw, "visibility"));
        assertEquals("layout_below", attr(def, "layout_below"), attr(iw, "layout_below"));
        assertEquals("layout_width", attr(def, "layout_width"), attr(iw, "layout_width"));
    }

    private static Set<String> activityLookups() throws Exception {
        String source = new String(Files.readAllBytes(new File(ACTIVITY).toPath()), StandardCharsets.UTF_8);
        Set<String> ids = new TreeSet<>();
        Matcher m = ACTIVITY_LOOKUP.matcher(source);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    private static Map<String, Element> viewsById(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(new File(path));
        Map<String, Element> views = new HashMap<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String id = e.getAttributeNS(ANDROID_NS, "id");
            if (id.isEmpty()) continue;
            views.put(id.replaceFirst("^@\\+?id/", ""), e);
        }
        return views;
    }

    private static String attr(Element e, String name) {
        return e.getAttributeNS(ANDROID_NS, name);
    }
}
