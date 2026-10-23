package com.garua.gps;

import android.content.Context;

// Captura cualquier excepción NO controlada del proceso (crash nativo/Java) y la
// escribe a un archivo ANTES de que el sistema mate la app. Así queda registro de
// QUÉ y POR QUÉ falló aunque la app se cierre de golpe. El archivo se guarda en el
// almacenamiento interno de la app (getFilesDir → Capacitor Directory.Data), que JS
// lee después para exportar el .txt de diagnóstico.
public class CrashCatcher implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler prev;
    private final Context ctx;

    public CrashCatcher(Context c) {
        this.ctx = c.getApplicationContext();
        this.prev = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void instalar(Context c) {
        try { Thread.setDefaultUncaughtExceptionHandler(new CrashCatcher(c)); } catch (Throwable ignored) {}
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            pw.println("===== CRASH NATIVO " + ts + " =====");
            pw.println("hilo: " + (t != null ? t.getName() : "?"));
            pw.println("equipo: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " · Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
            try {
                Runtime rt = Runtime.getRuntime();
                long usadaMB = (rt.totalMemory() - rt.freeMemory()) / 1048576;
                long maxMB = rt.maxMemory() / 1048576;
                pw.println("memoria JVM: " + usadaMB + "/" + maxMB + " MB");
            } catch (Throwable ig) {}
            e.printStackTrace(pw);
            pw.flush();
            java.io.File f = new java.io.File(ctx.getFilesDir(), "crash-nativo.log");
            // Acotar el archivo: si ya pasa ~256 KB, empezar de cero para no crecer sin fin.
            boolean append = f.exists() && f.length() < 262144;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, append);
            fos.write((sw.toString() + "\n").getBytes("UTF-8"));
            fos.flush(); fos.close();
        } catch (Throwable ignored) {}
        // Dejar que el sistema siga su curso (mostrar "la app se detuvo").
        if (prev != null) prev.uncaughtException(t, e);
    }
}
