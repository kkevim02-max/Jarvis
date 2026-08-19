package com.levy.jarvis;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class RootBridge {
    private RootBridge() {}

    public static boolean available() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String exec(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            p.waitFor();
            return b.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static void keyEvent(int code) {
        exec("input keyevent " + code);
    }
}
