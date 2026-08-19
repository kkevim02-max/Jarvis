package com.levy.jarvis;

import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public final class ActionExecutor {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ActionExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim();
    }

    public void execute(JSONObject a) {
        String type = a.optString("type", "");
        String target = a.optString("target", "");
        String app = a.optString("app", "");
        int value = a.optInt("value", 0);

        switch (type) {
            case "volume_set": setVolume(value); break;
            case "volume_up": adjustVolume(AudioManager.ADJUST_RAISE); break;
            case "volume_down": adjustVolume(AudioManager.ADJUST_LOWER); break;
            case "media_play": media(KeyEvent.KEYCODE_MEDIA_PLAY); break;
            case "media_pause": media(KeyEvent.KEYCODE_MEDIA_PAUSE); break;
            case "media_next": media(KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "media_previous": media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "bluetooth_on": bluetooth(true); break;
            case "bluetooth_off": bluetooth(false); break;
            case "bluetooth_connect": connectBluetooth(target); break;
            case "wifi_on": wifi(true); break;
            case "wifi_off": wifi(false); break;
            case "open_app": openApp(target); break;
            case "search_music":
                if (!app.isEmpty()) openApp(app);
                main.postDelayed(() -> JarvisAccessibilityService.searchAndPlay(target), 1400);
                break;
            case "home":
                if (!JarvisAccessibilityService.globalHome()) RootBridge.keyEvent(3);
                break;
            case "back":
                if (!JarvisAccessibilityService.globalBack()) RootBridge.keyEvent(4);
                break;
            case "lock_screen": lockScreen(); break;
        }
    }

    private void setVolume(int percent) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int v = Math.max(0, Math.min(100, percent));
        int target = Math.round(max * (v / 100f));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
    }

    private void adjustVolume(int dir) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
    }

    private void media(int key) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        long t = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
    }

    private void bluetooth(boolean enable) {
        BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
        if (b == null) return;
        if (enable && !b.isEnabled()) b.enable();
        if (!enable && b.isEnabled()) b.disable();
    }

    private void connectBluetooth(String device) {
        BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
        if (b != null && !b.isEnabled()) b.enable();

        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);

        main.postDelayed(() -> {
            if (!device.isEmpty()) JarvisAccessibilityService.clickTextContains(device);
        }, 1400);
    }

    private void wifi(boolean enabled) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            wm.setWifiEnabled(enabled);
        } catch (Exception ignored) {}
    }

    private void openApp(String label) {
        if (label == null || label.trim().isEmpty()) return;
        PackageManager pm = context.getPackageManager();
        String wanted = norm(label);

        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            ApplicationInfo best = null;
            int bestScore = -1;

            for (ApplicationInfo info : apps) {
                CharSequence cs = pm.getApplicationLabel(info);
                String name = norm(cs == null ? "" : cs.toString());
                if (name.equals(wanted)) { best = info; bestScore = 100; break; }
                int score = 0;
                if (name.contains(wanted)) score = 80;
                else if (wanted.contains(name) && name.length() >= 3) score = 60;
                if (score > bestScore) { best = info; bestScore = score; }
            }

            if (best != null && bestScore > 0) {
                Intent launch = pm.getLaunchIntentForPackage(best.packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                }
            }
        } catch (Exception ignored) {}
    }

    private void lockScreen() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, JarvisDeviceAdminReceiver.class);
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow();
                return;
            }
        } catch (Exception ignored) {}
        RootBridge.keyEvent(26);
    }
}
