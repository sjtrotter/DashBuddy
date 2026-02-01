package cloud.trotter.dashbuddy.test.base

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SnapshotTestStats(private val folderName: String) {
    private val totalCount = AtomicInteger(0)
    private val passedCount = AtomicInteger(0)
    private val headerPrinted = AtomicBoolean(false) // Ensures header prints only once

    fun reset(total: Int) {
        totalCount.set(total)
        passedCount.set(0)
        headerPrinted.set(false)
    }

    /**
     * Called before EVERY test. Only prints if it's the very first one.
     */
    fun onTestStart() {
        if (!headerPrinted.getAndSet(true)) {
            println("\n╔══════════════════════════════════════════════════════════════╗")
            println("║ 📂 FOLDER: $folderName")
            println("║ 🔢 FILES:  ${totalCount.get()} snapshots found")
            println("╚══════════════════════════════════════════════════════════════╝")
        }
    }

    fun recordSuccess() {
        passedCount.incrementAndGet()
    }

    fun printFooter() {
        val passed = passedCount.get()
        val total = totalCount.get()
        val isPerfect = passed == total
        val emoji = if (isPerfect) "🎉" else "❌"
        val status = if (isPerfect) "PASSED" else "FAILED"

        println("╔══════════════════════════════════════════════════════════════╗")
        println("║ $emoji SUMMARY: $folderName")
        println("║ $status ($passed/$total passed)")
        println("╚══════════════════════════════════════════════════════════════╝\n")
    }
}