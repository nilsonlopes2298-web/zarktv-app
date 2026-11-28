package cfd.zarktvqx.launcher;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Ações na box, escolhendo sozinho o jeito certo para cada modo (dona / root / simples).
 * Cada método devolve null se deu certo ou o texto do erro (vai para o painel).
 */
public final class Controle {
    private Controle() {}

    /** Pacotes que nunca podem ser escondidos/desinstalados pelo painel. */
    static boolean protegido(Context c, String pacote) {
        return pacote.equals(c.getPackageName())
                || pacote.equals("android")
                || pacote.startsWith("com.android.systemui")
                || pacote.equals("com.android.settings")
                || pacote.equals("com.android.providers.settings")
                || pacote.equals("com.android.packageinstaller")
                || pacote.equals("com.google.android.packageinstaller")
                || pacote.equals("com.android.shell");
    }

    private static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    // ---------- Instalar ----------

    /**
     * Instala o APK. Nos modos dona/root é silencioso. No simples abre o instalador (cliente aperta "Instalar").
     * @return null = instalou (ou instalador aberto), texto = erro.
     */
    public static String instalar(Context c, File apk) {
        String modo = Box.modo(c);
        if (Box.DONA.equals(modo)) {
            String e = instalarComoDona(c, apk);
            if (e == null) return null;
            // se o Android não aceitar, tenta os outros jeitos
            if (Root.tem()) return instalarRoot(apk);
            return abrirInstalador(c, apk);
        }
        if (Box.ROOT.equals(modo)) {
            String e = instalarRoot(apk);
            if (e == null) return null;
            return abrirInstalador(c, apk);
        }
        return abrirInstalador(c, apk);
    }

    public static boolean silencioso(Context c) {
        return !Box.SIMPLES.equals(Box.modo(c));
    }

    private static String instalarRoot(File apk) {
        // cópia para /data/local/tmp: alguns "pm" não leem a pasta do app
        String tmp = "/data/local/tmp/zark_" + System.currentTimeMillis() + ".apk";
        Root.Resultado r = Root.rodar("cat " + Root.aspas(apk.getAbsolutePath()) + " > " + tmp
                + " && chmod 644 " + tmp + " && pm install -r " + tmp + "; rm -f " + tmp);
        if (r.saida.contains("Success")) return null;
        return "pm install: " + r.saida;
    }

    @TargetApi(21)
    private static String instalarComoDona(Context c, File apk) {
        if (Build.VERSION.SDK_INT < 21) return "Android antigo";
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            InputStream in = new FileInputStream(apk);
            OutputStream out = s.openWrite("zark", 0, apk.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            in.close();
            out.close();
            Intent i = new Intent(c, ResultadoInstalacao.class);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT);
            ResultadoInstalacao.esperar(id);
            s.commit(pend.getIntentSender());
            s.close();
            String r = ResultadoInstalacao.aguardar(id, 180000);
            return r;
        } catch (Exception e) {
            return "Instalação silenciosa: " + e.getMessage();
        }
    }

    private static String abrirInstalador(Context c, File apk) {
        try {
            apk.setReadable(true, false);
            Uri u = Build.VERSION.SDK_INT >= 24 ? ApkProvider.uri(apk) : Uri.fromFile(apk);
            Intent i = new Intent(Build.VERSION.SDK_INT >= 24 ? Intent.ACTION_INSTALL_PACKAGE : Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return "Não abriu o instalador: " + e.getMessage();
        }
    }

    // ---------- Desinstalar / esconder ----------

    public static String desinstalar(Context c, String pacote) {
        if (protegido(c, pacote)) return "Pacote protegido";
        if (!Box.instalado(c, pacote)) return null;
        String modo = Box.modo(c);
        if (Root.tem()) {
            Root.Resultado r = Root.rodar("pm uninstall " + Root.aspas(pacote));
            if (r.saida.contains("Success")) return null;
            // app de sistema: tira só do usuário (volta se resetar a box)
            r = Root.rodar("pm uninstall --user 0 " + Root.aspas(pacote) + " || pm disable-user --user 0 " + Root.aspas(pacote) + " || pm disable " + Root.aspas(pacote));
            if (r.ok) return null;
            return r.saida;
        }
        if (Box.DONA.equals(modo) && Build.VERSION.SDK_INT >= 21) {
            if (sistema(c, pacote)) return esconder(c, pacote, true);
            try {
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                Intent i = new Intent(c, ResultadoInstalacao.class);
                int id = (int) (System.currentTimeMillis() & 0x7fffffff);
                PendingIntent pend = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT);
                ResultadoInstalacao.esperar(id);
                pi.uninstall(pacote, pend.getIntentSender());
                return ResultadoInstalacao.aguardar(id, 60000);
            } catch (Exception e) {
                return esconder(c, pacote, true);
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pacote));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Esconde (desliga) ou mostra de novo um app, sem apagar. */
    public static String esconder(Context c, String pacote, boolean esconder) {
        if (esconder && protegido(c, pacote)) return "Pacote protegido";
        if (Box.ehDona(c) && Build.VERSION.SDK_INT >= 21) {
            try {
                boolean ok = dpm(c).setApplicationHidden(Box.admin(c), pacote, esconder);
                if (ok) return null;
            } catch (Exception ignored) {}
        }
        if (Root.tem()) {
            Root.Resultado r = Root.rodar((esconder ? "pm disable " : "pm enable ") + Root.aspas(pacote));
            if (r.ok) return null;
            return r.saida;
        }
        return "Box no modo simples: não dá para esconder apps";
    }

    private static boolean sistema(Context c, String pacote) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pacote, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Proteger a box ----------

    /**
     * Roda ao assumir a box e a cada conversa com o painel:
     * fixa a launcher como tela inicial, bloqueia instalações de fora e as atualizações do fabricante.
     */
    public static void proteger(Context c, boolean controleTotal, boolean bloquearFora) {
        boolean dona = Box.ehDona(c) && Build.VERSION.SDK_INT >= 21;
        if (controleTotal) {
            if (dona) protegerDona(c, bloquearFora);
            else if (Root.tem()) protegerRoot(c);
        } else {
            // Box do cliente: libera tudo (ele pode instalar outras coisas e trocar a tela inicial)
            if (dona) liberarDona(c);
            if (Root.tem()) liberarRoot(c);
        }
    }

    @TargetApi(21)
    private static void liberarDona(Context c) {
        DevicePolicyManager d = dpm(c);
        ComponentName adm = Box.admin(c);
        try { d.clearPackagePersistentPreferredActivities(adm, c.getPackageName()); } catch (Exception ignored) {}
        try { d.clearUserRestriction(adm, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES); } catch (Exception ignored) {}
        try { d.setUninstallBlocked(adm, c.getPackageName(), false); } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 23) {
            try { d.setSystemUpdatePolicy(adm, null); } catch (Exception ignored) {}
        }
    }

    private static void liberarRoot(Context c) {
        java.util.Set<String> desligados = Box.prefs(c).getStringSet("telas_desligadas", null);
        if (desligados == null || desligados.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String comp : desligados) sb.append("pm enable ").append(Root.aspas(comp)).append("; ");
        Root.rodar(sb.toString());
        Box.prefs(c).edit().remove("telas_desligadas").apply();
    }

    @TargetApi(21)
    private static void protegerDona(Context c, boolean bloquearFora) {
        DevicePolicyManager d = dpm(c);
        ComponentName adm = Box.admin(c);
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(adm, f, new ComponentName(c, HomeActivity.class));
        } catch (Exception ignored) {}
        try {
            if (bloquearFora) d.addUserRestriction(adm, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            else d.clearUserRestriction(adm, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        } catch (Exception ignored) {}
        try {
            d.setUninstallBlocked(adm, c.getPackageName(), true);
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                // segura as atualizações do sistema do fabricante
                d.setSystemUpdatePolicy(adm, SystemUpdatePolicy.createPostponeInstallPolicy());
            } catch (Exception ignored) {}
        }
    }

    private static void protegerRoot(Context c) {
        // Animações mais curtas = box fraca mais rápida
        Root.rodar("settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5");
        // Desliga as outras telas iniciais, assim a nossa fica fixa
        PackageManager pm = c.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        List<android.content.pm.ResolveInfo> lista = pm.queryIntentActivities(home, 0);
        StringBuilder sb = new StringBuilder();
        java.util.Set<String> desligados = new java.util.HashSet<>(Box.prefs(c).getStringSet("telas_desligadas", new java.util.HashSet<String>()));
        for (android.content.pm.ResolveInfo ri : lista) {
            String p = ri.activityInfo.packageName;
            if (p.equals(c.getPackageName()) || p.equals("com.android.settings") || p.contains("provision")) continue;
            String comp = p + "/" + ri.activityInfo.name;
            sb.append("pm disable ").append(Root.aspas(comp)).append("; ");
            desligados.add(comp);
        }
        if (sb.length() > 0) {
            Root.rodar(sb.toString());
            Box.prefs(c).edit().putStringSet("telas_desligadas", desligados).apply();
        }
    }

    /** Fecha o que está rodando escondido (ajuda muito a box de 1 GB). */
    public static void aliviar(Context c, java.util.Set<String> manter) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationInfo> apps = c.getPackageManager().getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                String p = ai.packageName;
                if (p.equals(c.getPackageName()) || manter.contains(p) || protegido(c, p)) continue;
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                am.killBackgroundProcesses(p);
            }
        } catch (Exception ignored) {}
    }

    public static boolean reiniciar(Context c) {
        if (Box.ehDona(c) && Build.VERSION.SDK_INT >= 24) {
            try {
                dpm(c).reboot(Box.admin(c));
                return true;
            } catch (Exception ignored) {}
        }
        if (Root.tem()) {
            Root.rodar("reboot");
            return true;
        }
        return false;
    }
}
