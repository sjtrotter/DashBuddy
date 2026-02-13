package cloud.trotter.dashbuddy.test.util

import timber.log.Timber

/**
 * A Timber Tree that pipes logs to System.out for Unit Tests.
 */
class ConsoleTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Simple format: [TAG] Message
        println("  [$tag] $message")
    }
}