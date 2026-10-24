package com.tunnellite.app;

import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.Session;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Servidor SOCKS5 local (so CONNECT) que encaminha tudo por canais direct-tcpip do SSH. */
public class SocksServer {

    private final Session session;
    private final Object lock = new Object();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;

    public SocksServer(Session session) { this.session = session; }

    public void start(int port) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    break;
                }
            }
        }, "socks-accept").start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    private static void reply(OutputStream out, int code) throws IOException {
        out.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private void handle(Socket client) {
        ChannelDirectTCPIP ch = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            // saudacao
            if (in.read() != 5) { client.close(); return; }
            int nm = in.read();
            readFully(in, new byte[nm]);
            out.write(new byte[]{5, 0});
            out.flush();

            // pedido
            byte[] h = new byte[4];
            readFully(in, h);
            int cmd = h[1], atyp = h[3];
            String host;
            if (atyp == 1) {
                byte[] a = new byte[4];
                readFully(in, a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else if (atyp == 3) {
                int len = in.read();
                byte[] a = new byte[len];
                readFully(in, a);
                host = new String(a, "ISO-8859-1");
            } else if (atyp == 4) {
                byte[] a = new byte[16];
                readFully(in, a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else {
                reply(out, 8);
                client.close();
                return;
            }
            int port = (in.read() << 8) | in.read();

            if (cmd != 1) {
                reply(out, 7);
                client.close();
                return;
            }

            InputStream sin;
            OutputStream sout;
            try {
                synchronized (lock) {
                    ch = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
                }
                ch.setHost(host);
                ch.setPort(port);
                sin = ch.getInputStream();
                sout = ch.getOutputStream();
                ch.connect(15000);
            } catch (Exception e) {
                try { reply(out, 5); } catch (IOException ignored) {}
                client.close();
                return;
            }
            reply(out, 0);

            final ChannelDirectTCPIP fch = ch;
            final Socket fclient = client;
            final OutputStream fsout = sout;
            Thread up = new Thread(() -> {
                copy(in, fsout);
                closeQuietly(fch, fclient);
            }, "socks-up");
            up.start();
            copy(sin, out);
            closeQuietly(ch, client);
        } catch (Exception e) {
            closeQuietly(ch, client);
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {}
    }

    private static void closeQuietly(ChannelDirectTCPIP ch, Socket s) {
        try { if (ch != null) ch.disconnect(); } catch (Throwable ignored) {}
        try { s.close(); } catch (Throwable ignored) {}
    }
}
