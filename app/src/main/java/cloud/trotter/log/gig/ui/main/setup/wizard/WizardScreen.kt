package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import cloud.trotter.log.gig.ui.components.economy.EditSession
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.domain.config.OfferStrategy
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.feature.setup.wizard.cards.GasPriceCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.MetricSliderCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.SelectionCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.VehicleCard
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardBottomBar
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardTopBar
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveStatus
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.ui.main.setup.wizard.cards.EconomyCostsCard
import kotlinx.coroutines.launch
import cloud.trotter.log.gig.domain.format.Formats

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    viewModel: WizardViewModel = hiltViewModel(),
    onComplete: () -> Unit,
    onExit: () -> Unit,
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val vehicle by viewModel.vehicleDraft.state.collectAsStateWithLifecycle()
    val saveStatus by viewModel.saveStatus.collectAsStateWithLifecycle()
    val saving = saveStatus == SaveStatus.Saving

    val pagerState = rememberPagerState(pageCount = { steps.size })
    val coroutineScope = rememberCoroutineScope()
    val costsSession = remember(viewModel) { EditSession(coroutineScope) }
    // One Back (#1447 N5): the title bar's arrow runs exactly what system Back runs. On a later step
    // it finishes the open cost fields, then goes to the previous page; on step 1 the BackHandler is
    // off, system Back pops the wizard, and the arrow calls [onExit] — nothing is written.
    // While a save runs (#1473) Back does nothing: leaving would cancel the save mid-write.
    val back: () -> Unit = back@{
        if (saving) return@back
        if (pagerState.currentPage > 0) {
            coroutineScope.launch {
                if (costsSession.finishAll()) pagerState.animateScrollToPage(pagerState.currentPage - 1)
            }
        } else {
            onExit()
        }
    }
    androidx.activity.compose.BackHandler(enabled = pagerState.currentPage > 0 || saving) { back() }

    val isCherryPicker = state.strategy == OfferStrategy.CHERRY_PICKER

    // Skip and Save share the completion sheet but must NOT share a save path: Skip writes nothing (#347).
    var skipRequested by remember { mutableStateOf(false) }
    // The sheet renders the save's outcome (#1473); it never opens while the save runs.
    val outcome = when (val status = saveStatus) {
        SaveStatus.Saved -> CompletionOutcome.Saved
        is SaveStatus.Failed -> CompletionOutcome.Failed(status.reason)
        SaveStatus.Idle -> if (skipRequested) CompletionOutcome.Skipped else null
        SaveStatus.Saving -> null
    }
    var finished by remember { mutableStateOf(false) }
    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            onComplete()
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Pre-fetch string resources OUTSIDE the pager's plain (non-@Composable) formatter lambdas.
    val shoppingItemsFormat = stringResource(R.string.wizard_screen_shopping_value_format)
    val hourlyValueFormat = stringResource(R.string.wizard_screen_hourly_value_format)
    val distanceValueFormat = stringResource(R.string.wizard_screen_distance_value_format)
    val hourlyFooterAuto = stringResource(R.string.wizard_screen_hourly_footer_auto)
    val hourlyFooterFlag = stringResource(R.string.wizard_screen_hourly_footer_flag)

    Scaffold(
        topBar = {
            WizardTopBar(
                currentStep = pagerState.currentPage,
                totalSteps = steps.size,
                onBack = back,
                onSkip = {
                    costsSession.discardAll()
                    skipRequested = true
                },
                enabled = !saving,
            )
        },
        bottomBar = {
            WizardBottomBar(
                showBack = pagerState.currentPage > 0,
                isLastStep = pagerState.currentPage == steps.size - 1,
                onBackClick = {
                    coroutineScope.launch { if (costsSession.finishAll()) pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                onNextClick = {
                    coroutineScope.launch {
                        if (costsSession.finishAll()) {
                            if (pagerState.currentPage < steps.size - 1) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            } else {
                                viewModel.save()
                            }
                        }
                    }
                },
                saving = saving,
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false
            ) { page ->
                when (val currentStep = steps[page]) {
                    WizardStep.VEHICLE -> {
                        VehicleCard(
                            step = currentStep,
                            draft = vehicle,
                            actions = viewModel.vehicleDraft,
                            onAllowVehicleLookup = viewModel::allowVehicleLookup,
                        )
                    }

                    WizardStep.GAS_PRICE -> {
                        val now by rememberNow()
                        GasPriceCard(
                            step = currentStep, fuelType = state.fuelType,
                            isAuto = state.isGasPriceAuto, price = state.gasPrice,
                            isFetching = state.isFetchingGasPrice,
                            source = state.gasPriceSource,
                            lookupFailed = state.gasPriceLookupFailed,
                            nowMillis = now,
                            onFuelTypeSelected = viewModel::updateFuelType,
                            onAutoToggle = viewModel::toggleAutoGasPrice,
                            onPriceChange = viewModel::updateGasPrice,
                            onRetry = viewModel::attemptAutoGasPriceFetch,
                        )
                    }

                    WizardStep.ECONOMY_COSTS -> {
                        EconomyCostsCard(
                            state = state,
                            vehicle = vehicle,
                            onClassChange = viewModel.vehicleDraft::updateVehicleClass,
                            session = costsSession,
                            onChainCostChange = viewModel::updateChainCost,
                            onChainLifetimeChange = viewModel::updateChainLifetime,
                            onBatteryCostChange = viewModel::updateBatteryCost,
                            onBatteryLifetimeChange = viewModel::updateBatteryLifetime,
                            onBeltCostChange = viewModel::updateBeltCost,
                            onBeltLifetimeChange = viewModel::updateBeltLifetime,
                            onTireCostChange = viewModel::updateTireCost,
                            onTireLifetimeChange = viewModel::updateTireLifetime,
                            onOilCostChange = viewModel::updateOilCost,
                            onOilIntervalChange = viewModel::updateOilInterval,
                            onBrakesCostChange = viewModel::updateBrakesCost,
                            onBrakesIntervalChange = viewModel::updateBrakesInterval,
                            onFluidsCostChange = viewModel::updateFluidsCost,
                            onFluidsIntervalChange = viewModel::updateFluidsInterval,
                            onMiscYearlyChange = viewModel::updateMiscYearly,
                            onMiscMilesChange = viewModel::updateMiscMiles,
                            onPurchasePriceChange = viewModel::updatePurchasePrice,
                            onLifetimeMilesChange = viewModel::updateLifetimeMiles,
                            onInsuranceChange = viewModel::updateInsuranceDelta,
                            onRegistrationChange = viewModel::updateRegistrationDelta,
                            onExpectedAnnualMilesChange = viewModel::updateExpectedAnnualMiles,
                            onPhoneTotalChange = viewModel::updatePhoneTotal,
                            onPhoneLinesChange = viewModel::updatePhoneLines,
                            onPhoneShareChange = viewModel::updatePhoneShare,
                            onDepreciationIncludeChange = viewModel::updateDepreciationInclude,
                            onConsumptionChange = viewModel.vehicleDraft::updateConsumption,
                            onElectricityPriceChange = viewModel.vehicleDraft::updateElectricityPrice,
                        )
                    }

                    WizardStep.GOAL -> {
                        SelectionCard(
                            step = currentStep,
                            option1Title = stringResource(R.string.wizard_screen_goal_option1_title),
                            option1Desc = stringResource(R.string.wizard_screen_goal_option1_desc),
                            option2Title = stringResource(R.string.wizard_screen_goal_option2_title),
                            option2Desc = stringResource(R.string.wizard_screen_goal_option2_desc),
                            option3Title = stringResource(R.string.wizard_screen_goal_option3_title),
                            option3Desc = stringResource(R.string.wizard_screen_goal_option3_desc),
                            selectedIndex = when (state.strategy) {
                                OfferStrategy.CHERRY_PICKER -> 0
                                OfferStrategy.PROTECT_STATUS -> 1
                                OfferStrategy.MANUAL -> 2
                            },
                            onOptionSelected = { index ->
                                viewModel.updateStrategy(
                                    when (index) {
                                        0 -> OfferStrategy.CHERRY_PICKER
                                        1 -> OfferStrategy.PROTECT_STATUS
                                        else -> OfferStrategy.MANUAL
                                    }
                                )
                            }
                        )
                    }

                    WizardStep.SHOPPING -> {
                        MetricSliderCard(
                            step = currentStep, value = state.maxItems, valueRange = 1f..100f,
                            valueFormatter = { shoppingItemsFormat.format(Formats.decimal(it.toDouble(), 0)) },
                            onValueChange = viewModel::updateMaxItems,
                            footerText = stringResource(R.string.wizard_screen_shopping_footer)
                        )
                    }

                    WizardStep.MIN_PAYOUT -> {
                        MetricSliderCard(
                            step = currentStep, value = state.minPayout, valueRange = 2f..20f,
                            valueFormatter = { Formats.money(it.toDouble()) },
                            onValueChange = viewModel::updateMinPayout,
                            footerText = stringResource(R.string.wizard_screen_min_payout_footer)
                        )
                    }

                    WizardStep.TARGET_HOURLY -> {
                        MetricSliderCard(
                            step = currentStep, value = state.targetHourly, valueRange = 10f..40f,
                            valueFormatter = { hourlyValueFormat.format(Formats.money0(it.toDouble())) },
                            onValueChange = viewModel::updateTargetHourly,
                            footerText = if (isCherryPicker) hourlyFooterAuto else hourlyFooterFlag
                        )
                    }

                    WizardStep.MAX_DISTANCE -> {
                        MetricSliderCard(
                            step = currentStep, value = state.maxDistance, valueRange = 1f..25f,
                            valueFormatter = { distanceValueFormat.format(Formats.decimal(it.toDouble())) },
                            onValueChange = viewModel::updateMaxDistance,
                            footerText = stringResource(R.string.wizard_screen_distance_footer)
                        )
                    }
                }
            }
        }
    }

    if (outcome != null) {
        WizardCompletionSheet(
            outcome = outcome,
            sheetState = sheetState,
            onDone = {
                if (outcome == CompletionOutcome.Skipped) {
                    coroutineScope.launch {
                        sheetState.hide()
                        skipRequested = false
                        viewModel.skipAndFinish(onComplete)
                    }
                } else {
                    finish()
                }
            },
            onRetry = viewModel::save,
            onBackToSetup = viewModel::dismissSaveFailure,
            // Saved: everything is written, so closing the sheet finishes. Failed: back to the last step with the
            // answers intact. Skipped: the skip has not happened yet, so back to the wizard.
            onDismiss = {
                when (outcome) {
                    CompletionOutcome.Saved -> finish()
                    is CompletionOutcome.Failed -> viewModel.dismissSaveFailure()
                    CompletionOutcome.Skipped -> skipRequested = false
                }
            },
        )
    }
}
