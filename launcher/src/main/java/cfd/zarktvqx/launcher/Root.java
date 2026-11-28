package cfd.zarktvqx.launcher;

import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;

/** Comandos com root (muitas MXQ já vêm com "su"). */
public final class Root {
    private static Boolean tem;

    private Root() {}

    public static synchronized boolean tem() {
        if (tem != null) return tem;
        boolean achou = false;
        String[] lugares = {"/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su", "/vendor/bin/su"};
        for (String l : lugares) if (new File(l).exists()) { achou = true; break; }
        if (!achou) { tem = false; return false; }
        Resultado r = rodar("id");
        tem = r.ok && r.saida.contains("uid=0");
        return tem;
    }

    public static final class Resultado {
        public final boolean ok;
        public final String saida;
        Resultado(boolean ok, String saida) { this.ok = ok; this.saida = saida; }
    }

    public static Resultado rodar(String comando) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(comando + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();
            String saida = new String(Rede.ler(p.getInputStream()), "UTF-8");
            InputStream err = p.getErrorStream();
            String erro = new String(Rede.ler(err), "UTF-8");
            int cod = p.waitFor();
            return new Resultado(cod == 0, (saida + erro).trim());
        } catch (Exception e) {
            return new Resultado(false, String.valueOf(e.getMessage()));
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Aspas seguras para o shell. */
    public static String aspas(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
