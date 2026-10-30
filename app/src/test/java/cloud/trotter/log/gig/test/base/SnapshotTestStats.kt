package cloud.trotter.log.gig.test.base

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SnapshotTestStats(private val folderName: String) {
    private val totalCount = AtomicInteger(0)
    private val passedCount = AtomicInteger(0)
    private val headerPrinted = AtomicBoolean(false)

    // Just a long line, no calculation needed
    private val bar = "═".repeat(79)

    fun reset(total: Int) {
        totalCount.set(total)
        passedCount.set(0)
        headerPrinted.set(false)
    }

    fun recordSuccess() {
        passedCount.incrementAndGet()
    }

    fun onTestStart() {
        if (!headerPrinted.get()) printHeader()
    }

    // --- PRINTING ---

    fun printHeader() {
        if (!headerPrinted.getAndSet(true)) {
            println()
            println("╔$bar")
            println("║ 📂 FOLDER: $folderName")
            println("║ 🔢 FILES:  ${totalCount.get()} snapshots found")
            println("╚$bar")
        }
    }

    fun printFooter() {
        val passed = passedCount.get()
        val total = totalCount.get()

        // 1. Logic: Success if counts match OR if it was the special "empty" case
        val isEmpty = total == 0
        val isPerfect = isEmpty || passed == total

        // 2. Prepare Strings
        val emoji = if (isPerfect) "🎉" else "❌"
        val status = if (isPerfect) "PASSED" else "FAILED"
        val details = if (isEmpty) "($folderName empty)" else "($passed/$total passed)"

        // 3. Print (Single block, no branching)
        println()
        println("╔$bar") // Note: Use "╠$BAR" if you want it to visually connect to the tests above
        println("║ $emoji SUMMARY: $folderName")
        println("║ $status $details")
        println("╚$bar")
        println()
    }
}