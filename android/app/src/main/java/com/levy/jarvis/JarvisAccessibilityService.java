package com.levy.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Locale;

public class JarvisAccessibilityService extends AccessibilityService {
    private static volatile JarvisAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean available() {
        return instance != null;
    }

    public static boolean globalBack() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public static boolean globalHome() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private static String norm(CharSequence s) {
        if (s == null) return "";
        String x = Normalizer.normalize(s.toString(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return x.trim();
    }

    private static boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        while (n != null) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }

    public static boolean clickTextContains(String target) {
        if (instance == null) return false;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return false;

        String t = norm(target);
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            String text = norm(n.getText());
            String desc = norm(n.getContentDescription());
            if ((!text.isEmpty() && text.contains(t)) || (!desc.isEmpty() && desc.contains(t))) {
                if (clickNode(n)) return true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo firstEditable() {
        if (instance == null) return null;
        AccessibilityNodeInfo root = instance.getRootInActiveWindow();
        if (root == null) return null;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if (n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return null;
    }

    public static boolean searchAndPlay(String query) {
        if (instance == null) return false;
        try {
            boolean opened =
                    clickTextContains("pesquisar") ||
                    clickTextContains("buscar") ||
                    clickTextContains("search");
            Thread.sleep(opened ? 650 : 250);

            AccessibilityNodeInfo edit = firstEditable();
            if (edit == null) return false;

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

            Thread.sleep(250);
            RootBridge.keyEvent(66);
            Thread.sleep(1700);

            if (clickTextContains(query)) return true;

            String[] parts = query.split("\\s+");
            for (String p : parts) {
                if (p.length() >= 4 && clickTextContains(p)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
