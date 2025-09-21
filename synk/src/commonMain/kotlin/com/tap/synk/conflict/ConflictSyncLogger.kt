package com.tap.synk.conflict

import kotlin.time.Duration
import kotlin.time.TimeSource

internal object ConflictSyncLogger {

    enum class Role(val emoji: String, val label: String) {
        INITIATOR("🔵", "Initiator"),
        RESPONDER("🟢", "Responder"),
    }

    var isEnabled: Boolean = false

    private val timeSource = TimeSource.Monotonic

    inline fun log(role: Role, message: () -> String) {
        if (isEnabled) {
            println(format(role, "├─ ${message()}"))
        }
    }

    inline fun <T> stage(role: Role, name: String, block: () -> T): T {
        if (!isEnabled) {
            return block()
        }
        println(format(role, "┌─ $name"))
        val mark = timeSource.markNow()
        return try {
            block()
        } finally {
            val elapsed = mark.elapsedNow()
            println(format(role, "└─ completed in ${elapsed.pretty()}"))
        }
    }

    fun divider(role: Role, title: String) {
        if (isEnabled) {
            val bar = "─".repeat(12)
            println(format(role, "$bar $title $bar"))
        }
    }

    private fun format(role: Role, message: String): String =
        "[Synk][ConflictSync] ${role.emoji} ${role.label.padEnd(9)} | $message"

    private fun Duration.pretty(): String = toString()
}
