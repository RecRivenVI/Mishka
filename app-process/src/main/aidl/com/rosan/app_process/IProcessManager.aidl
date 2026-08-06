package com.rosan.app_process;

interface IProcessManager {
    void exit(int code) = 1;

    // Transaction 2 is handled manually by ProcessManager.onTransact.
}
