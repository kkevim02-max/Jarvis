package com.levy.jarvis;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class RootBridge {
    private RootBridge() {}

    // Evita abrir um novo processo "su" a cada atualização da tela.
    // O resultado é mantido durante a execução do app; ao reiniciar o processo,
    // o Jarvis testa novamente normalmente.
    private static volatile Boolean cachedAvailable = null;

    public static boolean available() {
        Boolean cached = cachedAvailable;
        if (cached != null) return cached;

        synchronized (RootBridge.class) {
            if (cachedAvailable != null) return cachedAvailable;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                cachedAvailable = (p.waitFor() == 0);
            } catch (Exception e) {
                cachedAvailable = false;
            }
            return cachedAvailable;
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
