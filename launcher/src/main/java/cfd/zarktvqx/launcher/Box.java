package cfd.zarktvqx.launcher;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

/**
 * Tudo sobre esta box: identidade, modo de controle e o que mandar ao painel.
 *
 * Modos (descobertos sozinhos, a box antiga e a nova usam o mesmo APK):
 *  - "dona"    : launcher é dona do aparelho (Android 5+, configurada uma vez pelo ADB).
 *                Instala/atualiza sem clique, esconde apps, bloqueia instalações de fora e fica fixa como tela inicial.
 *  - "root"    : box com root (muitas MXQ vêm assim). Faz o mesmo usando o "su".
 *  - "simples" : sem nenhum dos dois. Funciona como tela inicial, mas instalação pede um clique do cliente.
 */
public final class Box {
    public static final String DONA = "dona", ROOT = "root", SIMPLES = "simples";

    private Box() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("box", Context.MODE_PRIVATE);
    }

    public static ComponentName admin(Context c) {
        return new ComponentName(c, Admin.class);
    }

    public static boolean ehDona(Context c) {
        if (Build.VERSION.SDK_INT < 21) return false;
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
    }

    public static String modo(Context c) {
        if (ehDona(c)) return DONA;
        if (Root.tem()) return ROOT;
        return SIMPLES;
    }

    public static String token(Context c) { return prefs(c).getString("token", null); }
    public static String codigo(Context c) { return prefs(c).getString("codigo", "—"); }
    public static String cliente(Context c) { return prefs(c).getString("cliente", null); }

    public static String idAparelho(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        return (id == null ? "" : id) + "-" + Build.SERIAL;
    }

    public static int memoriaMb(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 16) {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                return (int) (mi.totalMem / (1024 * 1024));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public static String processador() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"));
            try {
                String l;
                String hw = null;
                while ((l = br.readLine()) != null) {
                    if (l.startsWith("Hardware")) hw = l.substring(l.indexOf(':') + 1).trim();
                }
                if (hw != null) return hw;
            } finally {
                br.close();
            }
        } catch (Exception ignored) {}
        return Build.HARDWARE;
    }

    /** "wifi" com sinal 0..4, "cabo" ou "sem". */
    public static String redeTipo(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return "sem";
        if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) return "cabo";
        return "wifi";
    }

    public static int sinalWifi(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int rssi = wm.getConnectionInfo().getRssi();
            return WifiManager.calculateSignalLevel(rssi, 5); // 0..4
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean instalado(Context c, String pacote) {
        try {
            c.getPackageManager().getPackageInfo(pacote, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Ficha que vai ao painel a cada conversa. */
    public static JSONObject ficha(Context c) throws Exception {
        JSONObject j = new JSONObject();
        j.put("id_aparelho", idAparelho(c));
        j.put("token", token(c));
        j.put("fabricante", Build.MANUFACTURER);
        j.put("modelo", Build.MODEL);
        j.put("placa", Build.BOARD);
        j.put("processador", processador());
        j.put("android", Build.VERSION.RELEASE);
        j.put("sdk", Build.VERSION.SDK_INT);
        j.put("modo", modo(c));
        j.put("memoria_mb", memoriaMb(c));
        j.put("launcher_versao", BuildConfig.VERSION_NAME);
        j.put("launcher_codigo", BuildConfig.VERSION_CODE);
        j.put("tela_inicial", ehTelaInicial(c));
        String rede = redeTipo(c);
        j.put("rede", rede);
        if ("wifi".equals(rede)) j.put("sinal", sinalWifi(c));
        JSONArray apps = new JSONArray();
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> lista = pm.getInstalledPackages(0);
        for (PackageInfo pi : lista) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            JSONObject a = new JSONObject();
            a.put("p", pi.packageName);
            a.put("v", pi.versionName == null ? "" : pi.versionName);
            a.put("c", pi.versionCode);
            a.put("s", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            a.put("on", ai.enabled);
            try {
                CharSequence nome = pm.getApplicationLabel(ai);
                a.put("n", nome == null ? pi.packageName : nome.toString());
            } catch (Exception e) {
                a.put("n", pi.packageName);
            }
            apps.put(a);
        }
        j.put("apps", apps);
        return j;
    }

    public static boolean ehTelaInicial(Context c) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }
}
