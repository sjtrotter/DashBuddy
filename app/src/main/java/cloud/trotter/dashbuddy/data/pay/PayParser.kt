package cloud.trotter.dashbuddy.data.pay

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

//import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class PayParser @Inject constructor() {

    // Support both real IDs and Test Log IDs
    private val titleIds = listOf("pay_line_item_title", "name")
    private val valueIds = listOf("pay_line_item_value", "value")

    // Explicitly ignore these IDs to prevent "Partial Row" warnings
    private val ignoredIds = listOf("final_value", "section_title")

    fun parsePayFromTree(root: UiNode): ParsedPay {
        Timber.d(
            "Starting recursive parse on root: ${root.className} (children: ${root.children.size})"
        )

        val appPay = mutableListOf<ParsedPayItem>()
        val tips = mutableListOf<ParsedPayItem>()

        var inTipsSection = false

        fun walk(node: UiNode, depth: Int) {
            val text = node.text?.trim() ?: ""
            // Cleaner indentation: 2 spaces per depth level
            val indent = "  ".repeat(depth)

            // 1. Detect Section Headers
            if (text.equals("Customer tips", ignoreCase = true)) {
                Timber.d("$indent[Header] 'Customer tips' -> Mode: TIPS")
                inTipsSection = true
            }
            if (text.equals("DoorDash pay", ignoreCase = true)) {
                Timber.d("$indent[Header] 'DoorDash pay' -> Mode: APP PAY")
                inTipsSection = false
            }

            // 2. Detect Rows (Container logic)
            val titleNode = node.children.find { child -> titleIds.any { child.hasId(it) } }
            val valueNode = node.children.find { child -> valueIds.any { child.hasId(it) } }

            // Debug: Check if we found anything resembling a row
            if (titleNode != null || valueNode != null) {
                if (titleNode != null && valueNode != null) {
                    // Full Match found
                    val title = titleNode.text?.trim() ?: "Unknown"
                    val amountStr = valueNode.text?.trim() ?: ""
                    val amount = amountStr.replace("$", "").toDoubleOrNull() ?: 0.0

                    // Nested log for the item found under the header
                    Timber.d("$indent  -> Found Item: '$title' = $amount (Tips=$inTipsSection)")

                    val item = ParsedPayItem(title, amount)

                    if (inTipsSection) {
                        tips.add(item)
                    } else {
                        appPay.add(item)
                    }

                    return
                } else {
                    // Partial Match Check
                    // Filter out known ignored nodes like the headline total itself
                    val isIgnored =
                        node.children.any { child -> ignoredIds.any { child.hasId(it) } }

                    if (!isIgnored) {
                        Timber.w(
                            "$indent  [WARN] Partial Row! TitleNode=${titleNode?.text}, ValueNode=${valueNode?.text}"
                        )
                    }
                }
            }

            // 3. Recurse
            node.children.forEach { walk(it, depth + 1) }
        }

        walk(root, 0)

        Timber.d("Parse Complete. AppPay: ${appPay.size}, Tips: ${tips.size}")
        return ParsedPay(appPay, tips)
    }
}