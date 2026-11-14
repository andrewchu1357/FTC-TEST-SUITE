package org.ftctestbed.viewer;

import org.ftctestbed.sim.code.HardwareScanner;
import org.ftctestbed.sim.code.RobotPackages;
import org.ftctestbed.viewer.model.ModelLoader;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * "Import robot…": makes a robot package from the team's code (linked or copied), a robot
 * config (written from the code's hardware names or copied from an example) and an optional
 * CAD model, then opens it.
 */
final class ImportRobotDialog extends JDialog {
    private final Path projectDir;
    private final Consumer<Path> open;
    private final JTextField name = new JTextField("MyRobot", 16);
    private final JTextField codePath = new JTextField(28);
    private final JRadioButton link = new JRadioButton("Link to it: always run my latest code", true);
    private final JRadioButton copy = new JRadioButton("Copy it into the robot package");
    private final JRadioButton generate = new JRadioButton("Write one from the hardware names in my code", true);
    private final JRadioButton fromBuzzBot = new JRadioButton("Start from BuzzBot's (config/robot.jsonc)");
    private final JRadioButton fromStarter = new JRadioButton("Start from the starter robot's");
    private final JTextField cadPath = new JTextField(28);
    private final JButton create = new JButton("Create robot");

    ImportRobotDialog(JFrame owner, Path projectDir, Consumer<Path> open) {
        super(owner, "Import robot", true);
        this.projectDir = projectDir;
        this.open = open;

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        form.add(row(new JLabel("Robot name"), name));
        form.add(Box.createVerticalStrut(6));

        JPanel code = section("Team code (optional)");
        JButton chooseCode = new JButton("Choose…");
        chooseCode.addActionListener(e -> chooseCode());
        code.add(row(codePath, chooseCode));
        code.add(note("Your FtcRobotController project folder, its TeamCode folder, or a .zip of it."));
        ButtonGroup g1 = new ButtonGroup();
        g1.add(link);
        g1.add(copy);
        code.add(row(link));
        code.add(row(copy));
        form.add(code);

        JPanel cfg = section("Robot config");
        ButtonGroup g2 = new ButtonGroup();
        for (JRadioButton b : new JRadioButton[]{generate, fromBuzzBot, fromStarter}) {
            g2.add(b);
            cfg.add(row(b));
        }
        cfg.add(note("Either way, check sizes, motor/servo types and mechanisms afterwards (Parts tab)."));
        form.add(cfg);

        JPanel cad = section("CAD model (optional)");
        JButton chooseCad = new JButton("Choose…");
        chooseCad.addActionListener(e -> chooseCad());
        cad.add(row(cadPath, chooseCad));
        cad.add(note("GLB/glTF (Onshape: right-click the assembly → Export → GLTF), STL or OBJ."));
        form.add(cad);

        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        create.addActionListener(e -> createRobot());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(create);
        getRootPane().setDefaultButton(create);

        JPanel content = new JPanel(new BorderLayout());
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel row(JComponent... items) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        for (JComponent c : items) r.add(c);
        r.setAlignmentX(LEFT_ALIGNMENT);
        return r;
    }

    private static JComponent note(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        return row(l);
    }

    private void chooseCode() {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle("Team code folder or .zip");
        c.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        c.setFileFilter(new FileNameExtensionFilter("Folders and .zip files", "zip"));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path p = c.getSelectedFile().toPath();
            codePath.setText(p.toString());
            if (p.toString().toLowerCase().endsWith(".zip")) copy.setSelected(true);
            if (name.getText().equals("MyRobot")) name.setText(guessName(p));
        }
    }

    private static String guessName(Path p) {
        String n = p.getFileName().toString().replaceAll("\\.zip$", "");
        if (n.equalsIgnoreCase("TeamCode") || n.equalsIgnoreCase("java") || n.equalsIgnoreCase("FtcRobotController")) {
            Path parent = p.getParent();
            if (parent != null && parent.getFileName() != null && !n.equalsIgnoreCase("FtcRobotController")) return guessName(parent);
        }
        n = n.replaceAll("[^A-Za-z0-9 _-]", "");
        return n.isEmpty() ? "MyRobot" : n.substring(0, Math.min(n.length(), 30));
    }

    private void chooseCad() {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle("Robot CAD export");
        c.setFileFilter(new FileNameExtensionFilter("3D models (GLB, glTF, STL, OBJ)", ModelLoader.EXTENSIONS.toArray(new String[0])));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) cadPath.setText(c.getSelectedFile().getPath());
    }

    private void createRobot() {
        String codeText = codePath.getText().trim(), cadText = cadPath.getText().trim();
        Path template = generate.isSelected() ? null
                : fromBuzzBot.isSelected() ? projectDir.resolve("config/robot.jsonc") : projectDir.resolve("config/examples/starter-robot.jsonc");
        if (codeText.isEmpty() && template == null) {
            JOptionPane.showMessageDialog(this, "Choose your team code, or start from one of the example configs.", "Import robot",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        RobotPackages.Options options = new RobotPackages.Options(projectDir, name.getText(),
                codeText.isEmpty() ? null : Path.of(codeText), link.isSelected(), template);
        create.setEnabled(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<RobotPackages.Result, Void>() {
            @Override
            protected RobotPackages.Result doInBackground() throws Exception {
                RobotPackages.Result r = RobotPackages.create(options);
                if (!cadText.isEmpty()) {
                    try {
                        ModelPanel.importModel(Path.of(cadText), r.robotFile());
                        r.report().add("Imported the CAD model " + Path.of(cadText).getFileName()
                                + ". Line it up and make parts move in the Model tab.");
                    } catch (Exception e) {
                        r.report().add("WARNING: could not import the CAD model: " + e.getMessage());
                    }
                }
                return r;
            }

            @Override
            protected void done() {
                setCursor(null);
                create.setEnabled(true);
                RobotPackages.Result r;
                try {
                    r = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(ImportRobotDialog.this, cause.getMessage(), "Import robot", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                StringBuilder b = new StringBuilder();
                b.append("Created ").append(projectDir.relativize(r.robotFile())).append("\n\n");
                for (String line : r.report()) b.append("• ").append(line).append('\n');
                if (!r.devices().isEmpty()) {
                    b.append("\nHardware your code uses (").append(r.javaFiles()).append(" Java files):\n");
                    for (HardwareScanner.Device d : r.devices()) {
                        b.append("  ").append(d.javaType()).append(" \"").append(d.name()).append("\"   ").append(d.where()).append('\n');
                    }
                }
                b.append("\nThe simulator will now compile the code and open the robot.");
                JTextArea text = new JTextArea(b.toString(), Math.min(24, b.toString().split("\n").length + 1), 80);
                text.setEditable(false);
                text.setFont(org.ftctestbed.viewer.platform.Os.mono(Font.PLAIN, 12));
                JOptionPane.showMessageDialog(ImportRobotDialog.this, new JScrollPane(text), "Robot imported", JOptionPane.INFORMATION_MESSAGE);
                dispose();
                open.accept(r.robotFile());
            }
        }.execute();
    }
}
