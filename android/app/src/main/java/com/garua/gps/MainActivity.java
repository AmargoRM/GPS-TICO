package com.garua.gps;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registrar plugins nativos antes de super.
        registerPlugin(KeepAwakePlugin.class);
        registerPlugin(GnssPlugin.class);
        super.onCreate(savedInstanceState);
        // Acción del widget en arranque en frío: se guarda y JS la consume al iniciar.
        String action = getIntent() != null ? getIntent().getStringExtra("widget_action") : null;
        if (action != null) WidgetActionBridge.setPending(action);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // App ya viva (arranque en caliente): entregar la acción al plugin → JS.
        String action = intent != null ? intent.getStringExtra("widget_action") : null;
        if (action != null) WidgetActionBridge.deliver(action);
    }
}
