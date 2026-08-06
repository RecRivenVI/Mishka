package top.yukonga.mishka.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.core.module.Module
import org.koin.dsl.module
import top.yukonga.mishka.data.backup.BackupManager
import top.yukonga.mishka.data.database.getAppDatabase
import top.yukonga.mishka.platform.AppListProvider
import top.yukonga.mishka.platform.BootStartManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceController
import top.yukonga.mishka.platform.WifiPolicyController
import top.yukonga.mishka.platform.privileged.DeviceCapabilityProvider
import top.yukonga.mishka.platform.privileged.DeviceCapabilityProviderImpl
import top.yukonga.mishka.platform.privileged.PrivilegedOperationProvider
import top.yukonga.mishka.platform.privileged.RootProcessSession
import top.yukonga.mishka.platform.privileged.lifecycle.RecyclerManager
import top.yukonga.mishka.platform.privileged.process.AppProcessTerminal
import top.yukonga.mishka.platform.privileged.recycler.AppProcessRecycler
import top.yukonga.mishka.platform.privileged.recycler.ProcessHookRecycler
import top.yukonga.mishka.platform.privileged.recycler.ShizukuHookRecycler
import top.yukonga.mishka.platform.privileged.PROCESS_HOOK_RECYCLER_MANAGER_QUALIFIER
import top.yukonga.mishka.service.ProfileUpdateScheduler
import top.yukonga.mishka.service.SuperIslandBypass
import top.yukonga.mishka.service.VpnNotificationPublisher

/**
 * Android 平台单例：均绑定 application Context（`androidContext()`）。
 * 需 Activity 上下文的资源（FilePicker / VPN 授权 launcher）由 MainActivity 直接持有，不入 Koin。
 */
val androidPlatformModule: Module = module {
    single { getAppDatabase(androidContext()) }
    single { PlatformStorage(androidContext()) }
    single<DeviceCapabilityProvider> { DeviceCapabilityProviderImpl(androidContext()) }
    single { RecyclerManager<AppProcessTerminal, AppProcessRecycler> { AppProcessRecycler(it) } }
    single(named(PROCESS_HOOK_RECYCLER_MANAGER_QUALIFIER)) {
        RecyclerManager<AppProcessTerminal, ProcessHookRecycler> { terminal ->
            ProcessHookRecycler(terminal, androidContext(), get())
        }
    }
    single { RootProcessSession(get(named(PROCESS_HOOK_RECYCLER_MANAGER_QUALIFIER))) }
    single { ShizukuHookRecycler() }
    single { PrivilegedOperationProvider() }
    single { SuperIslandBypass(androidContext(), get(), get(), get(), get()) }
    single { VpnNotificationPublisher(androidContext(), get(), get(), get()) }
    single { ProxyServiceController(androidContext()) }
    single { AppListProvider(androidContext()) }
    single { WifiPolicyController(androidContext()) }
    single { BootStartManager(androidContext()) }
    single { BackupManager(androidContext(), get(), get(), get(), get()) }
    single { ProfileUpdateScheduler(androidContext(), get(), get()) }
}
