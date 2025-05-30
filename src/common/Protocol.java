package common;

import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;

public class Protocol {
    public String type;
    public String username;
    public String fileName;
    public String content;
    public String statusCode;
    public String timestamp;

    public Protocol(String type, String username, String fileName, String content) {
        this.type = type;
        this.username = username;
        this.fileName = fileName;
        this.content = content;
        this.statusCode = "200 OK";
        this.timestamp = getCurrentTimestamp();
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date());
    }

    public String serialize() {
        String encodedContent = Base64.getEncoder().encodeToString(content.getBytes());
        return String.join(";", type, username, fileName, encodedContent, statusCode, timestamp);
    }

    public static Protocol deserialize(String msg) {
        String[] parts = msg.split(";", 6);
        Protocol p = new Protocol(parts[0], parts[1], parts[2], "");

        if (parts.length > 3) {
            byte[] decodedBytes = Base64.getDecoder().decode(parts[3]);
            p.content = new String(decodedBytes);
        }

        if (parts.length > 4) {
            p.statusCode = parts[4];
        }

        if (parts.length > 5) {
            p.timestamp = parts[5];
        }

        return p;
    }
}
