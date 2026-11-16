package com.asoft.ios;

import android.app.Application;

/**
 * The app's process, before any of its screens or services: where the app notices how its process before ended
 * and catches its own crashes (AppExit), whichever part of it Android starts first (a screen, the setup's
 * service, adb's intents in a debug build).
 */
public class AsoftApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Keep process-start work Java-only. Reading CNTFRQ_EL0 and measuring CNTVCT_EL0 crosses
        // into native signal handling; defer that work until an engine/setup actually needs the clock.
        // A background native fault still terminates the entire Android process, even if the UI is not
        // using the engine. The counter is cached by CounterFreq when first requested.
        if (getPackageName().equals(Application.getProcessName())) {
            AppExit.onStart(this);
        }
    }
}
