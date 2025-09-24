package com.tap.synk.conflict

import kotlin.time.TimeSource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone

object ConflictSyncLogger {

    enum class Role(val emoji: String, val label: String) {
        INITIATOR("🔵", "Initiator"),
        RESPONDER("🟢", "Responder"),
    }

    var isEnabled: Boolean = false

    private const val LEFT_COLUMN_WIDTH = 80
    private val timeSource = TimeSource.Monotonic

    inline fun log(role: Role, message: () -> String) {
        if (!isEnabled) return
        append(role, "├─ ${message()}")
    }

    suspend fun <T> stage(role: Role, name: String, block: suspend () -> T): T {
        if (!isEnabled) {
            return block()
        }
        append(role, "┌─ $name")
        val mark = timeSource.markNow()
        return try {
            block()
        } finally {
            val elapsed = mark.elapsedNow()
            append(role, "└─ completed in $elapsed")
        }
    }

    fun divider(role: Role, title: String) {
        if (!isEnabled) return
        val bar = "─".repeat(12)
        append(role, "$bar $title $bar")
    }

    fun append(role: Role, message: String) {
        val formatted = format(role, message)
        when (role) {
            Role.INITIATOR -> printlnRow(formatted, null)
            Role.RESPONDER -> printlnRow(null, formatted)
        }
    }

    private fun format(role: Role, message: String): String {
        return "[Synk][ConflictSync] ${role.emoji} ${role.label.padEnd(9)} | $message"
    }

    @OptIn(ExperimentalTime::class)
    private fun printlnRow(left: String?, right: String?) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val datetime = now.toString().replace('T', ' ').take(23)

        val leftColumn = (left ?: "").padEnd(LEFT_COLUMN_WIDTH)
        val rightColumn = right ?: ""
        println("$datetime $leftColumn$rightColumn")
    }
}
