package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import cloud.trotter.log.gig.ui.components.economy.EditSession
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.config.OfferStrategy
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.feature.setup.wizard.cards.GasPriceCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.MetricSliderCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.SelectionCard
import cloud.trotter.log.gig.feature.setup.wizard.cards.VehicleCard
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardBottomBar
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardTopBar
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.ui.main.setup.wizard.cards.EconomyCostsCard
import kotlinx.coroutines.launch
import cloud.trotter.log.gig.domain.format.Formats

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    viewModel: WizardViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val vehicle by viewModel.vehicleDraft.state.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { steps.size })
    val coroutineScope = rememberCoroutineScope()
    val costsSession = remember(viewModel) { EditSession(coroutineScope) }
    androidx.activity.compose.BackHandler(enabled = pagerState.currentPage > 0) {
        coroutineScope.launch {
            if (costsSession.finishAll()) pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }

    val isCherryPicker = state.strategy == OfferStrategy.CHERRY_PICKER

    var showCompletionSheet by remember { mutableStateOf(false) }
    // Skip and Finish share the completion sheet but must NOT share a save path:
    // Skip writes nothing (#347).
    var skippedSetup by remember { mutableStateOf(false) }
    var vehicleSaveFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
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
                onSkip = {
                    costsSession.discardAll()
                    skippedSetup = true
                    showCompletionSheet = true
                }
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
                                skippedSetup = false
                                showCompletionSheet = true
                            }
                        }
                    }
                }
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
                        GasPriceCard(
                            step = currentStep, fuelType = state.fuelType,
                            isAuto = state.isGasPriceAuto, price = state.gasPrice,
                            isFetching = state.isFetchingGasPrice,
                            onFuelTypeSelected = viewModel::updateFuelType,
                            onAutoToggle = viewModel::toggleAutoGasPrice,
                            onPriceChange = viewModel::updateGasPrice
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

    if (showCompletionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCompletionSheet = false },
            sheetState = sheetState,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.wizard_screen_content_desc_success),
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    stringResource(R.string.wizard_screen_all_set_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (skippedSetup) {
                        stringResource(R.string.wizard_screen_skipped_desc)
                    } else {
                        stringResource(R.string.wizard_screen_completed_desc)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (vehicleSaveFailed) {
                    Text(
                        WizardCopy.VEHICLE_SAVE_FAILED,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        if (vehicleSaveFailed) {
                            onComplete()
                        } else if (skippedSetup) {
                            coroutineScope.launch {
                                sheetState.hide()
                                showCompletionSheet = false
                                viewModel.skipAndFinish(onComplete)
                            }
                        } else {
                            coroutineScope.launch {
                                if (!costsSession.finishAll()) return@launch
                                saving = true
                                viewModel.saveAndFinish {
                                    saving = false
                                    if (viewModel.vehicleSaveFailed.replayCache.isNotEmpty()) {
                                        vehicleSaveFailed = true
                                        viewModel.vehicleSaveFailureHandled()
                                    } else {
                                        onComplete()
                                    }
                                }
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.wizard_screen_lets_go), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
