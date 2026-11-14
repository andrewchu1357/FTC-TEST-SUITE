package org.ftctestbed.viewer.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

/** A panel drawn as a Fluent material (see Fluent.Material) with rounded corners and an outline. */
public class Surface extends JPanel {
    private final Fluent.Material material;
    private final int arc;

    /** arc: corner diameter; padding: space between the edge and what's inside. */
    public Surface(Fluent.Material material, int arc, int padding, LayoutManager layout) {
        super(layout);
        this.material = material;
        this.arc = arc;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
    }

    public Fluent.Material material() {
        return material;
    }

    public int arc() {
        return arc;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Fluent.paint((Graphics2D) g, Fluent.round(getWidth(), getHeight(), arc), material);
    }
}
