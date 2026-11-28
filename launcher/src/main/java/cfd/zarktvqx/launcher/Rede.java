package cfd.zarktvqx.launcher;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Conversa com o painel (ZarkTV Cloud) e baixa arquivos.
 * Box antiga (Android 4.4 a 7.0) não confia no certificado do Let's Encrypt e não liga o TLS 1.2
 * sozinha: aqui a gente liga o TLS 1.2 e junta os certificados ISRG aos do aparelho.
 */
public final class Rede {
    public static final String PAINEL = "https://painel.zarktvqx.cfd/boxes/api";

    private static SSLSocketFactory fabrica;

    private Rede() {}

    public static synchronized void preparar(Context ctx) {
        if (fabrica != null || Build.VERSION.SDK_INT >= 25) return;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            // Certificados que já vêm no aparelho
            KeyStore sistema = KeyStore.getInstance("AndroidCAStore");
            sistema.load(null, null);
            Enumeration<String> nomes = sistema.aliases();
            while (nomes.hasMoreElements()) {
                String n = nomes.nextElement();
                Certificate c = sistema.getCertificate(n);
                if (c != null) ks.setCertificateEntry(n, c);
            }
            // Raízes do Let's Encrypt (painel e lojinha)
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int[] raizes = {R.raw.isrg_x1, R.raw.isrg_x2};
            for (int i = 0; i < raizes.length; i++) {
                InputStream in = ctx.getResources().openRawResource(raizes[i]);
                try {
                    ks.setCertificateEntry("zark_isrg_" + i, cf.generateCertificate(in));
                } finally {
                    in.close();
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, tmf.getTrustManagers(), null);
            fabrica = new Tls12(sc.getSocketFactory());
        } catch (Exception e) {
            fabrica = null;
        }
    }

    private static HttpURLConnection abrir(String url, int tempoMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && fabrica != null) {
            ((HttpsURLConnection) c).setSSLSocketFactory(fabrica);
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(tempoMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ZarkTVLauncher/" + BuildConfig.VERSION_NAME + " (Android " + Build.VERSION.RELEASE + ")");
        return c;
    }

    /** Segue redirecionamento entre http e https (o GitHub manda para outro endereço). */
    private static HttpURLConnection abrirSeguindo(String url, int tempoMs) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = abrir(url, tempoMs);
            c.setInstanceFollowRedirects(false);
            int st = c.getResponseCode();
            if (st == 301 || st == 302 || st == 303 || st == 307 || st == 308) {
                String novo = c.getHeaderField("Location");
                c.disconnect();
                if (novo == null) throw new IOException("Redirecionamento sem endereço");
                url = new URL(new URL(url), novo).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Muitos redirecionamentos");
    }

    /** POST de JSON para o painel. Devolve a resposta em JSON (ou lança erro). */
    public static JSONObject post(String caminho, JSONObject corpo) throws Exception {
        HttpURLConnection c = abrir(PAINEL + caminho, 20000);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] dados = corpo.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(dados.length);
            OutputStream out = c.getOutputStream();
            out.write(dados);
            out.close();
            int st = c.getResponseCode();
            InputStream in = st >= 400 ? c.getErrorStream() : c.getInputStream();
            String txt = in == null ? "{}" : new String(ler(in), "UTF-8");
            JSONObject r = new JSONObject(txt.length() == 0 ? "{}" : txt);
            if (st >= 400 && !r.has("erro")) r.put("erro", "Erro " + st);
            return r;
        } finally {
            c.disconnect();
        }
    }

    public static byte[] baixarBytes(String url) throws IOException {
        HttpURLConnection c = abrirSeguindo(url, 30000);
        try {
            if (c.getResponseCode() >= 400) throw new IOException("Erro " + c.getResponseCode());
            return ler(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    public interface Progresso { void agora(int porcento); }

    /** Baixa um arquivo grande (APK) e devolve o SHA-256 dele em hexadecimal. */
    public static String baixarArquivo(String url, File destino, Progresso p) throws Exception {
        HttpURLConnection c = abrirSeguindo(url, 60000);
        try {
            if (c.getResponseCode() >= 400) throw new IOException("Erro " + c.getResponseCode() + " ao baixar");
            long total = c.getContentLength();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(destino);
            byte[] buf = new byte[16384];
            long feito = 0;
            int n, ultimo = -1;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    feito += n;
                    if (p != null && total > 0) {
                        int pc = (int) (feito * 100 / total);
                        if (pc != ultimo) { ultimo = pc; p.agora(pc); }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            return hex(md.digest());
        } finally {
            c.disconnect();
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] ler(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            in.close();
        }
        return bo.toByteArray();
    }

    /** Liga o TLS 1.2 nos sockets (Android 4.4 tem, mas vem desligado). */
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory base;
        Tls12(SSLSocketFactory base) { this.base = base; }
        private Socket ligar(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2"});
            }
            return s;
        }
        @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return ligar(base.createSocket(s, h, p, a)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return ligar(base.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return ligar(base.createSocket(h, p, l, lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return ligar(base.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return ligar(base.createSocket(h, p, l, lp)); }
    }
}
