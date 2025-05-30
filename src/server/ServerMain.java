package server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.file.*;

public class ServerMain {
    static List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());
    static Map<String, String> fileContents = Collections.synchronizedMap(new HashMap<>());
    static Set<String> fileNames = Collections.synchronizedSet(new HashSet<>());
    static Map<String, String> fileOwners = Collections.synchronizedMap(new HashMap<>());
    static Map<String, Set<String>> fileEditors = Collections.synchronizedMap(new HashMap<>());

    public static void main(String[] args) throws IOException {
        loadFiles();

        ServerSocket serverSocket = new ServerSocket(12345);
        System.out.println("Sunucu başlatıldı...");

        while (true) {
            Socket clientSocket = serverSocket.accept();
            ClientHandler handler = new ClientHandler(clientSocket, clients, fileContents, fileNames, fileOwners, fileEditors);
            clients.add(handler);
            new Thread(handler).start();
        }
    }

    private static void loadFiles() {
        File dir = new File("sunucu_dosyalar");
        if (!dir.exists()) {
            System.out.println("Dosya klasörü bulunamadı, yeni klasör oluşturulacak.");
            dir.mkdir();
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            try {
                String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
                fileContents.put(file.getName(), content);
                fileNames.add(file.getName());
                System.out.println("Yüklendi: " + file.getName());
            } catch (IOException e) {
                System.err.println("Dosya yüklenirken hata: " + file.getName());
            }
        }
    }
}
