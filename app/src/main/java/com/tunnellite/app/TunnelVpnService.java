package com.tunnellite.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;

import hev.htproxy.TProxyService;

public class TunnelVpnService extends VpnService {

    public static final String ACTION_START = "com.tunnellite.START";
    public static final String ACTION_STOP = "com.tunnellite.STOP";

    public static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2;
    public static volatile int state = DISCONNECTED;
    private static final StringBuilder LOG = new StringBuilder();

    private SshTunnel tunnel;
    private ParcelFileDescriptor vpn;
    private Thread worker;
    private boolean tproxyStarted;

    public static synchronized void log(String s) {
        LOG.append(s).append('\n');
        if (LOG.length() > 4000) LOG.delete(0, LOG.length() - 4000);
    }

    public static synchronized String getLog() { return LOG.toString(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            disconnect();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || state != DISCONNECTED) return START_NOT_STICKY;

        startForegroundNotif();

        final SshTunnel.Config c = new SshTunnel.Config();
        c.host = intent.getStringExtra("host");
        c.port = intent.getIntExtra("port", 443);
        c.user = intent.getStringExtra("user");
        c.pass = intent.getStringExtra("pass");
        c.sni = intent.getStringExtra("sni");
        c.payload = intent.getStringExtra("payload");
        c.useSni = intent.getBooleanExtra("useSni", true);

        state = CONNECTING;
        log("Conectando...");
        worker = new Thread(() -> {
            try {
                tunnel = new SshTunnel();
                tunnel.start(c, TunnelVpnService::log);

                Builder b = new Builder();
                b.setSession("Tunnel Lite");
                b.setMtu(1500);
                b.addAddress("198.18.0.1", 32);
                b.addAddress("fd00::1", 128);
                b.addRoute("0.0.0.0", 0);
                b.addRoute("::", 0);
                b.addDnsServer("198.18.0.2");
                // o proprio app fica fora da VPN, para o SSH nao entrar em loop
                b.addDisallowedApplication(getPackageName());
                vpn = b.establish();
                if (vpn == null) throw new IllegalStateException("VPN nao autorizada");

                File cfg = new File(getFilesDir(), "tproxy.yml");
                String yml = "tunnel:\n"
                        + "  mtu: 1500\n"
                        + "  ipv4: 198.18.0.1\n"
                        + "socks5:\n"
                        + "  port: " + SshTunnel.PROXY_PORT + "\n"
                        + "  address: 127.0.0.1\n"
                        + "mapdns:\n"
                        + "  address: 198.18.0.2\n"
                        + "  port: 53\n"
                        + "  network: 240.0.0.0\n"
                        + "  netmask: 240.0.0.0\n"
                        + "  cache-size: 10000\n";
                try (FileOutputStream fo = new FileOutputStream(cfg)) {
                    fo.write(yml.getBytes("UTF-8"));
                }
                TProxyService.TProxyStartService(cfg.getAbsolutePath(), vpn.getFd());
                tproxyStarted = true;
                log("tun2socks iniciado.");

                state = CONNECTED;
                log("Conectado.");
                while (state == CONNECTED && tunnel != null && tunnel.isAlive()) {
                    Thread.sleep(2000);
                }
                if (state == CONNECTED) log("Ligacao SSH caiu.");
            } catch (InterruptedException ignored) {
            } catch (Throwable e) {
                log("Erro: " + e);
            }
            disconnect();
            stopSelf();
        }, "tunnel-worker");
        worker.start();
        return START_NOT_STICKY;
    }

    private void startForegroundNotif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("tunnel", "Tunnel", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "tunnel")
                .setContentTitle("Tunnel Lite")
                .setContentText("Tunel ativo")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build();
        startForeground(1, n);
    }

    private synchronized void disconnect() {
        state = DISCONNECTED;
        if (tproxyStarted) {
            try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
            tproxyStarted = false;
        }
        try { if (vpn != null) vpn.close(); } catch (Throwable ignored) {}
        vpn = null;
        if (tunnel != null) tunnel.stop();
        tunnel = null;
        stopForeground(true);
    }

    @Override
    public void onRevoke() {
        disconnect();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
