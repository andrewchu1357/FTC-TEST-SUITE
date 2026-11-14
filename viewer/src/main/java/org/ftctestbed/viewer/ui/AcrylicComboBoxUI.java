package org.ftctestbed.viewer.ui;

import com.formdev.flatlaf.ui.FlatComboBoxUI;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * FlatLaf's combo box with its drop-down list on acrylic, like the app's menus (Windows 11
 * draws both as flyouts): the window shows through blurred, captured once as the list opens
 * (see Acrylic), and the list's rows are see-through over it. Installed for every combo box by
 * Appearance.
 */
public class AcrylicComboBoxUI extends FlatComboBoxUI {
    private static final Color CLEAR = new Color(0, 0, 0, 0);

    public static ComponentUI createUI(JComponent c) {
        return new AcrylicComboBoxUI();
    }

    @Override
    protected ComboPopup createPopup() {
        return new AcrylicPopup(comboBox);
    }

    /** The drop-down list, on acrylic. */
    protected class AcrylicPopup extends FlatComboPopup {
        private BufferedImage backdrop;
        private Point screen;

        @SuppressWarnings("rawtypes")
        protected AcrylicPopup(JComboBox combo) {
            super(combo);
        }

        @Override
        public void setLocation(int x, int y) {
            screen = new Point(x, y); // where it'll open (after Swing moved it to fit on the screen)
            super.setLocation(x, y);
        }

        @Override
        public void setVisible(boolean b) {
            if (b && !isVisible()) {
                // The list and its scroll pane show the acrylic through them; selected rows keep
                // their highlight (FlatLaf's, in the theme's selection color).
                scroller.setOpaque(false);
                scroller.getViewport().setOpaque(false);
                list.setOpaque(false);
                list.setBackground(CLEAR);
                backdrop = AcrylicMenu.capture(this, screen);
            }
            super.setVisible(b);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Acrylic.paint((Graphics2D) g, new Rectangle(0, 0, getWidth(), getHeight()), backdrop);
        }
    }
}
