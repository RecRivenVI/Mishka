package top.yukonga.mishka.platform.privileged

import android.content.pm.PackageManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import top.yukonga.mishka.BuildConfig

suspend fun <T> requireShizukuPermissionGranted(action: suspend () -> T): T {
    callbackFlow {
        Sui.init(BuildConfig.APPLICATION_ID)
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.pingBinder()) {
                send(Unit)
            } else {
                close(IllegalStateException("Shizuku service is not running."))
            }
            awaitClose()
        } else {
            val requestCode = (Int.MIN_VALUE..Int.MAX_VALUE).random()
            val listener =
                Shizuku.OnRequestPermissionResultListener { currentRequestCode, _ ->
                    if (currentRequestCode != requestCode) return@OnRequestPermissionResultListener
                    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                        trySend(Unit)
                    } else {
                        close(IllegalStateException("Shizuku permission denied."))
                    }
                }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(requestCode)
            awaitClose { Shizuku.removeRequestPermissionResultListener(listener) }
        }
    }.catch {
        throw IllegalStateException("Shizuku is not available.", it)
    }.first()

    return action()
}
