package client;


import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.List;

import common.ButtonTabComponent;
import common.Protocol;

public class EditorGUI {
    private String serverIP;
    private int serverPort;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private JFrame frame;

    private JComboBox<String> fileComboBox;
    private JComboBox<String> editComboBox;
    private DefaultListModel<String> activeUsersListModel;
    private JList<String> activeUsersList;
    private JButton updateEditorsButton;
    private Timer delayedSaveTimer;
    private String username;

    private JTabbedPane tabbedPane;
    private Map<String, EditorTab> openFiles = new HashMap<>();

    private static class EditorTab {
        JTextArea textArea;
        DefaultListModel<String> editorsListModel;
        JList<String> editorsList;
        boolean contentChanged;

        EditorTab() {
            textArea = new JTextArea();
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            editorsListModel = new DefaultListModel<>();
            editorsList = new JList<>(editorsListModel);
            contentChanged = false;
        }
    }

    public EditorGUI(String serverIP, int serverPort, String username) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.username = username;
    }

    public void start() {
        try {
            socket = new Socket(serverIP, serverPort);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            out.println(new Protocol("MSG_HELLO", username, "", "").serialize());

            SwingUtilities.invokeLater(this::initializeUI);

            new Thread(this::listenToServer).start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Sunucuya bağlanılamadı.", "Hata", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    private void initializeUI() {
        UIManager.put("Label.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("Button.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("ComboBox.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("List.font", new Font("Segoe UI", Font.PLAIN, 13));
        UIManager.put("TextArea.font", new Font("Consolas", Font.PLAIN, 13));

        frame = new JFrame("Çok Kullanıcılı Editör - " + username);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1000, 650);
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                sendAllEdits();
                try {
                    Thread.sleep(500);
                    socket.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                frame.dispose();
                System.exit(0);
            }
        });


        // Üst Panel: Düzenleme işlemleri ve dosya seçimi
        JPanel topPanel = createTopPanel();

        // Ana Sekme Paneli
        tabbedPane = new JTabbedPane();

        // Sağ Panel: Aktif kullanıcılar ve editör güncelleme
        JPanel rightPanel = createRightPanel();

        // Alt Panel: Kaydet butonu
        JButton saveButton = new JButton("Kaydet");
        saveButton.addActionListener(e -> sendEdit());
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(saveButton);

        frame.setLayout(new BorderLayout(5, 5));
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(tabbedPane, BorderLayout.CENTER);
        frame.add(rightPanel, BorderLayout.EAST);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        setupDelayedSave();

        frame.setVisible(true);
    }

    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 5));

        editComboBox = new JComboBox<>(new String[]{"Düzenle", "Kes", "Kopyala", "Yapıştır"});
        editComboBox.addActionListener(e -> {
            EditorTab currentTab = getCurrentEditorTab();
            if (currentTab == null) return;
            switch ((String) editComboBox.getSelectedItem()) {
                case "Kes":
                    currentTab.textArea.cut();
                    break;
                case "Kopyala":
                    currentTab.textArea.copy();
                    break;
                case "Yapıştır":
                    currentTab.textArea.paste();
                    break;
            }
            editComboBox.setSelectedIndex(0);
        });

        fileComboBox = new JComboBox<>();
        fileComboBox.setPreferredSize(new Dimension(180, 25));
        fileComboBox.addActionListener(e -> {
            if (e.getSource() == fileComboBox && fileComboBox.getSelectedItem() != null) {
                String selectedFile = (String) fileComboBox.getSelectedItem();
                if (!selectedFile.isEmpty()) openFileInTab(selectedFile, "");
            }
        });

        JButton newFileButton = new JButton("Yeni Dosya");
        newFileButton.addActionListener(e -> {
            String newFileName = JOptionPane.showInputDialog(frame, "Yeni dosya adı:");
            if (newFileName != null && !newFileName.trim().isEmpty()) {
                if (!newFileName.toLowerCase().endsWith(".txt")) newFileName += ".txt";
                sendAllEdits();
                out.println(new Protocol("MSG_CREATE", username, newFileName, "").serialize());
                out.println(new Protocol("MSG_LIST", username, "", "").serialize());
                openFileInTab(newFileName, "");
            }
        });

        JButton listFilesButton = new JButton("Dosya Listesi");
        listFilesButton.addActionListener(e -> out.println(new Protocol("MSG_LIST", username, "", "").serialize()));

        topPanel.add(editComboBox);
        topPanel.add(new JLabel("Dosya:"));
        topPanel.add(fileComboBox);
        topPanel.add(newFileButton);
        topPanel.add(listFilesButton);
        

        return topPanel;
    }

    private JPanel createRightPanel() {
        JPanel rightPanel = new JPanel();
        rightPanel.setPreferredSize(new Dimension(220, 0));
        rightPanel.setLayout(new BorderLayout(5, 5));

        JLabel activeUsersLabel = new JLabel("Aktif Kullanıcılar:");
        activeUsersLabel.setHorizontalAlignment(SwingConstants.CENTER);
        activeUsersLabel.setFont(activeUsersLabel.getFont().deriveFont(Font.BOLD, 14f));


        activeUsersListModel = new DefaultListModel<>();
        activeUsersList = new JList<>(activeUsersListModel);
        activeUsersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JScrollPane usersScrollPane = new JScrollPane(activeUsersList);

        updateEditorsButton = new JButton("Düzenleyicileri Güncelle");
        updateEditorsButton.setEnabled(false);
        updateEditorsButton.addActionListener(e -> updateEditors());

        rightPanel.add(activeUsersLabel, BorderLayout.NORTH);
        rightPanel.add(usersScrollPane, BorderLayout.CENTER);
        rightPanel.add(updateEditorsButton, BorderLayout.SOUTH);

        tabbedPane.addChangeListener(e -> {
            updateEditorsButtonState();
        });


        return rightPanel;
    }

    private EditorTab getCurrentEditorTab() {
        int idx = tabbedPane.getSelectedIndex();
        if (idx < 0) return null;
        String fileName = tabbedPane.getTitleAt(idx);
        return openFiles.get(fileName);
    }

    private void openFileInTab(String fileName, String content) {

        if (openFiles.containsKey(fileName)) {

            tabbedPane.setSelectedIndex(tabbedPane.indexOfTab(fileName));
            return;
        }

        EditorTab editorTab = new EditorTab();
        editorTab.textArea.setText(content);
        editorTab.textArea.setEnabled(true);
        editorTab.contentChanged = false;
        editorTab.textArea.setEditable(false);

        editorTab.textArea.getDocument().addDocumentListener(new DocumentListener() {
            private void scheduleSave() {
                if (!editorTab.contentChanged) {
                    editorTab.contentChanged = true;
                    return;
                }
                delayedSaveTimer.restart();
            }

            public void insertUpdate(DocumentEvent e) {
                scheduleSave();
            }

            public void removeUpdate(DocumentEvent e) {
                scheduleSave();
            }

            public void changedUpdate(DocumentEvent e) {
                scheduleSave();
            }
        });


        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        JScrollPane scrollPane = new JScrollPane(editorTab.textArea);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel editorsPanel = new JPanel(new BorderLayout());
        JLabel editorsLabel = new JLabel("Editörler:");
        editorsLabel.setHorizontalAlignment(SwingConstants.CENTER);
        editorsLabel.setFont(editorsLabel.getFont().deriveFont(Font.BOLD, 13f));
        editorsPanel.add(editorsLabel, BorderLayout.NORTH);
        editorsPanel.add(new JScrollPane(editorTab.editorsList), BorderLayout.CENTER);
        editorsPanel.setPreferredSize(new Dimension(160, 0));

        mainPanel.add(editorsPanel, BorderLayout.EAST);

        tabbedPane.addTab(fileName, mainPanel);
        tabbedPane.setSelectedComponent(mainPanel);
        tabbedPane.setTabComponentAt(tabbedPane.indexOfComponent(mainPanel),
                new ButtonTabComponent(tabbedPane, fileNameToRemove -> {
                    openFiles.remove(fileNameToRemove);
                    out.println(new Protocol("MSG_LEAVE", username, fileNameToRemove, "").serialize());
                })
        );
        openFiles.put(fileName, editorTab);

        // Sunucuya katılma ve editörleri çekme mesajları
        out.println(new Protocol("MSG_JOIN", username, fileName, "").serialize());
        out.println(new Protocol("MSG_GET_EDITORS", username, fileName, "").serialize());
    }

    private void sendEdit() {
        EditorTab editorTab = getCurrentEditorTab();
        if (editorTab == null || !editorTab.contentChanged) return;

        String fileName = tabbedPane.getTitleAt(tabbedPane.getSelectedIndex());
        out.println(new Protocol("MSG_EDIT", username, fileName, editorTab.textArea.getText()).serialize());

        editorTab.contentChanged = false;
    }

    private void sendAllEdits() {
        openFiles.forEach((fileName, editorTab) -> {
            if (editorTab.contentChanged) {
                out.println(new Protocol("MSG_EDIT", username, fileName, editorTab.textArea.getText()).serialize());
                editorTab.contentChanged = false;
            }
        });
    }

    private void updateEditors() {
        EditorTab currentTab = getCurrentEditorTab();
        if (currentTab == null) return;

        String currentFile = tabbedPane.getTitleAt(tabbedPane.getSelectedIndex());
        List<String> selectedUsers = new ArrayList<>(activeUsersList.getSelectedValuesList());
        selectedUsers.remove(username); // Kendini listeden çıkar

        if (selectedUsers.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "En az bir kullanıcı seçmelisiniz.", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String editorsStr = String.join(",", selectedUsers);
        out.println(new Protocol("MSG_SET_EDITORS", username, currentFile, editorsStr).serialize());
    }

    private void setupDelayedSave() {
        delayedSaveTimer = new Timer(1000, e -> sendAllEdits());
        delayedSaveTimer.setRepeats(false);
    }

    private void listenToServer() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Protocol msg = Protocol.deserialize(line);
                switch (msg.type) {
                    case "MSG_EDIT":
                        handleEditMessage(msg);
                        break;

                    case "MSG_LIST":
                        updateFileList(msg.content);
                        break;

                    case "MSG_DENY":
                        handleDenyMessage(msg);
                        break;

                    case "MSG_INFO":
                        showInfo(msg.content);
                        break;

                    case "MSG_EDITORS":
                        updateEditorsList(msg.fileName, msg.content);
                        break;

                    case "MSG_ACTIVE_USERS":
                        updateActiveUsers(msg.content);
                        break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(frame, "Sunucu bağlantısı kesildi.", "Hata", JOptionPane.ERROR_MESSAGE));
        }
    }

    private void handleEditMessage(Protocol msg) {
        EditorTab editorTab = openFiles.get(msg.fileName);
        if (editorTab != null) {
            SwingUtilities.invokeLater(() -> {
                editorTab.contentChanged = false;
                editorTab.textArea.setText(msg.content);
                editorTab.textArea.setEditable(true);
                editorTab.textArea.setEnabled(true);
            });
        }
    }



    private void updateFileList(String fileListStr) {
        SwingUtilities.invokeLater(() -> {
            fileComboBox.removeAllItems();
            String[] files = fileListStr.split(",");
            for (String file : files) {
                if (!file.trim().isEmpty())
                    fileComboBox.addItem(file.trim());
            }
        });
    }

    private void handleDenyMessage(Protocol msg) {
        String deniedFile = msg.fileName;
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(frame,
                    "Bu dosyayı düzenlemeye yetkiniz yok: " + deniedFile,
                    "Yetki Hatası", JOptionPane.WARNING_MESSAGE);
            // Dosya açık ise sekmesini kapat
            int idx = tabbedPane.indexOfTab(deniedFile);
            if (idx >= 0) {
                tabbedPane.removeTabAt(idx);
                openFiles.remove(deniedFile);
            }
        });
    }

    private void showInfo(String info) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, info, "Bilgi", JOptionPane.INFORMATION_MESSAGE));
    }

    private void updateEditorsList(String fileName, String editorsStr) {
        EditorTab editorTab = openFiles.get(fileName);

        if (editorTab != null) {
            SwingUtilities.invokeLater(() -> {
                editorTab.editorsListModel.clear();

                if (editorsStr.contains(";editors:")) {
                    String[] parts = editorsStr.split(";editors:", 2);
                    String owner = parts[0].trim();
                    editorTab.editorsListModel.addElement(owner);

                    String editorsCSV = parts[1];
                    if (!editorsCSV.trim().isEmpty()) {
                        String[] editors = editorsCSV.split(",");
                        for (String editor : editors) {
                            editorTab.editorsListModel.addElement(editor.trim());
                        }
                    }
                } else {
                    System.out.println("UYARI: ;editors: bulunamadı! editorsStr: " + editorsStr);
                    editorTab.editorsListModel.addElement(editorsStr.trim()); // fallback
                }

                updateEditorsButtonState();
            });
        }
    }





    private void updateActiveUsers(String usersStr) {
        SwingUtilities.invokeLater(() -> {
            activeUsersListModel.clear();
            if (!usersStr.trim().isEmpty()) {
                String[] users = usersStr.split(",");
                for (String user : users) {
                    if (!user.trim().isEmpty())
                        activeUsersListModel.addElement(user.trim());
                }
            }
        });
    }
    private void updateEditorsButtonState() {
        EditorTab currentTab = getCurrentEditorTab();
        if (currentTab == null) {
            updateEditorsButton.setEnabled(false);
            return;
        }
        int idx = tabbedPane.getSelectedIndex();
        if (idx < 0) {
            updateEditorsButton.setEnabled(false);
            return;
        }
        String currentFile = tabbedPane.getTitleAt(idx);
        boolean isOwner = currentTab.editorsListModel.contains(username + " (dosya sahibi)");
        updateEditorsButton.setEnabled(isOwner);
    }

}

