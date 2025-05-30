package client;

import javax.swing.*;

public class ClientMain {
    public static void main(String[] args) {
        String username = JOptionPane.showInputDialog(null, "Kullanıcı adınızı giriniz:", "Giriş", JOptionPane.PLAIN_MESSAGE);
        if (username != null && !username.trim().isEmpty()) {
            EditorGUI gui = new EditorGUI("localhost", 12345, username.trim());
            gui.start();
        } else {
            JOptionPane.showMessageDialog(null, "Kullanıcı adı boş olamaz.");
        }
    }
}
