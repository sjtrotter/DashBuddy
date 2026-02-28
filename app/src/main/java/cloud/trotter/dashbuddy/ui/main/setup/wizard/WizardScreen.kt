package cloud.trotter.dashbuddy.ui.main.setup.wizard

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.ui.main.setup.wizard.cards.GasPriceCard
import cloud.trotter.dashbuddy.ui.main.setup.wizard.cards.MetricSliderCard
import cloud.trotter.dashbuddy.ui.main.setup.wizard.cards.SelectionCard
import cloud.trotter.dashbuddy.ui.main.setup.wizard.cards.VehicleCard
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardBottomBar
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardTopBar
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.DashStrategy
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    viewModel: WizardViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val steps by viewModel.steps.collectAsState()
    val state by viewModel.state.collectAsState()

    val availableYears by viewModel.availableYears.collectAsState()
    val availableMakes by viewModel.availableMakes.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val availableTrimNames by viewModel.availableTrimNames.collectAsState()

    val pagerState = rememberPagerState(pageCount = { steps.size })
    val coroutineScope = rememberCoroutineScope()

    val isCherryPicker = state.strategy == DashStrategy.CHERRY_PICKER

    var showCompletionSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            WizardTopBar(
                currentStep = pagerState.currentPage,
                totalSteps = steps.size,
                onSkip = { showCompletionSheet = true }
            )
        },
        bottomBar = {
            WizardBottomBar(
                showBack = pagerState.currentPage > 0,
                isLastStep = pagerState.currentPage == steps.size - 1,
                onBackClick = {
                    coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                onNextClick = {
                    if (pagerState.currentPage < steps.size - 1) {
                        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        showCompletionSheet = true
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
                            vehicleType = state.vehicleType,
                            year = state.vehicleYear,
                            make = state.vehicleMake,
                            model = state.vehicleModel,
                            trim = state.vehicleTrim,
                            mpg = state.estimatedMpg,
                            availableYears = availableYears,
                            availableMakes = availableMakes,
                            availableModels = availableModels,
                            availableTrims = availableTrimNames,
                            onTypeSelected = viewModel::updateVehicleType,
                            onYearSelected = viewModel::onYearSelected,
                            onMakeSelected = viewModel::onMakeSelected,
                            onModelSelected = viewModel::onModelSelected,
                            onTrimSelected = viewModel::onTrimSelected,
                            onMpgChanged = viewModel::updateEstimatedMpg
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

                    WizardStep.GOAL -> {
                        SelectionCard(
                            step = currentStep,
                            option1Title = "Cherry Picker",
                            option1Desc = "Maximize profit. Auto-decline unprofitable offers.",
                            option2Title = "Protect Platinum",
                            option2Desc = "Maintain high acceptance rate. Do not auto-decline anything.",
                            option3Title = "Manual Mode",
                            option3Desc = "Just show me the math. I will make my own decisions.",
                            selectedIndex = when (state.strategy) {
                                DashStrategy.CHERRY_PICKER -> 0
                                DashStrategy.PROTECT_PLATINUM -> 1
                                DashStrategy.MANUAL -> 2
                            },
                            onOptionSelected = { index ->
                                viewModel.updateStrategy(
                                    when (index) {
                                        0 -> DashStrategy.CHERRY_PICKER
                                        1 -> DashStrategy.PROTECT_PLATINUM
                                        else -> DashStrategy.MANUAL
                                    }
                                )
                            }
                        )
                    }

                    WizardStep.SHOPPING -> {
                        MetricSliderCard(
                            step = currentStep, value = state.maxItems, valueRange = 1f..100f,
                            valueFormatter = {
                                String.format(
                                    Locale.getDefault(),
                                    "%.0f items",
                                    it
                                )
                            },
                            onValueChange = viewModel::updateMaxItems,
                            footerText = "We'll use this to score shopping offers on the HUD."
                        )
                    }

                    WizardStep.MIN_PAYOUT -> {
                        MetricSliderCard(
                            step = currentStep, value = state.minPayout, valueRange = 2f..20f,
                            valueFormatter = { String.format(Locale.getDefault(), "$%.2f", it) },
                            onValueChange = viewModel::updateMinPayout,
                            footerText = if (isCherryPicker) "We will auto-decline offers below this amount." else "We will flag offers below this amount in red."
                        )
                    }

                    WizardStep.TARGET_HOURLY -> {
                        MetricSliderCard(
                            step = currentStep, value = state.targetHourly, valueRange = 10f..40f,
                            valueFormatter = {
                                String.format(
                                    Locale.getDefault(),
                                    "$%.0f / hr",
                                    it
                                )
                            },
                            onValueChange = viewModel::updateTargetHourly,
                            footerText = if (isCherryPicker) "We will auto-decline offers below this rate." else "We will flag offers below this rate in red."
                        )
                    }

                    WizardStep.MAX_DISTANCE -> {
                        MetricSliderCard(
                            step = currentStep, value = state.maxDistance, valueRange = 1f..25f,
                            valueFormatter = { String.format(Locale.getDefault(), "%.1f mi", it) },
                            onValueChange = viewModel::updateMaxDistance,
                            footerText = if (isCherryPicker) "We will auto-decline offers exceeding this distance." else "We will flag offers exceeding this distance in red."
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
                        contentDescription = "Success",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "You're all set!",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Your setup is complete. Remember, you can always tweak these targets, change your vehicle, or adjust your automation strategy later in the Settings menu.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                            showCompletionSheet = false
                            viewModel.saveAndFinish(onComplete)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Let's Go Dash", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}