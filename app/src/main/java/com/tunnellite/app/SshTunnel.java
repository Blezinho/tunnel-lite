package com.tunnellite.app;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Abre SSH (opcionalmente dentro de TLS com SNI, ou apos um payload HTTP) e sobe um SOCKS5 local. */
public class SshTunnel {

    public static final int PROXY_PORT = 1080;

    public interface LogSink { void log(String s); }

    public static class Config {
        public String host, user, pass, sni, payload;
        public int port;
        public boolean useSni;
    }

    private Session session;
    private SocksServer proxy;

    public void start(final Config c, final LogSink log) throws Exception {
        JSch jsch = new JSch();
        session = jsch.getSession(c.user, c.host, c.port);
        session.setPassword(c.pass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.setServerAliveInterval(30000);
        session.setSocketFactory(new SocketFactory() {
            @Override
            public Socket createSocket(String h, int p) throws IOException {
                try {
                    return openBaseSocket(c, log);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            @Override
            public InputStream getInputStream(Socket s) throws IOException { return s.getInputStream(); }
            @Override
            public OutputStream getOutputStream(Socket s) throws IOException { return s.getOutputStream(); }
        });
        log.log("Abrindo SSH...");
        session.connect(25000);
        log.log("SSH autenticado.");
        proxy = new SocksServer(session);
        proxy.start(PROXY_PORT);
        log.log("SOCKS5 local em 127.0.0.1:" + PROXY_PORT);
    }

    public boolean isAlive() {
        return session != null && session.isConnected();
    }

    public void stop() {
        try { if (proxy != null) proxy.stop(); } catch (Throwable ignored) {}
        try { if (session != null) session.disconnect(); } catch (Throwable ignored) {}
        proxy = null;
        session = null;
    }

    private static Socket openBaseSocket(Config c, LogSink log) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(c.host, c.port), 15000);
        raw.setTcpNoDelay(true);

        if (c.useSni) {
            String sni = (c.sni == null || c.sni.isEmpty()) ? c.host : c.sni;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String a) {}
                public void checkServerTrusted(X509Certificate[] chain, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, sni, c.port, true);
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(sni)));
            s.setSSLParameters(p);
            log.log("TLS handshake com SNI " + sni + "...");
            s.startHandshake();
            log.log("TLS ok.");
            return s;
        } else {
            String pl = c.payload == null ? "" : c.payload;
            pl = pl.replace("[crlf]", "\r\n").replace("[cr]", "\r").replace("[lf]", "\n")
                    .replace("[host]", c.host).replace("[port]", String.valueOf(c.port));
            OutputStream out = raw.getOutputStream();
            out.write(pl.getBytes("ISO-8859-1"));
            out.flush();
            log.log("Payload enviado.");
            // le a resposta HTTP ate ao fim dos cabecalhos
            InputStream in = raw.getInputStream();
            int m = 0, n = 0, ch;
            StringBuilder first = new StringBuilder();
            while (n < 8192 && (ch = in.read()) != -1) {
                n++;
                if (first.length() < 80 && ch != '\r' && ch != '\n') first.append((char) ch);
                if ((ch == '\r' && (m == 0 || m == 2)) || (ch == '\n' && (m == 1 || m == 3))) m++;
                else m = (ch == '\r') ? 1 : 0;
                if (m == 4) break;
            }
            log.log("Resposta: " + first);
            return raw;
        }
    }
}
