package com.garua.gps;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registrar plugins nativos antes de super.
        registerPlugin(KeepAwakePlugin.class);
        registerPlugin(GnssPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
