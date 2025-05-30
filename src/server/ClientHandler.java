package server;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import common.Protocol;

public class ClientHandler implements Runnable {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private List<ClientHandler> clients;
    private Map<String, String> fileContents;
    private Set<String> fileNames;
    private static Map<String, String> fileOwners = Collections.synchronizedMap(new HashMap<>());
    private static Map<String, Set<String>> fileEditors = Collections.synchronizedMap(new HashMap<>());
    private static Set<String> activeUsers = Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, ReentrantLock> fileLocks = Collections.synchronizedMap(new HashMap<>());

    private String username;

    private Set<String> joinedFiles = Collections.synchronizedSet(new HashSet<>());

    public ClientHandler(Socket socket, List<ClientHandler> clients,
                         Map<String, String> fileContents, Set<String> fileNames,
                         Map<String, String> fileOwners, Map<String, Set<String>> fileEditors) throws IOException {
        this.socket = socket;
        this.clients = clients;
        this.fileContents = fileContents;
        this.fileNames = fileNames;
        ClientHandler.fileOwners = fileOwners;
        ClientHandler.fileEditors = fileEditors;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
    }

    public void send(String msg) {
        out.println(msg);
    }

    private void broadcastToFileEditors(String fileName, String msg) {
        synchronized (clients) {
            for (ClientHandler client : clients) {
                if (client.joinedFiles.contains(fileName)) {
                    client.send(msg);
                }
            }
        }
    }

    private void sendActiveUsersToAll() {
        String users = String.join(",", activeUsers);
        Protocol activeUsersMsg = new Protocol("MSG_ACTIVE_USERS", "SERVER", "", users);
        activeUsersMsg.statusCode = "200 OK";

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
        try (FileChannel channel = FileChannel.open(file.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes("UTF-8"));
            channel.write(buffer);

        } catch (IOException e) {
            System.err.println("Dosya FileChannel ile kaydedilirken hata: " + file.getName());
            e.printStackTrace();
        }
    }

    private void disconnect() {
        if (username != null) {
            activeUsers.remove(username);
            sendActiveUsersToAll();
        }
        joinedFiles.clear();

        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            // İlk mesajdan kullanıcı adını al
            String input = in.readLine();
            if (input == null) return;
            Protocol firstMsg = Protocol.deserialize(input);
            this.username = firstMsg.username;

            // Aktif kullanıcılar listesine ekle ve güncelle
            activeUsers.add(username);
            sendActiveUsersToAll();

            while ((input = in.readLine()) != null) {
                Protocol message = Protocol.deserialize(input);
                System.out.println("Alındı: " + message.serialize());

                switch (message.type) {
                    case "MSG_CREATE":
                        String newFile = message.fileName;
                        if (!fileNames.contains(newFile)) {
                            fileNames.add(newFile);
                            fileContents.put(newFile, "");
                            fileOwners.put(newFile, message.username);
                            fileEditors.put(newFile, new HashSet<>());
                            fileLocks.put(newFile, new ReentrantLock());

                            Protocol createOK = new Protocol("MSG_INFO", "SERVER", newFile, "Dosya oluşturuldu.");
                            createOK.statusCode = "201 Created";
                            this.send(createOK.serialize());
                        } else {
                            Protocol denyCreate = new Protocol("MSG_DENY", "SERVER", newFile, "Bu isimde bir dosya zaten var.");
                            denyCreate.statusCode = "409 Conflict";
                            this.send(denyCreate.serialize());
                        }
                        break;

                    case "MSG_EDIT":
                        String editor = message.username;
                        String owner = fileOwners.get(message.fileName);
                        Set<String> editorsSet = fileEditors.get(message.fileName);

                        boolean isOwner = editor.equals(owner);
                        boolean isEditor = editorsSet != null && editorsSet.contains(editor);

                        if (isOwner || isEditor) {
                            ReentrantLock lock = fileLocks.get(message.fileName);
                            if (lock != null) lock.lock();
                            try {
                                String currentContent = fileContents.get(message.fileName);

                                if (currentContent != null && currentContent.equals(message.content)) {
                                    break;
                                }

                                fileContents.put(message.fileName, message.content);
                                fileNames.add(message.fileName);
                                saveToFile(message.fileName, message.content);
                            } finally {
                                if (lock != null) lock.unlock();
                            }

                            Protocol editMsg = new Protocol("MSG_EDIT", editor, message.fileName, message.content);
                            editMsg.statusCode = "200 OK";

                            synchronized (clients) {
                                for (ClientHandler client : clients) {
                                    if (client.joinedFiles.contains(message.fileName) && !client.username.equals(editor)) {
                                        client.send(editMsg.serialize());
                                    }
                                }
                            }
                        } else {
                            Protocol denyEdit = new Protocol("MSG_DENY", "SERVER", message.fileName, "Dosyayı düzenleme yetkiniz yok!");
                            denyEdit.statusCode = "403 Forbidden";
                            this.send(denyEdit.serialize());
                        }
                        break;


                    case "MSG_JOIN":
                        joinedFiles.add(message.fileName);

                        if (fileContents.containsKey(message.fileName)) {
                            Protocol contentMsg = new Protocol("MSG_EDIT", "SERVER", message.fileName, fileContents.get(message.fileName));
                            contentMsg.statusCode = "200 OK";
                            this.send(contentMsg.serialize());
                        }

                        Set<String> editors = fileEditors.getOrDefault(message.fileName, Collections.emptySet());
                        String ownerStr = fileOwners.getOrDefault(message.fileName, "");
                        String combinedMsg = ownerStr + " (dosya sahibi)" + ";editors:" + String.join(",", editors);

                        Protocol getEditorsMsg = new Protocol("MSG_EDITORS", "SERVER", message.fileName, combinedMsg);
                        getEditorsMsg.statusCode = "200 OK";
                        this.send(getEditorsMsg.serialize());
                        break;

                    case "MSG_LIST":
                        String fileList = String.join(",", fileNames);
                        Protocol listMsg = new Protocol("MSG_LIST", "SERVER", "", fileList);
                        listMsg.statusCode = "200 OK";
                        this.send(listMsg.serialize());
                        break;

                    case "MSG_SET_EDITORS":
                        String fileName = message.fileName;
                        String sender = message.username;
                        String fileOwner = fileOwners.get(fileName);

                        if (fileOwner == null || !fileOwner.equals(sender)) {
                            Protocol denySet = new Protocol("MSG_DENY", "SERVER", fileName, "Sadece dosya sahibi düzenleyicileri değiştirebilir.");
                            denySet.statusCode = "403 Forbidden";
                            this.send(denySet.serialize());
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

                        String combined = fileOwner +" (dosya sahibi)" + ";editors:" + String.join(",", newEditors);
                        Protocol editorsUpdate = new Protocol("MSG_EDITORS", "SERVER", fileName, combined);
                        editorsUpdate.statusCode = "200 OK";

                        synchronized (clients) {
                            for (ClientHandler client : clients) {
                                client.send(editorsUpdate.serialize());
                            }
                        }

                        System.out.println("MSG_SET_EDITORS alındı. Dosya: " + fileName + ", Yeni editörler: " + newEditors);
                        System.out.println("Tüm istemcilere MSG_EDITORS mesajı gönderiliyor.");

                        Protocol infoUpdate = new Protocol("MSG_INFO", "SERVER", fileName, "Düzenleyiciler güncellendi.");
                        infoUpdate.statusCode = "200 OK";
                        this.send(infoUpdate.serialize());
                        break;

                    case "MSG_GET_EDITORS":
                        Set<String> eds = fileEditors.getOrDefault(message.fileName, Collections.emptySet());
                        String own = fileOwners.getOrDefault(message.fileName, "");
                        String combinedMsg2 =  own +" (dosya sahibi)"+ ";editors:" + String.join(",", eds);
                        Protocol getEditors = new Protocol("MSG_EDITORS", "SERVER", message.fileName, combinedMsg2);
                        getEditors.statusCode = "200 OK";
                        this.send(getEditors.serialize());
                        break;
                    case "MSG_LEAVE":
                        String leavingFile = message.fileName;
                        joinedFiles.remove(leavingFile);

                        Set<String> currentEditors = fileEditors.get(leavingFile);
                        if (currentEditors != null) {
                            currentEditors.remove(username);

                            if (currentEditors.isEmpty()) {
                                fileEditors.remove(leavingFile);
                                fileLocks.remove(leavingFile);
                            }

                            String ownerOfFile = fileOwners.getOrDefault(leavingFile, "");
                            String editorsStr = String.join(",", currentEditors);
                            String combinedLeaveMsg = ownerOfFile + " (dosya sahibi);editors:" + editorsStr;

                            Protocol leaveEditorsUpdate = new Protocol("MSG_EDITORS", "SERVER", leavingFile, combinedLeaveMsg);
                            leaveEditorsUpdate.statusCode = "200 OK";

                            broadcastToFileEditors(leavingFile, leaveEditorsUpdate.serialize());
                        }

                        Protocol leaveAck = new Protocol("MSG_INFO", "SERVER", leavingFile, "Dosyadan çıkış yapıldı.");
                        leaveAck.statusCode = "200 OK";
                        this.send(leaveAck.serialize());
                        break;

                    default:
                        Protocol unknownMsg = new Protocol("MSG_DENY", "SERVER", "", "Bilinmeyen mesaj türü: " + message.type);
                        unknownMsg.statusCode = "400 Bad Request";
                        this.send(unknownMsg.serialize());
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("İstemci bağlantısı kesildi: " + username);
        } finally {
            disconnect();
        }
    }
}