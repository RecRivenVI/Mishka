package com.rosan.app_process;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.Keep;

public class ProcessManager extends IProcessManager.Stub {
    @Keep
    public ProcessManager() {
        super();
    }

    @Override
    public void exit(int code) {
        System.exit(code);
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
