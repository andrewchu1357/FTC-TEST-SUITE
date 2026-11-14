package org.ftctestbed.viewer;

import org.ftctestbed.sim.config.PartsCatalog;
import org.ftctestbed.sim.config.RobotConfig;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Browse the parts catalog (built-in + the team's config/parts files), see which parts the
 * current robot uses, and copy the robot.jsonc snippet for a part.
 */
final class PartsPanel extends JPanel {
    private static final String[] CATEGORY_LABELS = {"All parts", "Motors", "Servos", "Wheels", "Slide kits", "Odometry pods", "Sensors"};
    private static final Font MONO = org.ftctestbed.viewer.platform.Os.mono(Font.PLAIN, 12);

    private final PartsCatalog catalog;
    private final Path projectDir;
    private final Consumer<String> log;
    private final JComboBox<String> category = new JComboBox<>(CATEGORY_LABELS);
    private final JTextField search = new JTextField(12);
    private final PartsModel model = new PartsModel();
    private final JTable table = new JTable(model);
    private final JTextArea detail = new JTextArea(5, 30);
    private String snippet = "";

    PartsPanel(RobotConfig robot, Path projectDir, Consumer<String> log) {
        super(new BorderLayout(0, 4));
        this.catalog = robot.catalog();
        this.projectDir = projectDir;
        this.log = log;

        JTextArea onRobot = new JTextArea(usedSummary(robot));
        onRobot.setEditable(false);
        onRobot.setFont(MONO.deriveFont(11f));
        onRobot.setLineWrap(true);
        onRobot.setWrapStyleWord(true);
        JScrollPane onRobotScroll = new JScrollPane(onRobot);
        onRobotScroll.setBorder(BorderFactory.createTitledBorder("On this robot"));

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        category.setFocusable(false);
        filters.add(category);
        filters.add(new JLabel("Search"));
        filters.add(search);
        category.addActionListener(e -> refilter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refilter(); }
            @Override public void removeUpdate(DocumentEvent e) { refilter(); }
            @Override public void changedUpdate(DocumentEvent e) { refilter(); }
        });

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        int[] widths = {170, 55, 230, 60};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });

        detail.setEditable(false);
        detail.setFont(MONO.deriveFont(11f));
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setText("Select a part to see its details and how to use it in robot.jsonc.");

        JButton copy = new JButton("Copy snippet");
        copy.addActionListener(e -> {
            if (snippet.isEmpty()) return;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(snippet), null);
            log.accept("Copied to the clipboard:\n" + snippet);
        });
        JButton newFile = new JButton("Add your own parts…");
        newFile.addActionListener(e -> openTeamPartsFile());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : new JButton[]{copy, newFile}) {
            b.setFocusable(false);
            buttons.add(b);
        }
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JScrollPane(detail), BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel catalogPanel = new JPanel(new BorderLayout(0, 4));
        catalogPanel.add(filters, BorderLayout.NORTH);
        catalogPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        catalogPanel.add(south, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, onRobotScroll, catalogPanel);
        split.setResizeWeight(0.15);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        refilter();
    }

    private static String usedSummary(RobotConfig robot) {
        Map<String, List<String>> byPart = new LinkedHashMap<>();
        Map<String, PartsCatalog.Part> parts = new LinkedHashMap<>();
        for (RobotConfig.PartUse u : robot.partsUsed()) {
            byPart.computeIfAbsent(u.category() + "/" + u.partId(), k -> new ArrayList<>()).add(u.device());
            parts.put(u.category() + "/" + u.partId(), robot.catalog().find(u.category(), u.partId()));
        }
        if (byPart.isEmpty()) return "This robot's config doesn't name any catalog parts yet. Give a device a \"type\" "
                + "(or the drivetrain a \"wheel\", a slide a \"kit\") to use a part's specs.";
        StringBuilder b = new StringBuilder();
        double knownMass = 0;
        for (Map.Entry<String, List<String>> e : byPart.entrySet()) {
            PartsCatalog.Part p = parts.get(e.getKey());
            List<String> devices = e.getValue();
            boolean wheels = devices.size() == 1 && devices.get(0).startsWith("drive wheels");
            int count = wheels ? 4 : devices.size();
            b.append(count).append(" × ").append(p != null ? p.vendor() + " " + p.name() : e.getKey())
                    .append("  (").append(String.join(", ", devices)).append(")\n");
            if (p != null && p.specs().get("massG") instanceof Double g) knownMass += count * g / 1000;
        }
        if (knownMass > 0) {
            b.append(String.format(Locale.US, "\nParts with a listed mass: %.2f kg of the robot's %.1f kg.", knownMass, robot.mass()));
        }
        for (String w : robot.catalog().warnings()) b.append("\n⚠ ").append(w);
        return b.toString().trim();
    }

    private void refilter() {
        int sel = category.getSelectedIndex();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<PartsCatalog.Part> rows = new ArrayList<>();
        for (PartsCatalog.Part p : catalog.all()) {
            if (sel > 0 && !p.category().equals(PartsCatalog.CATEGORIES.get(sel - 1))) continue;
            String hay = (p.id() + " " + p.name() + " " + p.vendor() + " " + p.sku()).toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !hay.contains(q)) continue;
            rows.add(p);
        }
        model.setRows(rows);
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        PartsCatalog.Part p = model.rows.get(table.convertRowIndexToModel(row));
        snippet = snippetFor(p);
        StringBuilder b = new StringBuilder();
        b.append(p.vendor().isEmpty() ? "" : p.vendor() + " ").append(p.name());
        if (!p.sku().isEmpty()) b.append("   SKU ").append(p.sku());
        b.append("\n").append(PartsCatalog.summary(p));
        b.append("\nid: ").append(p.id()).append("   from: ").append(p.source());
        if (!p.note().isEmpty()) b.append("\nNote: ").append(p.note());
        b.append("\n\nIn robot.jsonc:\n").append(snippet);
        detail.setText(b.toString());
        detail.setCaretPosition(0);
    }

    static String snippetFor(PartsCatalog.Part p) {
        String id = p.id();
        return switch (p.category()) {
            case "motors" -> "\"motors\": [ { \"name\": \"myMotor\", \"type\": \"" + id + "\" } ]";
            case "servos" -> "\"servos\": [ { \"name\": \"myServo\", \"type\": \"" + id + "\" } ]"
                    + (Boolean.TRUE.equals(p.specs().get("continuousMode"))
                    ? "\n// or in continuous mode:\n\"crServos\": [ { \"name\": \"myCrServo\", \"type\": \"" + id + "\" } ]" : "");
            case "wheels" -> "\"drivetrain\": { \"wheel\": \"" + id + "\", ... }";
            case "slides" -> "\"mechanisms\": [ { \"name\": \"lift\", \"type\": \"slide\", \"motors\": [\"lift\"], \"kit\": \"" + id + "\", \"massLbs\": 2 } ]";
            case "odometryPods" -> "\"odometryPods\": [ { \"name\": \"par\", \"type\": \"" + id + "\", \"direction\": \"forward\", \"xIn\": 0, \"yIn\": 6 } ]";
            default -> switch (String.valueOf(p.specs().get("kind"))) {
                case "distance" -> "\"distanceSensors\": [ { \"name\": \"front\", \"type\": \"" + id + "\", \"xIn\": 9 } ]";
                case "color" -> "\"colorSensors\": [ { \"name\": \"color\", \"hopperSlot\": 0 } ]   // " + p.name();
                case "switch" -> "\"limitSwitches\": [ { \"name\": \"liftDown\", \"mechanism\": \"lift\", \"pressedBelow\": 0.01 } ]   // " + p.name();
                default -> "// " + p.name();
            };
        };
    }

    /** Creates config/parts/team-parts.jsonc from a template (if needed) and opens it. */
    private void openTeamPartsFile() {
        Path file = projectDir.resolve("config/parts/team-parts.jsonc");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, """
                        // Your team's parts. Same format as the built-in catalog
                        // (sim-core/src/main/resources/parts/catalog.jsonc). A part with the same id as a
                        // built-in one replaces it, which is handy for measured values.
                        // Press "Reload robot" in the simulator after editing.
                        {
                          "servos": [
                            // { "id": "Our-Claw-Servo", "name": "Claw servo (measured)", "vendor": "goBILDA",
                            //   "rangeDeg": 300, "secPer60Deg": 0.12, "usAtRangeStart": 500, "usAtRangeEnd": 2500 }
                          ],
                          "motors": [
                            // { "id": "Our-Drive-Motor", "name": "312 rpm with a 1:2 belt", "sdkName": "GoBILDA 5202/3/4 series",
                            //   "ticksPerRev": 1075.4, "freeRpm": 156, "stallTorqueKgCm": 48.6, "stallCurrentA": 9.2, "freeCurrentA": 0.25 }
                          ],
                          "wheels": [],
                          "slides": [],
                          "odometryPods": [],
                          "sensors": []
                        }
                        """);
                log.accept("Created " + projectDir.relativize(file));
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file.toFile());
            } else {
                log.accept("Edit " + file + " to add parts.");
            }
        } catch (Exception ex) {
            log.accept("Could not open " + file + ": " + ex.getMessage());
        }
    }

    private static final class PartsModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Part", "Vendor", "Specs", "From"};
        List<PartsCatalog.Part> rows = List.of();

        void setRows(List<PartsCatalog.Part> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int c) { return COLUMNS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            PartsCatalog.Part p = rows.get(r);
            return switch (c) {
                case 0 -> p.name();
                case 1 -> p.vendor();
                case 2 -> PartsCatalog.summary(p);
                default -> p.source();
            };
        }
    }
}
