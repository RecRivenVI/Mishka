package com.rosan.app_process;

import android.content.Context;
import android.net.IConnectivityManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.Keep;

public class ProcessManager extends IProcessManager.Stub {
    private static final String TAG = "ProcessManager";
    private static final int FIREWALL_CHAIN_OEM_DENY_3 = 9;
    private static final int FIREWALL_RULE_DEFAULT = 0;
    private static final Object RECOVERY_LOCK = new Object();

    private static NetworkRecovery pendingRecovery;

    @Keep
    public ProcessManager() {
        super();
    }

    @Override
    public void exit(int code) {
        restorePendingNetwork();
        System.exit(code);
    }

    @Override
    public boolean armNetworkRecovery(int uid, boolean chainWasEnabled) {
        // The parent arms this before applying UID deny, so EOF can always restore the baseline.
        synchronized (RECOVERY_LOCK) {
            pendingRecovery = new NetworkRecovery(uid, chainWasEnabled);
        }
        return true;
    }

    @Override
    public boolean clearNetworkRecovery() {
        synchronized (RECOVERY_LOCK) {
            pendingRecovery = null;
        }
        return true;
    }

    static boolean restorePendingNetwork() {
        final NetworkRecovery recovery;
        synchronized (RECOVERY_LOCK) {
            recovery = pendingRecovery;
            if (recovery == null) return true;
            pendingRecovery = null;
        }
        try {
            IConnectivityManager connectivity = IConnectivityManager.Stub.asInterface(
                    ServiceManager.getService(Context.CONNECTIVITY_SERVICE)
            );
            connectivity.setUidFirewallRule(
                    FIREWALL_CHAIN_OEM_DENY_3,
                    recovery.uid,
                    FIREWALL_RULE_DEFAULT
            );
            if (!recovery.chainWasEnabled) {
                connectivity.setFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3, false);
            }
            Log.i(TAG, "Restored pending network state before privileged process exit");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to restore pending network state", e);
            return false;
        }
    }

    private static final class NetworkRecovery {
        final int uid;
        final boolean chainWasEnabled;

        NetworkRecovery(int uid, boolean chainWasEnabled) {
            this.uid = uid;
            this.chainWasEnabled = chainWasEnabled;
        }
    }

    private boolean targetTransact(IBinder binder, int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return AppProcess.binderWithCleanCallingIdentity(() -> binder.transact(code, data, reply, flags));
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != AppProcess.TRANSACTION_REMOTE_TRANSACT)
            return super.onTransact(code, data, reply, flags);
        Parcel targetData = Parcel.obtain();
        try {
            data.enforceInterface(this.asBinder().getInterfaceDescriptor());
            IBinder binder = data.readStrongBinder();
            int targetCode = data.readInt();
            int targetFlags = data.readInt();
            targetData.appendFrom(data, data.dataPosition(), data.dataAvail());
            return targetTransact(binder, targetCode, targetData, reply, targetFlags);
        } finally {
            targetData.recycle();
        }
    }
}
