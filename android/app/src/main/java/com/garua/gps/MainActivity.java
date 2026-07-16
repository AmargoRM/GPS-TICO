package com.garua.gps;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Registrar el plugin nativo de pantalla encendida antes de super.
        registerPlugin(KeepAwakePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
