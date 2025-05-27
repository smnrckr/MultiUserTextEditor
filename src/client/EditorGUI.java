package client;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import common.Protocol;

public class EditorGUI {
    private String serverIP;
    private int serverPort;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private JFrame frame;
    private JTextArea textArea;
    private JLabel fileNameField;
    private String username;
    private JComboBox<String> editComboBox;
    private JComboBox<String> fileComboBox;
    private DefaultListModel<String> editorsListModel;
    private JList<String> editorsList;
    private JButton updateEditorsButton;

    private DefaultListModel<String> activeUsersListModel;
    private JList<String> activeUsersList;

    private boolean contentChanged = false;
    private Timer delayedSaveTimer;

    private ActionListener fileComboBoxListener;


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

            frame = new JFrame("Çok Kullanıcılı Editör - " + username);
            textArea = new JTextArea(20, 60);
            textArea.setEnabled(false);
            fileNameField = new JLabel("Yeni Dosya");
            fileNameField.setEnabled(false);
            fileComboBox = new JComboBox<>();
            fileComboBox.setPreferredSize(new Dimension(150, 25));

            editComboBox = new JComboBox<>(new String[]{"Düzenle", "Kes", "Kopyala", "Yapıştır"});
            editComboBox.setPreferredSize(new Dimension(100, 25));

            JButton listButton = new JButton("Dosya Listesi");
            JButton sendButton = new JButton("Kaydet");
            JButton newFileButton = new JButton("Yeni Dosya");

            activeUsersListModel = new DefaultListModel<>();
            activeUsersList = new JList<>(activeUsersListModel);
            activeUsersList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            activeUsersList.setVisibleRowCount(8);
            activeUsersList.setFixedCellWidth(150);

            editorsListModel = new DefaultListModel<>();
            editorsList = new JList<>(editorsListModel);

            JScrollPane editorsScrollPane = new JScrollPane(editorsList);
            JScrollPane usersScrollPane = new JScrollPane(activeUsersList);

            updateEditorsButton = new JButton("Düzenleyicileri Güncelle");

            delayedSaveTimer = new Timer(1000, e -> {
                if (contentChanged) {
                    sendEdit();
                    contentChanged = false;  // Mesaj gönderildi, tekrar bekle
                }
            });
            delayedSaveTimer.setRepeats(false);

            textArea.getDocument().addDocumentListener(new DocumentListener() {
                private void restartDelayedSaveTimer() {
                    contentChanged = true;
                    delayedSaveTimer.restart();
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    restartDelayedSaveTimer();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    restartDelayedSaveTimer();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    restartDelayedSaveTimer();
                }
            });



            JPanel topPanel = new JPanel();
            topPanel.add(editComboBox);
            topPanel.add(new JLabel("Dosya:"));
            topPanel.add(fileNameField);
            topPanel.add(newFileButton);
            topPanel.add(listButton);
            topPanel.add(new JLabel("Mevcut Dosyalar:"));
            topPanel.add(fileComboBox);


            JPanel centerPanel = new JPanel(new BorderLayout());
            centerPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);

            JPanel rightPanel = new JPanel(new BorderLayout());
            JPanel userPanel = new JPanel(new BorderLayout());
            userPanel.add(new JLabel("Aktif Kullanıcılar:"), BorderLayout.NORTH);
            userPanel.add(usersScrollPane, BorderLayout.CENTER);

            JPanel editorPanel = new JPanel(new BorderLayout());
            editorPanel.add(new JLabel("Düzenleyiciler:"), BorderLayout.NORTH);
            editorPanel.add(editorsScrollPane, BorderLayout.CENTER);
            editorPanel.add(updateEditorsButton, BorderLayout.SOUTH);

            rightPanel.add(userPanel, BorderLayout.NORTH);
            rightPanel.add(editorPanel, BorderLayout.CENTER);

            frame.setLayout(new BorderLayout());
            frame.setSize(1000, 600);
            frame.setResizable(true);
            frame.add(topPanel, BorderLayout.NORTH);
            frame.add(centerPanel, BorderLayout.CENTER);
            frame.add(rightPanel, BorderLayout.EAST);
            frame.add(sendButton, BorderLayout.SOUTH);

            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                public void windowClosing(java.awt.event.WindowEvent e) {
                    sendEdit();
                    try { socket.close(); } catch (IOException ex) { ex.printStackTrace(); }
                    frame.dispose();
                    System.exit(0);
                }
            });

            frame.pack();
            frame.setVisible(true);
            fileComboBoxListener = e -> {
                String selectedFile = (String) fileComboBox.getSelectedItem();
                if (selectedFile != null && !selectedFile.isEmpty()) {
                    fileNameField.setText(selectedFile);
                    fileNameField.setEnabled(true);
                    joinFile(selectedFile);
                }
            };

            fileComboBox.addActionListener(fileComboBoxListener);

            listButton.addActionListener(e -> {
                Protocol listRequest = new Protocol("MSG_LIST", username, "", "");
                out.println(listRequest.serialize());
            });

            newFileButton.addActionListener(e -> {
                String newFileName = JOptionPane.showInputDialog(frame, "Yeni dosya adı:");
                if (newFileName != null) {
                    newFileName = newFileName.trim();

                    if (!newFileName.toLowerCase().endsWith(".txt")) {
                        newFileName += ".txt";
                    }

                    if (!newFileName.isEmpty()) {
                        sendEdit();
                        Protocol createMsg = new Protocol("MSG_CREATE", username, newFileName, "");
                        out.println(createMsg.serialize());
                        fileNameField.setText(newFileName);

                        out.println(new Protocol("MSG_LIST", username, "", "").serialize());

                        joinFile(newFileName);
                    }
                }
            });

            sendButton.addActionListener(e -> sendEdit());

            fileComboBox.addActionListener(e -> {
                String selectedFile = (String) fileComboBox.getSelectedItem();
                if (selectedFile != null && !selectedFile.isEmpty()) {
                    sendEdit();
                    fileNameField.setText(selectedFile);
                    fileNameField.setEnabled(true);
                    joinFile(selectedFile);
                }
            });

            updateEditorsButton.addActionListener(e -> {
                String currentFile = fileNameField.getText();
                if (currentFile.isEmpty()) return;

                List<String> selectedUsers = activeUsersList.getSelectedValuesList();
                selectedUsers.remove(username);
                if (selectedUsers.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "En az bir kullanıcı seçmelisiniz.", "Uyarı", JOptionPane.WARNING_MESSAGE);
                }

                String editorsStr = String.join(",", selectedUsers);
                Protocol setEditorsMsg = new Protocol("MSG_SET_EDITORS", username, currentFile, editorsStr);
                out.println(setEditorsMsg.serialize());
            });

            editComboBox.addActionListener(e -> {
                String selected = (String) editComboBox.getSelectedItem();
                if (selected == null || selected.equals("Düzenle")) return;

                switch (selected) {
                    case "Kes":
                        textArea.requestFocusInWindow();
                        textArea.cut();
                        break;
                    case "Kopyala":
                        textArea.requestFocusInWindow();
                        textArea.copy();
                        break;
                    case "Yapıştır":
                        textArea.requestFocusInWindow();
                        textArea.paste();
                        break;

                }
                editComboBox.setSelectedIndex(0);
            });

            new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        Protocol msg = Protocol.deserialize(line);
                        switch (msg.type) {
                            case "MSG_EDIT":
                                if (msg.fileName.equals(fileNameField.getText())) {
                                    SwingUtilities.invokeLater(() -> {
                                        contentChanged = false; // dışarıdan gelen veri, iç değişiklik olarak sayma
                                        textArea.setText(msg.content);
                                        textArea.setEnabled(true);
                                    });
                                }
                                break;


                            case "MSG_LIST":
                                String[] files = msg.content.split(",");

                                SwingUtilities.invokeLater(() -> {
                                    fileComboBox.removeActionListener(fileComboBoxListener);

                                    String previouslySelected = (String) fileComboBox.getSelectedItem();
                                    fileComboBox.removeAllItems();
                                    for (String f : files) fileComboBox.addItem(f);

                                    if (previouslySelected != null && !previouslySelected.isEmpty()) {
                                        fileComboBox.setSelectedItem(previouslySelected);
                                    }

                                    fileComboBox.addActionListener(fileComboBoxListener);
                                });
                                break;

                            case "MSG_DENY":
                                JOptionPane.showMessageDialog(frame, msg.content, "Yetki Hatası", JOptionPane.WARNING_MESSAGE);
                                break;

                            case "MSG_INFO":
                                JOptionPane.showMessageDialog(frame, msg.content, "Bilgi", JOptionPane.INFORMATION_MESSAGE);
                                break;

                            case "MSG_EDITORS":
                                String owner = "";
                                String[] parts = msg.content.split(";");
                                Set<String> editorsSet = new HashSet<>();
                                for (String part : parts) {
                                    if (part.startsWith("owner:")) {
                                        owner = part.substring(6).trim();
                                    } else if (part.startsWith("editors:")) {
                                        String[] eds = part.substring(8).split(",");
                                        for (String ed : eds)
                                            if (!ed.trim().isEmpty()) editorsSet.add(ed.trim());
                                    }
                                }
                                final String ownerFinal = owner;
                                SwingUtilities.invokeLater(() -> {
                                    editorsListModel.clear();
                                    if (!ownerFinal.isEmpty())
                                        editorsListModel.addElement(ownerFinal + " (dosya sahibi)");
                                    for (String ed : editorsSet)
                                        if (!ed.equals(ownerFinal)) editorsListModel.addElement(ed);
                                    updateEditorsButton.setEnabled(ownerFinal.equals(username));
                                });
                                break;

                            case "MSG_ACTIVE_USERS":
                                String[] users = msg.content.isEmpty() ? new String[0] : msg.content.split(",");
                                SwingUtilities.invokeLater(() -> {
                                    activeUsersListModel.clear();
                                    for (String user : users)
                                        if (!user.trim().isEmpty()) activeUsersListModel.addElement(user.trim());
                                });
                                break;
                        }
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }).start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendEdit() {
        if (!contentChanged) return;

        String text = textArea.getText();
        String fileName = fileNameField.getText();

        if (fileName == null || fileName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Lütfen bir dosya seçin veya oluşturun.");
            return;
        }

        Protocol editMsg = new Protocol("MSG_EDIT", username, fileName, text);
        out.println(editMsg.serialize());

        System.out.println("sendEdit at " + System.currentTimeMillis());

        contentChanged = false;
    }

    private void joinFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            fileNameField.setEnabled(false);
            return;
        }
        fileNameField.setEnabled(true);
        out.println(new Protocol("MSG_JOIN", username, fileName, "").serialize());
        out.println(new Protocol("MSG_GET_EDITORS", username, fileName, "").serialize());
    }
}


