package server;

import java.io.*;
import java.net.*;
import java.util.*;
import common.Protocol;

public class ClientHandler implements Runnable {
    private Socket socket; // İstemcinin bağlantı soketi
    private BufferedReader in; // İstemciden gelen veriyi okumak için
    private PrintWriter out;  // İstemciye veri göndermek için
    private List<ClientHandler> clients;  // Tüm bağlı istemcilerin listesi (paylaşılan)
    private Map<String, String> fileContents; // Dosya adı -> içerik
    private Set<String> fileNames;
    private static Map<String, String> fileOwners = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, Set<String>> fileEditors = Collections.synchronizedMap(new HashMap<>());
    private static Set<String> activeUsers = Collections.synchronizedSet(new HashSet<>());

    private String username;


    public ClientHandler(Socket socket, List<ClientHandler> clients,
                         Map<String, String> fileContents, Set<String> fileNames,
                         Map<String, String> fileOwners, Map<String, Set<String>> fileEditors)  throws IOException {
        this.socket = socket;
        this.clients = clients;
        this.fileContents = fileContents;
        this.fileNames = fileNames;
        this.fileOwners = fileOwners;
        this.fileEditors = fileEditors;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
    }

    public void send(String msg) {
        out.println(msg);
    }

    private void broadcastToOthers(String msg) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client != this) {
                    client.send(msg);
                }
            }
        }
    }

    private void sendActiveUsersToAll() {
        String users = String.join(",", activeUsers);
        Protocol activeUsersMsg = new Protocol("MSG_ACTIVE_USERS", "SERVER", "", users);

        synchronized (clients) {
            for (ClientHandler client : clients) {
                client.send(activeUsersMsg.serialize());
            }
        }
    }


    private void saveToFile(String fileName, String content) {
        File dir = new File("sunucu_dosyalar");
        if (!dir.exists()) dir.mkdir();

        File file = new File(dir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write(content);
        } catch (IOException e) {
            System.err.println("Dosya kaydedilirken hata: " + file.getName());
        }
    }

    @Override
    public void run() {
        try {
            String input;

            input = in.readLine();
            Protocol firstMsg = Protocol.deserialize(input);
            this.username = firstMsg.username;
            while ((input = in.readLine()) != null) {
                Protocol message = Protocol.deserialize(input);
                System.out.println("Alındı: " + message.serialize());

                switch (message.type) {
                    case "MSG_CREATE":
                        String newFile = message.fileName;
                        if (!fileNames.contains(newFile)) {
                            fileNames.add(newFile);
                            fileContents.put(newFile, "");
                            fileOwners.put(newFile, message.username);  // Sahip burada atanıyor
                            fileEditors.put(newFile, new HashSet<>()); // Boş editör seti başlat
                            this.send(new Protocol("MSG_INFO", "SERVER", newFile, "Dosya oluşturuldu.").serialize());
                        } else {
                            this.send(new Protocol("MSG_DENY", "SERVER", newFile, "Bu isimde bir dosya zaten var.").serialize());
                        }
                        break;

                    case "MSG_EDIT":
                        String editor = message.username;
                        String owner = fileOwners.get(message.fileName);
                        Set<String> editorsSet = fileEditors.get(message.fileName);

                        boolean isOwner = editor.equals(owner);
                        boolean isEditor = editorsSet != null && editorsSet.contains(editor);

                        if (isOwner || isEditor) {
                            fileContents.put(message.fileName, message.content);
                            fileNames.add(message.fileName);
                            saveToFile(message.fileName, message.content);

                            broadcastToOthers(message.serialize());

                        } else {
                            Protocol denyMsg = new Protocol("MSG_DENY", "SERVER", "", "Dosyayı düzenleme yetkiniz yok!");
                            this.send(denyMsg.serialize());
                        }
                        break;

                    case "MSG_JOIN":
                        activeUsers.add(message.username);

                        // aktif kullanıcı listesini güncelle ve tüm istemcilere gönder
                        sendActiveUsersToAll();

                        if (fileContents.containsKey(message.fileName)) {
                            Protocol contentMsg = new Protocol("MSG_EDIT", "SERVER", message.fileName, fileContents.get(message.fileName));
                            this.send(contentMsg.serialize());
                        }
                        break;


                    case "MSG_LIST":
                        String fileList = String.join(",", fileNames);
                        Protocol listMsg = new Protocol("MSG_LIST", "SERVER", "", fileList);
                        this.send(listMsg.serialize());
                        break;

                    case "MSG_SET_EDITORS":
                        String fileName = message.fileName;
                        String sender = message.username;
                        String fileOwner = fileOwners.get(fileName);

                        if (fileOwner == null || !fileOwner.equals(sender)) {
                            this.send(new Protocol("MSG_DENY", "SERVER", fileName, "Sadece dosya sahibi düzenleyicileri değiştirebilir.").serialize());
                            break;
                        }

                        Set<String> newEditors = new HashSet<>();
                        if (!message.content.isEmpty()) {
                            String[] eds = message.content.split(",");
                            for (String ed : eds) {
                                if (!ed.trim().isEmpty()) {
                                    newEditors.add(ed.trim());
                                }
                            }
                        }

                        fileEditors.put(fileName, newEditors);

                        String combined = "owner:" + fileOwner + ";editors:" + String.join(",", newEditors);
                        synchronized (clients) {
                            for (ClientHandler client : clients) {
                                client.send(new Protocol("MSG_EDITORS", "SERVER", fileName, combined).serialize());
                            }
                        }
                        System.out.println("MSG_SET_EDITORS alındı. Dosya: " + fileName + ", Yeni editörler: " + newEditors);
                        System.out.println("Tüm istemcilere MSG_EDITORS mesajı gönderiliyor.");
                        this.send(new Protocol("MSG_INFO", "SERVER", fileName, "Düzenleyiciler güncellendi.").serialize());

                        break;

                    case "MSG_GET_EDITORS":
                        Set<String> editors = fileEditors.get(message.fileName);
                        if (editors == null) editors = Collections.emptySet();

                        String ownerStr = fileOwners.getOrDefault(message.fileName, "");
                        String combinedMsg = "owner:" + ownerStr + ";editors:" + String.join(",", editors);

                        this.send(new Protocol("MSG_EDITORS", "SERVER", message.fileName, combinedMsg).serialize());
                        break;

                    default:
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("İstemci bağlantısı kesildi: " + username);
        }
    }
}