package top.yukonga.mishka.service

import android.content.Context
import android.util.AtomicFile
import top.yukonga.mishka.platform.privileged.Authorizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

internal data class XmsfRecoveryRecord(
    val authorizer: Authorizer,
    val chainWasEnabled: Boolean,
)

internal interface XmsfRecoveryJournal {
    fun read(): ByteArray?
    fun write(content: ByteArray): Boolean
    fun clear(): Boolean
}

internal class XmsfRecoveryStore(
    private val journal: XmsfRecoveryJournal,
) {
    constructor(context: Context) : this(
        AtomicFileXmsfRecoveryJournal(File(context.noBackupFilesDir, JOURNAL_FILE_NAME)),
    ) {
        migrateLegacyPreferences(context)
    }

    fun read(): XmsfRecoveryRecord? {
        val content = journal.read() ?: return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(content)).use { input ->
                check(input.readInt() == JOURNAL_VERSION)
                XmsfRecoveryRecord(
                    authorizer = Authorizer.fromStorage(input.readUTF()),
                    chainWasEnabled = input.readBoolean(),
                )
            }
        }.getOrElse {
            // 日志损坏时仍优先解除 UID deny；chain 原状态未知则保守地不主动关闭。
            XmsfRecoveryRecord(Authorizer.None, chainWasEnabled = true)
        }
    }

    fun arm(authorizer: Authorizer, chainWasEnabled: Boolean): Boolean {
        val content = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(JOURNAL_VERSION)
                output.writeUTF(authorizer.storageValue)
                output.writeBoolean(chainWasEnabled)
            }
            bytes.toByteArray()
        }
        return journal.write(content)
    }

    fun updateBaselineIfChanged(authorizer: Authorizer, chainWasEnabled: Boolean): Boolean {
        val current = read()
        if (current?.authorizer == authorizer && current.chainWasEnabled == chainWasEnabled) return true
        return arm(authorizer, chainWasEnabled)
    }

    fun clear(): Boolean = journal.clear()

    private fun migrateLegacyPreferences(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val migrationSucceeded = if (
            read() == null && preferences.getString(LEGACY_PENDING, "false") == "true"
        ) {
            arm(
                authorizer = Authorizer.fromStorage(
                    preferences.getString(LEGACY_AUTHORIZER, Authorizer.None.storageValue)
                        ?: Authorizer.None.storageValue
                ),
                chainWasEnabled = preferences.getString(LEGACY_CHAIN_WAS_ENABLED, "true") == "true",
            )
        } else {
            true
        }
        if (!migrationSucceeded) return
        preferences.edit()
            .remove(LEGACY_PENDING)
            .remove(LEGACY_CHAIN_WAS_ENABLED)
            .remove(LEGACY_AUTHORIZER)
            .commit()
    }

    private companion object {
        private const val JOURNAL_FILE_NAME = "xmsf-recovery"
        private const val JOURNAL_VERSION = 1
        private const val PREFERENCES_NAME = "mishka_prefs"
        private const val LEGACY_PENDING = "xmsf_recovery_pending"
        private const val LEGACY_CHAIN_WAS_ENABLED = "xmsf_recovery_chain_was_enabled"
        private const val LEGACY_AUTHORIZER = "xmsf_recovery_authorizer"
    }
}

private class AtomicFileXmsfRecoveryJournal(
    file: File,
) : XmsfRecoveryJournal {
    private val atomicFile = AtomicFile(file)

    override fun read(): ByteArray? =
        if (atomicFile.baseFile.exists()) atomicFile.openRead().use { it.readBytes() } else null

    override fun write(content: ByteArray): Boolean {
        var output: FileOutputStream? = null
        return try {
            val stream = atomicFile.startWrite()
            output = stream
            stream.write(content)
            atomicFile.finishWrite(stream)
            true
        } catch (_: Exception) {
            output?.let { runCatching { atomicFile.failWrite(it) } }
            false
        }
    }

    override fun clear(): Boolean {
        atomicFile.delete()
        return !atomicFile.baseFile.exists()
    }
}
