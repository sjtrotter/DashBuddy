package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.res.AssetManager
import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * #1151 review PP2 — every `loadDefaults` exit is a load ATTEMPT the consent front door can see:
 * the no-files early return and the catch-all both mark it, without publishing an enumeration.
 */
class JsonRuleInterpreterLoadAttemptTest {

    @Test
    fun `no rule files still marks the load attempted`() = runTest {
        val assets = mock<AssetManager> { on { list(any()) } doReturn emptyArray() }
        val context = mock<Context> { on { this.assets } doReturn assets }
        val grants = mock<RuleCapabilityGrants>()

        JsonRuleInterpreter(context, grants).loadDefaults()

        verify(grants).markLoadAttempted()
        verify(grants, never()).reconcile(any())
    }

    @Test
    fun `a failing load still marks the load attempted`() = runTest {
        val assets = mock<AssetManager> { on { list(any()) } doThrow RuntimeException("boom") }
        val context = mock<Context> { on { this.assets } doReturn assets }
        val grants = mock<RuleCapabilityGrants>()

        JsonRuleInterpreter(context, grants).loadDefaults()

        verify(grants).markLoadAttempted()
    }
}
