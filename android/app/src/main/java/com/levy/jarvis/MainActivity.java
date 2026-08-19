package com.levy.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private final android.os.Handler handler = new android.os.Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestPermissions();
        startJarvis();
        handler.post(refresh);
    }

    private TextView text(String value, float size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(220, 240, 248));
        t.setPadding(20, 14, 20, 14);
        return t;
    }

    private Button button(String title, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(16, 8, 16, 8);
        b.setLayoutParams(p);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(8, 13, 18));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 26, 16, 26);

        TextView title = text("J.A.R.V.I.S", 32);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Color.rgb(100, 216, 255));
        box.addView(title);

        TextView subtitle = text("Android Node - Motorola", 15);
        subtitle.setGravity(Gravity.CENTER);
        box.addView(subtitle);

        status = text("Carregando...", 16);
        status.setPadding(24, 34, 24, 24);
        box.addView(status);

        box.addView(button("Iniciar Jarvis", v -> startJarvis()));
        box.addView(button("Ativar Acessibilidade", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        box.addView(button("Ativar Administrador", v -> requestAdmin()));
        box.addView(button("Ignorar economia de bateria", v -> requestBattery()));
        box.addView(button("Testar ROOT", v -> {
            boolean ok = RootBridge.available();
            android.widget.Toast.makeText(this,
                    ok ? "ROOT disponível" : "ROOT não respondeu",
                    android.widget.Toast.LENGTH_LONG).show();
        }));

        TextView note = text(
                "Depois de configurado, você não precisa deixar esta tela aberta. " +
                "O Jarvis continua ativo pela notificação.", 14);
        box.addView(note);

        scroll.addView(box);
        setContentView(scroll);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            String[] perms = {
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            boolean need = false;
            for (String p : perms) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need = true;
            if (need) requestPermissions(perms, 10);
        }
    }

    private void startJarvis() {
        Intent s = new Intent(this, JarvisService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
    }

    private void requestAdmin() {
        ComponentName cn = new ComponentName(this, JarvisDeviceAdminReceiver.class);
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permite ao Jarvis bloquear a tela quando você pedir.");
        startActivity(i);
    }

    private void requestBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String server = JarvisService.serverHost == null ? "procurando..." : JarvisService.serverHost;
            status.setText(
                    "Serviço: " + (JarvisService.running ? "ATIVO" : "PARADO") +
                    "\nPC: " + server +
                    "\nAcessibilidade: " + (JarvisAccessibilityService.available() ? "ATIVA" : "DESATIVADA") +
                    "\nROOT: " + (RootBridge.available() ? "OK" : "não autorizado") +
                    "\nÚltimo áudio: " + (JarvisService.lastHeard.isEmpty() ? "-" : JarvisService.lastHeard)
            );
            handler.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
