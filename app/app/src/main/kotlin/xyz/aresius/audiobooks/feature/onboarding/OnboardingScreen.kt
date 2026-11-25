package xyz.aresius.audiobooks.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.aresius.audiobooks.R
import xyz.aresius.audiobooks.core.design.PrimaryButton
import xyz.aresius.audiobooks.core.design.SecondaryButton
import xyz.aresius.audiobooks.core.design.Spacing
import xyz.aresius.audiobooks.feature.bittorrent.BitTorrentCard
import xyz.aresius.audiobooks.feature.bittorrent.BitTorrentRiskText
import xyz.aresius.audiobooks.feature.debrid.DebridSection

/** The first-run flow (also what a signed-out app shows): server -> how to join -> names -> Debrid -> BitTorrent. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step == OnboardingStep.Method || state.step == OnboardingStep.Details) { viewModel.back() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.l, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        AnimatedContent(targetState = state.step, label = "onboarding-step") { step ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                when (step) {
                    OnboardingStep.Server -> ServerStep(state, viewModel)
                    OnboardingStep.Method -> MethodStep(state, viewModel)
                    OnboardingStep.Details -> DetailsStep(state, viewModel)
                    OnboardingStep.Debrid -> DebridStep(onContinue = viewModel::debridDone)
                    OnboardingStep.BitTorrent -> BitTorrentStep(onDone = viewModel::finish)
                }
            }
        }
    }
}

@Composable
private fun StepTitle(@StringRes title: Int, @StringRes subtitle: Int? = null) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    if (subtitle != null) Text(stringResource(subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServerStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    StepTitle(R.string.onboarding_server_title, R.string.onboarding_server_subtitle)
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = vm::onServerUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.onboarding_server_label)) },
        placeholder = { Text(stringResource(R.string.onboarding_server_placeholder)) },
        supportingText = { Text(stringResource(R.string.onboarding_server_help)) },
        isError = state.error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        enabled = !state.checkingServer,
    )
    state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    PrimaryButton(stringResource(R.string.common_continue), onClick = vm::checkServer, enabled = state.serverUrlValid, loading = state.checkingServer)
    if (state.showDemoShortcut) {
        TextButton(onClick = vm::useDemoServer, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_use_demo)) }
    }
}

@Composable
private fun MethodStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    StepTitle(R.string.onboarding_method_title)
    if (state.bootstrapped == false) {
        Text(stringResource(R.string.onboarding_method_fresh_instance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    GoogleMethod(state, vm)
    MethodCard(R.string.onboarding_method_invite, R.string.onboarding_method_invite_desc, recommended = state.bootstrapped == true) { vm.chooseMethod(OnboardingMethod.Invite) }
    MethodCard(R.string.onboarding_method_first, R.string.onboarding_method_first_desc, recommended = state.bootstrapped == false) { vm.chooseMethod(OnboardingMethod.FirstSetup) }
    MethodCard(R.string.onboarding_method_link, R.string.onboarding_method_link_desc, recommended = false) { vm.chooseMethod(OnboardingMethod.Link) }
    state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    TextButton(onClick = vm::back, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
}

/** "Continue with Google": only when the server has it switched on; without Google Play services the card turns into an explanation. */
@Composable
private fun GoogleMethod(state: OnboardingUiState, vm: OnboardingViewModel) {
    if (!state.googleEnabled) return
    val context = LocalContext.current
    val available = remember(context) { vm.googleAvailable(context) }
    if (available) {
        MethodCard(R.string.google_continue, R.string.google_continue_desc, recommended = state.bootstrapped == true, enabled = !state.busy) { vm.continueWithGoogle(context) }
    } else {
        Text(stringResource(R.string.google_not_available_here), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MethodCard(@StringRes title: Int, @StringRes description: Int, recommended: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = if (recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailsStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val secretLabel = when (state.method) {
        OnboardingMethod.Invite -> R.string.onboarding_secret_invite
        OnboardingMethod.FirstSetup -> R.string.onboarding_secret_password
        OnboardingMethod.Link -> R.string.onboarding_secret_link
        OnboardingMethod.GoogleJoin -> R.string.onboarding_secret_invite
    }
    val action = when (state.method) {
        OnboardingMethod.GoogleJoin -> R.string.google_join_action
        OnboardingMethod.Invite -> R.string.onboarding_action_join
        OnboardingMethod.FirstSetup -> R.string.onboarding_action_setup
        OnboardingMethod.Link -> R.string.onboarding_action_link
    }
    StepTitle(R.string.onboarding_details_title)
    if (state.method == OnboardingMethod.GoogleJoin) {
        Text(stringResource(R.string.google_join_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.secretIsCode) {
        CodeField(
            value = state.secret,
            label = stringResource(secretLabel),
            enabled = !state.busy,
            onChange = vm::onSecretChange,
            onPaste = vm::onCodePasted,
            onScan = vm::onScanResult,
        )
    } else {
        OutlinedTextField(
            value = state.secret,
            onValueChange = vm::onSecretChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(secretLabel)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            enabled = !state.busy,
        )
    }
    if (state.needsDisplayName) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = vm::onDisplayNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.onboarding_display_name)) },
            supportingText = { Text(stringResource(R.string.onboarding_display_name_help)) },
            enabled = !state.busy,
        )
    }
    OutlinedTextField(
        value = state.deviceName,
        onValueChange = vm::onDeviceNameChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.onboarding_device_name)) },
        supportingText = { Text(stringResource(R.string.onboarding_device_name_help)) },
        enabled = !state.busy,
    )
    state.error?.let { Text(it.message(state.method), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    val context = LocalContext.current
    PrimaryButton(
        stringResource(action),
        onClick = { if (state.method == OnboardingMethod.GoogleJoin) vm.joinWithGoogle(context) else vm.signIn() },
        loading = state.busy,
    )
    TextButton(onClick = vm::back, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
}

@Composable
private fun DebridStep(onContinue: () -> Unit) {
    StepTitle(R.string.onboarding_debrid_title, R.string.onboarding_debrid_subtitle)
    DebridSection()
    PrimaryButton(stringResource(R.string.common_continue), onClick = onContinue)
    SecondaryButton(stringResource(R.string.onboarding_skip), onClick = onContinue)
}

@Composable
private fun BitTorrentStep(onDone: () -> Unit) {
    StepTitle(R.string.onboarding_bittorrent_title)
    BitTorrentRiskText()
    BitTorrentCard()
    PrimaryButton(stringResource(R.string.onboarding_finish), onClick = onDone)
    Text(stringResource(R.string.onboarding_bittorrent_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
