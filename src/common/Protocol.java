package common;

public class Protocol {
    public String type;
    public String username;
    public String fileName;
    public String content;

    public Protocol(String type, String username, String fileName, String content) {
        this.type = type;
        this.username = username;
        this.fileName = fileName;
        this.content = content;
    }

    public String serialize() {
        return type + "|" + username + "|" + fileName + "|" + content.replace("\n", "\\n");
    }

    public static Protocol deserialize(String msg) {
        String[] parts = msg.split("\\|", 4);
        String type = parts.length > 0 ? parts[0] : "";
        String username = parts.length > 1 ? parts[1] : "";
        String fileName = parts.length > 2 ? parts[2] : "";
        String content = parts.length > 3 ? parts[3].replace("\\n", "\n") : "";
        return new Protocol(type, username, fileName, content);
    }
}
