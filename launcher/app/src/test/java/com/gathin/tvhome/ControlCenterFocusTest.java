package com.gathin.tvhome;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * A focused Control Center tile turns white. Its labels and icons take the tvos_tile_* colour selectors, which
 * switch to dark only while the view itself is focused. The tile holds the focus, so each of them must copy the
 * tile's state with duplicateParentState, or it stays white on white.
 */
public class ControlCenterFocusTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String LAYOUT = "src/main/res/layout/popup_control_center.xml";

    @Test public void focusDependentColoursFollowTheTileFocus() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<Element> views = stateColouredViews();
        assertTrue("guard: found only " + views.size() + " state-coloured views", views.size() >= 20);
        for (Element view : views) {
            if (!"true".equals(attr(view, "duplicateParentState"))) offenders.add(describe(view));
        }
        assertTrue("views whose focus colour does not follow the tile: " + offenders, offenders.isEmpty());
    }

    /** TextViews with a tvos_tile_* textColor and ImageViews with a tvos_tile_* tint. */
    private static List<Element> stateColouredViews() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(new File(LAYOUT));
        NodeList all = doc.getElementsByTagName("*");
        List<Element> views = new ArrayList<>();
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (e.getTagName().equals("TextView") && attr(e, "textColor").startsWith("@color/tvos_tile_")) views.add(e);
            if (e.getTagName().equals("ImageView") && attr(e, "tint").startsWith("@color/tvos_tile_")) views.add(e);
        }
        return views;
    }

    private static String describe(Element e) {
        String id = attr(e, "id");
        if (!id.isEmpty()) return e.getTagName() + " " + id;
        String text = attr(e, "text");
        if (!text.isEmpty()) return e.getTagName() + " \"" + text + "\"";
        return e.getTagName() + " " + attr(e, "src");
    }

    private static String attr(Element e, String name) {
        return e.getAttributeNS(ANDROID_NS, name);
    }
}
