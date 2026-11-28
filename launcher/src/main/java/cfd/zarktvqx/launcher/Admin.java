package cfd.zarktvqx.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Recebe a "posse" da box (dpm set-device-owner). Ao ganhar, já protege e sincroniza. */
public class Admin extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context c, Intent i) {
        SincronizarService.agora(c);
    }

    @Override
    public void onProfileProvisioningComplete(Context c, Intent i) {
        SincronizarService.agora(c);
    }
}
