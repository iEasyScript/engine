package com.projectx.supervisor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Function;

/**
 * Per-pid Unix-domain control socket at {@code $XDG_RUNTIME_DIR/projectx/<pid>.sock} (or
 * {@code /tmp/projectx-<user>/<pid>.sock}). The launcher connects with no elevation and sends one
 * line (STATUS / INJECT / UNINJECT / REINJECT / PING); we reply with one line. One socket per
 * process makes multiple client instances independent.
 */
final class ControlSocket {
    private final Function<String, String> handler;
    private ServerSocketChannel server;
    private Path socketPath;
    private volatile boolean running;

    ControlSocket(Function<String, String> handler) {
        this.handler = handler;
    }

    void start() throws IOException {
        socketPath = resolveSocketPath();
        Files.createDirectories(socketPath.getParent());
        try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}

        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
        running = true;

        Thread t = new Thread(this::acceptLoop, "projectx-control");
        t.setDaemon(true);
        t.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}
        }));
        System.out.println("[Supervisor] control socket listening at " + socketPath);
    }

    private void acceptLoop() {
        while (running) {
            try (SocketChannel ch = server.accept()) {
                handleConnection(ch);
            } catch (Throwable t) {
                if (running) System.err.println("[Supervisor] control accept error: " + t);
            }
        }
    }

    private void handleConnection(SocketChannel ch) throws IOException {
        BufferedReader in = new BufferedReader(
            new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
        Writer out = new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8);
        String line = in.readLine();
        if (line == null) return;
        String resp;
        try { resp = handler.apply(line); } catch (Throwable t) { resp = "ERR " + t; }
        out.write(resp);
        out.write('\n');
        out.flush();
    }

    /**
     * AF_UNIX sockets are bound to a filesystem path on Windows too, so only the directory choice is
     * platform-specific: the XDG runtime dir when the desktop provides one, otherwise the JVM's temp
     * dir, which resolves correctly on both platforms.
     */
    private static Path resolveSocketPath() {
        long pid = ProcessHandle.current().pid();
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path base = (runtime != null && !runtime.isEmpty())
            ? Paths.get(runtime, "projectx")
            : Paths.get(System.getProperty("java.io.tmpdir"),
                        "projectx-" + System.getProperty("user.name", "user"));
        return base.resolve(pid + ".sock");
    }
}
