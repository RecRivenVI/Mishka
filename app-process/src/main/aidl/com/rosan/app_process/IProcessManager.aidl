package com.rosan.app_process;

interface IProcessManager {
    void exit(int code) = 1;

    boolean armNetworkRecovery(int uid, boolean chainWasEnabled) = 4;

    boolean clearNetworkRecovery() = 5;

    // Transaction 2 is handled manually by ProcessManager.onTransact.
}
