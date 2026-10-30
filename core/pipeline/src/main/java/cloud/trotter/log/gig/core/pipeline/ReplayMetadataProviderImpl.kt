package cloud.trotter.log.gig.core.pipeline

import android.os.Build
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.capture.ReplayMetadataProvider
import cloud.trotter.log.gig.domain.pipeline.PipelineRegistry
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import cloud.trotter.log.gig.domain.pipeline.StateMachineContract
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
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
        // bundled rules have no release tag yet; `dev` is the contract's token for them (#1197) —
        // the loaded bundle's tag replaces it once OTA rules exist.
        rulesetReleaseTag = "dev",
        pipelineVersions = PipelineRegistry.pipelines,
        stateMachineApiVersion = "${StateMachineContract.API_VERSION_MAJOR}.${StateMachineContract.API_VERSION_MINOR}",
        appVersion = appVersionName,
        deviceFingerprint = Build.FINGERPRINT,
    )
}
