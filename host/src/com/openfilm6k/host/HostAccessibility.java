package com.openfilm6k.host;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** registered as an accessibility service purely for the system-bound keep-alive:
    engine + :8800 server must be reachable for the camera even when the app is not open */
public class HostAccessibility extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        Engine.get().start();
        Server.get().start(this);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
