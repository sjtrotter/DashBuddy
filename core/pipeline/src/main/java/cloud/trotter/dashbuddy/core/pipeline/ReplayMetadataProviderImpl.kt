package cloud.trotter.dashbuddy.core.pipeline

import android.os.Build
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.pipeline.PipelineRegistry
import cloud.trotter.dashbuddy.domain.pipeline.RuleEngineConstants
import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ReplayMetadataProviderImpl @Inject constructor(
    private val interpreter: JsonRuleInterpreter,
    @param:Named("appVersionName") private val appVersionName: String,
) : ReplayMetadataProvider {

    override fun current(): ReplayMetadata = ReplayMetadata(
        engineVersion = RuleEngineConstants.VERSION,
        rulesetFormatVersion = interpreter.loadedFormatVersion,
        pipelineVersions = PipelineRegistry.pipelines,
        stateMachineApiVersion = "${StateMachineContract.API_VERSION_MAJOR}.${StateMachineContract.API_VERSION_MINOR}",
        appVersion = appVersionName,
        deviceFingerprint = Build.FINGERPRINT,
    )
}
