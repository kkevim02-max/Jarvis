package com.levy.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Toast;

public class MainActivity extends Activity {
    private final android.os.Handler handler = new android.os.Handler();
    private TextView status;
    private TextView pc;
    private TextView heard;
    private TextView reply;
    private JarvisOrbView orb;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestPermissions();
        startJarvis();
        handler.post(refresh);
    }

    private TextView label(String text, float size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, float radius, float strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (strokeWidth > 0) g.setStroke(dp(strokeWidth), stroke);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(rounded(Color.rgb(14, 25, 34), Color.rgb(35, 82, 102), 18, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(12), dp(8), dp(12), dp(8));
        c.setLayoutParams(p);
        return c;
    }

    private Button actionButton(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.rgb(201, 241, 255));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(rounded(Color.rgb(13, 32, 43), Color.rgb(50, 181, 225), 15, 1));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1f);
        p.setMargins(dp(5), dp(5), dp(5), dp(5));
        b.setLayoutParams(p);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(5, 10, 15));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(20), dp(8), dp(28));

        TextView title = label("J.A.R.V.I.S", 31, Color.rgb(91, 220, 255));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.17f);
        root.addView(title);

        TextView sub = label("PERSONAL INTELLIGENCE • 2.0", 11, Color.rgb(112, 154, 172));
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.10f);
        root.addView(sub);

        orb = new JarvisOrbView(this);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(270));
        op.setMargins(0, dp(4), 0, 0);
        orb.setLayoutParams(op);
        root.addView(orb);

        status = label("Inicializando...", 20, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(status);

        TextView wake = label("Diga  “Ei, mano...”", 15, Color.rgb(104, 219, 246));
        wake.setGravity(Gravity.CENTER);
        wake.setPadding(dp(8), dp(2), dp(8), dp(16));
        root.addView(wake);

        LinearLayout info = card();
        TextView infoTitle = label("NÚCLEO", 11, Color.rgb(105, 175, 202));
        infoTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(infoTitle);
        pc = label("PC: procurando...", 15, Color.rgb(220, 238, 245));
        heard = label("Ouvi: —", 14, Color.rgb(180, 207, 218));
        reply = label("Resposta: —", 14, Color.rgb(180, 207, 218));
        info.addView(pc);
        info.addView(heard);
        info.addView(reply);
        root.addView(info);

        LinearLayout controls = card();
        TextView controlTitle = label("CONFIGURAÇÃO", 11, Color.rgb(105, 175, 202));
        controlTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        controls.addView(controlTitle);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(actionButton("Iniciar Jarvis", v -> startJarvis()));
        row1.addView(actionButton("Acessibilidade", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        controls.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(actionButton("Bateria", v -> requestBattery()));
        row2.addView(actionButton("Testar ROOT", v -> testRoot()));
        controls.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(actionButton("Administrador", v -> requestAdmin()));
        row3.addView(actionButton("Reconectar PC", v -> {
            JarvisService.serverHost = null;
            Toast.makeText(this, "Procurando o PC novamente", Toast.LENGTH_SHORT).show();
        }));
        controls.addView(row3);
        root.addView(controls);

        TextView note = label(
                "O Jarvis continua ativo em segundo plano. Comandos simples são executados sem esperar pela IA.",
                12, Color.rgb(102, 132, 145));
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(20), dp(12), dp(20), 0);
        root.addView(note);

        scroll.addView(root);
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
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
    }

    private void requestAdmin() {
        ComponentName cn = new ComponentName(this, JarvisDeviceAdminReceiver.class);
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn);
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Permite ao Jarvis bloquear a tela quando você pedir.");
        startActivity(i);
    }

    private void requestBattery() {
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } else {
                Toast.makeText(this, "Economia de bateria já está liberada", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void testRoot() {
        RootBridge.resetRootCache();
        boolean ok = RootBridge.available();
        Toast.makeText(this, ok ? "ROOT disponível" : "ROOT não autorizado", Toast.LENGTH_LONG).show();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            orb.setMode(JarvisService.stateCode);
            status.setText(JarvisService.statusText);
            String host = JarvisService.serverHost;
            pc.setText("PC: " + (host == null ? "procurando..." : host) +
                    "   •   ROOT: " + (RootBridge.available() ? "OK" : "—"));
            heard.setText("Ouvi: " + (JarvisService.lastHeard.isEmpty() ? "—" : JarvisService.lastHeard));
            reply.setText("Resposta: " + (JarvisService.lastReply.isEmpty() ? "—" : JarvisService.lastReply));
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refresh);
        super.onDestroy();
    }
}
