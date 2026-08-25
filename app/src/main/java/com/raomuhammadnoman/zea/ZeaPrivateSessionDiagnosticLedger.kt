package com.raomuhammadnoman.zea

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object ZeaPrivateSessionDiagnosticLedger {
    private const val DIAGNOSTICS_DIRECTORY = "zea-diagnostics"
    private const val LEDGER_FILE = "private-session-ledger.tsv"
    private const val PREVIOUS_LEDGER_FILE = "private-session-ledger.previous.tsv"
    private const val HEALTH_FILE = "private-session-ledger-health.tsv"
    private const val PREVIOUS_HEALTH_FILE = "private-session-ledger-health.previous.tsv"
    private const val INTERNAL_EXPORT_FILE = "private-session-ledger-internal-export.tsv"
    private const val MAX_BYTES = 256L * 1024L
    private const val MAX_RECORDS = 512
    private const val MAX_EVENT_LENGTH = 64
    private const val MAX_PACKAGE_LENGTH = 180
    private const val MAX_STATE_LENGTH = 240
    private const val MAX_REASON_LENGTH = 240
    private const val MAX_MESSAGE_LENGTH = 256
    private const val MAX_CAUSE_DEPTH = 8

    private const val HEADER =
        "wall_utc\telapsed_ms\tsession_hash\tevent_code\ttarget_package\tstate\treason"

    private const val HEALTH_HEADER =
        "wall_utc\telapsed_ms\toperation_code\tstorage_tier\tevent_code\texception_class\texception_message\tcause_chain\treason_code"

    private val writeLock = Any()
    private val utf8 = Charsets.UTF_8

    @Volatile
    private var initializationAttempted = false

    private var activeTier = StorageTier.NONE
    private var primaryDirectory: File? = null
    private var internalDirectory: File? = null

    private enum class StorageTier {
        PRIMARY,
        FALLBACK,
        NONE
    }

    private class StorageOperationException(
        val operationCode: String,
        val original: Throwable
    ) : IOException(original.message, original)

    fun record(
        context: Context,
        sessionId: String,
        eventCode: String,
        targetPackage: String,
        state: String = "",
        reason: String = ""
    ) {
        runCatching {
            synchronized(writeLock) {
                val appContext = context.applicationContext
                initializeIfNeeded(appContext)

                val record = buildLedgerRecord(
                    sessionId = sessionId,
                    eventCode = eventCode,
                    targetPackage = targetPackage,
                    state = state,
                    reason = reason
                )

                when (activeTier) {
                    StorageTier.PRIMARY -> writePrimaryOrFallback(
                        appContext = appContext,
                        record = record
                    )

                    StorageTier.FALLBACK -> writeFallbackAndExport(
                        appContext = appContext,
                        record = record,
                        reasonCode = "ACTIVE_FALLBACK"
                    )

                    StorageTier.NONE -> writeHealthEvent(
                        eventCode = "LEDGER_FAILED",
                        operationCode = "RECORD",
                        tier = StorageTier.NONE,
                        reasonCode = "NO_ACTIVE_STORAGE"
                    )
                }
            }
        }
    }

    private fun initializeIfNeeded(context: Context) {
        if (initializationAttempted) return
        initializationAttempted = true

        val internalResult = prepareDirectory(
            directory = File(context.filesDir, DIAGNOSTICS_DIRECTORY),
            operationCode = "FALLBACK_DIRECTORY"
        )

        internalResult.onSuccess { directory ->
            internalDirectory = directory
            writeHealthEvent(
                eventCode = "LEDGER_INIT_STARTED",
                operationCode = "INITIALIZE",
                tier = StorageTier.FALLBACK,
                reasonCode = "FIRST_WRITE_IN_PROCESS"
            )
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_DIRECTORY_READY",
                operationCode = "FALLBACK_DIRECTORY",
                tier = StorageTier.FALLBACK,
                reasonCode = "DIRECTORY_READY"
            )
        }.onFailure { failure ->
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_DIRECTORY_FAILED",
                operationCode = failureOperation(failure, "FALLBACK_DIRECTORY"),
                tier = StorageTier.FALLBACK,
                reasonCode = "DIRECTORY_UNAVAILABLE",
                failure = originalFailure(failure)
            )
        }

        val existingInternalLedger = internalDirectory?.let {
            File(it, LEDGER_FILE)
        }
        if (existingInternalLedger != null &&
            existingInternalLedger.exists() &&
            existingInternalLedger.length() > 0L
        ) {
            if (initializeFallbackLedger(context, "EXISTING_INTERNAL_SOURCE")) {
                return
            }
        }

        val externalRootResult = runCatching {
            context.getExternalFilesDir(null)
                ?: throw StorageOperationException(
                    "PRIMARY_ROOT",
                    IOException("EXTERNAL_FILES_DIRECTORY_NULL")
                )
        }

        externalRootResult.onSuccess { externalRoot ->
            val primaryResult = prepareDirectory(
                directory = File(externalRoot, DIAGNOSTICS_DIRECTORY),
                operationCode = "PRIMARY_DIRECTORY"
            )
            primaryResult.onSuccess { directory ->
                primaryDirectory = directory
                writeHealthEvent(
                    eventCode = "LEDGER_PRIMARY_DIRECTORY_READY",
                    operationCode = "PRIMARY_DIRECTORY",
                    tier = StorageTier.PRIMARY,
                    reasonCode = "DIRECTORY_READY"
                )
            }.onFailure { failure ->
                writeHealthEvent(
                    eventCode = "LEDGER_PRIMARY_DIRECTORY_FAILED",
                    operationCode = failureOperation(failure, "PRIMARY_DIRECTORY"),
                    tier = StorageTier.PRIMARY,
                    reasonCode = "DIRECTORY_UNAVAILABLE",
                    failure = originalFailure(failure)
                )
            }
        }.onFailure { failure ->
            writeHealthEvent(
                eventCode = "LEDGER_PRIMARY_DIRECTORY_FAILED",
                operationCode = failureOperation(failure, "PRIMARY_ROOT"),
                tier = StorageTier.PRIMARY,
                reasonCode = "EXTERNAL_ROOT_UNAVAILABLE",
                failure = originalFailure(failure)
            )
        }

        if (initializePrimaryLedger()) return
        if (initializeFallbackLedger(context, "PRIMARY_UNAVAILABLE")) return

        activeTier = StorageTier.NONE
        writeHealthEvent(
            eventCode = "LEDGER_FAILED",
            operationCode = "INITIALIZE",
            tier = StorageTier.NONE,
            reasonCode = "NO_DURABLE_LEDGER_AVAILABLE"
        )
    }

    private fun initializePrimaryLedger(): Boolean {
        val directory = primaryDirectory ?: return false
        val ledger = File(directory, LEDGER_FILE)
        val previous = File(directory, PREVIOUS_LEDGER_FILE)
        val initRecord = buildLedgerRecord(
            sessionId = "ledger-init",
            eventCode = "LEDGER_INIT_STARTED",
            targetPackage = "com.raomuhammadnoman.zea",
            state = "tier=PRIMARY",
            reason = "first_write_in_process"
        )

        return try {
            writeBoundedRecord(ledger, previous, HEADER, initRecord)
            verifyDurableFile(ledger, "PRIMARY_VERIFY")
            activeTier = StorageTier.PRIMARY
            writeHealthEvent(
                eventCode = "LEDGER_PRIMARY_WRITE_READY",
                operationCode = "PRIMARY_SELF_CHECK",
                tier = StorageTier.PRIMARY,
                reasonCode = "SELF_CHECK_PASSED"
            )
            writeDurabilityHealth(StorageTier.PRIMARY, "PRIMARY_SELF_CHECK")
            true
        } catch (failure: Throwable) {
            writeHealthEvent(
                eventCode = "LEDGER_PRIMARY_WRITE_FAILED",
                operationCode = failureOperation(failure, "PRIMARY_SELF_CHECK"),
                tier = StorageTier.PRIMARY,
                reasonCode = "SELF_CHECK_FAILED",
                failure = originalFailure(failure)
            )
            false
        }
    }

    private fun initializeFallbackLedger(
        context: Context,
        reasonCode: String
    ): Boolean {
        val directory = internalDirectory ?: return false
        val ledger = File(directory, LEDGER_FILE)
        val previous = File(directory, PREVIOUS_LEDGER_FILE)
        val initRecord = buildLedgerRecord(
            sessionId = "ledger-init",
            eventCode = "LEDGER_INIT_STARTED",
            targetPackage = "com.raomuhammadnoman.zea",
            state = "tier=FALLBACK",
            reason = reasonCode
        )

        return try {
            writeBoundedRecord(ledger, previous, HEADER, initRecord)
            verifyDurableFile(ledger, "FALLBACK_VERIFY")
            activeTier = StorageTier.FALLBACK
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_READY",
                operationCode = "FALLBACK_SELF_CHECK",
                tier = StorageTier.FALLBACK,
                reasonCode = "SELF_CHECK_PASSED"
            )
            writeDurabilityHealth(StorageTier.FALLBACK, "FALLBACK_SELF_CHECK")
            attemptInternalExport(context)
            true
        } catch (failure: Throwable) {
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_FAILED",
                operationCode = failureOperation(failure, "FALLBACK_SELF_CHECK"),
                tier = StorageTier.FALLBACK,
                reasonCode = "SELF_CHECK_FAILED",
                failure = originalFailure(failure)
            )
            false
        }
    }

    private fun writePrimaryOrFallback(
        appContext: Context,
        record: String
    ) {
        val directory = primaryDirectory
        if (directory == null) {
            activeTier = StorageTier.FALLBACK
            writeFallbackAndExport(
                appContext = appContext,
                record = record,
                reasonCode = "PRIMARY_DIRECTORY_MISSING"
            )
            return
        }

        val ledger = File(directory, LEDGER_FILE)
        val previous = File(directory, PREVIOUS_LEDGER_FILE)
        try {
            writeBoundedRecord(ledger, previous, HEADER, record)
            writeDurabilityHealth(StorageTier.PRIMARY, "PRIMARY_RECORD")
        } catch (failure: Throwable) {
            writeHealthEvent(
                eventCode = "LEDGER_PRIMARY_WRITE_FAILED",
                operationCode = failureOperation(failure, "PRIMARY_RECORD"),
                tier = StorageTier.PRIMARY,
                reasonCode = "PRIMARY_WRITE_FAILED",
                failure = originalFailure(failure)
            )
            activeTier = StorageTier.FALLBACK
            seedFallbackFromPrimary(ledger)
            writeFallbackAndExport(
                appContext = appContext,
                record = record,
                reasonCode = "PRIMARY_WRITE_FAILED"
            )
        }
    }

    private fun writeFallbackAndExport(
        appContext: Context,
        record: String,
        reasonCode: String
    ) {
        val directory = internalDirectory
        if (directory == null) {
            activeTier = StorageTier.NONE
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_FAILED",
                operationCode = "FALLBACK_RECORD",
                tier = StorageTier.FALLBACK,
                reasonCode = "FALLBACK_DIRECTORY_MISSING"
            )
            writeHealthEvent(
                eventCode = "LEDGER_FAILED",
                operationCode = "RECORD",
                tier = StorageTier.NONE,
                reasonCode = "FALLBACK_UNAVAILABLE"
            )
            return
        }

        val ledger = File(directory, LEDGER_FILE)
        val previous = File(directory, PREVIOUS_LEDGER_FILE)
        try {
            writeBoundedRecord(ledger, previous, HEADER, record)
            verifyDurableFile(ledger, "FALLBACK_VERIFY")
            activeTier = StorageTier.FALLBACK
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_READY",
                operationCode = "FALLBACK_RECORD",
                tier = StorageTier.FALLBACK,
                reasonCode = reasonCode
            )
            writeDurabilityHealth(StorageTier.FALLBACK, "FALLBACK_RECORD")
            attemptInternalExport(appContext)
        } catch (failure: Throwable) {
            activeTier = StorageTier.NONE
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_FAILED",
                operationCode = failureOperation(failure, "FALLBACK_RECORD"),
                tier = StorageTier.FALLBACK,
                reasonCode = "FALLBACK_WRITE_FAILED",
                failure = originalFailure(failure)
            )
            writeHealthEvent(
                eventCode = "LEDGER_FAILED",
                operationCode = "RECORD",
                tier = StorageTier.NONE,
                reasonCode = "ALL_STORAGE_FAILED",
                failure = originalFailure(failure)
            )
        }
    }

    private fun seedFallbackFromPrimary(primaryLedger: File) {
        val directory = internalDirectory ?: return
        val fallbackLedger = File(directory, LEDGER_FILE)
        if (fallbackLedger.exists() && fallbackLedger.length() > 0L) return
        if (!primaryLedger.exists() || primaryLedger.length() <= 0L) return

        runCatching {
            val records = readRecords(primaryLedger, HEADER)
            val bytes = serializeRecords(HEADER, records)
            atomicReplace(fallbackLedger, bytes)
        }.onFailure { failure ->
            writeHealthEvent(
                eventCode = "LEDGER_FALLBACK_WRITE_FAILED",
                operationCode = failureOperation(failure, "FALLBACK_SEED"),
                tier = StorageTier.FALLBACK,
                reasonCode = "PRIMARY_SEED_FAILED",
                failure = originalFailure(failure)
            )
        }
    }

    private fun attemptInternalExport(context: Context) {
        val internal = internalDirectory ?: return
        val internalLedger = File(internal, LEDGER_FILE)
        if (!internalLedger.exists() || internalLedger.length() <= 0L) return

        writeHealthEvent(
            eventCode = "LEDGER_EXPORT_ATTEMPTED",
            operationCode = "EXPORT_INTERNAL_LEDGER",
            tier = StorageTier.FALLBACK,
            reasonCode = "EXTERNAL_MIRROR"
        )

        try {
            val externalRoot = context.getExternalFilesDir(null)
                ?: throw StorageOperationException(
                    "EXPORT_ROOT",
                    IOException("EXTERNAL_FILES_DIRECTORY_NULL")
                )
            val externalDirectory = requireDirectory(
                directory = File(externalRoot, DIAGNOSTICS_DIRECTORY),
                operationCode = "EXPORT_DIRECTORY"
            )
            primaryDirectory = externalDirectory
            val exportFile = File(externalDirectory, INTERNAL_EXPORT_FILE)
            val bytes = readBoundedBytes(internalLedger)
            atomicReplace(exportFile, bytes)
            verifyDurableFile(exportFile, "EXPORT_VERIFY")
            writeHealthEvent(
                eventCode = "LEDGER_EXPORT_SUCCEEDED",
                operationCode = "EXPORT_INTERNAL_LEDGER",
                tier = StorageTier.FALLBACK,
                reasonCode = "EXTERNAL_MIRROR_READY"
            )
        } catch (failure: Throwable) {
            writeHealthEvent(
                eventCode = "LEDGER_EXPORT_FAILED",
                operationCode = failureOperation(failure, "EXPORT_INTERNAL_LEDGER"),
                tier = StorageTier.FALLBACK,
                reasonCode = "EXTERNAL_MIRROR_FAILED",
                failure = originalFailure(failure)
            )
        }
    }

    private fun writeDurabilityHealth(
        tier: StorageTier,
        operationCode: String
    ) {
        writeHealthEvent(
            eventCode = "LEDGER_RECORD_FLUSHED",
            operationCode = operationCode,
            tier = tier,
            reasonCode = "FLUSH_COMPLETED"
        )
        writeHealthEvent(
            eventCode = "LEDGER_RECORD_SYNCED",
            operationCode = operationCode,
            tier = tier,
            reasonCode = "SYNC_COMPLETED"
        )
    }

    private fun writeHealthEvent(
        eventCode: String,
        operationCode: String,
        tier: StorageTier,
        reasonCode: String,
        failure: Throwable? = null
    ) {
        val directory = internalDirectory ?: return
        runCatching {
            val health = File(directory, HEALTH_FILE)
            val previousHealth = File(directory, PREVIOUS_HEALTH_FILE)
            val record = buildHealthRecord(
                eventCode = eventCode,
                operationCode = operationCode,
                tier = tier,
                reasonCode = reasonCode,
                failure = failure
            )
            writeBoundedRecord(
                ledger = health,
                previousLedger = previousHealth,
                header = HEALTH_HEADER,
                record = record
            )
        }
    }

    private fun buildLedgerRecord(
        sessionId: String,
        eventCode: String,
        targetPackage: String,
        state: String,
        reason: String
    ): String {
        return listOf(
            utcTimestamp(System.currentTimeMillis()),
            SystemClock.elapsedRealtime().toString(),
            shortHash(sessionId),
            sanitizeCode(eventCode, MAX_EVENT_LENGTH),
            sanitizePackage(targetPackage, MAX_PACKAGE_LENGTH),
            sanitizeValue(state, MAX_STATE_LENGTH),
            sanitizeValue(reason, MAX_REASON_LENGTH)
        ).joinToString("\t")
    }

    private fun buildHealthRecord(
        eventCode: String,
        operationCode: String,
        tier: StorageTier,
        reasonCode: String,
        failure: Throwable?
    ): String {
        val safeFailure = failure?.let { originalFailure(it) }
        return listOf(
            utcTimestamp(System.currentTimeMillis()),
            SystemClock.elapsedRealtime().toString(),
            sanitizeCode(operationCode, MAX_EVENT_LENGTH),
            tier.name,
            sanitizeCode(eventCode, MAX_EVENT_LENGTH),
            sanitizeClassName(safeFailure?.javaClass?.name.orEmpty()),
            sanitizeMessage(safeFailure?.message.orEmpty()),
            causeChain(safeFailure),
            sanitizeCode(reasonCode, MAX_EVENT_LENGTH)
        ).joinToString("\t")
    }

    private fun prepareDirectory(
        directory: File,
        operationCode: String
    ): Result<File> {
        return runCatching {
            requireDirectory(directory, operationCode)
        }
    }

    private fun requireDirectory(
        directory: File,
        operationCode: String
    ): File {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw StorageOperationException(
                    operationCode,
                    IOException("PATH_EXISTS_BUT_IS_NOT_DIRECTORY")
                )
            }
            return directory
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw StorageOperationException(
                operationCode,
                IOException("DIRECTORY_CREATE_FAILED")
            )
        }
        return directory
    }

    private fun writeBoundedRecord(
        ledger: File,
        previousLedger: File,
        header: String,
        record: String
    ) {
        val parent = ledger.parentFile ?: throw StorageOperationException(
            "PARENT_DIRECTORY",
            IOException("PARENT_DIRECTORY_MISSING")
        )
        requireDirectory(parent, "PARENT_DIRECTORY")
        recoverInterruptedReplacement(ledger)
        recoverInterruptedReplacement(previousLedger)

        val existingRecords = readRecords(ledger, header)
        val candidateRecords = existingRecords.toMutableList()
        candidateRecords.add(record)
        val candidateBytes = serializeRecords(header, candidateRecords)

        if (candidateRecords.size > MAX_RECORDS ||
            candidateBytes.size.toLong() > MAX_BYTES
        ) {
            if (ledger.exists() && ledger.length() > 0L) {
                val previousBytes = serializeRecords(header, existingRecords)
                atomicReplace(previousLedger, previousBytes)
            }
            val rotatedBytes = serializeRecords(header, listOf(record))
            if (rotatedBytes.size.toLong() > MAX_BYTES) {
                throw StorageOperationException(
                    "RECORD_BOUNDS",
                    IOException("SINGLE_RECORD_EXCEEDS_MAX_BYTES")
                )
            }
            atomicReplace(ledger, rotatedBytes)
        } else {
            atomicReplace(ledger, candidateBytes)
        }
    }

    private fun readRecords(file: File, header: String): List<String> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        if (file.length() > MAX_BYTES) {
            throw StorageOperationException(
                "READ_BOUNDS",
                IOException("LEDGER_EXCEEDS_MAX_BYTES")
            )
        }

        val records = mutableListOf<String>()
        try {
            FileInputStream(file).use { stream ->
                BufferedReader(InputStreamReader(stream, utf8)).use { reader ->
                    val first = reader.readLine()
                    if (first != header) {
                        throw StorageOperationException(
                            "READ_HEADER",
                            IOException("UNEXPECTED_LEDGER_HEADER")
                        )
                    }
                    while (records.size < MAX_RECORDS) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) records.add(line)
                    }
                }
            }
        } catch (failure: StorageOperationException) {
            throw failure
        } catch (failure: Throwable) {
            throw StorageOperationException("READ_LEDGER", failure)
        }
        return records
    }

    private fun serializeRecords(
        header: String,
        records: List<String>
    ): ByteArray {
        val text = buildString {
            append(header)
            append('\n')
            records.take(MAX_RECORDS).forEach { record ->
                append(record)
                append('\n')
            }
        }
        return text.toByteArray(utf8)
    }

    private fun readBoundedBytes(file: File): ByteArray {
        if (!file.exists() || file.length() <= 0L) {
            throw StorageOperationException(
                "EXPORT_READ",
                IOException("INTERNAL_LEDGER_MISSING")
            )
        }
        if (file.length() > MAX_BYTES) {
            throw StorageOperationException(
                "EXPORT_BOUNDS",
                IOException("INTERNAL_LEDGER_EXCEEDS_MAX_BYTES")
            )
        }
        return try {
            file.readBytes()
        } catch (failure: Throwable) {
            throw StorageOperationException("EXPORT_READ", failure)
        }
    }

    private fun atomicReplace(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw StorageOperationException(
            "ATOMIC_PARENT",
            IOException("PARENT_DIRECTORY_MISSING")
        )
        requireDirectory(parent, "ATOMIC_PARENT")
        recoverInterruptedReplacement(target)

        val suffix = SystemClock.elapsedRealtime().toString()
        val temporary = File(parent, target.name + "." + suffix + ".tmp")
        val backup = replacementBackup(target)

        val stream = try {
            FileOutputStream(temporary, false)
        } catch (failure: Throwable) {
            throw StorageOperationException("FILE_CREATE", failure)
        }

        try {
            try {
                stream.write(bytes)
            } catch (failure: Throwable) {
                throw StorageOperationException("FILE_WRITE", failure)
            }

            try {
                stream.flush()
            } catch (failure: Throwable) {
                throw StorageOperationException("FILE_FLUSH", failure)
            }

            try {
                stream.fd.sync()
            } catch (failure: Throwable) {
                throw StorageOperationException("FILE_SYNC", failure)
            }
        } finally {
            try {
                stream.close()
            } catch (failure: Throwable) {
                temporary.delete()
                throw StorageOperationException("FILE_CLOSE", failure)
            }
        }

        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw StorageOperationException(
                "ATOMIC_BACKUP_DELETE",
                IOException("STALE_BACKUP_DELETE_FAILED")
            )
        }

        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            temporary.delete()
            throw StorageOperationException(
                "ATOMIC_BACKUP_RENAME",
                IOException("TARGET_TO_BACKUP_RENAME_FAILED")
            )
        }

        if (!temporary.renameTo(target)) {
            if (hadTarget && backup.exists() && !target.exists()) {
                backup.renameTo(target)
            }
            temporary.delete()
            throw StorageOperationException(
                "ATOMIC_TARGET_RENAME",
                IOException("TEMPORARY_TO_TARGET_RENAME_FAILED")
            )
        }

        if (backup.exists()) {
            backup.delete()
        }
    }

    private fun recoverInterruptedReplacement(target: File) {
        val backup = replacementBackup(target)
        if (!target.exists() && backup.exists()) {
            if (!backup.renameTo(target)) {
                throw StorageOperationException(
                    "ATOMIC_RECOVERY",
                    IOException("BACKUP_RECOVERY_FAILED")
                )
            }
        } else if (target.exists() && backup.exists()) {
            backup.delete()
        }
    }

    private fun replacementBackup(target: File): File {
        return File(target.parentFile, target.name + ".replace.bak")
    }

    private fun verifyDurableFile(file: File, operationCode: String) {
        if (!file.exists() || !file.isFile || file.length() <= 0L) {
            throw StorageOperationException(
                operationCode,
                IOException("DURABLE_FILE_VERIFICATION_FAILED")
            )
        }
        if (file.length() > MAX_BYTES) {
            throw StorageOperationException(
                operationCode,
                IOException("DURABLE_FILE_EXCEEDS_MAX_BYTES")
            )
        }
    }

    private fun failureOperation(
        failure: Throwable,
        fallback: String
    ): String {
        return if (failure is StorageOperationException) {
            failure.operationCode
        } else {
            fallback
        }
    }

    private fun originalFailure(failure: Throwable): Throwable {
        return if (failure is StorageOperationException) {
            failure.original
        } else {
            failure
        }
    }

    private fun causeChain(failure: Throwable?): String {
        if (failure == null) return "none"
        val classes = mutableListOf<String>()
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            classes.add(sanitizeClassName(current.javaClass.name))
            current = current.cause
            depth += 1
        }
        return classes.joinToString(">")
            .take(MAX_MESSAGE_LENGTH)
            .ifBlank { "none" }
    }

    private fun utcTimestamp(timestamp: Long): String {
        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        )
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }

    private fun shortHash(value: String): String {
        if (value.isBlank()) return "none"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(utf8))
        return digest.take(8).joinToString("") {
            String.format(Locale.US, "%02X", it.toInt() and 0xFF)
        }
    }

    private fun sanitizeCode(value: String, limit: Int): String {
        return value.uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9_]"), "_")
            .take(limit)
            .ifBlank { "UNKNOWN" }
    }

    private fun sanitizePackage(value: String, limit: Int): String {
        return value
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(limit)
            .ifBlank { "unknown" }
    }

    private fun sanitizeClassName(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9._$-]"), "_")
            .take(MAX_MESSAGE_LENGTH)
            .ifBlank { "none" }
    }

    private fun sanitizeMessage(value: String): String {
        return redactSensitive(
            value
                .replace(Regex("[\\t\\r\\n]"), "_")
                .replace(
                    Regex("(?i)(?:[A-Z]:\\\\|/)[^\\s\\t\\r\\n]*"),
                    "<path>"
                )
        ).take(MAX_MESSAGE_LENGTH).ifBlank { "none" }
    }

    private fun sanitizeValue(value: String, limit: Int): String {
        val basic = value
            .replace(Regex("[\\t\\r\\n]"), "_")
            .replace(Regex("[^A-Za-z0-9._:=,;|+\\-/]"), "_")
        return redactSensitive(basic)
            .take(limit)
            .ifBlank { "none" }
    }

    private fun redactSensitive(value: String): String {
        return value
            .replace(
                Regex("(?i)(pin|password|passphrase|storepass|keypass|keystore|key_alias|environment|intent_extra)[=:][^,;|\\s]*"),
                "$1=<redacted>"
            )
    }
}
