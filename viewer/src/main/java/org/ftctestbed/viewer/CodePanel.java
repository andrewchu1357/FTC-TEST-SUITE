package org.ftctestbed.viewer;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.ftctestbed.sim.code.RobotCode;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The Code tab: the robot's own source files, editable in the app. "Save & Run" saves, compiles
 * (stopping at compile errors, which are marked in the editor) and reloads the simulator with the
 * new code, then starts the OpMode that's selected in the Driver Station.
 */
final class CodePanel extends JPanel {
    /** What the Code tab needs from the window. */
    interface Host {
        /** The OpMode selected in the Driver Station, or null. */
        String selectedOpMode();

        /** Stop whatever runs and reload the simulator with the freshly compiled code. */
        void runNewCode(CodeSession.State state);

        void log(String message);
    }

    private static final Color ERROR_LINE = new Color(255, 60, 60, 64);
    private static final Font CODE_FONT = new Font(org.ftctestbed.viewer.platform.Os.monoFamily(), Font.PLAIN, 13);
    private static final int MENU = menuMask();

    private final CodeWorkspace ws;
    private final Host host;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("code");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final Map<Path, Editor> editors = new LinkedHashMap<>();
    private final JLabel status = new JLabel(" ");
    private final JButton runButton = new JButton("▶ Save & Run");
    private final JCheckBox startAfter = new JCheckBox("and start the OpMode", true);
    private final JTextField find = new JTextField(12);
    private final DefaultListModel<RobotCode.Problem> problemModel = new DefaultListModel<>();
    private final JList<RobotCode.Problem> problemList = new JList<>(problemModel);
    private final JPanel problemsPanel = new JPanel(new BorderLayout());
    private final JLabel problemsTitle = new JLabel();
    private List<RobotCode.Problem> problems = List.of();
    private boolean compiling;

    /** A tree entry: a source folder, a (compacted) package folder, or a .java file. */
    private record Item(Path path, String label, boolean folder) {
        @Override
        public String toString() {
            return label;
        }
    }

    CodePanel(CodeWorkspace ws, Host host) {
        super(new BorderLayout());
        this.ws = ws;
        this.host = host;

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD));
        runButton.setToolTipText(org.ftctestbed.viewer.platform.Os.text("Save every file, compile, and reload the simulator with the new code (⌘R)"));
        runButton.addActionListener(e -> saveAndRun(false));
        startAfter.setToolTipText("After reloading, INIT and START the OpMode selected in the Driver Station");
        find.setColumns(10);
        JButton save = new JButton("Save");
        save.setToolTipText(org.ftctestbed.viewer.platform.Os.text("Save this file (⌘S)"));
        save.addActionListener(e -> saveCurrent());
        JButton newFile = new JButton("New…");
        newFile.setToolTipText(org.ftctestbed.viewer.platform.Os.text("New OpMode or class (⌘N)"));
        newFile.addActionListener(e -> newFile());
        JButton delete = new JButton("Delete");
        delete.setToolTipText("Move the selected file to the Trash");
        delete.addActionListener(e -> deleteSelected());
        find.putClientProperty("JTextField.variant", "search"); // the macOS search field (magnifier, rounded)
        find.putClientProperty("JTextField.placeholderText", org.ftctestbed.viewer.platform.Os.text("Find (⌘F)"));
        find.setToolTipText(org.ftctestbed.viewer.platform.Os.text("Find in this file (⌘F, then Enter / ⌘G for the next match)"));
        find.addActionListener(e -> findNext());
        for (JComponent c : new JComponent[]{runButton, startAfter, save, newFile, delete}) c.setFocusable(false);
        bar.add(runButton);
        bar.add(startAfter);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(save);
        bar.add(newFile);
        bar.add(delete);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(find);
        add(bar, BorderLayout.NORTH);
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.setFont(status.getFont().deriveFont(12f));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p != null && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Item it && !it.folder()) {
                    open(it.path(), 0);
                }
            }
        });
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setMinimumSize(new Dimension(160, 100));

        problemList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                String text = value instanceof RobotCode.Problem p
                        ? p.file().getFileName() + ":" + p.line() + "   " + p.message().lines().findFirst().orElse("") : String.valueOf(value);
                return super.getListCellRendererComponent(list, text, index, sel, focus);
            }
        });
        problemList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && problemList.getSelectedValue() != null) {
                RobotCode.Problem p = problemList.getSelectedValue();
                open(p.file(), (int) p.line());
            }
        });
        problemList.setVisibleRowCount(5);
        JPanel problemsBar = new JPanel(new BorderLayout());
        problemsTitle.setForeground(new Color(0xC62828));
        problemsTitle.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        JButton anyway = new JButton("Run anyway");
        anyway.setToolTipText("Reload without the files that don't compile (OpModes in them are left out)");
        anyway.setFocusable(false);
        anyway.addActionListener(e -> saveAndRun(true));
        problemsBar.add(problemsTitle, BorderLayout.CENTER);
        problemsBar.add(anyway, BorderLayout.EAST);
        problemsPanel.add(problemsBar, BorderLayout.NORTH);
        problemsPanel.add(new JScrollPane(problemList), BorderLayout.CENTER);
        problemsPanel.setVisible(false);

        JPanel right = new JPanel(new BorderLayout());
        right.add(tabs, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(problemsPanel, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        right.add(south, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, right);
        split.setDividerLocation(230);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        if (ws.isEmpty()) {
            status.setText("No code found for this robot.");
            runButton.setEnabled(false);
        } else {
            status.setText("Changes are saved when you run, and when the window closes.");
        }
        fillTree();
        bindKeys();
    }

    // ------------------------------------------------------------------ files

    private org.ftctestbed.viewer.ui.Theme theme = org.ftctestbed.viewer.ui.Appearance.theme();

    /** Colors every editor (and ones opened later) for the app's theme, at its text size. */
    void applyTheme(org.ftctestbed.viewer.ui.Theme theme) {
        this.theme = theme;
        for (Editor ed : editors.values()) colorEditor(ed);
        problemsTitle.setForeground(theme.palette.stop());
    }

    private void colorEditor(Editor ed) {
        org.ftctestbed.viewer.ui.Theme.Code c = theme.palette.code();
        RSyntaxTextArea t = ed.text;
        try (var in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + c.base() + ".xml")) {
            if (in != null) Theme.load(in).apply(t);
        } catch (IOException ignored) {
            // the default colors are fine
        }
        t.setFont(CODE_FONT.deriveFont((float) (CODE_FONT.getSize2D() * org.ftctestbed.viewer.ui.Appearance.textScale())));
        t.setBackground(c.background());
        t.setForeground(c.text());
        t.setCaretColor(c.caret());
        t.setSelectionColor(c.selection());
        t.setCurrentLineHighlightColor(c.currentLine());
        org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = t.getSyntaxScheme();
        int[] text = {org.fife.ui.rsyntaxtextarea.Token.IDENTIFIER, org.fife.ui.rsyntaxtextarea.Token.OPERATOR,
                org.fife.ui.rsyntaxtextarea.Token.SEPARATOR, org.fife.ui.rsyntaxtextarea.Token.FUNCTION};
        int[] keyword = {org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD, org.fife.ui.rsyntaxtextarea.Token.RESERVED_WORD_2};
        int[] string = {org.fife.ui.rsyntaxtextarea.Token.LITERAL_STRING_DOUBLE_QUOTE, org.fife.ui.rsyntaxtextarea.Token.LITERAL_CHAR,
                org.fife.ui.rsyntaxtextarea.Token.LITERAL_BACKQUOTE};
        int[] comment = {org.fife.ui.rsyntaxtextarea.Token.COMMENT_EOL, org.fife.ui.rsyntaxtextarea.Token.COMMENT_MULTILINE,
                org.fife.ui.rsyntaxtextarea.Token.COMMENT_DOCUMENTATION, org.fife.ui.rsyntaxtextarea.Token.COMMENT_KEYWORD,
                org.fife.ui.rsyntaxtextarea.Token.COMMENT_MARKUP};
        int[] number = {org.fife.ui.rsyntaxtextarea.Token.LITERAL_NUMBER_DECIMAL_INT, org.fife.ui.rsyntaxtextarea.Token.LITERAL_NUMBER_FLOAT,
                org.fife.ui.rsyntaxtextarea.Token.LITERAL_NUMBER_HEXADECIMAL, org.fife.ui.rsyntaxtextarea.Token.LITERAL_BOOLEAN};
        Object[][] roles = {{text, c.text()}, {keyword, c.keyword()}, {string, c.string()}, {comment, c.comment()}, {number, c.number()},
                {new int[]{org.fife.ui.rsyntaxtextarea.Token.DATA_TYPE}, c.type()},
                {new int[]{org.fife.ui.rsyntaxtextarea.Token.ANNOTATION}, c.annotation()}};
        for (Object[] role : roles) {
            for (int token : (int[]) role[0]) {
                // Every kind of token readable on the background (themes' comment colors are often faint).
                Color fg = org.ftctestbed.viewer.ui.Contrast.readableOn((Color) role[1], c.background(), org.ftctestbed.viewer.ui.Contrast.TEXT);
                if (scheme.getStyle(token) != null) scheme.getStyle(token).foreground = fg;
            }
        }
        t.setSyntaxScheme(scheme);
        org.fife.ui.rtextarea.Gutter g = ed.scroll.getGutter();
        g.setBackground(c.gutter());
        g.setLineNumberColor(org.ftctestbed.viewer.ui.Contrast.readableOn(c.gutterText(), c.gutter(), org.ftctestbed.viewer.ui.Contrast.TEXT));
        g.setBorderColor(c.gutter());
        g.setFoldIndicatorForeground(c.gutterText());
        g.setLineNumberFont(t.getFont());
        t.repaint();
    }

    private final class Editor {
        final Path file;
        final RSyntaxTextArea text = new RSyntaxTextArea();
        final RTextScrollPane scroll;
        final JLabel title = new JLabel();
        String saved;     // the file's text as shown (\n line endings)
        boolean crlf;     // the file uses Windows line endings: keep them when saving
        long mtime;

        Editor(Path file) throws IOException {
            this.file = file;
            text.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
            text.setCodeFoldingEnabled(true);
            text.setTabSize(4);
            text.setTabsEmulated(true);
            text.setAutoIndentEnabled(true);
            text.setBracketMatchingEnabled(true);
            text.setMarkOccurrences(true);
            text.setAntiAliasingEnabled(true);
            scroll = new RTextScrollPane(text);
            scroll.setLineNumbersEnabled(true);
            scroll.setIconRowHeaderEnabled(true);
            scroll.setBorder(null);
            colorEditor(this);
            load();
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { updateTitle(); }
                @Override public void removeUpdate(DocumentEvent e) { updateTitle(); }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
        }

        void load() throws IOException {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            crlf = raw.contains("\r\n");
            saved = raw.replace("\r\n", "\n");
            mtime = Files.getLastModifiedTime(file).toMillis();
            text.setText(saved);
            text.setCaretPosition(0);
            text.discardAllEdits();
            updateTitle();
        }

        boolean dirty() {
            return !text.getText().equals(saved);
        }

        void updateTitle() {
            title.setText(file.getFileName() + (dirty() ? "  •" : ""));
        }

        void save() throws IOException {
            String now = text.getText();
            Files.writeString(file, crlf ? now.replace("\n", "\r\n") : now, StandardCharsets.UTF_8);
            saved = now;
            mtime = Files.getLastModifiedTime(file).toMillis();
            updateTitle();
        }
    }

    /** The .java files under the code folders, to tell whether the tree needs rebuilding. */
    private List<Path> javaFiles() {
        List<Path> all = new ArrayList<>();
        for (CodeWorkspace.Root r : ws.roots()) {
            try (Stream<Path> s = Files.walk(r.dir())) {
                s.filter(p -> p.toString().endsWith(".java") && !skipped(r.dir().relativize(p))).sorted().forEach(all::add);
            } catch (IOException ignored) {
                // a folder that went away: it simply has no files
            }
        }
        return all;
    }

    private static boolean skipped(Path rel) {
        for (Path part : rel) if (part.toString().startsWith(".") || part.toString().equals("build")) return true;
        return false;
    }

    private List<Path> treeFiles = List.of();
    private java.util.Set<Path> javaDirs = java.util.Set.of();

    /** Rebuilds the tree if files were added or removed, keeping open folders open. */
    private void refreshTree() {
        List<Path> files = javaFiles();
        if (files.equals(treeFiles)) return;
        java.util.Set<Path> open = new java.util.HashSet<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath tp = tree.getPathForRow(i);
            if (tree.isExpanded(tp) && ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject() instanceof Item it) open.add(it.path());
        }
        Path selected = selectedTreePath();
        fillTree(files);
        if (!open.isEmpty()) {
            for (int i = tree.getRowCount() - 1; i >= 0; i--) {
                TreePath tp = tree.getPathForRow(i);
                if (((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject() instanceof Item it && it.folder() && !open.contains(it.path())) {
                    tree.collapsePath(tp);
                }
            }
        }
        if (selected != null) {
            for (int i = 0; i < tree.getRowCount(); i++) {
                if (((DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent()).getUserObject() instanceof Item it && it.path().equals(selected)) {
                    tree.setSelectionRow(i);
                }
            }
        }
    }

    private void fillTree() {
        fillTree(javaFiles());
    }

    private void fillTree(List<Path> files) {
        treeFiles = files;
        java.util.Set<Path> dirs = new java.util.HashSet<>();
        for (Path f : files) for (Path d = f.getParent(); d != null && dirs.add(d); d = d.getParent()) { }
        javaDirs = dirs;
        treeRoot.removeAllChildren();
        for (CodeWorkspace.Root r : ws.roots()) {
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Item(r.dir(), r.label(), true));
            addChildren(n, r.dir());
            treeRoot.add(n);
        }
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount() && i < 400; i++) tree.expandRow(i);
    }

    private void addChildren(DefaultMutableTreeNode parent, Path dir) {
        List<Path> folders = new ArrayList<>(), files = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(".") || n.equals("build")) continue;
                if (javaDirs.contains(p)) folders.add(p);
                else if (n.endsWith(".java") && Files.isRegularFile(p)) files.add(p);
            }
        } catch (IOException ignored) {
            return;
        }
        for (Path f : folders) {
            // Show org/firstinspires/ftc/teamcode as one "org.firstinspires.ftc.teamcode" node.
            Path d = f;
            StringBuilder label = new StringBuilder(f.getFileName().toString());
            while (true) {
                List<Path> sub = new ArrayList<>();
                boolean javaHere = false;
                try (Stream<Path> s = Files.list(d)) {
                    for (Path p : s.toList()) {
                        if (javaDirs.contains(p)) sub.add(p);
                        else if (p.toString().endsWith(".java")) javaHere = true;
                    }
                } catch (IOException e) {
                    break;
                }
                if (javaHere || sub.size() != 1) break;
                d = sub.get(0);
                label.append('.').append(d.getFileName());
            }
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Item(d, label.toString(), true));
            addChildren(n, d);
            parent.add(n);
        }
        for (Path f : files) parent.add(new DefaultMutableTreeNode(new Item(f, f.getFileName().toString(), false)));
    }

    /** Opens a file in a tab (or selects its tab) and moves to a line (1-based; 0 keeps the place). */
    void open(Path file, int line) {
        Path f = file.toAbsolutePath().normalize();
        Editor ed = editors.get(f);
        if (ed == null) {
            try {
                ed = new Editor(f);
            } catch (IOException e) {
                status.setText("Can't open " + f.getFileName() + ": " + e.getMessage());
                return;
            }
            editors.put(f, ed);
            tabs.addTab(null, ed.scroll);
            tabs.setTabComponentAt(tabs.getTabCount() - 1, tabHeader(ed));
            tabs.setToolTipTextAt(tabs.getTabCount() - 1, f.toString());
            mark(ed);
        }
        tabs.setSelectedComponent(ed.scroll);
        if (line > 0) {
            try {
                ed.text.setCaretPosition(ed.text.getLineStartOffset(Math.min(line, ed.text.getLineCount()) - 1));
            } catch (BadLocationException ignored) {
                // past the end: leave the caret
            }
        }
        Editor e2 = ed;
        javax.swing.SwingUtilities.invokeLater(() -> e2.text.requestFocusInWindow());
    }

    private JComponent tabHeader(Editor ed) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JButton close = new JButton("×");
        close.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 3));
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText(org.ftctestbed.viewer.platform.Os.text("Close (⌘W)"));
        close.addActionListener(e -> close(ed));
        p.add(ed.title);
        p.add(close);
        return p;
    }

    private Editor current() {
        Component c = tabs.getSelectedComponent();
        for (Editor e : editors.values()) if (e.scroll == c) return e;
        return null;
    }

    private void close(Editor ed) {
        if (ed.dirty()) {
            int c = JOptionPane.showConfirmDialog(this, "Save the changes to " + ed.file.getFileName() + "?", "Close",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (c == JOptionPane.CANCEL_OPTION || c == JOptionPane.CLOSED_OPTION) return;
            if (c == JOptionPane.YES_OPTION && !save(ed)) return;
        }
        tabs.remove(ed.scroll);
        editors.remove(ed.file);
    }

    private boolean save(Editor ed) {
        try {
            if (Files.exists(ed.file) && Files.getLastModifiedTime(ed.file).toMillis() != ed.mtime
                    && !Files.readString(ed.file, StandardCharsets.UTF_8).replace("\r\n", "\n").equals(ed.saved)) {
                int c = JOptionPane.showConfirmDialog(this, ed.file.getFileName() + " was changed outside the app since you opened it.\n"
                        + "Replace it with your version?", "File changed", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (c != JOptionPane.OK_OPTION) return false;
            }
            ed.save();
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Couldn't save " + ed.file + ":\n" + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void saveCurrent() {
        Editor ed = current();
        if (ed != null && ed.dirty() && save(ed)) status.setText("Saved " + ed.file.getFileName());
    }

    /** Saves every changed file; false if one couldn't be saved (or saving was cancelled). */
    boolean saveAll() {
        for (Editor ed : editors.values()) if (ed.dirty() && !save(ed)) return false;
        return true;
    }

    boolean hasUnsaved() {
        return editors.values().stream().anyMatch(Editor::dirty);
    }

    /** Saves without asking anything (the window is closing); returns the files that couldn't be saved. */
    List<Path> saveAllQuietly() {
        List<Path> failed = new ArrayList<>();
        for (Editor ed : editors.values()) {
            if (!ed.dirty()) continue;
            try {
                ed.save();
            } catch (IOException e) {
                failed.add(ed.file);
            }
        }
        return failed;
    }

    /** Re-reads open files that changed on disk (edited in another program) and weren't changed here. */
    void reloadChangedFiles() {
        for (Editor ed : editors.values()) {
            try {
                if (!Files.exists(ed.file) || Files.getLastModifiedTime(ed.file).toMillis() == ed.mtime || ed.dirty()) continue;
                int caret = ed.text.getCaretPosition();
                ed.load();
                ed.text.setCaretPosition(Math.min(caret, ed.text.getDocument().getLength()));
            } catch (IOException ignored) {
                // leave it as it is
            }
        }
        refreshTree();
    }

    private Path selectedTreePath() {
        TreePath p = tree.getSelectionPath();
        return p != null && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Item it ? it.path() : null;
    }

    private void newFile() {
        if (ws.isEmpty()) return;
        Path sel = selectedTreePath();
        Path folder = sel == null ? ws.roots().get(0).dir() : Files.isDirectory(sel) ? sel : sel.getParent();
        if (sel == null) {
            // default to the folder with the most code, usually org/firstinspires/ftc/teamcode
            Editor ed = current();
            if (ed != null) folder = ed.file.getParent();
        }
        JTextField name = new JTextField(18);
        JComboBox<String> kind = new JComboBox<>(new String[]{"TeleOp (LinearOpMode)", "Autonomous (LinearOpMode)", "TeleOp (iterative OpMode)", "Plain class"});
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
        form.add(new JLabel("Class name"));
        form.add(name);
        form.add(new JLabel("Kind"));
        form.add(kind);
        String pkg = ws.packageOf(folder);
        form.add(new JLabel("<html><small>In " + (pkg.isEmpty() ? "the default package" : pkg) + "</small></html>"));
        if (JOptionPane.showConfirmDialog(this, form, "New file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        CodeWorkspace.Template t = CodeWorkspace.Template.values()[kind.getSelectedIndex()];
        try {
            Path f = ws.newFile(folder, name.getText().trim(), t);
            fillTree();
            open(f, 0);
            status.setText("Created " + f.getFileName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "New file", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        Path sel = selectedTreePath();
        if (sel == null || Files.isDirectory(sel) || ws.rootOf(sel) == null) {
            status.setText("Select a file in the list to delete it.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Move " + sel.getFileName() + " to the Trash?", "Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        Editor ed = editors.remove(sel.toAbsolutePath().normalize());
        if (ed != null) tabs.remove(ed.scroll);
        boolean trashed = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                && Desktop.getDesktop().moveToTrash(sel.toFile());
        if (!trashed) {
            status.setText("Couldn't move " + sel.getFileName() + " to the Trash.");
            return;
        }
        fillTree();
        status.setText("Moved " + sel.getFileName() + " to the Trash.");
    }

    // ------------------------------------------------------------------ compile and run

    /** Saves, compiles, and (with no compile errors, or anyway) reloads the simulator with the new code. */
    void saveAndRun(boolean anyway) {
        if (compiling || ws.isEmpty()) return;
        if (!saveAll()) return;
        compiling = true;
        runButton.setEnabled(false);
        status.setText("Compiling…");
        new SwingWorker<CodeWorkspace.Check, Void>() {
            @Override
            protected CodeWorkspace.Check doInBackground() {
                return ws.compile();
            }

            @Override
            protected void done() {
                compiling = false;
                runButton.setEnabled(true);
                CodeWorkspace.Check c;
                try {
                    c = get();
                } catch (Exception e) {
                    status.setText("The compiler failed: " + e.getMessage());
                    return;
                }
                showProblems(c.problems());
                if (!c.ok() && !anyway) {
                    if (c.problems().isEmpty()) {
                        String why = c.messages().isEmpty() ? "see the Console" : c.messages().get(0);
                        status.setText("Didn't compile: " + why);
                        for (String m : c.messages()) host.log(m);
                    } else {
                        long files = c.problems().stream().map(RobotCode.Problem::file).distinct().count();
                        status.setText(String.format(Locale.US, "%d error%s in %d file%s: fix %s, or Run anyway.", c.problems().size(),
                                c.problems().size() == 1 ? "" : "s", files, files == 1 ? "" : "s", c.problems().size() == 1 ? "it" : "them"));
                        open(c.problems().get(0).file(), (int) c.problems().get(0).line());
                    }
                    return;
                }
                status.setText(String.format(Locale.US, "Compiled in %.1f s. Reloading…", c.seconds()));
                host.runNewCode(CodePanel.this.state(startAfter.isSelected() ? host.selectedOpMode() : null));
            }
        }.execute();
    }

    private void showProblems(List<RobotCode.Problem> list) {
        problems = list;
        problemModel.clear();
        for (RobotCode.Problem p : list) problemModel.addElement(p);
        problemsTitle.setText(list.size() + (list.size() == 1 ? " compile error" : " compile errors") + " (click one to go to it)");
        problemsPanel.setVisible(!list.isEmpty());
        for (Editor ed : editors.values()) mark(ed);
        revalidate();
    }

    /** Marks compile errors in an editor: a tinted line and an icon with the message. */
    private void mark(Editor ed) {
        ed.text.removeAllLineHighlights();
        ed.scroll.getGutter().removeAllTrackingIcons();
        for (RobotCode.Problem p : problems) {
            if (!p.file().toAbsolutePath().normalize().equals(ed.file) || p.line() <= 0 || p.line() > ed.text.getLineCount()) continue;
            try {
                ed.text.addLineHighlight((int) p.line() - 1, ERROR_LINE);
                ed.scroll.getGutter().addLineTrackingIcon((int) p.line() - 1, ERROR_ICON, p.message());
            } catch (BadLocationException ignored) {
                // the line is gone
            }
        }
    }

    private static final Icon ERROR_ICON = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xE53935));
            g.fillOval(x + 1, y + 1, 10, 10);
            g.setColor(Color.WHITE);
            g.fillRect(x + 5, y + 3, 2, 4);
            g.fillRect(x + 5, y + 8, 2, 2);
            g.dispose();
        }

        @Override public int getIconWidth() { return 12; }
        @Override public int getIconHeight() { return 12; }
    };

    /** The open files and cursor places, for the reloaded window. */
    CodeSession.State state(String runOpMode) {
        List<Path> files = new ArrayList<>(editors.keySet());
        Map<Path, Integer> carets = new HashMap<>();
        for (Editor ed : editors.values()) carets.put(ed.file, ed.text.getCaretPosition());
        Editor cur = current();
        return new CodeSession.State(files, cur == null ? null : cur.file, carets, null, runOpMode);
    }

    void restore(CodeSession.State s) {
        for (Path f : s.files()) if (Files.exists(f)) open(f, 0);
        for (Map.Entry<Path, Integer> e : s.carets().entrySet()) {
            Editor ed = editors.get(e.getKey());
            if (ed != null) ed.text.setCaretPosition(Math.min(e.getValue(), ed.text.getDocument().getLength()));
        }
        if (s.selected() != null && editors.containsKey(s.selected())) tabs.setSelectedComponent(editors.get(s.selected()).scroll);
    }

    /** Opens the first OpMode-looking file, so the tab isn't empty the first time. */
    void openSomethingIfEmpty() {
        if (!editors.isEmpty() || ws.isEmpty()) return;
        try (Stream<Path> s = Files.walk(ws.roots().get(0).dir())) {
            s.filter(p -> p.toString().endsWith(".java")).sorted()
                    .filter(p -> {
                        try {
                            String t = Files.readString(p);
                            return t.contains("@TeleOp") || t.contains("@Autonomous");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst().ifPresent(p -> open(p, 0));
        } catch (IOException ignored) {
            // nothing to open
        }
    }

    /** The status line (tests). */
    String statusText() {
        return status.getText();
    }

    /** The text in a file's open editor, or null (tests). */
    String editorText(Path file) {
        Editor ed = editors.get(file.toAbsolutePath().normalize());
        return ed == null ? null : ed.text.getText();
    }

    /** Replaces the text in a file's open editor, as typing would (tests). */
    void type(Path file, String text) {
        editors.get(file.toAbsolutePath().normalize()).text.setText(text);
    }

    int problemCount() {
        return problems.size();
    }

    // ------------------------------------------------------------------ find and keys

    private void findNext() {
        Editor ed = current();
        String q = find.getText();
        if (ed == null || q.isEmpty()) return;
        SearchContext ctx = new SearchContext(q);
        ctx.setMatchCase(false);
        ctx.setSearchForward(true);
        if (!SearchEngine.find(ed.text, ctx).wasFound()) {
            ed.text.setCaretPosition(0); // wrap around
            if (!SearchEngine.find(ed.text, ctx).wasFound()) status.setText("\"" + q + "\" isn't in this file.");
        }
    }

    private void bindKeys() {
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, MENU), "save", this::saveCurrent);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, MENU | InputEvent.SHIFT_DOWN_MASK), "saveAll", () -> {
            if (saveAll()) status.setText("Saved all files.");
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, MENU), "find", () -> {
            find.requestFocusInWindow();
            find.selectAll();
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, MENU), "findNext", this::findNext);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, MENU), "close", () -> {
            Editor ed = current();
            if (ed != null) close(ed);
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, MENU), "new", this::newFile);
    }

    /** ⌘ on a Mac, Ctrl elsewhere (⌘ when there's no screen, in tests). */
    private static int menuMask() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return InputEvent.META_DOWN_MASK;
        }
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
