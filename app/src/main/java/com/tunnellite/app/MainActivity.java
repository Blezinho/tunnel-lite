package com.tunnellite.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

public class MainActivity extends Activity {

    private EditText etHost, etPort, etUser, etPass, etSni, etPayload;
    private RadioButton rbSni, rbPayload;
    private Button btn;
    private TextView tvStatus, tvLog;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etSni = findViewById(R.id.etSni);
        etPayload = findViewById(R.id.etPayload);
        rbSni = findViewById(R.id.rbSni);
        rbPayload = findViewById(R.id.rbPayload);
        btn = findViewById(R.id.btnConnect);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);

        etHost.setText(prefs.getString("host", ""));
        etPort.setText(prefs.getString("port", "443"));
        etUser.setText(prefs.getString("user", ""));
        etPass.setText(prefs.getString("pass", ""));
        etSni.setText(prefs.getString("sni", ""));
        etPayload.setText(prefs.getString("payload", "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]"));
        boolean useSni = prefs.getBoolean("useSni", true);
        rbSni.setChecked(useSni);
        rbPayload.setChecked(!useSni);

        View.OnClickListener modeListener = v -> applyMode();
        rbSni.setOnClickListener(modeListener);
        rbPayload.setOnClickListener(modeListener);
        applyMode();

        btn.setOnClickListener(v -> toggle());

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void applyMode() {
        boolean sni = rbSni.isChecked();
        etSni.setVisibility(sni ? View.VISIBLE : View.GONE);
        etPayload.setVisibility(sni ? View.GONE : View.VISIBLE);
    }

    private void toggle() {
        if (TunnelVpnService.state != TunnelVpnService.DISCONNECTED) {
            Intent s = new Intent(this, TunnelVpnService.class).setAction(TunnelVpnService.ACTION_STOP);
            startService(s);
            return;
        }
        save();
        Intent i = VpnService.prepare(this);
        if (i != null) startActivityForResult(i, 1);
        else startVpn();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1 && res == RESULT_OK) startVpn();
    }

    private void startVpn() {
        Intent s = new Intent(this, TunnelVpnService.class).setAction(TunnelVpnService.ACTION_START);
        s.putExtra("host", etHost.getText().toString().trim());
        int port = 443;
        try { port = Integer.parseInt(etPort.getText().toString().trim()); } catch (Exception ignored) {}
        s.putExtra("port", port);
        s.putExtra("user", etUser.getText().toString().trim());
        s.putExtra("pass", etPass.getText().toString());
        s.putExtra("sni", etSni.getText().toString().trim());
        s.putExtra("payload", etPayload.getText().toString());
        s.putExtra("useSni", rbSni.isChecked());
        startForegroundService(s);
    }

    private void save() {
        prefs.edit()
                .putString("host", etHost.getText().toString().trim())
                .putString("port", etPort.getText().toString().trim())
                .putString("user", etUser.getText().toString().trim())
                .putString("pass", etPass.getText().toString())
                .putString("sni", etSni.getText().toString().trim())
                .putString("payload", etPayload.getText().toString())
                .putBoolean("useSni", rbSni.isChecked())
                .apply();
    }

    private void update() {
        switch (TunnelVpnService.state) {
            case TunnelVpnService.CONNECTING:
                tvStatus.setText("Conectando...");
                btn.setText("CANCELAR");
                break;
            case TunnelVpnService.CONNECTED:
                tvStatus.setText("Conectado");
                btn.setText("DESCONECTAR");
                break;
            default:
                tvStatus.setText("Desconectado");
                btn.setText("CONECTAR");
        }
        tvLog.setText(TunnelVpnService.getLog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
