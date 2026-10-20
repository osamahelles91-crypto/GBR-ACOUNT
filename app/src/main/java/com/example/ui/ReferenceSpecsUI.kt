package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.FormulationReferenceSpecs
import com.example.data.Formulation
import com.example.data.LabSession
import com.example.data.LabTest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReferenceSpecsScreen(
    formulation: Formulation,
    viewModel: GbrViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    // 1. Collect all lab sessions to find if the reference spec session exists in memory
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val session = remember(sessions, formulation.id) {
        sessions.find { it.id == "ref_specs_${formulation.id}" }
    }

    // 2. Automatically create/initialize the reference spec session if it does not exist in the database
    LaunchedEffect(formulation.id) {
        val existingDbSession = viewModel.repository.getLabSessionById("ref_specs_${formulation.id}")
        if (existingDbSession == null) {
            // Fetch existing reference specs to migrate historical values
            val specs = viewModel.repository.getFormulationReferenceSpecsSync(formulation.id)
            val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            
            val newSession = LabSession(
                id = "ref_specs_${formulation.id}",
                sessionNumber = "REF-${formulation.code}",
                testName = "المواصفات المرجعية (${formulation.name})",
                testDate = currentDateStr,
                technicianName = "مدير التركيبة",
                sampleOrProduct = formulation.name,
                category = "المواصفات المرجعية",
                testType = "🧪 فحص أحادي",
                sampleProperties = "FORMULATION_ID:${formulation.id}"
            )
            viewModel.repository.insertLabSession(newSession)
            
            // Migrate existing specs to LabTest entries if we have them
            if (specs != null) {
                // pH
                if (specs.phValue != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "🧪 فحص درجة القلوية (pH Value)",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        testValueA = specs.phValue
                    ))
                }
                // Density
                if (specs.densityFinalResult != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "🧪 فحص الكثافة النوعية (Specific Gravity - Density)",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        testValueA = String.format(Locale.US, "%.3f g/cm³", specs.densityFinalResult)
                    ))
                }
                // Solids
                if (specs.solidResultPct != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "🧪 فحص نسبة الصلابة والجفاف (Solid Content & Drying Time)",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        testValueA = String.format(Locale.US, "%.2f %%", specs.solidResultPct)
                    ))
                }
                // Binder
                if (specs.binderResultPct != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "🧪 فحص نسبة المادة الرابطة (Net Binder Content)",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        testValueA = String.format(Locale.US, "%.2f %%", specs.binderResultPct)
                    ))
                }
                // Viscosity
                if (specs.viscosityFinalResult != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "🧪 فحص اللزوجة (Viscosity Test - KU)",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        notes = specs.viscosityJson ?: "",
                        testValueA = String.format(Locale.US, "%.0f cP", specs.viscosityFinalResult)
                    ))
                }
                // Diluted Viscosity
                if (specs.viscosityDilutedFinalResult != null) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "اللزوجة بعد التخفيف بالماء",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        notes = specs.viscosityDilutedJson ?: "",
                        testValueA = String.format(Locale.US, "%.0f cP", specs.viscosityDilutedFinalResult)
                    ))
                }
                // Rheology
                if (specs.rheologyIndexResult != null || !specs.rheologyJson.isNullOrBlank()) {
                    viewModel.repository.insertLabTest(LabTest(
                        sessionId = "ref_specs_${formulation.id}",
                        name = "فحص السلوك الريولوجي",
                        status = "مكتمل",
                        executionDate = currentDateStr,
                        notes = specs.rheologyJson ?: "",
                        testValueA = if (specs.rheologyIndexResult != null) String.format(Locale.US, "%.2f R.I", specs.rheologyIndexResult) else ""
                    ))
                }
            }
        }
    }

    // 3. Automatically synchronize any updates in LabTests back to FormulationReferenceSpecs table
    val refSessionTests by viewModel.getLabTestsForSession("ref_specs_${formulation.id}")
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(refSessionTests) {
        if (refSessionTests.isNotEmpty()) {
            var phValue: String? = null
            var densityFinalResult: Double? = null
            var solidResultPct: Double? = null
            var binderResultPct: Double? = null
            var viscosityFinalResult: Double? = null
            var viscosityJson: String? = null
            var viscosityDilutedFinalResult: Double? = null
            var viscosityDilutedJson: String? = null
            var rheologyIndexResult: Double? = null
            var rheologyJson: String? = null

            refSessionTests.forEach { test ->
                val nameLower = test.name.lowercase()
                val matchResult = Regex("[0-9.]+").find(test.testValueA ?: "")
                val valADouble = matchResult?.value?.toDoubleOrNull()
                
                when {
                    nameLower.contains("قلوية") || nameLower.contains("ph") -> {
                        phValue = test.testValueA
                    }
                    nameLower.contains("كثافة") || nameLower.contains("density") || nameLower.contains("specific gravity") -> {
                        densityFinalResult = valADouble
                    }
                    nameLower.contains("صلابة") || nameLower.contains("solid") || nameLower.contains("جفاف") -> {
                        solidResultPct = valADouble
                    }
                    nameLower.contains("رابطة") || nameLower.contains("binder") -> {
                        binderResultPct = valADouble
                    }
                    nameLower.contains("تخفيف") || nameLower.contains("dilution") -> {
                        viscosityDilutedFinalResult = valADouble
                        if (test.notes.startsWith("WIZARD_VISCOSITY:")) {
                            viscosityDilutedJson = test.notes
                        }
                    }
                    nameLower.contains("اللزوجة") || nameLower.contains("viscosity") -> {
                        viscosityFinalResult = valADouble
                        if (test.notes.startsWith("WIZARD_VISCOSITY:")) {
                            viscosityJson = test.notes
                        }
                    }
                    nameLower.contains("ريولوجي") || nameLower.contains("rheology") -> {
                        rheologyIndexResult = valADouble
                        rheologyJson = test.notes
                    }
                }
            }

            val existing = viewModel.repository.getFormulationReferenceSpecsSync(formulation.id) ?: FormulationReferenceSpecs(formulationId = formulation.id)
            val updated = existing.copy(
                phValue = phValue ?: existing.phValue,
                densityFinalResult = densityFinalResult ?: existing.densityFinalResult,
                solidResultPct = solidResultPct ?: existing.solidResultPct,
                binderResultPct = binderResultPct ?: existing.binderResultPct,
                viscosityFinalResult = viscosityFinalResult ?: existing.viscosityFinalResult,
                viscosityJson = viscosityJson ?: existing.viscosityJson,
                viscosityDilutedFinalResult = viscosityDilutedFinalResult ?: existing.viscosityDilutedFinalResult,
                viscosityDilutedJson = viscosityDilutedJson ?: existing.viscosityDilutedJson,
                rheologyIndexResult = rheologyIndexResult ?: existing.rheologyIndexResult,
                rheologyJson = rheologyJson ?: existing.rheologyJson,
                approvalDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            )
            viewModel.saveFormulationReferenceSpecs(updated)
        }
    }

    // 4. Render within a Dialog and display SessionDetailsView
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                if (session != null) {
                    SessionDetailsView(
                        session = session,
                        viewModel = viewModel,
                        onBack = onDismiss,
                        onDelete = {
                            coroutineScope.launch {
                                viewModel.deleteLabSession(session)
                                viewModel.repository.deleteFormulationReferenceSpecsByFormulationId(formulation.id)
                            }
                            onDismiss()
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
