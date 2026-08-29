package io.persistenthistory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ScopeEditorDialog extends JDialog {
    private final RuleTableModel includeModel;
    private final RuleTableModel excludeModel;
    private final JCheckBox unscoped;
    private WorkspaceScope result;

    static WorkspaceScope showDialog(Component parent, Workspace workspace) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        ScopeEditorDialog dialog = new ScopeEditorDialog(owner, workspace);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return dialog.result;
    }

    private ScopeEditorDialog(Window owner, Workspace workspace) {
        super(owner, "Workspace scope — " + workspace.name(), ModalityType.APPLICATION_MODAL);
        includeModel = new RuleTableModel(workspace.scope().include());
        excludeModel = new RuleTableModel(workspace.scope().exclude());
        unscoped = new JCheckBox(
                "Unscoped: capture all targets",
                workspace.scope().unscoped());
        unscoped.setToolTipText(
                "When selected, include/exclude rules are preserved but not used for capture.");

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel header = new JPanel(new BorderLayout(8, 5));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        header.add(new JLabel(
                "<html>Rules use Burp-style matching. When scoped, a request must match an enabled "
                        + "include rule and must not match an enabled exclude rule.</html>"),
                BorderLayout.CENTER);
        JButton importJson = new JButton("Import Burp JSON...");
        importJson.addActionListener(e -> importJson());
        header.add(importJson, BorderLayout.EAST);
        header.add(unscoped, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel rules = new JPanel(new GridLayout(2, 1, 0, 8));
        rules.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        rules.add(rulePanel("Include in workspace", includeModel));
        rules.add(rulePanel("Exclude from workspace", excludeModel));
        add(rules, BorderLayout.CENTER);

        JButton save = new JButton("Save");
        JButton cancel = new JButton("Cancel");
        save.addActionListener(e -> {
            result = new WorkspaceScope(
                    includeModel.rules(),
                    excludeModel.rules(),
                    unscoped.isSelected());
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        footer.add(cancel);
        footer.add(save);
        add(footer, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(save);
        setMinimumSize(new Dimension(850, 560));
        setSize(new Dimension(980, 650));
    }

    private JPanel rulePanel(String title, RuleTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setMaxWidth(75);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);

        JButton add = new JButton("Add");
        JButton edit = new JButton("Edit");
        JButton remove = new JButton("Remove");

        add.addActionListener(e -> {
            ScopeRule rule = editRule(null);
            if (rule != null) {
                model.add(rule);
            }
        });
        edit.addActionListener(e -> editSelected(table, model));
        remove.addActionListener(e -> removeSelected(table, model));

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    editSelected(table, model);
                }
            }
        });

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        for (JButton button : List.of(add, edit, remove)) {
            button.setAlignmentX(Component.CENTER_ALIGNMENT);
            button.setMaximumSize(new Dimension(100, button.getPreferredSize().height));
            buttons.add(button);
            buttons.add(Box.createVerticalStrut(6));
        }

        JPanel body = new JPanel(new BorderLayout(8, 0));
        body.add(buttons, BorderLayout.WEST);
        body.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void editSelected(JTable table, RuleTableModel model) {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        int row = table.convertRowIndexToModel(selected);
        ScopeRule updated = editRule(model.get(row));
        if (updated != null) {
            model.set(row, updated);
        }
    }

    private void removeSelected(JTable table, RuleTableModel model) {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        int row = table.convertRowIndexToModel(selected);
        ScopeRule rule = model.get(row);
        if (JOptionPane.showConfirmDialog(
                this,
                "Remove this scope rule?\n" + rule,
                "Remove scope rule",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            model.remove(row);
        }
    }

    private ScopeRule editRule(ScopeRule initial) {
        JCheckBox enabled = new JCheckBox("Enabled", initial == null || initial.enabled());
        JComboBox<String> protocol = new JComboBox<>(new String[] {"any", "http", "https"});
        protocol.setSelectedItem(initial == null ? "any" : initial.protocol());
        JTextField host = new JTextField(initial == null ? "" : initial.host(), 32);
        JTextField port = new JTextField(initial == null ? "" : initial.port(), 32);
        JTextField file = new JTextField(initial == null ? "" : initial.file(), 32);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        form.add(enabled, c);
        c.gridwidth = 1;

        addField(form, c, 1, "Protocol:", protocol);
        addField(form, c, 2, "Host / IP range:", host);
        addField(form, c, 3, "Port:", port);
        addField(form, c, 4, "File / path:", file);

        c.gridx = 0; c.gridy = 5; c.gridwidth = 2;
        form.add(new JLabel(
                "<html>Host and port may be regexes; IPv4 CIDR/ranges are accepted in Host. "
                        + "File is a path regex and ignores the query string.</html>"), c);

        while (true) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    form,
                    initial == null ? "Add scope rule" : "Edit scope rule",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return null;
            }

            try {
                return new ScopeRule(
                        enabled.isSelected(),
                        String.valueOf(protocol.getSelectedItem()),
                        host.getText(),
                        port.getText(),
                        file.getText());
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(
                        this,
                        e.getMessage(),
                        "Invalid scope rule",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static void addField(
            JPanel form,
            GridBagConstraints c,
            int row,
            String label,
            Component field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        form.add(field, c);
    }

    private void importJson() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import Burp target scope JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path path = chooser.getSelectedFile().toPath();
        try {
            WorkspaceScope imported = BurpScopeJson.importScope(path);
            includeModel.setRules(imported.include());
            excludeModel.setRules(imported.exclude());
            unscoped.setSelected(false);
            JOptionPane.showMessageDialog(
                    this,
                    "Loaded " + imported.include().size() + " include and "
                            + imported.exclude().size() + " exclude rules.\n"
                            + "Review them, then click Save to apply them to this workspace.",
                    "Scope imported",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not import scope JSON:\n" + e.getMessage(),
                    "Import failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class RuleTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Enabled", "Protocol", "Host / IP range", "Port", "File"};
        private final List<ScopeRule> rows = new ArrayList<>();

        private RuleTableModel(List<ScopeRule> rules) {
            setRules(rules);
        }

        private void setRules(List<ScopeRule> rules) {
            rows.clear();
            rows.addAll(rules);
            fireTableDataChanged();
        }

        private List<ScopeRule> rules() {
            return List.copyOf(rows);
        }

        private ScopeRule get(int row) {
            return rows.get(row);
        }

        private void add(ScopeRule rule) {
            int row = rows.size();
            rows.add(rule);
            fireTableRowsInserted(row, row);
        }

        private void set(int row, ScopeRule rule) {
            rows.set(row, rule);
            fireTableRowsUpdated(row, row);
        }

        private void remove(int row) {
            rows.remove(row);
            fireTableRowsDeleted(row, row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ScopeRule rule = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> rule.enabled();
                case 1 -> rule.protocol();
                case 2 -> rule.host();
                case 3 -> rule.port();
                case 4 -> rule.file();
                default -> "";
            };
        }
    }
}
