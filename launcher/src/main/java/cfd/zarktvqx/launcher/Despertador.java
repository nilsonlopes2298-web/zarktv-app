package cfd.zarktvqx.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Liga junto com a box e acorda a cada 15 min para conversar com o painel. */
public class Despertador extends BroadcastReceiver {
    static final long INTERVALO = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent i) {
        agendar(c);
        SincronizarService.agora(c);
    }

    public static void agendar(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, Despertador.class).setAction("cfd.zarktvqx.launcher.ACORDAR");
        PendingIntent p = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(p);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVALO, INTERVALO, p);
    }
}
