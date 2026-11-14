package org.ftctestbed.viewer.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppearancePanelTest {
    /** The panel listens for theme changes only while shown, so a closed window can be freed. */
    @Test
    void listensOnlyWhileShown() {
        int before = Appearance.listenerCount();
        AppearancePanel panel = new AppearancePanel();
        assertEquals(before, Appearance.listenerCount(), "not before it is shown");
        panel.addNotify();
        assertEquals(before + 1, Appearance.listenerCount());
        panel.removeNotify();
        assertEquals(before, Appearance.listenerCount(), "none left behind when the window closes");
    }
}
