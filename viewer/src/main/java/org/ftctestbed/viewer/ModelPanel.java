package org.ftctestbed.viewer;

import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.viewer.model.ModelConfig;
import org.ftctestbed.viewer.model.ModelData;
import org.ftctestbed.viewer.model.ModelLoader;
import org.ftctestbed.viewer.three.RobotModel;
import org.ftctestbed.viewer.three.View3D;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Model tab: import the robot's CAD model, line it up with the simulated robot, and make
 * its parts move with mechanisms, servos, motors and wheels. Saved to {@code <robot>.model.jsonc}.
 */
final class ModelPanel extends JPanel {
    private static final String[] AXES = {"+X (forward)", "-X (back)", "+Y (left)", "-Y (right)", "+Z (up)", "-Z (down)"};
    private static final double[][] AXIS_VECTORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final RobotConfig robot;
    private final Path robotFile;
    private final Path modelFile;
    private final View3D view;
    private final Consumer<String> log;

    private ModelConfig cfg;
    private ModelData data;
    private RobotModel model;
    private ModelData.Node selected;
    private int editing = -1; // index of the binding shown in the editor
    private boolean updating;
    private long buildCounter;

    private final JLabel status = new JLabel();
    private final JButton importButton = new JButton("Import CAD file…");
    private final JButton saveButton = new JButton("Save");
    private final JComboBox<String> units = new JComboBox<>(ModelConfig.UNITS.toArray(new String[0]));
    private final JCheckBox autoCenter = new JCheckBox("Auto-center on the robot", true);
    private final JSpinner[] offset = {spinner(0, -60, 60, 0.25), spinner(0, -60, 60, 0.25), spinner(0, -60, 60, 0.25)};
    private final JCheckBox outline = new JCheckBox("Show robot size + front arrow");
    private final JCheckBox showGenerated = new JCheckBox("Show stand-in robot");
    private final JTextField search = new JTextField(10);
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("model");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);
    private final Map<ModelData.Node, DefaultMutableTreeNode> treeNodes = new IdentityHashMap<>();
    private final JLabel partLabel = new JLabel("Click a part in the 3D view or the list.");
    private final JComboBox<String> source = new JComboBox<>();
    private final JComboBox<String> motion = new JComboBox<>(new String[]{"auto", "translate", "rotate"});
    private final JComboBox<String> axis = new JComboBox<>(AXES);
    private final JCheckBox customPivot = new JCheckBox("Pivot (in)");
    private final JSpinner[] pivot = {spinner(0, -60, 60, 0.1), spinner(0, -60, 60, 0.1), spinner(0, -60, 60, 0.1)};
    private final JSpinner scale = spinner(1, -100, 100, 0.05);
    private final JSpinner zero = spinner(0, -10000, 10000, 1);
    private final JSlider preview = new JSlider(0, 100, 0);
    private final JButton applyBinding = new JButton("Apply");
    private final JButton removeBinding = new JButton("Remove");
    private final DefaultListModel<String> bindingItems = new DefaultListModel<>();
    private final JList<String> bindingList = new JList<>(bindingItems);
    private final Timer rebuildTimer = new Timer(350, e -> rebuild());
    private final JPanel body = new JPanel();

    ModelPanel(RobotConfig robot, Path robotFile, View3D view, Consumer<String> log) {
        super(new BorderLayout(0, 4));
        this.robot = robot;
        this.robotFile = robotFile;
        this.modelFile = ModelConfig.fileFor(robotFile);
        this.view = view;
        this.log = log;
        rebuildTimer.setRepeats(false);

        JPanel top = new JPanel(new BorderLayout());
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topButtons.add(importButton);
        topButtons.add(saveButton);
        top.add(status, BorderLayout.NORTH);
        top.add(topButtons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(placementPanel());
        body.add(partsPanel());
        body.add(bindingPanel());
        JScrollPane scroll = new JScrollPane(body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);

        for (Component c : new Component[]{importButton, saveButton, units, autoCenter, outline, showGenerated, source, motion, axis,
                customPivot, applyBinding, removeBinding, preview}) c.setFocusable(false);
        importButton.addActionListener(e -> importCad());
        saveButton.addActionListener(e -> save());
        source.addItem("— doesn't move —");
        for (RobotConfig.Mechanism m : robot.mechanisms()) source.addItem("mechanism:" + m.name() + "  (" + m.type() + ")");
        for (RobotConfig.Servo s : robot.servos()) source.addItem("servo:" + s.name() + (s.continuous() ? "  (CR)" : ""));
        for (RobotConfig.Motor m : robot.allMotors()) source.addItem("motor:" + m.name());
        for (String w : new String[]{"frontLeft", "frontRight", "backLeft", "backRight"}) source.addItem("wheel:" + w);
        applyBinding.addActionListener(e -> applyBinding());
        removeBinding.addActionListener(e -> removeBinding());
        preview.addChangeListener(e -> {
            if (model != null && editing >= 0) model.preview(editing, preview.getValue() / 100.0);
        });
        preview.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (model != null) model.preview(-1, 0); // back to the simulated value
                preview.setValue(0);
            }
        });
        view.setPartPickListener(part -> SwingUtilities.invokeLater(() -> {
            if (part != null) selectPart(part, true);
        }));
        outline.addActionListener(e -> view.setOutlineVisible(outline.isSelected()));
        load();
    }

    private static JSpinner spinner(double value, double min, double max, double step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.setPreferredSize(new Dimension(68, s.getPreferredSize().height));
        return s;
    }

    private JComponent placementPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Line it up (front = +X, up = +Z)"));
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r1.add(new JLabel("Units"));
        r1.add(units);
        JButton rx = new JButton("Tip X 90°"), ry = new JButton("Tip Y 90°"), rz = new JButton("Turn 90°"), reset = new JButton("Reset");
        for (JButton b : new JButton[]{rx, ry, rz, reset}) {
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(1, 4, 1, 4));
        }
        rx.addActionListener(e -> rotate('x'));
        ry.addActionListener(e -> rotate('y'));
        rz.addActionListener(e -> rotate('z'));
        reset.addActionListener(e -> {
            if (cfg == null) return;
            cfg.rotations.clear();
            changed();
        });
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r2.add(rx);
        r2.add(ry);
        r2.add(rz);
        r2.add(reset);
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r3.add(autoCenter);
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r4.add(new JLabel("Nudge (in) x"));
        r4.add(offset[0]);
        r4.add(new JLabel("y"));
        r4.add(offset[1]);
        r4.add(new JLabel("z"));
        r4.add(offset[2]);
        JPanel r5 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r5.add(outline);
        r5.add(showGenerated);
        for (JPanel r : new JPanel[]{r1, r2, r3, r4, r5}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            p.add(r);
        }
        units.addActionListener(e -> {
            if (!updating && cfg != null) {
                cfg.units = (String) units.getSelectedItem();
                changed();
            }
        });
        autoCenter.addActionListener(e -> {
            if (cfg != null) {
                cfg.autoCenter = autoCenter.isSelected();
                changed();
            }
        });
        showGenerated.addActionListener(e -> {
            if (cfg != null) {
                cfg.showGenerated = showGenerated.isSelected();
                changed();
            }
        });
        for (int i = 0; i < 3; i++) {
            int k = i;
            offset[i].addChangeListener(e -> {
                if (!updating && cfg != null) {
                    cfg.offsetIn[k] = (Double) offset[k].getValue();
                    changed();
                }
            });
        }
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private JComponent partsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(BorderFactory.createTitledBorder("Parts"));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel("Find"));
        row.add(search);
        p.add(row, BorderLayout.NORTH);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> {
            if (updating) return;
            TreePath path = tree.getSelectionPath();
            if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof PartItem item) {
                selectPart(item.node, false);
            }
        });
        JScrollPane s = new JScrollPane(tree);
        s.setPreferredSize(new Dimension(380, 170));
        p.add(s, BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { findPart(); }
            @Override public void removeUpdate(DocumentEvent e) { findPart(); }
            @Override public void changedUpdate(DocumentEvent e) { findPart(); }
        });
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private JComponent bindingPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Make the selected part move"));
        JPanel r0 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r0.add(partLabel);
        JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r1.add(new JLabel("With"));
        r1.add(source);
        JPanel r2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r2.add(new JLabel("Motion"));
        r2.add(motion);
        r2.add(new JLabel("Axis"));
        r2.add(axis);
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r3.add(customPivot);
        for (JSpinner s : pivot) r3.add(s);
        JButton center = new JButton("Part center");
        center.setFocusable(false);
        center.setMargin(new java.awt.Insets(1, 4, 1, 4));
        center.addActionListener(e -> {
            if (model == null || selected == null) return;
            double[] c = model.centerIn(selected);
            for (int i = 0; i < 3; i++) pivot[i].setValue(Math.round(c[i] * 100) / 100.0);
            customPivot.setSelected(true);
        });
        r3.add(center);
        JPanel r4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        r4.add(new JLabel("Scale"));
        r4.add(scale);
        r4.add(new JLabel("Zero"));
        r4.add(zero);
        r4.add(applyBinding);
        r4.add(removeBinding);
        JPanel r5 = new JPanel(new BorderLayout(4, 0));
        r5.add(new JLabel("Try it"), BorderLayout.WEST);
        r5.add(preview, BorderLayout.CENTER);
        JLabel help = new JLabel("<html><body style='width: 300px'><small>Scale: e.g. 0.5 for the middle stage of a slide. "
                + "Zero: the device value the CAD shows (slide inches, or degrees). Axes and pivot are robot coordinates "
                + "(x forward, y left, z up).</small></body></html>");
        bindingList.setVisibleRowCount(5);
        bindingList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updating || cfg == null) return;
            int i = bindingList.getSelectedIndex();
            if (i < 0 || i >= cfg.bindings.size()) return;
            ModelData.Node n = findNode(cfg.bindings.get(i).node);
            if (n != null) selectPart(n, true);
        });
        JScrollPane listScroll = new JScrollPane(bindingList);
        listScroll.setBorder(BorderFactory.createTitledBorder("Moving parts"));
        for (JComponent r : new JComponent[]{r0, r1, r2, r3, r4, r5, help, listScroll}) {
            r.setAlignmentX(LEFT_ALIGNMENT);
            p.add(r);
        }
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    // ------------------------------------------------------------------ loading / building

    private void load() {
        if (!Files.exists(modelFile)) {
            cfg = null;
            status.setText("<html>No 3D model yet. Import your robot's CAD export (GLB/glTF from Onshape is best; STL and OBJ work).</html>");
            setEditable(false);
            return;
        }
        try {
            cfg = ModelConfig.load(modelFile);
        } catch (Exception e) {
            status.setText("<html>Can't read " + modelFile.getFileName() + ": " + e.getMessage() + "</html>");
            setEditable(false);
            return;
        }
        status.setText("Loading " + cfg.file + "…");
        Path file = robotFile.resolveSibling(cfg.file);
        new Thread(() -> {
            try {
                ModelData d = ModelLoader.load(file);
                SwingUtilities.invokeLater(() -> {
                    data = d;
                    for (String w : d.warnings) log.accept("Model: " + w);
                    fillTree();
                    showConfig();
                    setEditable(true);
                    rebuild();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    status.setText("<html>Can't load " + file.getFileName() + ": " + e.getMessage() + "</html>");
                    log.accept("Could not load the 3D model " + file + ": " + e.getMessage());
                });
            }
        }, "model loader").start();
    }

    private void setEditable(boolean on) {
        for (Component c : new Component[]{saveButton, units, autoCenter, outline, showGenerated, search, tree, source, motion, axis,
                customPivot, scale, zero, applyBinding, removeBinding, preview, bindingList}) c.setEnabled(on);
        for (JSpinner s : offset) s.setEnabled(on);
        for (JSpinner s : pivot) s.setEnabled(on);
    }

    private void showConfig() {
        updating = true;
        units.setSelectedItem(cfg.units);
        autoCenter.setSelected(cfg.autoCenter);
        showGenerated.setSelected(cfg.showGenerated);
        for (int i = 0; i < 3; i++) offset[i].setValue(cfg.offsetIn[i]);
        bindingItems.clear();
        for (ModelConfig.Binding b : cfg.bindings) {
            bindingItems.addElement(b.node + "  ←  " + b.source + " " + b.device + (b.scale != 1 ? " ×" + b.scale : ""));
        }
        updating = false;
    }

    private void changed() {
        showConfig();
        rebuildTimer.restart();
    }

    /** Rebuilds the 3D model from the current settings (in the background) and shows it. */
    private void rebuild() {
        if (cfg == null || data == null) return;
        ModelConfig snapshot = cfg.copy();
        long id = ++buildCounter;
        String units0 = cfg.units.equals("auto") ? ModelConfig.detectUnits(data) + " (detected)" : cfg.units;
        new Thread(() -> {
            RobotModel m = new RobotModel(data, snapshot, robot);
            SwingUtilities.invokeLater(() -> {
                if (id != buildCounter) return; // a newer build is on its way
                model = m;
                view.setModel(m);
                double[] b = m.boundsMeters();
                status.setText(String.format(Locale.US, "<html>%s: %,d triangles, units %s, size %.1f × %.1f × %.1f in%s</html>",
                        cfg.file, data.triangles(), units0, (b[3] - b[0]) / 0.0254, (b[4] - b[1]) / 0.0254, (b[5] - b[2]) / 0.0254,
                        m.problems().isEmpty() ? "" : "<br><font color='#B00020'>" + String.join("<br>", m.problems()) + "</font>"));
                if (selected != null) m.select(selected);
            });
        }, "model builder").start();
    }

    private void fillTree() {
        treeRoot.removeAllChildren();
        treeNodes.clear();
        addTree(treeRoot, data.root);
        treeModel.reload();
        for (int i = 0; i < Math.min(tree.getRowCount(), 3); i++) tree.expandRow(i);
    }

    private void addTree(DefaultMutableTreeNode parent, ModelData.Node n) {
        DefaultMutableTreeNode t = new DefaultMutableTreeNode(new PartItem(n));
        treeNodes.put(n, t);
        parent.add(t);
        for (ModelData.Node c : n.children) addTree(t, c);
    }

    private record PartItem(ModelData.Node node) {
        @Override
        public String toString() {
            return node.name.isEmpty() ? "(unnamed)" : node.name;
        }
    }

    // ------------------------------------------------------------------ selection and bindings

    private void selectPart(ModelData.Node n, boolean fromView) {
        selected = n;
        if (model != null) model.select(n);
        DefaultMutableTreeNode t = treeNodes.get(n);
        if (t != null && fromView) {
            updating = true;
            TreePath path = new TreePath(t.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
            updating = false;
        }
        partLabel.setText("Part: " + (n.name.isEmpty() ? "(unnamed)" : n.name));
        editing = -1;
        for (int i = 0; i < cfg.bindings.size(); i++) if (cfg.bindings.get(i).node.equals(n.name)) editing = i;
        updating = true;
        if (editing >= 0) {
            ModelConfig.Binding b = cfg.bindings.get(editing);
            for (int i = 0; i < source.getItemCount(); i++) {
                if (source.getItemAt(i).startsWith(b.source + ":" + b.device)) source.setSelectedIndex(i);
            }
            motion.setSelectedItem(b.motion);
            axis.setSelectedIndex(b.axis == null ? (b.motion.equals("translate") ? 4 : 2) : axisIndex(b.axis));
            customPivot.setSelected(b.pivot != null);
            for (int i = 0; i < 3; i++) pivot[i].setValue(b.pivot == null ? 0.0 : b.pivot[i]);
            scale.setValue(b.scale);
            zero.setValue(b.zero);
            bindingList.setSelectedIndex(editing);
        } else {
            source.setSelectedIndex(0);
            motion.setSelectedItem("auto");
            customPivot.setSelected(false);
            scale.setValue(1.0);
            zero.setValue(0.0);
            bindingList.clearSelection();
        }
        updating = false;
    }

    private static int axisIndex(double[] v) {
        for (int i = 0; i < AXIS_VECTORS.length; i++) {
            if (Math.abs(AXIS_VECTORS[i][0] - v[0]) + Math.abs(AXIS_VECTORS[i][1] - v[1]) + Math.abs(AXIS_VECTORS[i][2] - v[2]) < 1e-6) return i;
        }
        return 4;
    }

    private void applyBinding() {
        if (cfg == null || selected == null) return;
        if (source.getSelectedIndex() <= 0) {
            removeBinding();
            return;
        }
        String item = (String) source.getSelectedItem();
        String key = item.contains("  ") ? item.substring(0, item.indexOf("  ")) : item;
        ModelConfig.Binding b = editing >= 0 ? cfg.bindings.get(editing) : new ModelConfig.Binding();
        b.node = selected.name;
        b.source = key.substring(0, key.indexOf(':'));
        b.device = key.substring(key.indexOf(':') + 1);
        b.motion = (String) motion.getSelectedItem();
        b.axis = AXIS_VECTORS[axis.getSelectedIndex()].clone();
        b.pivot = customPivot.isSelected() ? new double[]{(Double) pivot[0].getValue(), (Double) pivot[1].getValue(), (Double) pivot[2].getValue()} : null;
        b.scale = (Double) scale.getValue();
        b.zero = (Double) zero.getValue();
        if (selected.name.isEmpty()) {
            log.accept("This part has no name in the CAD file, so it can't be bound. Name it in CAD (or pick its parent).");
            return;
        }
        if (editing < 0) {
            cfg.bindings.add(b);
            editing = cfg.bindings.size() - 1;
        }
        changed();
        log.accept("\"" + b.node + "\" now moves with " + b.source + " " + b.device + ". Use \"Try it\" to check, then Save.");
    }

    private void removeBinding() {
        if (cfg == null || editing < 0) return;
        cfg.bindings.remove(editing);
        editing = -1;
        changed();
    }

    private void rotate(char axisName) {
        if (cfg == null) return;
        List<ModelConfig.Rotation> r = cfg.rotations;
        // Fold repeated turns about the same axis into one.
        if (!r.isEmpty() && r.get(r.size() - 1).axis() == axisName) {
            double d = (r.get(r.size() - 1).degrees() + 90) % 360;
            r.remove(r.size() - 1);
            if (d != 0) r.add(new ModelConfig.Rotation(axisName, d));
        } else {
            r.add(new ModelConfig.Rotation(axisName, 90));
        }
        changed();
    }

    private void findPart() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || data == null) return;
        List<ModelData.Node> hits = new ArrayList<>();
        data.root.forEach(n -> {
            if (n.name.toLowerCase(Locale.ROOT).contains(q)) hits.add(n);
        });
        if (!hits.isEmpty()) selectPart(hits.get(0), true);
    }

    private ModelData.Node findNode(String name) {
        ModelData.Node[] hit = {null};
        if (data != null) data.root.forEach(n -> {
            if (hit[0] == null && n.name.equals(name)) hit[0] = n;
        });
        return hit[0];
    }

    private void save() {
        if (cfg == null) return;
        try {
            cfg.save(modelFile);
            log.accept("Saved " + modelFile.getFileName());
        } catch (Exception e) {
            log.accept("Could not save " + modelFile + ": " + e.getMessage());
        }
    }

    /** Debugging/docs: selects a part by name and renders the whole tab (unscrolled) to an image. */
    java.awt.image.BufferedImage debugRender(String partName) {
        ModelData.Node n = partName == null ? null : findNode(partName);
        if (n != null) selectPart(n, true);
        JPanel all = new JPanel(new BorderLayout());
        java.awt.Dimension top = getComponent(0).getPreferredSize(), mid = body.getPreferredSize();
        int w = Math.max(getWidth(), 420), h = top.height + mid.height + 8;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(getBackground());
        g.fillRect(0, 0, w, h);
        java.awt.Component header = getComponent(0);
        header.setSize(w, top.height);
        header.paint(g);
        g.translate(0, top.height + 4);
        java.awt.Container parent = body.getParent();
        body.setSize(w, mid.height);
        body.doLayout();
        for (Component c : body.getComponents()) c.doLayout();
        body.paint(g);
        g.dispose();
        if (parent != null) parent.doLayout();
        return img;
    }

    // ------------------------------------------------------------------ import

    private void importCad() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Robot CAD export");
        chooser.setFileFilter(new FileNameExtensionFilter("3D models (GLB, glTF, STL, OBJ)", ModelLoader.EXTENSIONS.toArray(new String[0])));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path chosen = chooser.getSelectedFile().toPath();
        try {
            Path target = importModel(chosen, robotFile);
            log.accept("Imported " + chosen.getFileName() + " → " + target);
            outline.setSelected(true);
            view.setOutlineVisible(true);
            load();
        } catch (Exception e) {
            log.accept("Import failed: " + e.getMessage());
            javax.swing.JOptionPane.showMessageDialog(this, e.getMessage(), "Import failed", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Copies a CAD file (and the files it needs) next to the robot config and writes a model
     * config for it. Returns the model config file.
     */
    static Path importModel(Path cadFile, Path robotFile) throws Exception {
        ModelData check = ModelLoader.load(cadFile); // fail early on files we can't read
        Path dir = robotFile.toAbsolutePath().getParent();
        Path srcDir = cadFile.toAbsolutePath().getParent();
        if (!srcDir.equals(dir)) {
            Files.copy(cadFile, dir.resolve(cadFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            for (Path c : ModelLoader.companions(cadFile)) {
                Path rel = srcDir.relativize(c.toAbsolutePath());
                Path to = dir.resolve(rel);
                Files.createDirectories(to.getParent());
                if (Files.exists(c)) Files.copy(c, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        ModelConfig cfg = new ModelConfig();
        cfg.file = cadFile.getFileName().toString();
        // glTF is Y-up; CAD (and so STL/OBJ exports) is usually Z-up.
        if (check.units.equals("m")) cfg.rotations.add(new ModelConfig.Rotation('x', 90));
        Path modelFile = ModelConfig.fileFor(robotFile);
        cfg.save(modelFile);
        return modelFile;
    }
}
