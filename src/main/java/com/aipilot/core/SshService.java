package com.aipilot.core;

import com.aipilot.core.model.ProjectServer;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemorySourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** SSH exec and SFTP for extra folders bound to a server alias. */
public final class SshService {
    public record ExecResult(int exitCode, String stdout, String stderr, String error) {
        public boolean ok() {
            return error == null && exitCode == 0;
        }
    }

    private SshService() {
    }

    public static ExecResult ping(ProjectServer server) {
        return exec(server, "echo aipilot");
    }

    public static ExecResult exec(ProjectServer server, String command) {
        if (server == null || server.aliasOrEmpty().isBlank()) {
            return new ExecResult(-1, "", "", "Нет SSH-доступа");
        }
        if (command == null || command.isBlank()) {
            return new ExecResult(-1, "", "", "Пустая команда");
        }
        try (SSHClient client = connect(server);
             Session session = client.startSession()) {
            Session.Command cmd = session.exec(command);
            String stdout = readFully(cmd.getInputStream());
            String stderr = readFully(cmd.getErrorStream());
            cmd.join(120, TimeUnit.SECONDS);
            Integer exit = cmd.getExitStatus();
            return new ExecResult(exit == null ? -1 : exit, stdout, stderr, null);
        } catch (Exception e) {
            return new ExecResult(-1, "", "", e.getMessage());
        }
    }

    public static void sftpWrite(ProjectServer server, String remotePath, String content) throws IOException {
        try (SSHClient client = connect(server);
             SFTPClient sftp = client.newSFTPClient()) {
            byte[] data = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            sftp.put(new BytesSource(fileName(remotePath), data), remotePath);
        }
    }

    public static void sftpDelete(ProjectServer server, String remotePath) throws IOException {
        try (SSHClient client = connect(server);
             SFTPClient sftp = client.newSFTPClient()) {
            sftp.rm(remotePath);
        }
    }

    public static void sftpRename(ProjectServer server, String from, String to) throws IOException {
        try (SSHClient client = connect(server);
             SFTPClient sftp = client.newSFTPClient()) {
            sftp.rename(from, to);
        }
    }

    private static SSHClient connect(ProjectServer server) throws IOException {
        if (server.host == null || server.host.isBlank()) {
            throw new IOException("Не задан хост SSH «" + server.aliasOrEmpty() + "»");
        }
        if (server.password == null || server.password.isBlank()) {
            throw new IOException("Нет пароля SSH для «" + server.aliasOrEmpty() + "»");
        }
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.setConnectTimeout(15_000);
        client.connect(server.host.trim(), server.port <= 0 ? 22 : server.port);
        client.authPassword(server.login == null ? "" : server.login, server.password);
        return client;
    }

    private static String readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String fileName(String path) {
        String s = path == null ? "file" : path.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static final class BytesSource extends InMemorySourceFile {
        private final String name;
        private final byte[] data;

        private BytesSource(String name, byte[] data) {
            this.name = name == null || name.isBlank() ? "file" : name;
            this.data = data;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public long getLength() {
            return data.length;
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(data);
        }
    }
}
