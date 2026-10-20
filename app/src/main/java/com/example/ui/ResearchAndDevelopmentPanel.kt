package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import com.example.data.*
import com.example.formatQuantity
import com.example.formatPercent
import com.example.ui.theme.t
import org.json.JSONArray
import org.json.JSONObject

// Aesthetic constants matching GBR theme
val RndPurpleAccent = Color(0xFF8B5CF6)
val RndIndigoAccent = Color(0xFF6366F1)
val RndCyanAccent = Color(0xFF06B6D4)
val RndSuccessGreen = Color(0xFF10B981)
val RndWarningOrange = Color(0xFFF59E0B)
val RndErrorRed = Color(0xFFEF4444)
val RndDarkSlate = Color(0xFF0F172A)
val RndBorderLight = Color(0xFFE2E8F0)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ResearchAndDevelopmentPanel(
    viewModel: GbrViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val projects by viewModel.developmentProjects.collectAsStateWithLifecycle()
    val allSamples by viewModel.allDevelopmentSamples.collectAsStateWithLifecycle()
    val selectedProject by viewModel.selectedDevelopmentProject.collectAsStateWithLifecycle()
    val samples by viewModel.selectedDevelopmentProjectSamples.collectAsStateWithLifecycle()
    val selectedSample by viewModel.selectedDevelopmentSample.collectAsStateWithLifecycle()
    val rawMaterials by viewModel.rawMaterials.collectAsStateWithLifecycle()
    val formulations by viewModel.formulations.collectAsStateWithLifecycle()
    val allPackagingsRnd by viewModel.customPackagings.collectAsStateWithLifecycle()
    val allQCDefsRnd by viewModel.qualityTests.collectAsStateWithLifecycle()

    var activeRndScreen by remember { mutableStateOf("project_list") } // project_list, project_detail, sample_detail, compare_samples

    // Dialog state
    var showAddProjectDialog by remember { mutableStateOf(false) }
    var showAddSampleDialog by remember { mutableStateOf(false) }
    var selectedSamplesToCompare by remember { mutableStateOf<List<DevelopmentSample>>(emptyList()) }
    var showPairReportRnd by remember { mutableStateOf(false) }

    var sampleIsDirty by remember { mutableStateOf(false) }
    var triggerSaveSample by remember { mutableStateOf(false) }

    // تحديث العينة المفتوحة تلقائياً عند وصول نسخة أحدث من المزامنة (فقط إن لم يكن لدى المستخدم تعديلات غير محفوظة)
    LaunchedEffect(samples) {
        if (activeRndScreen == "sample_detail" && !sampleIsDirty) {
            val open = viewModel.selectedDevelopmentSample.value
            val fresh = open?.let { o -> samples.firstOrNull { it.id == o.id } }
            if (open != null && fresh != null && fresh != open) {
                viewModel.selectedDevelopmentSample.value = fresh
            }
        }
    }
    var showBackConfirmDialogRnd by remember { mutableStateOf(false) }

    val handleBackPress = {
        when (activeRndScreen) {
            "project_list" -> {
                viewModel.showSegment(null)
            }
            "project_detail" -> {
                viewModel.selectedDevelopmentProject.value = null
                activeRndScreen = "project_list"
            }
            "sample_detail" -> {
                if (sampleIsDirty) {
                    showBackConfirmDialogRnd = true
                } else {
                    viewModel.selectedDevelopmentSample.value = null
                    activeRndScreen = "project_detail"
                }
            }
            "compare_samples" -> {
                activeRndScreen = "project_detail"
            }
        }
    }

    BackHandler {
        handleBackPress()
    }

    if (showBackConfirmDialogRnd) {
        AlertDialog(
            onDismissRequest = { showBackConfirmDialogRnd = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "تأكيد الخروج دون حفظ ⚠️",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Text(
                    text = "تنبيه: لقد أجريت تعديلات على مكونات ومواصفات هذه العينة ولم يتم حفظها بعد. هل ترغب في حفظ التغييرات قبل الخروج أم تجاهلها؟",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            showBackConfirmDialogRnd = false
                            triggerSaveSample = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("حفظ التغييرات والخروج", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    
                    OutlinedButton(
                        onClick = {
                            showBackConfirmDialogRnd = false
                            sampleIsDirty = false
                            viewModel.selectedDevelopmentSample.value = null
                            activeRndScreen = "project_detail"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RndErrorRed),
                        border = BorderStroke(1.dp, RndErrorRed),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تجاهل التغييرات والخروج", fontWeight = FontWeight.Bold)
                    }
                    
                    TextButton(
                        onClick = { showBackConfirmDialogRnd = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إلغاء وجاري التعديل", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            RndHeader(
                activeScreen = activeRndScreen,
                projectName = selectedProject?.name,
                sampleName = selectedSample?.sampleNumber,
                selectedProject = selectedProject,
                selectedSample = selectedSample,
                projectSamples = samples,
                onBack = {
                    handleBackPress()
                }
            )
        },
        containerColor = Color(0xFFF8FAFC)
    ) { padValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padValues)
        ) {
            when (activeRndScreen) {
                "project_list" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProjectListScreen(
                            projects = projects,
                            allSamples = allSamples,
                            onProjectSelect = { project ->
                                viewModel.selectedDevelopmentProject.value = project
                                activeRndScreen = "project_detail"
                            },
                            onProjectDelete = { project ->
                                viewModel.deleteDevelopmentProject(project)
                            },
                            onProjectRename = { project, newName ->
                                viewModel.updateDevelopmentProject(project.copy(name = newName))
                            },
                            onAddProjectClick = { showAddProjectDialog = true },
                            onRestoreBackup = { viewModel.activeSegment.value = "settings" },
                            onCloudSync = { viewModel.triggerManualSync("all") }
                        )
                    }
                }

                "project_detail" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        selectedProject?.let { proj ->
                            ProjectDetailScreen(
                                project = proj,
                                samples = samples,
                                selectedSamplesToCompare = selectedSamplesToCompare,
                                onSampleSelect = { sample ->
                                    viewModel.selectedDevelopmentSample.value = sample
                                    activeRndScreen = "sample_detail"
                                },
                                onSampleDelete = { sample ->
                                    viewModel.deleteDevelopmentSample(sample)
                                },
                                onAddSampleClick = { showAddSampleDialog = true },
                                onToggleCompareSample = { sample ->
                                    selectedSamplesToCompare = if (selectedSamplesToCompare.any { it.id == sample.id }) {
                                        selectedSamplesToCompare.filterNot { it.id == sample.id }
                                    } else {
                                        if (selectedSamplesToCompare.size >= MAX_COMPARE_SAMPLES) {
                                            selectedSamplesToCompare.drop(1) + sample
                                        } else {
                                            selectedSamplesToCompare + sample
                                        }
                                    }
                                },
                                onCompareClick = {
                                    if (selectedSamplesToCompare.size >= 2) {
                                        showPairReportRnd = false
                                        activeRndScreen = "compare_samples"
                                    }
                                },
                                onClearCompare = { selectedSamplesToCompare = emptyList() },
                                onCloneSample = {
                                    viewModel.cloneDevelopmentSample(it)
                                },
                                onUpdateSample = {
                                    viewModel.updateDevelopmentSample(it)
                                }
                            )
                        } ?: run {
                            activeRndScreen = "project_list"
                        }
                    }
                }

                "sample_detail" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        selectedSample?.let { sample ->
                            SampleDetailScreen(
                                sample = sample,
                                rawMaterials = rawMaterials,
                                formulations = formulations,
                                allPackagings = allPackagingsRnd,
                                allQCDefinitions = allQCDefsRnd,
                                currentUserName = viewModel.currentUser.value?.username ?: "مدير النظام",
                                onUpdateSample = { updated ->
                                    viewModel.updateDevelopmentSample(updated)
                                },
                                onCloneSample = {
                                    viewModel.cloneDevelopmentSample(it)
                                    activeRndScreen = "project_detail"
                                },
                                onApproveSample = { sampleToExport, multiplier, onCreated ->
                                    viewModel.approveDevelopmentSample(sampleToExport, multiplier) { createdName, newFormulation ->
                                        onCreated(createdName, newFormulation)
                                    }
                                },
                                onOpenFormulation = { formulationToOpen ->
                                    sampleIsDirty = false
                                    viewModel.selectedDevelopmentSample.value = null
                                    viewModel.selectedDevelopmentProject.value = null
                                    viewModel.showSegment("formulations")
                                    viewModel.openFormulationDetails(formulationToOpen)
                                },
                                onDirtyStateChanged = { sampleIsDirty = it },
                                triggerSaveSample = triggerSaveSample,
                                onSaveCompleted = {
                                    sampleIsDirty = false
                                    triggerSaveSample = false
                                    viewModel.selectedDevelopmentSample.value = null
                                    activeRndScreen = "project_detail"
                                }
                            )
                        } ?: run {
                            activeRndScreen = "project_detail"
                        }
                    }
                }

                "compare_samples" -> {
                    if (selectedSamplesToCompare.size in 2..MAX_COMPARE_SAMPLES) {
                        if (showPairReportRnd && selectedSamplesToCompare.size == 2) {
                            CompareSamplesScreen(
                                sampleA = selectedSamplesToCompare[0],
                                sampleB = selectedSamplesToCompare[1],
                                rawMaterials = rawMaterials,
                                onBack = { showPairReportRnd = false }
                            )
                        } else {
                            MultiSampleCompareScreen(
                                samples = selectedSamplesToCompare,
                                rawMaterials = rawMaterials,
                                onBack = { handleBackPress() },
                                onOpenPairReport = { showPairReportRnd = true }
                            )
                        }
                    } else {
                        activeRndScreen = "project_detail"
                    }
                }
            }
        }
    }

    // New Project Dialog
    if (showAddProjectDialog) {
        var projName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showAddProjectDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, RndPurpleAccent),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🔬 مشروع تطوير جديد",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = projName,
                        onValueChange = { projName = it },
                        label = { Text("اسم مشروع البحث والتطوير") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (projName.isNotBlank()) {
                                    viewModel.addDevelopmentProject(projName)
                                    showAddProjectDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إضافة المشروع", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showAddProjectDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إلغاء", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    // New Sample Dialog
    if (showAddSampleDialog) {
        var sampleName by remember { mutableStateOf("") }
        var sampleNumber by remember { mutableStateOf("") }
        var targetWeightStr by remember { mutableStateOf("1.0") }
        var targetGoal by remember { mutableStateOf("") }
        var initialNotes by remember { mutableStateOf("") }
        var initializationMethod by remember { mutableStateOf(1) } // 1 = scratch, 2 = import
        var importedFormulation by remember { mutableStateOf<Formulation?>(null) }
        var isFormulationsDropdownExpanded by remember { mutableStateOf(false) }

        // Prefill index/number with size of samples + 1
        LaunchedEffect(samples, selectedProject) {
            val lastSample = samples.lastOrNull()
            if (lastSample != null && lastSample.sampleNumber.isNotBlank()) {
                sampleName = incrementTrailingNumber(lastSample.sampleName, "عينة 1")
                sampleNumber = incrementTrailingNumber(lastSample.sampleNumber, "CD-1")
            } else {
                val prjName = selectedProject?.name?.trim() ?: ""
                if (prjName.isNotBlank() && Regex("""^[a-zA-Z0-9\-_]+$""").matches(prjName)) {
                    if (Regex("""\d+$""").containsMatchIn(prjName)) {
                        sampleNumber = prjName
                    } else if (prjName.endsWith("-")) {
                        sampleNumber = "${prjName}1"
                    } else {
                        sampleNumber = "$prjName-1"
                    }
                } else {
                    sampleNumber = "CD-1"
                }
                sampleName = "عينة 1"
            }
        }

        Dialog(onDismissRequest = { showAddSampleDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, RndPurpleAccent),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🧪 عينة تجريبية جديدة",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = sampleName,
                        onValueChange = { sampleName = it },
                        label = { Text("اسم العينة (مثال: لاتكس مطاط فائق التغطية)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = sampleNumber,
                        onValueChange = { sampleNumber = it },
                        label = { Text("رقم العينة/الكود (مثال: RD-001)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = targetWeightStr,
                        onValueChange = { targetWeightStr = it },
                        label = { Text("الوزن المستهدف (كجم)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = targetGoal,
                        onValueChange = { targetGoal = it },
                        label = { Text("هدف العينة (المواصفات المطلوبة)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )

                    OutlinedTextField(
                        value = initialNotes,
                        onValueChange = { initialNotes = it },
                        label = { Text("ملاحظات أولية للباحث") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )

                    // Initialization method selector
                    Text("طريقة تركيب الصيغة:", fontWeight = FontWeight.Bold, color = RndDarkSlate)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = initializationMethod == 1,
                            onClick = { initializationMethod = 1 },
                            label = { Text("بدء صيغة من الصفر 🆕") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = initializationMethod == 2,
                            onClick = { initializationMethod = 2 },
                            label = { Text("استيراد صيغة قائمة 📥") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (initializationMethod == 2) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text("اختر التركيبة المستوردة:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { isFormulationsDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(importedFormulation?.name ?: "انقر لاختيار تركيبة")
                                }
                                DropdownMenu(
                                    expanded = isFormulationsDropdownExpanded,
                                    onDismissRequest = { isFormulationsDropdownExpanded = false }
                                ) {
                                    formulations.forEach { form ->
                                        DropdownMenuItem(
                                            text = { Text("${form.name} (${form.code})") },
                                            onClick = {
                                                importedFormulation = form
                                                isFormulationsDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (sampleName.isNotBlank() && sampleNumber.isNotBlank() && selectedProject != null) {
                                    val weightVal = targetWeightStr.toDoubleOrNull() ?: 1.0
                                    var initialItemsJson = "[]"
                                    
                                    if (initializationMethod == 2 && importedFormulation != null) {
                                        // We will import formulation items and operating recipe from formulation
                                        viewModel.viewModelScope.launch {
                                            val db = AppDatabase.getDatabase(viewModel.getApplication())
                                            val items = db.gbrDao().getFormulationItemsWithDetails(importedFormulation!!.id).first()
                                            val jArr = JSONArray()
                                            val rawMatMap = mutableMapOf<String, String>()
                                            for (itemVal in items) {
                                                val jObj = JSONObject()
                                                jObj.put("rawMaterialId", itemVal.rawMaterialId)
                                                jObj.put("rawMaterialName", itemVal.rawMaterialName)
                                                jObj.put("originalQuantityMultiplier", itemVal.quantityMultiplier) // Standard qty multiplier in formulation is in kg
                                                jArr.put(jObj)
                                                rawMatMap[itemVal.rawMaterialId] = itemVal.rawMaterialName
                                            }
                                            
                                            // Import operating recipe phases if available
                                            val formPhases = db.gbrDao().getRecipePhasesForFormulationSync(importedFormulation!!.id)
                                            val formItems = db.gbrDao().getRecipeItemsForFormulationSync(importedFormulation!!.id)
                                            
                                            val devPhases = if (formPhases.isNotEmpty()) {
                                                formPhases.map { p ->
                                                    val pItems = formItems.filter { it.phaseId == p.id }.sortedBy { it.sequence }.map { ri ->
                                                        DevRecipeItem(
                                                            rawMaterialId = ri.rawMaterialId,
                                                            rawMaterialName = rawMatMap[ri.rawMaterialId] ?: "",
                                                            ratio = ri.ratio,
                                                            sequence = ri.sequence
                                                        )
                                                    }
                                                    DevRecipePhase(
                                                        name = p.name,
                                                        sequence = p.sequence,
                                                        mixerRpm = p.mixerRpm,
                                                        durationMinutes = p.durationMinutes,
                                                        instructions = p.instructions,
                                                        items = pItems
                                                    )
                                                }
                                            } else {
                                                listOf(
                                                    DevRecipePhase(
                                                        name = "المرحلة الأولى",
                                                        sequence = 1,
                                                        mixerRpm = 0,
                                                        durationMinutes = 0,
                                                        instructions = "",
                                                        items = items.mapIndexed { idx, itm ->
                                                            DevRecipeItem(
                                                                rawMaterialId = itm.rawMaterialId,
                                                                rawMaterialName = itm.rawMaterialName,
                                                                ratio = 1.0,
                                                                sequence = idx
                                                            )
                                                        }
                                                    )
                                                )
                                            }
                                            
                                            viewModel.addDevelopmentSample(
                                                projectId = selectedProject!!.id,
                                                sampleName = sampleName,
                                                sampleNumber = sampleNumber,
                                                targetGoal = targetGoal,
                                                initialNotes = initialNotes,
                                                targetWeightKg = weightVal,
                                                itemsJson = jArr.toString(),
                                                recipeJson = serializeRecipeJson(devPhases)
                                            )
                                        }
                                    } else {
                                        viewModel.addDevelopmentSample(
                                            projectId = selectedProject!!.id,
                                            sampleName = sampleName,
                                            sampleNumber = sampleNumber,
                                            targetGoal = targetGoal,
                                            initialNotes = initialNotes,
                                            targetWeightKg = weightVal,
                                            itemsJson = initialItemsJson
                                        )
                                    }
                                    showAddSampleDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إضافة العينة", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { showAddSampleDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إلغاء", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RndHeader(
    activeScreen: String,
    projectName: String?,
    sampleName: String?,
    selectedProject: DevelopmentProject? = null,
    selectedSample: DevelopmentSample? = null,
    projectSamples: List<DevelopmentSample> = emptyList(),
    onBack: () -> Unit
) {
    var showKnowledgeLampDialog by remember { mutableStateOf(false) }

    Surface(
        color = RndDarkSlate,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = t("للخلف", "Back"),
                    tint = Color.White
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (activeScreen) {
                        "project_list" -> t("الأبحاث والتطوير (R&D)", "Research & Development (R&D)")
                        "project_detail" -> projectName ?: t("مشروع تطوير", "Development Project")
                        "sample_detail" -> sampleName ?: t("تفاصيل العينة", "Sample Details")
                        "compare_samples" -> t("مقارنة عينات التطوير", "Compare R&D Samples")
                        else -> t("الأبحاث والتطوير", "Research & Development")
                    },
                    fontSize = 18.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (activeScreen) {
                        "project_list" -> t("إدارة مشاريع الأبحاث والعينات المخبرية", "Manage research projects and lab samples")
                        "project_detail" -> t("قائمة العينات التجريبية التابعة للمشروع", "List of experimental samples in project")
                        "sample_detail" -> t("الصيغة المخبرية، القياسات، النتائج والاعتمادات", "Lab formula, measurements, results & approvals")
                        "compare_samples" -> t("مقارنة الفروقات الفنية والوزنية بين العينات", "Compare technical differences between samples")
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Knowledge Lamp Icon (مصباح المعرفة) for project_detail and sample_detail
            if (activeScreen == "project_detail" || activeScreen == "sample_detail") {
                IconButton(
                    onClick = { showKnowledgeLampDialog = true },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFFFFD700).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(12.dp))
                ) {
                    Text("💡", fontSize = 20.sp)
                }
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0x338B5CF6), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("🧪", fontSize = 24.sp)
            }
        }
    }

    if (showKnowledgeLampDialog) {
        KnowledgeLampDialog(
            selectedProject = selectedProject,
            selectedSample = selectedSample,
            projectSamples = projectSamples,
            activeScreen = activeScreen,
            onDismiss = { showKnowledgeLampDialog = false }
        )
    }
}

@Composable
fun KnowledgeLampDialog(
    selectedProject: DevelopmentProject?,
    selectedSample: DevelopmentSample?,
    projectSamples: List<DevelopmentSample>,
    activeScreen: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(2.dp, Color(0xFFFFD700)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFFFEF3C7), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💡", fontSize = 20.sp)
                        }
                        Text(
                            text = t("مصباح المعرفة - هدف العينة والمواصفات", "Knowledge Lamp - Sample Specs & Goal"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = RndDarkSlate
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = t("إغلاق", "Close"), tint = Color.Gray)
                    }
                }

                HorizontalDivider(color = Color(0xFFF1F5F9))

                if (activeScreen == "sample_detail" && selectedSample != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = selectedSample.sampleName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = RndDarkSlate
                            )
                            Surface(
                                color = RndPurpleAccent.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = selectedSample.sampleNumber,
                                    fontSize = 11.sp,
                                    color = RndPurpleAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFFE2E8F0))

                        Text(
                            text = t("🎯 هدف العينة والمواصفات المطلوبة:", "🎯 Sample Goal & Target Specifications:"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        Text(
                            text = if (selectedSample.targetGoal.isBlank()) t("لم يتم كتابة هدف لهذه العينة عند إنشائها", "No goal recorded for this sample during creation") else selectedSample.targetGoal,
                            fontSize = 13.sp,
                            color = Color(0xFF334155),
                            lineHeight = 20.sp
                        )

                        if (selectedSample.initialNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = t("📝 الملاحظات الأولية للباحث:", "📝 Researcher Initial Notes:"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = selectedSample.initialNotes,
                                fontSize = 12.sp,
                                color = Color(0xFF475569)
                            )
                        }

                        if (selectedSample.researchNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = t("🔬 ملاحظات الأبحاث والنتائج:", "🔬 Research & Result Notes:"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = selectedSample.researchNotes,
                                fontSize = 12.sp,
                                color = Color(0xFF475569)
                            )
                        }
                    }
                } else if (selectedProject != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${t("المشروع:", "Project:")} ${selectedProject.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = RndDarkSlate
                        )

                        if (projectSamples.isEmpty()) {
                            Text(
                                text = t("لا توجد عينات مخبرية مضافة لهذا المشروع بعد.", "No samples added to this project yet."),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            projectSamples.forEach { smp ->
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(smp.sampleName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = RndDarkSlate)
                                            Text(smp.sampleNumber, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = RndPurpleAccent)
                                        }
                                        Text(
                                            text = "${t("🎯 هدف العينة:", "🎯 Sample Goal:")} ${if (smp.targetGoal.isBlank()) t("لا يوجد هدف مسجل", "No recorded goal") else smp.targetGoal}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF334155)
                                        )
                                        if (smp.initialNotes.isNotBlank()) {
                                            Text(
                                                text = "${t("📝 ملاحظات الباحث:", "📝 Notes:")} ${smp.initialNotes}",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(t("حسناً، إغلاق", "OK, Close"), fontWeight = FontWeight.Bold, color = Color(0xFF78350F))
                }
            }
        }
    }
}

@Composable
fun EditSampleDialog(
    sample: DevelopmentSample,
    onDismiss: () -> Unit,
    onSave: (DevelopmentSample) -> Unit
) {
    var sampleName by remember { mutableStateOf(sample.sampleName) }
    var sampleNumber by remember { mutableStateOf(sample.sampleNumber) }
    var targetGoal by remember { mutableStateOf(sample.targetGoal) }
    var initialNotes by remember { mutableStateOf(sample.initialNotes) }
    var researchNotes by remember { mutableStateOf(sample.researchNotes) }
    var targetWeightStr by remember { mutableStateOf(sample.targetWeightKg.toString()) }
    var selectedStatus by remember { mutableStateOf(sample.status ?: "انتظار نتائج") }
    var statusNotes by remember { mutableStateOf(sample.statusNotes ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(2.dp, RndPurpleAccent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("✏️ تعديل كافة خصائص العينة", "✏️ Edit Sample Properties"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = RndDarkSlate
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = t("إغلاق", "Close"), tint = Color.Gray)
                    }
                }

                HorizontalDivider(color = Color(0xFFF1F5F9))

                // Sample Name
                OutlinedTextField(
                    value = sampleName,
                    onValueChange = { sampleName = it },
                    label = { Text(t("اسم العينة", "Sample Name")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                // Sample Number / Code
                OutlinedTextField(
                    value = sampleNumber,
                    onValueChange = { sampleNumber = it },
                    label = { Text(t("كود / رقم العينة", "Sample Code / Number")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                // Target Goal (المواصفات المطلوبة)
                OutlinedTextField(
                    value = targetGoal,
                    onValueChange = { targetGoal = it },
                    label = { Text(t("هدف العينة (المواصفات المطلوبة)", "Sample Goal (Target Specs)")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 2,
                    maxLines = 4
                )

                // Initial Notes
                OutlinedTextField(
                    value = initialNotes,
                    onValueChange = { initialNotes = it },
                    label = { Text(t("الملاحظات الأولية للباحث", "Researcher Initial Notes")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 2,
                    maxLines = 3
                )

                // Target Weight
                OutlinedTextField(
                    value = targetWeightStr,
                    onValueChange = { targetWeightStr = it },
                    label = { Text(t("الوزن المستهدف (كجم)", "Target Weight (kg)")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                // Research Notes
                OutlinedTextField(
                    value = researchNotes,
                    onValueChange = { researchNotes = it },
                    label = { Text(t("ملاحظات الأبحاث والنتائج", "Research & Results Notes")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 2,
                    maxLines = 3
                )

                // Status Selection
                Text(
                    text = t("حالة العينة المخبرية:", "Sample Lab Status:"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                val statuses = listOf(
                    "انتظار نتائج" to t("انتظار نتائج ⏳", "Awaiting Results ⏳"),
                    "معتمدة" to t("معتمدة 🟢", "Approved 🟢"),
                    "مقبولة" to t("مقبولة 🟡", "Accepted 🟡"),
                    "مرفوضة" to t("مرفوضة 🔴", "Rejected 🔴")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    statuses.forEach { (stKey, stLabel) ->
                        val isSel = (selectedStatus == stKey) || (stKey == "انتظار نتائج" && selectedStatus.isBlank())
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedStatus = stKey },
                            color = if (isSel) RndPurpleAccent else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (isSel) RndPurpleAccent else Color(0xFFE2E8F0))
                        ) {
                            Text(
                                text = stLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color.White else Color(0xFF475569),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Status Notes
                OutlinedTextField(
                    value = statusNotes,
                    onValueChange = { statusNotes = it },
                    label = { Text(t("ملاحظات الاعتماد/الحالة", "Status & Approval Notes")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 1,
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val wVal = targetWeightStr.toDoubleOrNull() ?: sample.targetWeightKg
                            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
                            val updated = sample.copy(
                                sampleName = sampleName,
                                sampleNumber = sampleNumber,
                                targetGoal = targetGoal,
                                initialNotes = initialNotes,
                                targetWeightKg = wVal,
                                researchNotes = researchNotes,
                                status = selectedStatus,
                                statusNotes = statusNotes.ifBlank { null },
                                statusUpdatedAt = if (selectedStatus != sample.status) dateStr else sample.statusUpdatedAt
                            )
                            onSave(updated)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(t("حفظ التعديلات", "Save Changes"), fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(t("إلغاء", "Cancel"), color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProjectListScreen(
    projects: List<DevelopmentProject>,
    allSamples: List<DevelopmentSample>,
    onProjectSelect: (DevelopmentProject) -> Unit,
    onProjectDelete: (DevelopmentProject) -> Unit,
    onProjectRename: (DevelopmentProject, String) -> Unit,
    onAddProjectClick: () -> Unit,
    onRestoreBackup: () -> Unit,
    onCloudSync: () -> Unit
) {
    var projectToEdit by remember { mutableStateOf<DevelopmentProject?>(null) }
    var projectToDelete by remember { mutableStateOf<DevelopmentProject?>(null) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, RndBorderLight),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "المشاريع البحثية النشطة",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = RndDarkSlate,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onAddProjectClick,
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "➕ مشروع جديد",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (projects.isEmpty()) {
                com.example.GbrEmptyState(
                    title = "لا توجد مشاريع تطوير (R&D) حالياً 🔬",
                    description = "للبدء بالتطوير الفعلي، أضف أول مشروع بحث وصياغة كيميائية، أو استورد نسخة احتياطية سابقة، أو قم بالمزامنة.",
                    onAddNew = onAddProjectClick,
                    onRestoreBackup = onRestoreBackup,
                    onCloudSync = onCloudSync,
                    addNewText = "إنشاء مشروع تطوير جديد ➕"
                )
            } else {
                val sortedProjects = remember(projects) {
                    projects.sortedWith(compareByDescending<DevelopmentProject> { pr -> pr.lastUpdated.ifBlank { pr.createdAt } }.thenByDescending { it.createdAt })
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    sortedProjects.forEach { project ->
                        Card(
                            onClick = { onProjectSelect(project) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color(0xFFEEF2F6), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🔬", fontSize = 18.sp)
                                    }
                                    Column {
                                        Text(project.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = RndDarkSlate)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        val projectSamplesCount = allSamples.count { it.projectId == project.id }
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text("🧪 عدد العينات: $projectSamplesCount", fontSize = 11.sp, color = Color.Gray)
                                            Text("البدء: ${project.createdAt.take(10)}", fontSize = 11.sp, color = Color.Gray)
                                            Text("آخر تعديل: ${project.lastUpdated.ifBlank { project.createdAt }.take(10)}", fontSize = 11.sp, color = Color.Gray)
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(onClick = { projectToEdit = project }) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = "تعديل اسم المشروع", tint = RndPurpleAccent)
                                    }
                                    IconButton(onClick = { projectToDelete = project }) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = t("حذف مشروع التطوير", "Delete Project"), tint = RndErrorRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (projectToEdit != null) {
        var editName by remember { mutableStateOf(projectToEdit!!.name) }
        Dialog(onDismissRequest = { projectToEdit = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(2.dp, RndPurpleAccent),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "✏️ تعديل اسم المشروع",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("اسم المشروع الجديد") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (editName.isNotBlank()) {
                                    onProjectRename(projectToEdit!!, editName.trim())
                                    projectToEdit = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("تعديل", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { projectToEdit = null },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t("إلغاء", "Cancel"), color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    if (projectToDelete != null) {
        val proj = projectToDelete!!
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = t("تأكيد حذف المشروع ⚠️", "Confirm Delete Project ⚠️"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate
                )
            },
            text = {
                Text(
                    text = t("هل أنت متأكد من حذف مشروع '${proj.name}'؟ سيتم حذف جميع العينات والبيانات المتعلقة به نهائياً ولا يمكن التراجع.", "Are you sure you want to delete project '${proj.name}'? All associated samples and data will be permanently deleted."),
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onProjectDelete(proj)
                        projectToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(t("نعم، حذف المشروع", "Yes, Delete Project"), fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectDetailScreen(
    project: DevelopmentProject,
    samples: List<DevelopmentSample>,
    selectedSamplesToCompare: List<DevelopmentSample>,
    onSampleSelect: (DevelopmentSample) -> Unit,
    onSampleDelete: (DevelopmentSample) -> Unit,
    onAddSampleClick: () -> Unit,
    onToggleCompareSample: (DevelopmentSample) -> Unit,
    onCompareClick: () -> Unit,
    onCloneSample: (DevelopmentSample) -> Unit,
    onUpdateSample: (DevelopmentSample) -> Unit,
    onClearCompare: () -> Unit = {}
) {
    var selectedSampleForStatusDialog by remember { mutableStateOf<DevelopmentSample?>(null) }
    var selectedSampleForQuickStatus by remember { mutableStateOf<DevelopmentSample?>(null) }
    var sampleToDelete by remember { mutableStateOf<DevelopmentSample?>(null) }
    var sampleToEdit by remember { mutableStateOf<DevelopmentSample?>(null) }

    // Sort samples logically by sample number
    val sortedSamples = remember(samples) {
        samples.sortedWith { s1, s2 ->
            var i = 0
            var j = 0
            val str1 = s1.sampleNumber
            val str2 = s2.sampleNumber
            var result = 0
            while (i < str1.length && j < str2.length) {
                val c1 = str1[i]
                val c2 = str2[j]
                if (c1.isDigit() && c2.isDigit()) {
                    var num1 = ""
                    while (i < str1.length && str1[i].isDigit()) {
                        num1 += str1[i]
                        i++
                    }
                    var num2 = ""
                    while (j < str2.length && str2[j].isDigit()) {
                        num2 += str2[j]
                        j++
                    }
                    val n1 = num1.toLongOrNull() ?: 0L
                    val n2 = num2.toLongOrNull() ?: 0L
                    val comp = n1.compareTo(n2)
                    if (comp != 0) {
                        result = comp
                        break
                    }
                } else {
                    val comp = c1.compareTo(c2)
                    if (comp != 0) {
                        result = comp
                        break
                    }
                    i++
                    j++
                }
            }
            if (result != 0) result else str1.length.compareTo(str2.length)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // Upper Card: Project Header
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, RndBorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header Title & Action Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(RndPurpleAccent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🔬", fontSize = 22.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                                color = RndDarkSlate
                            )
                            Text(
                                text = t("العينات والتركيبات التجريبية للمشروع", "Project Experimental Samples & Formulations"),
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedSamplesToCompare.size >= 2) {
                            Button(
                                onClick = onCompareClick,
                                colors = ButtonDefaults.buttonColors(containerColor = RndCyanAccent),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = t("⚖️ مقارنة (${selectedSamplesToCompare.size})", "⚖️ Compare (${selectedSamplesToCompare.size})"),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            OutlinedButton(
                                onClick = onClearCompare,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(text = t("✖ إلغاء", "✖ Clear"), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                        Button(
                            onClick = onAddSampleClick,
                            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = if (selectedSamplesToCompare.size >= 2) Modifier.weight(1f) else Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = t("عينة جديدة ➕", "New Sample ➕"),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Active Comparison Bar if samples selected
                if (selectedSamplesToCompare.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEFF6FF), RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, RndCyanAccent.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = t("العينات المقارنة المختارة (${selectedSamplesToCompare.size}/$MAX_COMPARE_SAMPLES):", "Selected for comparison (${selectedSamplesToCompare.size}/$MAX_COMPARE_SAMPLES):"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = RndDarkSlate,
                            maxLines = 1
                        )
                        selectedSamplesToCompare.forEach { sample ->
                            AssistChip(
                                onClick = { onToggleCompareSample(sample) },
                                label = {
                                    Text(
                                        text = "${sample.sampleNumber} - ${sample.sampleName}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White)
                            )
                        }
                        TextButton(onClick = onClearCompare) {
                            Text(text = t("✖ إلغاء التحديد", "✖ Clear selection"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB91C1C), maxLines = 1)
                        }
                    }
                }
            }
        }

        // Sample Cards Container (Vertical Stacked Cards)
        if (sortedSamples.isEmpty()) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, RndBorderLight),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🧪", fontSize = 36.sp)
                    Text(
                        text = t("لا توجد عينات تجريبية مضافة لهذه التجربة بعد", "No experimental samples added yet"),
                        color = RndDarkSlate,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = t("أضف أول عينة مادة واخلط المكونات وقس لزوجتها وخصائصها المخبرية بسهولة.", "Add your first sample, mix ingredients, and measure lab metrics effortlessly."),
                        color = Color.Gray,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onAddSampleClick,
                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(t("➕ إنشاء أول عينة تجريبية", "➕ Create First Sample"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sortedSamples.forEach { sample ->
                    EnhancedSampleCard(
                        sample = sample,
                        isComparing = selectedSamplesToCompare.any { it.id == sample.id },
                        onSampleSelect = { onSampleSelect(sample) },
                        onToggleCompare = { onToggleCompareSample(sample) },
                        onQuickStatusClick = { selectedSampleForQuickStatus = sample },
                        onStatusDetailsClick = { selectedSampleForStatusDialog = sample },
                        onEditClick = { sampleToEdit = sample },
                        onCloneClick = { onCloneSample(sample) },
                        onDeleteClick = { sampleToDelete = sample }
                    )
                }
            }
        }
    }

    // Status Info Dialog
    selectedSampleForStatusDialog?.let { s ->
        Dialog(onDismissRequest = { selectedSampleForStatusDialog = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, RndBorderLight),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = t("تفاصيل القرار الفني والحالة", "Status & Technical Decision"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = RndDarkSlate
                        )
                        IconButton(onClick = { selectedSampleForStatusDialog = null }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق")
                        }
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("عينة:", "Sample:"), fontSize = 13.sp, color = Color.Gray)
                        Text(s.sampleName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = RndDarkSlate)
                        Spacer(modifier = Modifier.weight(1f))
                        Surface(
                            color = RndPurpleAccent.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = s.sampleNumber,
                                fontSize = 11.sp,
                                color = RndPurpleAccent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val statusLabel = when (s.status) {
                        "معتمدة" -> "🟢 معتمدة"
                        "مقبولة" -> "🟡 مقبولة"
                        "مرفوضة" -> "🔴 مرفوضة"
                        "انتظار نتائج" -> "⏳ انتظار نتائج"
                        else -> "⏳ انتظار نتائج"
                    }
                    val statusColor = when (s.status) {
                        "معتمدة" -> Color(0xFFECFDF5)
                        "مقبولة" -> Color(0xFFFEF3C7)
                        "مرفوضة" -> Color(0xFFFEF2F2)
                        "انتظار نتائج" -> Color(0xFFF0F9FF)
                        else -> Color(0xFFF0F9FF)
                    }
                    val statusTextColor = when (s.status) {
                        "معتمدة" -> Color(0xFF047857)
                        "مقبولة" -> Color(0xFFB45309)
                        "مرفوضة" -> Color(0xFFB91C1C)
                        "انتظار نتائج" -> Color(0xFF0369A1)
                        else -> Color(0xFF0369A1)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(t("الحالة الحالية:", "Current Status:"), fontSize = 13.sp, color = Color.Gray)
                        Surface(
                            color = statusColor,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                fontSize = 13.sp,
                                color = statusTextColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("الملاحظات والسبب الفني:", "Technical Notes & Reasons:"), fontSize = 13.sp, color = Color.Gray)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = if (s.status == null) {
                                    t("لا توجد حالة محددة لهذه العينة بعد.", "No status set for this sample yet.")
                                } else if (s.statusNotes.isNullOrBlank()) {
                                    t("لم يتم كتابة أي ملاحظات أو أسباب لهذه الحالة.", "No notes recorded for this status.")
                                } else {
                                    s.statusNotes!!
                                },
                                fontSize = 13.sp,
                                color = if (s.status == null || s.statusNotes.isNullOrBlank()) Color.Gray else RndDarkSlate
                            )
                        }
                    }

                    if (s.status != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(t("المسؤول عن التعديل:", "Updated By:"), fontSize = 11.sp, color = Color.Gray)
                                Text(s.statusUpdatedBy ?: t("غير مسجل", "N/A"), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = RndDarkSlate)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(t("تاريخ التعديل:", "Updated At:"), fontSize = 11.sp, color = Color.Gray)
                                Text(s.statusUpdatedAt ?: t("غير مسجل", "N/A"), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = RndDarkSlate)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val currentSmp = s
                                selectedSampleForStatusDialog = null
                                selectedSampleForQuickStatus = currentSmp
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t("تغيير الحالة 🔄", "Change Status 🔄"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { selectedSampleForStatusDialog = null },
                            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(t("موافق", "OK"), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Quick Status Update Dialog
    selectedSampleForQuickStatus?.let { smp ->
        QuickStatusUpdateDialog(
            sample = smp,
            onDismiss = { selectedSampleForQuickStatus = null },
            onSave = { updatedStatus, note ->
                val currentDateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())
                val updatedSample = smp.copy(
                    status = updatedStatus,
                    statusNotes = note,
                    statusUpdatedAt = currentDateTime,
                    statusUpdatedBy = "المختبر الكيميائي",
                    isApproved = updatedStatus == "معتمدة",
                    approvedDate = if (updatedStatus == "معتمدة") currentDateTime else smp.approvedDate
                )
                onUpdateSample(updatedSample)
                selectedSampleForQuickStatus = null
            }
        )
    }

    // Delete Confirmation Dialog
    if (sampleToDelete != null) {
        val smp = sampleToDelete!!
        AlertDialog(
            onDismissRequest = { sampleToDelete = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = t("تأكيد حذف العينة ⚠️", "Confirm Delete Sample ⚠️"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate
                )
            },
            text = {
                Text(
                    text = t("هل أنت متأكد من حذف العينة '${smp.sampleName}' (${smp.sampleNumber})؟ سيتم حذف جميع صيغتها ونتائج قياساتها نهائياً ولا يمكن التراجع.", "Are you sure you want to delete sample '${smp.sampleName}' (${smp.sampleNumber})? All its formula and test results will be permanently deleted."),
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSampleDelete(smp)
                        sampleToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(t("نعم، حذف العينة", "Yes, Delete Sample"), fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { sampleToDelete = null }) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Full Edit Sample Dialog
    if (sampleToEdit != null) {
        EditSampleDialog(
            sample = sampleToEdit!!,
            onDismiss = { sampleToEdit = null },
            onSave = { updated ->
                onUpdateSample(updated)
                sampleToEdit = null
            }
        )
    }
}

// KPI Count Chip Component
@Composable
private fun RndKpiChip(
    title: String,
    count: String,
    icon: String,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(icon, fontSize = 12.sp)
                Text(
                    text = count,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = textColor
                )
            }
            Text(
                text = title,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Enhanced Sample Card Component
@Composable
private fun EnhancedSampleCard(
    sample: DevelopmentSample,
    isComparing: Boolean,
    onSampleSelect: () -> Unit,
    onToggleCompare: () -> Unit,
    onQuickStatusClick: () -> Unit,
    onStatusDetailsClick: () -> Unit,
    onEditClick: () -> Unit,
    onCloneClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    // Parse status visual styling
    val (statusLabel, statusBg, statusText, accentBorder) = when (sample.status) {
        "معتمدة" -> Quadruple("🟢 معتمدة", Color(0xFFECFDF5), Color(0xFF047857), Color(0xFF10B981))
        "مقبولة" -> Quadruple("🟡 مقبولة", Color(0xFFFEF3C7), Color(0xFFB45309), Color(0xFFF59E0B))
        "مرفوضة" -> Quadruple("🔴 مرفوضة", Color(0xFFFEF2F2), Color(0xFFB91C1C), Color(0xFFEF4444))
        else -> Quadruple("⏳ انتظار نتائج", Color(0xFFF0F9FF), Color(0xFF0369A1), Color(0xFF0284C7))
    }

    // Parse items & lab metrics
    val materialCount = remember(sample.itemsJson) {
        try {
            JSONArray(sample.itemsJson).length()
        } catch (_: Exception) { 0 }
    }

    val parsedLabResults = remember(sample.resultsJson) {
        try {
            val jArr = JSONArray(sample.resultsJson)
            val list = mutableListOf<Pair<String, String>>()
            for (k in 0 until jArr.length()) {
                val o = jArr.getJSONObject(k)
                list.add(Pair(o.optString("name", ""), o.optString("value", "")))
            }
            list.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    val formattedDate = remember(sample.createdAt) {
        val raw = sample.createdAt.trim()
        if (raw.isBlank()) ""
        else if (raw.contains("T")) raw.split("T").firstOrNull() ?: raw
        else if (raw.contains(" ")) raw.split(" ").firstOrNull() ?: raw
        else if (raw.length > 10) raw.take(10)
        else raw
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isComparing) Color(0xFFF0F9FF) else Color.White
        ),
        border = BorderStroke(
            width = if (isComparing) 2.dp else 1.dp,
            color = if (isComparing) RndCyanAccent else Color(0xFFE2E8F0)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onSampleSelect,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left Status Accent Strip
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(accentBorder)
            )

            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Row: Code Pill, Status Badge & Context Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = RndPurpleAccent.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = sample.sampleNumber,
                                fontSize = 11.sp,
                                color = RndPurpleAccent,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        if (sample.isApproved || sample.status == "معتمدة") {
                            Surface(
                                color = Color(0xFFECFDF5),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("🛡️", fontSize = 10.sp)
                                    Text(
                                        text = t("جاهزة للإنتاج", "Production Ready"),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF047857)
                                    )
                                }
                            }
                        }
                    }

                    // Interactive Status Badge & Action Menu
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = statusBg,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, accentBorder.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable { onQuickStatusClick() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = statusLabel,
                                    fontSize = 11.sp,
                                    color = statusText,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "تعديل الحالة",
                                    tint = statusText.copy(alpha = 0.7f),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "خيارات العينة", tint = Color.Gray)
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(t("تحديث القرار الفني والحالة", "Change Status Decision"), fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        showMenu = false
                                        onQuickStatusClick()
                                    },
                                    leadingIcon = { Text("🔄", fontSize = 14.sp) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("عرض ملاحظات القرار الفني", "View Status Notes"), fontSize = 13.sp) },
                                    onClick = {
                                        showMenu = false
                                        onStatusDetailsClick()
                                    },
                                    leadingIcon = { Text("📋", fontSize = 14.sp) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(t("تعديل خصائص العينة", "Edit Properties"), fontSize = 13.sp) },
                                    onClick = {
                                        showMenu = false
                                        onEditClick()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = RndPurpleAccent, modifier = Modifier.size(16.dp)) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("استنساخ العينة (Clone)", "Clone Sample"), fontSize = 13.sp) },
                                    onClick = {
                                        showMenu = false
                                        onCloneClick()
                                    },
                                    leadingIcon = { Text("🔀", fontSize = 14.sp) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text(t("حذف العينة", "Delete Sample"), fontSize = 13.sp, color = RndErrorRed) },
                                    onClick = {
                                        showMenu = false
                                        onDeleteClick()
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = RndErrorRed, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }

                // Sample Name & Target Goal
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = sample.sampleName,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = RndDarkSlate
                    )
                    if (sample.targetGoal.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("🎯", fontSize = 11.sp)
                            Text(
                                text = sample.targetGoal,
                                fontSize = 12.sp,
                                color = Color(0xFF475569),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Specs & Materials Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Materials count chip
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = t("🧩 $materialCount مواد خام", "🧩 $materialCount Raw Materials"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = RndDarkSlate,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Target Weight chip
                    Surface(
                        color = Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = t("⚖️ ${formatQuantity(sample.targetWeightKg)} كجم", "⚖️ ${formatQuantity(sample.targetWeightKg)} kg"),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = RndDarkSlate,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Dynamic Lab Metric Pills
                    parsedLabResults.take(3).forEach { (paramName, paramVal) ->
                        Surface(
                            color = RndIndigoAccent.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, RndIndigoAccent.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "$paramName: $paramVal",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = RndIndigoAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (parsedLabResults.size > 3) {
                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "+${parsedLabResults.size - 3} ${t("نتائج", "more")}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFF1F5F9), modifier = Modifier.padding(vertical = 2.dp))

                // Footer Row: Actions & Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Compare Toggle Chip
                        AssistChip(
                            onClick = onToggleCompare,
                            label = {
                                Text(
                                    text = if (isComparing) t("محددة للمقارنة ✔️", "Selected ✔️") else t("مقارنة ⚖️", "Compare ⚖️"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isComparing) RndCyanAccent.copy(alpha = 0.15f) else Color(0xFFF8FAFC),
                                labelColor = if (isComparing) RndCyanAccent else Color.Gray
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = if (isComparing) RndCyanAccent else Color(0xFFE2E8F0)
                            ),
                            modifier = Modifier.height(28.dp)
                        )

                        Text(
                            text = "📅 $formattedDate",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    // Open Details Button
                    Button(
                        onClick = onSampleSelect,
                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            text = t("فتح العينة 🚀", "Open Sample 🚀"),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// Quick Status Update Dialog Component
@Composable
private fun QuickStatusUpdateDialog(
    sample: DevelopmentSample,
    onDismiss: () -> Unit,
    onSave: (newStatus: String, note: String) -> Unit
) {
    var selectedStatus by remember { mutableStateOf(sample.status ?: "انتظار نتائج") }
    var noteInput by remember { mutableStateOf(sample.statusNotes ?: "") }

    val statusOptions = listOf(
        Triple("معتمدة", t("🟢 معتمدة (جاهزة للإنتاج)", "🟢 Approved (Ready for Production)"), Color(0xFF10B981)),
        Triple("مقبولة", t("🟡 مقبولة (تحتاج تحسينات بسيطة)", "🟡 Accepted (Needs minor tweaks)"), Color(0xFFF59E0B)),
        Triple("انتظار نتائج", t("⏳ انتظار نتائج الفحص المخبري", "⏳ Pending Lab Test Results"), Color(0xFF0284C7)),
        Triple("مرفوضة", t("🔴 مرفوضة (غير مطابقة للمواصفات)", "🔴 Rejected (Out of Specs)"), Color(0xFFEF4444))
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, RndPurpleAccent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("🔄 تحديث القرار الفني للعينة", "🔄 Update Sample Technical Status"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                Surface(
                    color = Color(0xFFF8FAFC),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🧪", fontSize = 16.sp)
                        Column {
                            Text(sample.sampleName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = RndDarkSlate)
                            Text(sample.sampleNumber, fontSize = 11.sp, color = RndPurpleAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t("اختر القرار الفني للحالة:", "Select Status Decision:"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RndDarkSlate)
                    statusOptions.forEach { (statusKey, statusTitle, statusColor) ->
                        val isSelected = selectedStatus == statusKey
                        Card(
                            onClick = { selectedStatus = statusKey },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) statusColor.copy(alpha = 0.12f) else Color(0xFFF8FAFC)
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) statusColor else Color(0xFFE2E8F0)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedStatus = statusKey },
                                    colors = RadioButtonDefaults.colors(selectedColor = statusColor)
                                )
                                Text(
                                    text = statusTitle,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) statusColor else RndDarkSlate
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteInput,
                    onValueChange = { noteInput = it },
                    label = { Text(t("ملاحظات القرار والسبب الفني", "Technical Decision Notes & Reasons"), fontSize = 12.sp) },
                    placeholder = { Text(t("ادخل أسباب القبول/الرفض أو الملاحظات المخبرية...", "Enter reasons or lab notes..."), fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSave(selectedStatus, noteInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(t("حفظ القرار", "Save Decision"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(t("إلغاء", "Cancel"), color = Color.Gray)
                    }
                }
            }
        }
    }
}

// Simple Helper Quadruple data container
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)


enum class SampleWeightDisplayMode {
    KG,
    PERCENTAGE,
    GRAMS
}

@Composable
fun SampleDetailScreen(
    sample: DevelopmentSample,
    rawMaterials: List<RawMaterial>,
    formulations: List<Formulation>,
    allPackagings: List<GbrViewModel.CustomPackaging> = emptyList(),
    allQCDefinitions: List<QualityTest> = emptyList(),
    currentUserName: String,
    onUpdateSample: (DevelopmentSample) -> Unit,
    onCloneSample: (DevelopmentSample) -> Unit,
    onApproveSample: (DevelopmentSample, Double, (String, Formulation) -> Unit) -> Unit,
    onOpenFormulation: (Formulation) -> Unit,
    onDirtyStateChanged: (Boolean) -> Unit,
    triggerSaveSample: Boolean,
    onSaveCompleted: () -> Unit
) {
    // Dialog flags
    var showAddMaterialDialog by remember { mutableStateOf(false) }
    var showEditMaterialDialog by remember { mutableStateOf<Map<String, Any>?>(null) }
    var quantityForEdit by remember { mutableStateOf("") }
    var showReplaceMaterialDialog by remember { mutableStateOf<Map<String, Any>?>(null) }
    var selectedRmForReplace by remember { mutableStateOf<com.example.data.RawMaterial?>(null) }
    var quantityForReplace by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<Map<String, Any>?>(null) }
    var showAddTestDialog by remember { mutableStateOf(false) }
    var showCostDetailsDialog by remember { mutableStateOf(false) }
    var showRecipeDialog by remember { mutableStateOf(false) }
    var showRecipeUnsavedChangesDialog by remember { mutableStateOf(false) }
    var showExecutionDialog by remember { mutableStateOf(false) }
    var showFullEditSampleDialog by remember { mutableStateOf(false) }
    var showSamplePrintDialog by remember { mutableStateOf(false) }

    // Copy to Formulations Workflow Dialogs
    var showConfirmSendDialog by remember { mutableStateOf(false) }
    var isSendingProgress by remember { mutableStateOf(false) }
    var sendProgressValue by remember { mutableFloatStateOf(0f) }
    var createdFormulationResult by remember { mutableStateOf<Pair<String, Formulation>?>(null) }
    var showMultiplierOptions by remember { mutableStateOf(false) }
    var exportMultiplierInput by remember { mutableStateOf("1.0") }

    // Baseline values for comparison
    val initialItems = remember(sample) {
        val list = mutableListOf<Map<String, Any>>()
        try {
            val jArr = JSONArray(sample.itemsJson)
            for (i in 0 until jArr.length()) {
                val o = jArr.getJSONObject(i)
                list.add(
                    mapOf(
                        "rawMaterialId" to o.optString("rawMaterialId").ifBlank { o.optInt("rawMaterialId", 0).toString() },
                        "rawMaterialName" to o.getString("rawMaterialName"),
                        "originalQuantityMultiplier" to o.getDouble("originalQuantityMultiplier")
                    )
                )
            }
        } catch (_: Exception) {}
        list
    }

    val initialResults = remember(sample) {
        val list = mutableListOf<Map<String, String>>()
        try {
            val jArr = JSONArray(sample.resultsJson)
            for (i in 0 until jArr.length()) {
                val o = jArr.getJSONObject(i)
                list.add(
                    mapOf(
                        "name" to o.getString("name"),
                        "value" to o.getString("value")
                    )
                )
            }
        } catch (_: Exception) {}
        list
    }

    val initialNotes = remember(sample) { sample.researchNotes }
    val initialTargetWeight = remember(sample) { sample.targetWeightKg }
    val initialStatus = remember(sample) { sample.status }
    val initialStatusNotes = remember(sample) { sample.statusNotes ?: "" }

    // Current items from JSON initialized directly from sample baseline
    var itemsList by remember(sample) { mutableStateOf(initialItems) }
    var resultsList by remember(sample) { mutableStateOf(initialResults) }
    var rNotes by remember(sample) { mutableStateOf(initialNotes) }
    var targetWeight by remember(sample) { mutableStateOf(initialTargetWeight) }
    var sampleRecipeJson by remember(sample) { mutableStateOf(sample.recipeJson) }
    var weightDisplayMode by remember { mutableStateOf(SampleWeightDisplayMode.KG) }

    var sampleStatus by remember(sample) { mutableStateOf(initialStatus) }
    var sampleStatusNotes by remember(sample) { mutableStateOf(initialStatusNotes) }

    // Auto-sync recipeJson with itemsList when materials are added, removed, or imported
    LaunchedEffect(itemsList) {
        val syncedPhases = getOrInitRecipe(sample, itemsList, sampleRecipeJson)
        val syncedJson = serializeRecipeJson(syncedPhases)
        if (syncedJson != sampleRecipeJson) {
            sampleRecipeJson = syncedJson
        }
    }

    // Compute hasPendingChanges dynamically
    val hasPendingChanges = remember(
        itemsList, resultsList, rNotes, targetWeight, sampleRecipeJson, sampleStatus, sampleStatusNotes,
        initialItems, initialResults, initialNotes, initialTargetWeight, sample.recipeJson, initialStatus, initialStatusNotes
    ) {
        // Compare items
        if (itemsList.size != initialItems.size) return@remember true
        for (i in itemsList.indices) {
            val cur = itemsList[i]
            val init = initialItems[i]
            val curId = cur["rawMaterialId"]?.toString() ?: ""
            val initId = init["rawMaterialId"]?.toString() ?: ""
            val curName = cur["rawMaterialName"]?.toString() ?: ""
            val initName = init["rawMaterialName"]?.toString() ?: ""
            val curQty = (cur["originalQuantityMultiplier"] as? Number)?.toDouble() ?: 0.0
            val initQty = (init["originalQuantityMultiplier"] as? Number)?.toDouble() ?: 0.0
            if (curId != initId || curName != initName || kotlin.math.abs(curQty - initQty) > 0.0001) {
                return@remember true
            }
        }

        // Compare results
        if (resultsList.size != initialResults.size) return@remember true
        for (i in resultsList.indices) {
            val cur = resultsList[i]
            val init = initialResults[i]
            if (cur["name"] != init["name"] || cur["value"] != init["value"]) {
                return@remember true
            }
        }

        if (sampleStatus != initialStatus || sampleStatusNotes != initialStatusNotes) return@remember true

        // التطبيع التلقائي لـ recipeJson (إضافة/حذف مواد، تحديث الأسماء) ليس تعديلاً من المستخدم: نقارن مع الأساس بعد تطبيعه بنفس الدالة
        val normalizedInitialRecipe = try {
            serializeRecipeJson(getOrInitRecipe(sample, initialItems, sample.recipeJson))
        } catch (_: Exception) { sample.recipeJson }
        rNotes != initialNotes || targetWeight != initialTargetWeight ||
            (sampleRecipeJson != sample.recipeJson && sampleRecipeJson != normalizedInitialRecipe)
    }

    // Propagate dirty state to parent
    LaunchedEffect(hasPendingChanges) {
        onDirtyStateChanged(hasPendingChanges)
    }

    // Helper to serialize itemsList and resultsList to update model
    val saveSampleChanges = {
        val syncedPhases = getOrInitRecipe(sample, itemsList, sampleRecipeJson)
        val syncedJson = serializeRecipeJson(syncedPhases)
        sampleRecipeJson = syncedJson

        val jItems = JSONArray()
        for (i in itemsList) {
            val obj = JSONObject()
            obj.put("rawMaterialId", i["rawMaterialId"])
            obj.put("rawMaterialName", i["rawMaterialName"])
            obj.put("originalQuantityMultiplier", i["originalQuantityMultiplier"])
            jItems.put(obj)
        }

        val jResults = JSONArray()
        for (r in resultsList) {
            val obj = JSONObject()
            obj.put("name", r["name"])
            obj.put("value", r["value"])
            jResults.put(obj)
        }

        val statusChanged = (sampleStatus != sample.status || sampleStatusNotes != (sample.statusNotes ?: ""))
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())

        val updated = sample.copy(
            itemsJson = jItems.toString(),
            resultsJson = jResults.toString(),
            researchNotes = rNotes,
            targetWeightKg = targetWeight,
            recipeJson = syncedJson,
            status = sampleStatus,
            statusNotes = if (sampleStatus != null) sampleStatusNotes else null,
            statusUpdatedAt = if (statusChanged && sampleStatus != null) dateStr else sample.statusUpdatedAt,
            statusUpdatedBy = if (statusChanged && sampleStatus != null) currentUserName else sample.statusUpdatedBy
        )
        onUpdateSample(updated)
    }

    // Observe save trigger from parent
    LaunchedEffect(triggerSaveSample) {
        if (triggerSaveSample) {
            saveSampleChanges()
            onSaveCompleted()
        }
    }

    // Total original weights sum (can be around 1000 kg, e.g. standard mix size)
    val totalOriginalWeightKgValue = itemsList.sumOf { (it["originalQuantityMultiplier"] as? Double) ?: 0.0 }

    val currentEditedSample = remember(sample, itemsList, resultsList, rNotes, targetWeight, sampleRecipeJson) {
        val jItems = JSONArray()
        for (i in itemsList) {
            val obj = JSONObject()
            obj.put("rawMaterialId", i["rawMaterialId"])
            obj.put("rawMaterialName", i["rawMaterialName"])
            obj.put("originalQuantityMultiplier", i["originalQuantityMultiplier"])
            jItems.put(obj)
        }

        val jResults = JSONArray()
        for (r in resultsList) {
            val obj = JSONObject()
            obj.put("name", r["name"])
            obj.put("value", r["value"])
            jResults.put(obj)
        }

        sample.copy(
            itemsJson = jItems.toString(),
            resultsJson = jResults.toString(),
            researchNotes = rNotes,
            targetWeightKg = targetWeight,
            recipeJson = sampleRecipeJson
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (hasPendingChanges) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                        Text(
                            text = "توجد تعديلات غير محفوظة!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFFB45309)
                        )
                    }
                    Text(
                        text = "لقد قمت بإجراء تعديلات على عناصر هذه العينة التجريبية (المكونات، اللزوجة، الأوزان، أو الملاحظات). يرجى حفظ التغييرات لاعتمادها في النظام رسميًا.",
                        fontSize = 12.sp,
                        color = Color(0xFF92400E)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                saveSampleChanges()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("حفظ التغييرات المعلقة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                // Reload/reset from baseline
                                itemsList = initialItems
                                resultsList = initialResults
                                rNotes = initialNotes
                                targetWeight = initialTargetWeight
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFD97706)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD97706)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("تراجع (تجاهل التغييرات)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Quick Header Card with specs and objectives
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, RndBorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t("معلومات العينة التجريبية الأساسية", "Basic Experimental Sample Information"), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RndDarkSlate)
                        IconButton(onClick = { showFullEditSampleDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = t("تعديل جميع خصائص العينة", "Edit all sample properties"), tint = RndPurpleAccent)
                        }
                        IconButton(onClick = { showSamplePrintDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Print, contentDescription = t("طباعة العينة وتصدير PDF", "Print sample / export PDF"), tint = com.example.ui.theme.GBRBlueMain)
                        }
                    }
                }

                // Prominent large action button to send copy to formulations without wrapping
                // Disabled if there are unsaved pending changes
                Button(
                    onClick = {
                        showConfirmSendDialog = true
                    },
                    enabled = !hasPendingChanges,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RndSuccessGreen,
                        disabledContainerColor = Color(0xFFCBD5E1),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = if (!hasPendingChanges) Color.White else Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasPendingChanges) t("يرجى حفظ التعديلات أولاً للإرسال", "Save Changes First to Send") else t("إرسال نسخة إلى قسم التركيبات", "Send Copy to Formulations"),
                        color = if (!hasPendingChanges) Color.White else Color(0xFF64748B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("رقم العينة للكود المخبري:", fontSize = 11.sp, color = Color.Gray)
                    Text(sample.sampleNumber, fontWeight = FontWeight.Bold, color = RndPurpleAccent, fontSize = 14.sp)
                }

                HorizontalDivider(color = Color(0xFFF1F5F9))

                Text("الهدف الأساسي للتجربة ومواصفات الطلاء:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text(
                    text = if (sample.targetGoal.isBlank()) "لم يسجل هدف فني مخصص" else sample.targetGoal,
                    color = RndDarkSlate,
                    fontSize = 13.sp
                )

                if (sample.initialNotes.isNotBlank()) {
                    Text("الملاحظات الأولية للجهة أو الباحث:", fontSize = 11.sp, color = Color.Gray)
                    Text(sample.initialNotes, fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }

        // Card for Status Selection (معتمدة, مقبولة, مرفوضة) and Status Notes
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, RndBorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("حالة العينة التجريبية والقرار الفني", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RndDarkSlate)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statuses = listOf(
                        Triple("انتظار نتائج", "⏳ انتظار نتائج", Color(0xFFE0F2FE)),
                        Triple("معتمدة", "🟢 معتمدة", Color(0xFFD1FAE5)),
                        Triple("مقبولة", "🟡 مقبولة", Color(0xFFFFEDB3)),
                        Triple("مرفوضة", "🔴 مرفوضة", Color(0xFFFEE2E2))
                    )
                    
                    statuses.forEach { (statusKey, statusLabel, bgColor) ->
                        val isSelected = sampleStatus == statusKey
                        val borderCol = if (isSelected) {
                            when (statusKey) {
                                "انتظار نتائج" -> Color(0xFF0284C7)
                                "معتمدة" -> RndSuccessGreen
                                "مقبولة" -> RndWarningOrange
                                else -> RndErrorRed
                            }
                        } else Color(0xFFE2E8F0)
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(45.dp)
                                .background(if (isSelected) bgColor else Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                .border(BorderStroke(if (isSelected) 2.dp else 1.dp, borderCol), RoundedCornerShape(10.dp))
                                .clickable {
                                    sampleStatus = if (isSelected) null else statusKey
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) RndDarkSlate else Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (sampleStatus != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("سبب القرار الفني / ملاحظات الحالة:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RndDarkSlate)
                        OutlinedTextField(
                            value = sampleStatusNotes,
                            onValueChange = { sampleStatusNotes = it },
                            placeholder = { Text("أمثلة: اللزوجة ممتازة، أو اللون يحتاج تعديل...", fontSize = 12.sp, color = Color.Gray) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                        )
                        
                        if (sample.statusUpdatedAt != null) {
                            Text(
                                text = "تعديل بواسطة: ${sample.statusUpdatedBy ?: "غير معروف"} في ${sample.statusUpdatedAt}",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }

        // Header block for Formula Configuration & Conversions
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("الصيغة والمواد الفعالة في العينة", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RndDarkSlate)
            Text("ادمج المواد الكيميائية واضبط نسب التعديل بكجم", fontSize = 11.sp, color = Color.Gray)
        }

        // Edit target weight
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, RndBorderLight), RoundedCornerShape(12.dp))
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("الوزن المستهدف للعينة التجريبية:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = RndDarkSlate)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var weightInput by remember { mutableStateOf(targetWeight.toString()) }
                OutlinedTextField(
                    value = weightInput,
                    onValueChange = {
                        weightInput = it
                        val num = it.toDoubleOrNull()
                        if (num != null && num > 0.0) {
                            targetWeight = num
                        }
                    },
                    suffix = { Text("كجم") },
                    modifier = Modifier.width(100.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title for Ingredients table & Toggler
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "مكونات العينة",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = com.example.ui.theme.GBRDarkIndigo,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Mode Toggler for Weight Display: KG (وزن), Percentage (نسبة مئوية / 100 كجم), Grams (العينة)
            Row(
                modifier = Modifier
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(20.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // KG Mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (weightDisplayMode == SampleWeightDisplayMode.KG) com.example.ui.theme.GBRBlueMain else Color.Transparent)
                        .clickable { weightDisplayMode = SampleWeightDisplayMode.KG }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = t("وزن (كجم)", "Weight (kg)"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (weightDisplayMode == SampleWeightDisplayMode.KG) Color.White else Color(0xFF64748B)
                    )
                }
                // Percentage Mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (weightDisplayMode == SampleWeightDisplayMode.PERCENTAGE) com.example.ui.theme.GBRBlueMain else Color.Transparent)
                        .clickable { weightDisplayMode = SampleWeightDisplayMode.PERCENTAGE }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = t("نسبة مئوية %", "Percentage %"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (weightDisplayMode == SampleWeightDisplayMode.PERCENTAGE) Color.White else Color(0xFF64748B)
                    )
                }
                // Sample Mode (Grams)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (weightDisplayMode == SampleWeightDisplayMode.GRAMS) com.example.ui.theme.GBRBlueMain else Color.Transparent)
                        .clickable { weightDisplayMode = SampleWeightDisplayMode.GRAMS }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = t("العينة", "Sample (g)"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (weightDisplayMode == SampleWeightDisplayMode.GRAMS) Color.White else Color(0xFF64748B)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Table of materials showing original vs converted (grams / percentage) weight
        if (itemsList.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(t("التركيب فارغ حالياً!", "Formulation is currently empty!"), color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(t("قم بإضافة المواد الخام وأوزانها بكجم لتطبيق حساب التحويل اللحظي للمختبر.", "Add raw materials and their weights in kg for real-time laboratory conversion."), color = Color.LightGray, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        } else {
            val sharedRndItems = remember(itemsList, weightDisplayMode, targetWeight, totalOriginalWeightKgValue) {
                itemsList.map { item ->
                    val matId = item["rawMaterialId"] as? String ?: ""
                    val matName = item["rawMaterialName"] as? String ?: ""
                    val origQty = item["originalQuantityMultiplier"] as? Double ?: 0.0

                    val displayVal = when (weightDisplayMode) {
                        SampleWeightDisplayMode.KG -> formatQuantity(origQty)
                        SampleWeightDisplayMode.PERCENTAGE -> {
                            val pct = if (totalOriginalWeightKgValue > 0) {
                                (origQty / totalOriginalWeightKgValue) * 100.0
                            } else {
                                0.0
                            }
                            "${formatQuantity(pct)} %"
                        }
                        SampleWeightDisplayMode.GRAMS -> {
                            val gQty = if (totalOriginalWeightKgValue > 0) {
                                (origQty / totalOriginalWeightKgValue) * targetWeight * 1000.0
                            } else {
                                0.0
                            }
                            formatQuantity(gQty)
                        }
                    }

                    SharedMaterialItem(
                        id = matId,
                        rawMaterialId = matId,
                        rawMaterialName = matName,
                        displayValue = displayVal,
                        needsGrinding = false,
                        grindingDurationMinutes = 0
                    )
                }
            }

            SharedMaterialsTable(
                items = sharedRndItems,
                isReadOnly = false,
                isEditable = (weightDisplayMode == SampleWeightDisplayMode.KG),
                onItemClick = { sItem ->
                    val itm = itemsList.firstOrNull { (it["rawMaterialId"] as? String ?: "") == sItem.rawMaterialId }
                    if (itm != null && weightDisplayMode == SampleWeightDisplayMode.KG) {
                        showEditMaterialDialog = itm
                        quantityForEdit = (itm["originalQuantityMultiplier"] as? Double ?: 0.0).toString()
                    }
                },
                onMoveUp = { sItem, idx ->
                    if (idx > 0) {
                        val copyList = itemsList.toMutableList()
                        val temp = copyList[idx]
                        copyList[idx] = copyList[idx - 1]
                        copyList[idx - 1] = temp
                        itemsList = copyList
                    }
                },
                onMoveDown = { sItem, idx ->
                    if (idx < itemsList.size - 1) {
                        val copyList = itemsList.toMutableList()
                        val temp = copyList[idx]
                        copyList[idx] = copyList[idx + 1]
                        copyList[idx + 1] = temp
                        itemsList = copyList
                    }
                },
                onDelete = { sItem ->
                    val itm = itemsList.firstOrNull { (it["rawMaterialId"] as? String ?: "") == sItem.rawMaterialId }
                    if (itm != null) {
                        itemToDelete = itm
                    }
                }
            )
        }

        // Total weights summary card (matching Formulations التركيبات المعتمدة exactly)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = t("عدد المواد", "Materials Count"),
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${itemsList.size}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = RndDarkSlate
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(Color(0xFFE2E8F0))
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when (weightDisplayMode) {
                            SampleWeightDisplayMode.KG -> t("إجمالي الوزن", "Total Weight")
                            SampleWeightDisplayMode.PERCENTAGE -> t("إجمالي النسب", "Total Percentage")
                            SampleWeightDisplayMode.GRAMS -> t("إجمالي الوزن التجريبي", "Total Test Weight")
                        },
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val totalDisplay = when (weightDisplayMode) {
                        SampleWeightDisplayMode.KG -> t("${formatQuantity(totalOriginalWeightKgValue)} كجم", "${formatQuantity(totalOriginalWeightKgValue)} kg")
                        SampleWeightDisplayMode.PERCENTAGE -> if (totalOriginalWeightKgValue > 0) t("100%", "100%") else t("0%", "0%")
                        SampleWeightDisplayMode.GRAMS -> t("${formatQuantity(targetWeight * 1000.0)} غم", "${formatQuantity(targetWeight * 1000.0)} g")
                    }
                    Text(
                        text = totalDisplay,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF0284C7)
                    )
                }
            }
        }

        // Dynamic GBR style "إضافة مادة" large button
        Button(
            onClick = {
                showAddMaterialDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(t("إضافة مادة", "Add Material"), fontWeight = FontWeight.Bold, color = Color.White)
        }

        // Test Results & Quality Specs Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, RndBorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("القياسات الفيزيائية ونتائج اللمسات", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RndDarkSlate)
                        Text("سجل قراءات اللزوجة، الـ pH، البياض وقوة التغطية", fontSize = 10.sp, color = Color.Gray)
                    }

                    Button(
                        onClick = { showAddTestDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = RndCyanAccent),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("➕ نتيجة اختبار", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                if (resultsList.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .background(Color(0xFFEEF2F6), RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("لا نتائج مدونة كيميائياً بعد", color = Color.Gray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("سجل خصائص المنتج لاختبار تماسك تركيب العزل أو البياض اللحظي.", color = Color.LightGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    resultsList.forEachIndexed { index, map ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFFEEF2F6), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("📝", fontSize = 14.sp)
                                }
                                Text(map["name"] ?: "", fontWeight = FontWeight.Bold, color = RndDarkSlate, fontSize = 13.sp)
                            }
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Live editable value
                                var valText by remember(map["value"]) { mutableStateOf(map["value"] ?: "") }
                                BasicTextField(
                                    value = valText,
                                    onValueChange = {
                                        valText = it
                                        val copyRes = resultsList.toMutableList()
                                        val newMap = copyRes[index].toMutableMap()
                                        newMap["value"] = it
                                        copyRes[index] = newMap
                                        resultsList = copyRes
                                    },
                                    textStyle = LocalTextStyle.current.copy(
                                        textAlign = TextAlign.End,
                                        fontWeight = FontWeight.Bold,
                                        color = RndCyanAccent,
                                        fontSize = 14.sp
                                    ),
                                    modifier = Modifier
                                        .width(100.dp)
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )

                                IconButton(
                                    onClick = {
                                        val copyRes = resultsList.toMutableList()
                                        copyRes.removeAt(index)
                                        resultsList = copyRes
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "حذف النتيجة", tint = RndErrorRed)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Researcher Notes (ملاحظات الباحث الكبيرة) Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, RndBorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ملاحظات الباحث والتطوير (تفصيلية وسجل الملاحظات)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = RndDarkSlate)
                OutlinedTextField(
                    value = rNotes,
                    onValueChange = {
                        rNotes = it
                    },
                    readOnly = false,
                    label = { Text("اكتب ملاحظات اللمس والتشرب والتجفيف فائق الحصانة...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10
                )
            }
        }

        // 1. High-contrast Vibrant Blue "حساب التكاليف" button
        Button(
            onClick = { showCostDetailsDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = t("💰 حساب التكاليف وتأثير أسعار المواد الخام", "💰 Cost Calculation & Raw Material Prices"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Dark Slate "وصفة التشغيل" button
        Button(
            onClick = {
                if (hasPendingChanges) {
                    showRecipeUnsavedChangesDialog = true
                } else {
                    showRecipeDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RndDarkSlate),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("📖 وصفة التشغيل والخلط للعينة", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Purple "تنفيذ العينة" button
        Button(
            onClick = { showExecutionDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("🧪 تنفيذ تشغيل العينة", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Color.White)
        }
    }

    // Add Material Dialog
    if (showAddMaterialDialog) {
        var selectedMat by remember { mutableStateOf<RawMaterial?>(null) }
        var matMultiplierStr by remember { mutableStateOf("0.0") }
        var dialogSearchQuery by remember { mutableStateOf("") }

        val filteredRawMaterials = rawMaterials.filter { mat ->
            mat.isActive && (mat.name.contains(dialogSearchQuery, ignoreCase = true) || 
            mat.productionName.contains(dialogSearchQuery, ignoreCase = true))
        }

        AlertDialog(
            onDismissRequest = { showAddMaterialDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = t("➕ إضافة مادة خام للتركيب المخبري", "➕ Add Raw Material to Lab Formulation"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search Box
                    OutlinedTextField(
                        value = dialogSearchQuery,
                        onValueChange = { dialogSearchQuery = it },
                        placeholder = { Text("بحث باسم المادة أو كود الإنتاج...", fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "بحث",
                                tint = RndPurpleAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (dialogSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { dialogSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "مسح",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RndPurpleAccent,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    Text(
                        text = "قائمة المواد الخام الاختيارية (مجموع: ${filteredRawMaterials.size} مادة):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )

                    // scrollable box for materials
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (filteredRawMaterials.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "لا توجد نتائج مطابقة لمصطلح البحث.",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                filteredRawMaterials.forEach { mat ->
                                    val isSelected = selectedMat?.id == mat.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) RndPurpleAccent.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable {
                                                selectedMat = mat
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = mat.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (isSelected) RndDarkSlate else Color.DarkGray,
                                                textAlign = TextAlign.Right,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (mat.productionName.isNotBlank()) {
                                                Text(
                                                    text = "كود الإنتاج: ${mat.productionName}",
                                                    fontSize = 11.sp,
                                                    color = if (isSelected) RndPurpleAccent else Color.Gray,
                                                    textAlign = TextAlign.Right,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "تم التحديد",
                                                tint = RndPurpleAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFFE2E8F0).copy(alpha = 0.5f))
                                }
                            }
                        }
                    }

                    if (selectedMat != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RndPurpleAccent.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "المادة المختارة: ${selectedMat!!.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = RndDarkSlate,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        }
                    }

                    // Quantity text field
                    OutlinedTextField(
                        value = matMultiplierStr,
                        onValueChange = { matMultiplierStr = it },
                        label = { Text("الكمية الأصلية (كجم في الخلاط الكلي) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RndPurpleAccent,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedMat != null) {
                            val q = matMultiplierStr.toDoubleOrNull() ?: 0.0
                            
                            val copy = itemsList.toMutableList()
                            // Check if already contains
                            if (copy.any { (it["rawMaterialId"] as? String) == selectedMat!!.id }) {
                                // Update quantity
                                val idx = copy.indexOfFirst { (it["rawMaterialId"] as? String) == selectedMat!!.id }
                                val m = copy[idx].toMutableMap()
                                m["originalQuantityMultiplier"] = q
                                copy[idx] = m
                            } else {
                                copy.add(
                                    mapOf(
                                        "rawMaterialId" to selectedMat!!.id,
                                        "rawMaterialName" to selectedMat!!.name,
                                        "originalQuantityMultiplier" to q
                                    )
                                )
                            }
                            itemsList = copy
                            showAddMaterialDialog = false
                        }
                    },
                    enabled = selectedMat != null && matMultiplierStr.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(t("إضافة مادة", "Add Material"), fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMaterialDialog = false }) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Edit Material Quantity / Replace Dialog (Matching MainActivity style)
    if (showEditMaterialDialog != null) {
        val currentItem = showEditMaterialDialog!!
        val matName = currentItem["rawMaterialName"] as? String ?: ""
        AlertDialog(
            onDismissRequest = { showEditMaterialDialog = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "تعديل / استبدال المادة",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "المادة الحالية: $matName",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = RndDarkSlate
                    )

                    OutlinedTextField(
                        value = quantityForEdit,
                        onValueChange = { input ->
                            if (input.isEmpty() || input.toDoubleOrNull() != null || input.endsWith(".")) {
                                quantityForEdit = input
                            }
                        },
                        label = { Text("الكمية الجديدة (كجم) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RndPurpleAccent,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    OutlinedButton(
                        onClick = {
                            showReplaceMaterialDialog = currentItem
                            selectedRmForReplace = null
                            quantityForReplace = quantityForEdit
                            showEditMaterialDialog = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RndPurpleAccent),
                        border = BorderStroke(1.dp, RndPurpleAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "استبدال المادة",
                            tint = RndPurpleAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استبدال هذه المادة بمادة أخرى", fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qty = quantityForEdit.toDoubleOrNull()
                        if (qty != null && qty >= 0.0) {
                            val copyList = itemsList.toMutableList()
                            val idx = copyList.indexOfFirst { (it["rawMaterialId"]?.toString()) == (currentItem["rawMaterialId"]?.toString()) }
                            if (idx != -1) {
                                val map = copyList[idx].toMutableMap()
                                map["originalQuantityMultiplier"] = qty
                                copyList[idx] = map
                                itemsList = copyList
                            }
                            showEditMaterialDialog = null
                        }
                    },
                    enabled = quantityForEdit.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("حفظ تعديل الكمية", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditMaterialDialog = null }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Replace Material Dialog for R&D
    if (showReplaceMaterialDialog != null) {
        val currentItem = showReplaceMaterialDialog!!
        val matName = currentItem["rawMaterialName"] as? String ?: ""
        var replaceSearchQuery by remember { mutableStateOf("") }

        val filteredRawMaterials = rawMaterials.filter { mat ->
            mat.isActive && (mat.name.contains(replaceSearchQuery, ignoreCase = true) ||
                    mat.productionName.contains(replaceSearchQuery, ignoreCase = true))
        }

        AlertDialog(
            onDismissRequest = { showReplaceMaterialDialog = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "استبدال مادة خام في عينة التطوير",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFFBEB), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFFCD34D), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "المادة المراد استبدالها: $matName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                    }

                    OutlinedTextField(
                        value = replaceSearchQuery,
                        onValueChange = { replaceSearchQuery = it },
                        placeholder = { Text("بحث باسم المادة البديلة أو كود الإنتاج...", fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "بحث",
                                tint = RndPurpleAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (replaceSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { replaceSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "مسح",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RndPurpleAccent,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    Text(
                        text = "اختر المادة البديلة (مجموع: ${filteredRawMaterials.size} مادة):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (filteredRawMaterials.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "لا توجد نتائج مطابقة لمصطلح البحث.",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                filteredRawMaterials.forEach { mat ->
                                    val isSelected = selectedRmForReplace?.id == mat.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) RndPurpleAccent.copy(alpha = 0.12f) else Color.Transparent)
                                            .clickable {
                                                selectedRmForReplace = mat
                                            }
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = mat.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (isSelected) RndDarkSlate else Color.DarkGray,
                                                textAlign = TextAlign.Right,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            if (mat.productionName.isNotBlank()) {
                                                Text(
                                                    text = "كود الإنتاج: ${mat.productionName}",
                                                    fontSize = 11.sp,
                                                    color = if (isSelected) RndPurpleAccent else Color.Gray,
                                                    textAlign = TextAlign.Right,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "تم التحديد",
                                                tint = RndPurpleAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = Color(0xFFE2E8F0).copy(alpha = 0.5f))
                                }
                            }
                        }
                    }

                    if (selectedRmForReplace != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RndPurpleAccent.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = "المادة البديلة المختارة: ${selectedRmForReplace!!.name}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = RndDarkSlate,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        }
                    }

                    OutlinedTextField(
                        value = quantityForReplace,
                        onValueChange = { input ->
                            if (input.isEmpty() || input.toDoubleOrNull() != null || input.endsWith(".")) {
                                quantityForReplace = input
                            }
                        },
                        label = { Text("الكمية للتركيبة (كجم) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = RndPurpleAccent,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qty = quantityForReplace.toDoubleOrNull()
                        if (selectedRmForReplace != null && qty != null && qty > 0.0) {
                            val copyList = itemsList.toMutableList()
                            val oldRawMatId = currentItem["rawMaterialId"] as? String ?: ""
                            val idx = copyList.indexOfFirst { (it["rawMaterialId"] as? String ?: "") == oldRawMatId }
                            if (idx != -1) {
                                val map = copyList[idx].toMutableMap()
                                map["rawMaterialId"] = selectedRmForReplace!!.id
                                map["rawMaterialName"] = selectedRmForReplace!!.name
                                map["originalQuantityMultiplier"] = qty
                                copyList[idx] = map
                                itemsList = copyList

                                if (oldRawMatId.isNotBlank() && oldRawMatId != selectedRmForReplace!!.id && sampleRecipeJson.isNotBlank()) {
                                    try {
                                        val currentPhases = parseRecipeJson(sampleRecipeJson)
                                        val newId = selectedRmForReplace!!.id
                                        val newName = selectedRmForReplace!!.name
                                        val updatedPhases = currentPhases.map { p ->
                                            p.copy(
                                                items = p.items.map { itm ->
                                                    if (itm.rawMaterialId == oldRawMatId) {
                                                        itm.copy(rawMaterialId = newId, rawMaterialName = newName)
                                                    } else {
                                                        itm
                                                    }
                                                }
                                            )
                                        }
                                        sampleRecipeJson = serializeRecipeJson(updatedPhases)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                            }
                            showReplaceMaterialDialog = null
                        }
                    },
                    enabled = selectedRmForReplace != null && quantityForReplace.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("تأكيد الاستبدال", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceMaterialDialog = null }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Delete Material Confirmation Dialog (Matching MainActivity style)
    if (itemToDelete != null) {
        val currentItem = itemToDelete!!
        val matName = currentItem["rawMaterialName"] as? String ?: ""
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "حذف المادة من الصيغة",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndErrorRed,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف مادة ($matName) من صيغة العينة التجريبية؟",
                        fontSize = 14.sp,
                        color = RndDarkSlate
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deletedMatId = currentItem["rawMaterialId"]?.toString() ?: ""
                        val copyList = itemsList.toMutableList()
                        copyList.remove(currentItem)
                        itemsList = copyList

                        if (deletedMatId.isNotBlank()) {
                            try {
                                val currentPhases = parseRecipeJson(sampleRecipeJson)
                                val updatedPhases = currentPhases.mapNotNull { p ->
                                    val filteredItems = p.items.filter { it.rawMaterialId != deletedMatId }
                                    if (filteredItems.isEmpty() && currentPhases.size > 1) null
                                    else p.copy(items = filteredItems)
                                }
                                sampleRecipeJson = serializeRecipeJson(
                                    if (updatedPhases.isEmpty() && copyList.isNotEmpty()) {
                                        getOrInitRecipe(sample, copyList, "")
                                    } else updatedPhases
                                )
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndErrorRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("حذف المادة", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Add Custom Test Record Dialog
    if (showAddTestDialog) {
        var testName by remember { mutableStateOf("") }
        var testVal by remember { mutableStateOf("") }
        val commonTests = listOf("اللزوجة (Viscosity)", "pH الرقم الهيدروجيني", "دجة البياض (Whiteness)", "قوة التغطية (Coverage)", "مظهر الغشاء (Film Appearance)", "زمن التجفيف (Drying Time)", "مقاومة الغسيل (Washability)")

        Dialog(onDismissRequest = { showAddTestDialog = false }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, RndCyanAccent),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("🧪 إضافة نتيجة اختبار مخبري مخصص", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RndDarkSlate)

                    Text("اختر أو اكتب اختبار شائع:", fontSize = 12.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        commonTests.forEach { test ->
                            SuggestionChip(
                                onClick = { testName = test },
                                label = { Text(test) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = testName,
                        onValueChange = { testName = it },
                        label = { Text("اسم فحص القياس") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = testVal,
                        onValueChange = { testVal = it },
                        label = { Text("قيمة النتيجة المقاسة") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                if (testName.isNotBlank() && testVal.isNotBlank()) {
                                    val copy = resultsList.toMutableList()
                                    copy.add(
                                        mapOf(
                                            "name" to testName.trim(),
                                            "value" to testVal.trim()
                                        )
                                    )
                                    resultsList = copy
                                    showAddTestDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RndCyanAccent),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إضافة", color = Color.White)
                        }
                        OutlinedButton(
                            onClick = { showAddTestDialog = false },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إلغاء", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    if (showSamplePrintDialog) {
        SamplePrintDialog(
            sample = sample,
            items = itemsList,
            results = resultsList,
            notes = rNotes,
            targetWeightKg = targetWeight,
            rawMaterials = rawMaterials,
            allPackagings = allPackagings,
            allQCDefinitions = allQCDefinitions,
            onDismiss = { showSamplePrintDialog = false }
        )
    }

    // Cost Details Full Screen Page (حساب التكاليف)
    if (showCostDetailsDialog) {
        Dialog(
            onDismissRequest = { showCostDetailsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val calculatedCosts = itemsList.map { itm ->
                val mId = itm["rawMaterialId"] as? String ?: (itm["rawMaterialId"] as? Int)?.toString() ?: ""
                val oQty = itm["originalQuantityMultiplier"] as? Double ?: 0.0
                val material = rawMaterials.firstOrNull { it.id == mId }
                val unitPrice = (material?.price ?: 0.0).let { if (it == 0.0) (itm["rawMaterialPrice"] as? Double ?: 0.0) else it }
                val rawUnit = material?.priceUnit ?: "شيكل"
                val unitName = if (rawUnit == "شيكل" || rawUnit.isBlank()) t("شيكل", "ILS") else rawUnit
                val totalCost = oQty * unitPrice
                mapOf(
                    "name" to (itm["rawMaterialName"] as? String ?: ""),
                    "qty" to oQty,
                    "unitPrice" to unitPrice,
                    "unitName" to unitName,
                    "totalCost" to totalCost
                )
            }

            val totalFormulaCostVal = calculatedCosts.sumOf { (it["totalCost"] as? Double) ?: 0.0 }
            val costPerKg = if (totalOriginalWeightKgValue > 0.0) totalFormulaCostVal / totalOriginalWeightKgValue else 0.0

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFF8FAFC)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header Bar with Back Button
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                IconButton(
                                    onClick = { showCostDetailsDialog = false },
                                    modifier = Modifier
                                        .background(Color(0xFFF1F5F9), CircleShape)
                                        .size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color(0xFF1E293B)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            t("💰 حساب تكاليف العينة المخبرية", "💰 Sample Cost Calculation"),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color(0xFF0F172A),
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1
                                        )
                                        Surface(
                                            color = Color(0xFFE0F2FE),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = sample.sampleNumber,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0284C7),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "اسم العينة: ${sample.sampleName}",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { showCostDetailsDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(t("رجوع للعينة", "Back to Sample"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    // PROMINENT HERO CARD FOR NET COST PER KG (تكلفة الكيلو الصافي)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0284C7)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "🏷️",
                                        fontSize = 18.sp
                                    )
                                    Text(
                                        text = t("تكلفة الكيلو الصافي (كلفة الطلاء فقط)", "Net Paint Cost per kg"),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = "التكلفة المباشرة للمواد الكيميائية لكل 1 كغم قبل التعبئة",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.85f)
                                )
                            }

                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%,.3f", costPerKg)} ${t("شيكل/كجم", "ILS/kg")}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF0369A1),
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // Table Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "📋 جدول تكلفة المواد الخام الحالية",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // Table Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(t("المادة", "Material"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(2f), textAlign = TextAlign.Right)
                                Text(t("الكمية (كجم)", "Qty (kg)"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                Text(t("سعر الكيلو (ILS)", "Price/kg (ILS)"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                                Text(t("إجمالي التكاليف", "Total Cost"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Left)
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (calculatedCosts.isEmpty()) {
                                Text(
                                    text = t("لا توجد مواد مضافة في هذه العينة حالياً.", "No raw materials added to this sample yet."),
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                calculatedCosts.forEachIndexed { idx, c ->
                                    val name = c["name"] as String
                                    val qty = c["qty"] as Double
                                    val unitPrice = c["unitPrice"] as Double
                                    val totalCost = c["totalCost"] as Double
                                    val unitName = c["unitName"] as String

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(2f), textAlign = TextAlign.Right)
                                        Text(formatQuantity(qty), fontSize = 12.sp, color = Color(0xFF475569), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                                        Text("${String.format(java.util.Locale.US, "%.2f", unitPrice)} $unitName", fontSize = 12.sp, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                                        Text(
                                            "${String.format(java.util.Locale.US, "%.2f", totalCost)} $unitName",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0F172A),
                                            modifier = Modifier.weight(1.2f),
                                            textAlign = TextAlign.Left
                                        )
                                    }
                                    if (idx < calculatedCosts.size - 1) {
                                        HorizontalDivider(color = Color(0xFFF1F5F9))
                                    }
                                }
                            }
                        }
                    }

                    // Automatic Summary Card (ملخص التكاليف)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                        border = BorderStroke(1.dp, Color(0xFFBAE6FD))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "📊 ملخص التكاليف والتحليل التلقائي",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color(0xFF0369A1)
                            )

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t("إجمالي تكلفة المواد الخام:", "Total Raw Material Cost:"), fontSize = 12.sp, color = Color(0xFF334155))
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%,.2f", totalFormulaCostVal)} ${t("شيكل", "ILS")}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t("الوزن الكلي للعينة:", "Total Sample Weight:"), fontSize = 12.sp, color = Color(0xFF334155))
                                Text(
                                    text = "${formatQuantity(totalOriginalWeightKgValue)} ${t("كجم", "kg")}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }

                            HorizontalDivider(color = Color(0xFFBAE6FD).copy(alpha = 0.5f))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = t("تكلفة الكيلو الصافي:", "Net Cost per kg:"),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0369A1)
                                )
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%,.3f", costPerKg)} ${t("شيكل/كجم", "ILS/kg")}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF0284C7)
                                )
                            }
                        }
                    }

                    // Bottom Action Button
                    Button(
                        onClick = { showCostDetailsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text(t("إغلاق والعودة للعينة", "Close & Back to Sample"), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showRecipeUnsavedChangesDialog) {
        AlertDialog(
            onDismissRequest = { showRecipeUnsavedChangesDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "تنبيه: توجد تعديلات غير محفوظة!",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate
                    )
                }
            },
            text = {
                Text(
                    text = "بسبب وجود تعديلات جديدة على التركيبة، يجب حفظ التغييرات أولاً حتى تستطيع الاطلاع على وصفة التشغيل التي سوف تتأثر بالتعديلات.",
                    fontSize = 14.sp,
                    color = Color(0xFF475569),
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecipeUnsavedChangesDialog = false
                        saveSampleChanges()
                        showRecipeDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حفظ التعديلات وفتح وصفة التشغيل", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRecipeUnsavedChangesDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("إلغاء", color = Color(0xFF64748B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        )
    }

    if (showRecipeDialog) {
        SampleRecipeDialog(
            sample = sample,
            itemsList = itemsList,
            currentRecipeJson = sampleRecipeJson,
            targetWeight = targetWeight,
            totalOriginalWeightKgValue = totalOriginalWeightKgValue,
            onRecipeChanged = { newJson ->
                sampleRecipeJson = newJson
            },
            onDismiss = { showRecipeDialog = false }
        )
    }

    if (showExecutionDialog) {
        SampleExecutionDialog(
            sample = sample,
            itemsList = itemsList,
            currentRecipeJson = sampleRecipeJson,
            targetWeight = targetWeight,
            totalOriginalWeightKgValue = totalOriginalWeightKgValue,
            onExecutionCompleted = { updatedNotes, updatedRecipeJson ->
                rNotes = updatedNotes
                sampleRecipeJson = updatedRecipeJson
                onUpdateSample(sample.copy(researchNotes = updatedNotes, recipeJson = updatedRecipeJson))
                showExecutionDialog = false
            },
            onDismiss = { showExecutionDialog = false }
        )
    }

    if (showFullEditSampleDialog) {
        EditSampleDialog(
            sample = sample,
            onDismiss = { showFullEditSampleDialog = false },
            onSave = { updated ->
                onUpdateSample(updated)
                showFullEditSampleDialog = false
            }
        )
    }

    // 1. Confirmation Dialog with aesthetic Multiplier Option
    if (showConfirmSendDialog) {
        val parsedMultiplier = exportMultiplierInput.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val isScaled = kotlin.math.abs(parsedMultiplier - 1.0) > 0.0001
        val scaledTotalWeight = totalOriginalWeightKgValue * parsedMultiplier

        AlertDialog(
            onDismissRequest = {
                showConfirmSendDialog = false
                showMultiplierOptions = false
                exportMultiplierInput = "1.0"
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            icon = {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(RndPurpleAccent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = null,
                        tint = RndPurpleAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "تأكيد إرسال نسخة لقسم التركيبات",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "هل أنت متأكد من إرسال نسخة لقسم التركيبات؟",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E293B),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "سيتم إنشاء تركيبة جديدة في قسم التركيبات (🟡 قيد التطوير) بنسب ومكونات العينة الحالية (${sample.sampleName})، مع إمكانية استمرار تطوير وتعديل هذه العينة بحرية.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    // Multiplier toggle card - aesthetic, clean, and unobtrusive
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (showMultiplierOptions || isScaled) Color(0xFFF5F3FF) else Color(0xFFF8FAFC)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (showMultiplierOptions || isScaled) RndPurpleAccent.copy(alpha = 0.6f) else Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showMultiplierOptions = !showMultiplierOptions },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = RndPurpleAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "معامل ضرب الكميات (تكبير / تصغير)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (showMultiplierOptions || isScaled) RndPurpleAccent else Color(0xFF334155)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isScaled) RndPurpleAccent else Color(0xFFE2E8F0),
                                        contentColor = if (isScaled) Color.White else Color(0xFF475569)
                                    ) {
                                        Text(
                                            text = if (isScaled) "×$exportMultiplierInput" else "1.0x (كما هي)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Icon(
                                        imageVector = if (showMultiplierOptions) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (showMultiplierOptions) {
                                HorizontalDivider(color = Color(0xFFE2E8F0))

                                Text(
                                    text = "اختر المعامل المطلوب لضرب كميات كافة المواد دفعة واحدة:",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )

                                // Preset Chips
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val presets = listOf(
                                        "1.0" to "1.0x (الأصلية)",
                                        "1.5" to "1.5x",
                                        "2.0" to "2.0x",
                                        "5.0" to "5.0x",
                                        "10.0" to "10.0x",
                                        "0.5" to "0.5x (النصف)"
                                    )
                                    items(presets) { (value, label) ->
                                        val isSelected = exportMultiplierInput == value
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) RndPurpleAccent else Color.White,
                                            border = BorderStroke(1.dp, if (isSelected) RndPurpleAccent else Color(0xFFCBD5E1)),
                                            modifier = Modifier.clickable { exportMultiplierInput = value }
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else Color(0xFF334155),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // Custom Input
                                OutlinedTextField(
                                    value = exportMultiplierInput,
                                    onValueChange = { input ->
                                        if (input.isEmpty() || input.matches(Regex("""^\d*\.?\d*$"""))) {
                                            exportMultiplierInput = input
                                        }
                                    },
                                    label = { Text("قيمة المعامل المخصص", fontSize = 11.sp) },
                                    placeholder = { Text("مثال: 1.5", fontSize = 11.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = RndPurpleAccent,
                                        focusedLabelColor = RndPurpleAccent,
                                        unfocusedContainerColor = Color.White,
                                        focusedContainerColor = Color.White
                                    )
                                )

                                // Weight Comparison Preview
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White,
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("الوزن الإجمالي الأصلي:", fontSize = 11.sp, color = Color.Gray)
                                            Text(
                                                String.format(java.util.Locale.US, "%.2f كجم", totalOriginalWeightKgValue),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF475569)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("الوزن بعد تطبيق المعامل:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RndPurpleAccent)
                                            Text(
                                                String.format(java.util.Locale.US, "%.2f كجم", scaledTotalWeight),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = RndPurpleAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmSendDialog = false
                        sendProgressValue = 0f
                        isSendingProgress = true
                        onApproveSample(currentEditedSample, parsedMultiplier) { createdName, newFormulation ->
                            createdFormulationResult = Pair(createdName, newFormulation)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndSuccessGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isScaled) "نعم، إرسال نسخة بمعامل (×$parsedMultiplier)" else "نعم، إرسال نسخة الآن",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showConfirmSendDialog = false
                        showMultiplierOptions = false
                        exportMultiplierInput = "1.0"
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // 2. Animated Progress Indicator Dialog (3.5 seconds)
    if (isSendingProgress) {
        LaunchedEffect(Unit) {
            val totalTimeMs = 3500L
            val stepIntervalMs = 50L
            val steps = (totalTimeMs / stepIntervalMs).toInt()
            for (step in 1..steps) {
                kotlinx.coroutines.delay(stepIntervalMs)
                sendProgressValue = step.toFloat() / steps.toFloat()
            }
            // Finished progress
            isSendingProgress = false
        }

        Dialog(
            onDismissRequest = { /* Non-cancellable during progress */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(RndPurpleAccent.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { sendProgressValue },
                            modifier = Modifier.size(48.dp),
                            color = RndPurpleAccent,
                            trackColor = Color(0xFFE2E8F0),
                            strokeWidth = 4.dp
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "جاري إنشاء نسخة من التركيبة...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = RndDarkSlate,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "يتم الآن ترحيل المكونات ونسب الخلط وبناء سجل التركيبة في قسم الإنتاج والتركيبات",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }

                    LinearProgressIndicator(
                        progress = { sendProgressValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = RndPurpleAccent,
                        trackColor = Color(0xFFF1F5F9)
                    )

                    Text(
                        text = "${(sendProgressValue * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndPurpleAccent
                    )
                }
            }
        }
    }

    // 3. Success / Congratulations Dialog
    if (!isSendingProgress && createdFormulationResult != null) {
        val result = createdFormulationResult!!
        AlertDialog(
            onDismissRequest = {
                createdFormulationResult = null
                showMultiplierOptions = false
                exportMultiplierInput = "1.0"
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            icon = {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(RndSuccessGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = RndSuccessGreen,
                        modifier = Modifier.size(36.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "تهانينا! 🎉",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "تم إنشاء نسخة من التركيبة بنجاح!",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RndSuccessGreen,
                        textAlign = TextAlign.Center
                    )

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("القسم المستلم:", fontSize = 12.sp, color = Color.Gray)
                                Text("قسم التركيبات (قيد التطوير)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RndPurpleAccent)
                            }
                            HorizontalDivider(color = Color(0xFFE2E8F0))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("اسم التركيبة:", fontSize = 12.sp, color = Color.Gray)
                                Text(result.first, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = RndDarkSlate)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("كود التركيبة:", fontSize = 12.sp, color = Color.Gray)
                                Text(result.second.code, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val form = result.second
                        createdFormulationResult = null
                        showMultiplierOptions = false
                        exportMultiplierInput = "1.0"
                        onOpenFormulation(form)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("فتح التركيبة", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        createdFormulationResult = null
                        showMultiplierOptions = false
                        exportMultiplierInput = "1.0"
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("إغلاق النافذة", color = Color.Gray, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
fun CompareSamplesScreen(
    sampleA: DevelopmentSample,
    sampleB: DevelopmentSample,
    rawMaterials: List<RawMaterial>,
    onBack: () -> Unit = {}
) {
    val reportModel = remember(sampleA, sampleB, rawMaterials) {
        buildComparisonReportFromSamples(sampleA, sampleB, "مشروع تطوير العينات", rawMaterials)
    }

    ComparisonReportScreenView(
        model = reportModel,
        onBack = onBack
    )
}

// Helpers parsing JSON formats safely
private fun formatExactWeight(value: Double): String {
    if (value == 0.0) return "0"
    val df = java.text.DecimalFormat("0.####", java.text.DecimalFormatSymbols(java.util.Locale.US))
    return df.format(value)
}

private data class ParsedMat(val id: String, val name: String, val multiplier: Double)
private data class ParsedResult(val name: String, val value: String)

private fun parseItems(itemsJson: String): List<ParsedMat> {
    val list = mutableListOf<ParsedMat>()
    try {
        val jArr = JSONArray(itemsJson)
        for (i in 0 until jArr.length()) {
            val o = jArr.getJSONObject(i)
            list.add(
                ParsedMat(
                    id = o.optString("rawMaterialId").ifBlank { o.optInt("rawMaterialId", 0).toString() },
                    name = o.getString("rawMaterialName"),
                    multiplier = o.getDouble("originalQuantityMultiplier")
                )
            )
        }
    } catch (_: Exception) {}
    return list
}

private fun parseResults(resultsJson: String): List<ParsedResult> {
    val list = mutableListOf<ParsedResult>()
    try {
        val jArr = JSONArray(resultsJson)
        for (i in 0 until jArr.length()) {
            val o = jArr.getJSONObject(i)
            list.add(
                ParsedResult(
                    name = o.getString("name"),
                    value = o.getString("value")
                )
            )
        }
    } catch (_: Exception) {}
    return list
}

private fun incrementTrailingNumber(str: String, fallback: String = "CD-1"): String {
    val trimmed = str.trim()
    if (trimmed.isBlank()) return fallback

    val regex = Regex("""^(.*?)(\d+)$""")
    val match = regex.find(trimmed)
    if (match != null) {
        val prefix = match.groupValues[1]
        val numberStr = match.groupValues[2]
        val nextNumber = (numberStr.toLongOrNull() ?: 0L) + 1L
        val format = "%0${numberStr.length}d"
        val formattedNumber = String.format(java.util.Locale.US, format, nextNumber)
        return prefix + formattedNumber
    }

    if (trimmed.endsWith("-")) {
        return "${trimmed}1"
    }
    return "$trimmed-1"
}

private fun formatVal(value: Double): String {
    return String.format(java.util.Locale.US, "%.2f", value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleRecipeDialog(
    sample: DevelopmentSample,
    itemsList: List<Map<String, Any>>,
    currentRecipeJson: String = sample.recipeJson,
    targetWeight: Double,
    totalOriginalWeightKgValue: Double,
    onRecipeChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var phases by remember(currentRecipeJson, itemsList) { 
        mutableStateOf(getOrInitRecipe(sample, itemsList, currentRecipeJson)) 
    }
    val isReadOnly = false

    val triggerSave = { newList: List<DevRecipePhase> ->
        phases = newList
        onRecipeChanged(serializeRecipeJson(newList))
    }

    var showEditPhaseDialog by remember { mutableStateOf<DevRecipePhase?>(null) }
    var editPhaseNameInput by remember { mutableStateOf("") }
    var editPhaseRpmInput by remember { mutableStateOf("") }
    var editPhaseDurationInput by remember { mutableStateOf("") }
    var editPhaseInstructionsInput by remember { mutableStateOf("") }

    var showDeletePhaseConfirmation by remember { mutableStateOf<DevRecipePhase?>(null) }
    var showSplitItemDialog by remember { mutableStateOf<Pair<DevRecipePhase, DevRecipeItem>?>(null) }
    var splitAmountInput by remember { mutableStateOf("") }
    var selectedTargetPhaseId by remember { mutableStateOf("") }
    var showCreatePhaseFromItemDialog by remember { mutableStateOf<Pair<DevRecipePhase, DevRecipeItem>?>(null) }
    var showMergeConfirmationDialog by remember { mutableStateOf<Pair<Pair<DevRecipePhase, DevRecipeItem>, DevRecipeItem>?>(null) }
    val flatRecipeItemsList = remember(phases) {
        phases.flatMap { p -> p.items }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF8FAFC)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.LightGray.copy(alpha = 0.2f))
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "الرجوع",
                            tint = RndPurpleAccent
                        )
                    }

                    Text(
                        text = if (isReadOnly) "📋 تقرير وصفة التشغيل للعينة (للاطلاع)" else "📖 وصفة التشغيل والخلط للعينة التجريبية",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = RndDarkSlate
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF2F6)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📋 مراحل وخطوات التشغيل للوزن المستهدف: $targetWeight كجم (الوزن الكلي للمواد: ${formatVal(totalOriginalWeightKgValue)} كجم)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = RndDarkSlate,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (phases.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("لا توجد مراحل تشغيل معرفة حالياً", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(phases) { phaseIndex, phase ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.2.dp, Color(0xFFE2E8F0)),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Surface(
                                                    color = RndPurpleAccent.copy(alpha = 0.1f),
                                                    shape = CircleShape,
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = "${phaseIndex + 1}",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Black,
                                                            color = RndPurpleAccent
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = phase.name,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Black,
                                                    color = RndDarkSlate
                                                )
                                            }

                                            if (!isReadOnly) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            if (phaseIndex > 0) {
                                                                val list = phases.toMutableList()
                                                                val current = list[phaseIndex]
                                                                val target = list[phaseIndex - 1]
                                                                list[phaseIndex] = target.copy(sequence = current.sequence, name = "المرحلة ${current.sequence}")
                                                                list[phaseIndex - 1] = current.copy(sequence = target.sequence, name = "المرحلة ${target.sequence}")
                                                                triggerSave(list.sortedBy { it.sequence })
                                                            }
                                                        },
                                                        enabled = phaseIndex > 0,
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowUp,
                                                            contentDescription = "رفع المرحلة",
                                                            tint = if (phaseIndex > 0) RndPurpleAccent else Color.LightGray,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            if (phaseIndex < phases.size - 1) {
                                                                val list = phases.toMutableList()
                                                                val current = list[phaseIndex]
                                                                val target = list[phaseIndex + 1]
                                                                list[phaseIndex] = target.copy(sequence = current.sequence, name = "المرحلة ${current.sequence}")
                                                                list[phaseIndex + 1] = current.copy(sequence = target.sequence, name = "المرحلة ${target.sequence}")
                                                                triggerSave(list.sortedBy { it.sequence })
                                                            }
                                                        },
                                                        enabled = phaseIndex < phases.size - 1,
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.KeyboardArrowDown,
                                                            contentDescription = "خفض المرحلة",
                                                            tint = if (phaseIndex < phases.size - 1) RndPurpleAccent else Color.LightGray,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = {
                                                            editPhaseNameInput = phase.name
                                                            editPhaseRpmInput = phase.mixerRpm.toString()
                                                            editPhaseDurationInput = phase.durationMinutes.toString()
                                                            editPhaseInstructionsInput = phase.instructions
                                                            showEditPhaseDialog = phase
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Settings,
                                                            contentDescription = "إعدادات المرحلة",
                                                            tint = Color(0xFF0F766E),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }

                                                    IconButton(
                                                        onClick = { showDeletePhaseConfirmation = phase },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Delete,
                                                            contentDescription = "حذف المرحلة",
                                                            tint = RndErrorRed,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = if (phase.mixerRpm > 0) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = if (phase.mixerRpm > 0) "الخلط: ${phase.mixerRpm} RPM" else "الخلط: غير محدد",
                                                    fontSize = 11.sp,
                                                    color = if (phase.mixerRpm > 0) Color(0xFF1E40AF) else Color.Gray,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                            Surface(
                                                color = if (phase.durationMinutes > 0) Color(0xFFFFF7ED) else Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = if (phase.durationMinutes > 0) "الزمن: ${phase.durationMinutes} دقيقة" else "الزمن: غير محدد",
                                                    fontSize = 11.sp,
                                                    color = if (phase.durationMinutes > 0) Color(0xFFC2410C) else Color.Gray,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = if (phase.instructions.isNotBlank()) "تعليمات المشغل: ${phase.instructions}" else "تعليمات المشغل: غير محدد",
                                            fontSize = 11.sp,
                                            color = if (phase.instructions.isNotBlank()) Color(0xFF475569) else Color.LightGray,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            fontWeight = FontWeight.Medium,
                                            textAlign = TextAlign.Right
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))
                                        HorizontalDivider(color = Color(0xFFF1F5F9))
                                        Spacer(modifier = Modifier.height(8.dp))

                                        if (phase.items.isEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 12.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text("لا توجد مواد داخل هذه المرحلة حالياً", fontSize = 11.sp, color = Color.LightGray)
                                            }
                                        } else {
                                            phase.items.forEachIndexed { itemIndex, item ->
                                                val sampleItem = itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }
                                                val originalWeight = sampleItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
                                                val standardWeightInPhase = originalWeight * item.ratio
                                                
                                                val scaledWeightInGrams = if (totalOriginalWeightKgValue > 0) {
                                                    (standardWeightInPhase / totalOriginalWeightKgValue) * targetWeight * 1000.0
                                                } else {
                                                    0.0
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable(enabled = !isReadOnly && originalWeight > 0.0) {
                                                            splitAmountInput = ""
                                                            selectedTargetPhaseId = phases.firstOrNull { it.id != phase.id }?.id ?: ""
                                                            showSplitItemDialog = Pair(phase, item)
                                                        }
                                                        .padding(vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = null,
                                                            tint = RndPurpleAccent,
                                                            modifier = Modifier.size(14.dp)
                                                        )

                                                        if (!isReadOnly) {
                                                            IconButton(
                                                                onClick = { showCreatePhaseFromItemDialog = Pair(phase, item) },
                                                                modifier = Modifier.size(28.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = Icons.Default.KeyboardArrowDown,
                                                                    contentDescription = "إنشاء خطوة جديدة بدءاً من هنا",
                                                                    tint = Color(0xFF0F766E),
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                            }
                                                        }

                                                        Column {
                                                            Text(
                                                                text = item.rawMaterialName,
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.sp,
                                                                color = RndDarkSlate,
                                                            )
                                                            if (item.ratio < 1.0) {
                                                                Text(
                                                                    text = "نسبة التوزيع للدفعة: ${formatVal(item.ratio * 100.0)}%",
                                                                    fontSize = 10.sp,
                                                                    color = Color.Gray,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Column(horizontalAlignment = Alignment.End) {
                                                            Text(
                                                                text = "${formatVal(scaledWeightInGrams)} جرام",
                                                                fontSize = 12.sp,
                                                                color = RndPurpleAccent,
                                                                fontWeight = FontWeight.Black
                                                            )
                                                            Text(
                                                                text = "الوزن القياسي: ${formatVal(standardWeightInPhase)} كجم",
                                                                fontSize = 10.sp,
                                                                color = Color.Gray
                                                            )
                                                        }

                                                        if (!isReadOnly) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                            ) {
                                                                val isUpEnabled = itemIndex > 0 || phaseIndex > 0
                                                                IconButton(
                                                                    onClick = {
                                                                        val itemPos = flatRecipeItemsList.indexOfFirst { it.id == item.id }
                                                                        val upperItem = if (itemPos > 0) flatRecipeItemsList[itemPos - 1] else null
                                                                        if (upperItem != null && upperItem.rawMaterialId == item.rawMaterialId) {
                                                                            showMergeConfirmationDialog = Pair(phase to item, upperItem)
                                                                        } else {
                                                                            val items = phase.items.toMutableList()
                                                                            if (itemIndex > 0) {
                                                                                val cur = items[itemIndex]
                                                                                val target = items[itemIndex - 1]
                                                                                items[itemIndex] = target.copy(sequence = cur.sequence)
                                                                                items[itemIndex - 1] = cur.copy(sequence = target.sequence)
                                                                                val newList = phases.map { p ->
                                                                                    if (p.id == phase.id) p.copy(items = items.sortedBy { it.sequence }) else p
                                                                                }
                                                                                triggerSave(newList)
                                                                            } else if (phaseIndex > 0) {
                                                                                val prevPhase = phases[phaseIndex - 1]
                                                                                val updatedItems = items.toMutableList()
                                                                                val currentItem = updatedItems.removeAt(itemIndex)
                                                                                val prevItems = prevPhase.items.toMutableList()
                                                                                prevItems.add(currentItem.copy(sequence = prevItems.size))
                                                                                
                                                                                val newList = phases.map { p ->
                                                                                    when (p.id) {
                                                                                        phase.id -> p.copy(items = updatedItems.mapIndexed { idx, itm -> itm.copy(sequence = idx) })
                                                                                        prevPhase.id -> p.copy(items = prevItems)
                                                                                        else -> p
                                                                                    }
                                                                                }
                                                                                triggerSave(newList)
                                                                            }
                                                                        }
                                                                    },
                                                                    enabled = isUpEnabled,
                                                                    modifier = Modifier.size(24.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.KeyboardArrowUp,
                                                                        contentDescription = "نقل لأعلى",
                                                                        tint = if (isUpEnabled) RndPurpleAccent else Color.LightGray,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }

                                                                val isDownEnabled = itemIndex < phase.items.size - 1 || phaseIndex < phases.size - 1
                                                                IconButton(
                                                                    onClick = {
                                                                        val items = phase.items.toMutableList()
                                                                        if (itemIndex < items.size - 1) {
                                                                            val cur = items[itemIndex]
                                                                            val target = items[itemIndex + 1]
                                                                            items[itemIndex] = target.copy(sequence = cur.sequence)
                                                                            items[itemIndex + 1] = cur.copy(sequence = target.sequence)
                                                                            val newList = phases.map { p ->
                                                                                if (p.id == phase.id) p.copy(items = items.sortedBy { it.sequence }) else p
                                                                            }
                                                                            triggerSave(newList)
                                                                        } else if (phaseIndex < phases.size - 1) {
                                                                            val nextPhase = phases[phaseIndex + 1]
                                                                            val updatedItems = items.toMutableList()
                                                                            val currentItem = updatedItems.removeAt(itemIndex)
                                                                            val nextItems = nextPhase.items.toMutableList()
                                                                            nextItems.add(0, currentItem.copy(sequence = 0))
                                                                            
                                                                            val newList = phases.map { p ->
                                                                                when (p.id) {
                                                                                    phase.id -> p.copy(items = updatedItems.mapIndexed { idx, itm -> itm.copy(sequence = idx) })
                                                                                    nextPhase.id -> p.copy(items = nextItems.mapIndexed { idx, itm -> itm.copy(sequence = idx) })
                                                                                    else -> p
                                                                                }
                                                                            }
                                                                            triggerSave(newList)
                                                                        }
                                                                    },
                                                                    enabled = isDownEnabled,
                                                                    modifier = Modifier.size(24.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.KeyboardArrowDown,
                                                                        contentDescription = "نقل لأسفل",
                                                                        tint = if (isDownEnabled) RndPurpleAccent else Color.LightGray,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                if (itemIndex < phase.items.size - 1) {
                                                    HorizontalDivider(color = Color(0xFFF8FAFC))
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isReadOnly) {
                                item {
                                    Button(
                                        onClick = {
                                            val nextSeq = (phases.maxOfOrNull { it.sequence } ?: 0) + 1
                                            val newPhase = DevRecipePhase(
                                                id = java.util.UUID.randomUUID().toString(),
                                                name = "المرحلة $nextSeq",
                                                sequence = nextSeq,
                                                mixerRpm = 0,
                                                durationMinutes = 0,
                                                instructions = ""
                                            )
                                            triggerSave(phases + newPhase)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.White)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("إضافة مرحلة جديدة +", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            item {
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = RndDarkSlate),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp)
                ) {
                    Text("إغلاق واعتماد الوصفة المعروضة", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    if (showEditPhaseDialog != null) {
        val phase = showEditPhaseDialog!!
        AlertDialog(
            onDismissRequest = { showEditPhaseDialog = null },
            title = { Text("إعدادات المرحلة", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editPhaseNameInput,
                        onValueChange = { editPhaseNameInput = it },
                        label = { Text("اسم المرحلة") }
                    )
                    OutlinedTextField(
                        value = editPhaseRpmInput,
                        onValueChange = { editPhaseRpmInput = it },
                        label = { Text("سرعة الخلط RPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = editPhaseDurationInput,
                        onValueChange = { editPhaseDurationInput = it },
                        label = { Text("مدة المرحلة بالدقائق") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = editPhaseInstructionsInput,
                        onValueChange = { editPhaseInstructionsInput = it },
                        label = { Text("تعليمات المشغل") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val rpmVal = editPhaseRpmInput.toIntOrNull() ?: 0
                        val durVal = editPhaseDurationInput.toIntOrNull() ?: 0
                        val newList = phases.map { p ->
                            if (p.id == phase.id) {
                                p.copy(
                                    name = editPhaseNameInput,
                                    mixerRpm = rpmVal,
                                    durationMinutes = durVal,
                                    instructions = editPhaseInstructionsInput
                                )
                            } else p
                        }
                        triggerSave(newList)
                        showEditPhaseDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent)
                ) {
                    Text("حفظ الإعدادات", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPhaseDialog = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    if (showDeletePhaseConfirmation != null) {
        val phase = showDeletePhaseConfirmation!!
        AlertDialog(
            onDismissRequest = { showDeletePhaseConfirmation = null },
            title = { Text("حذف المرحلة", fontWeight = FontWeight.Bold) },
            text = { Text("هل أنت متأكد من حذف ${phase.name}؟ سيتم نقل كافة مكوناتها تلقائياً لأقرب مرحلة تشغيل متاحة.") },
            confirmButton = {
                Button(
                    onClick = {
                        val remainingPhases = phases.filter { it.id != phase.id }
                        val targetPhase = remainingPhases.lastOrNull { it.sequence < phase.sequence }
                            ?: remainingPhases.firstOrNull { it.sequence > phase.sequence }
                        
                        val updatedPhases = if (targetPhase != null && phase.items.isNotEmpty()) {
                            remainingPhases.map { p ->
                                if (p.id == targetPhase.id) {
                                    val merged = p.items.toMutableList()
                                    for (item in phase.items) {
                                        val dup = merged.find { it.rawMaterialId == item.rawMaterialId }
                                        if (dup != null) {
                                            merged[merged.indexOf(dup)] = dup.copy(ratio = (dup.ratio + item.ratio).coerceAtMost(1.0))
                                        } else {
                                            merged.add(item.copy(sequence = merged.size))
                                        }
                                    }
                                    p.copy(items = merged)
                                } else p
                            }
                        } else remainingPhases

                        val resequenced = updatedPhases.sortedBy { it.sequence }.mapIndexed { idx, p ->
                            p.copy(sequence = idx + 1, name = "المرحلة ${idx + 1}")
                        }
                        triggerSave(resequenced)
                        showDeletePhaseConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndErrorRed)
                ) {
                    Text("حذف ونقل المكونات", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePhaseConfirmation = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    if (showSplitItemDialog != null) {
        val (phase, item) = showSplitItemDialog!!
        val sampleItem = itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }
        val originalWeight = sampleItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
        val currentWeightInPhase = originalWeight * item.ratio

        var isShiftMode by remember(showSplitItemDialog) { mutableStateOf(true) }
        val enteredWeight = com.example.parseUserDouble(splitAmountInput) ?: 0.0
        val isValid = splitAmountInput.isNotBlank() && enteredWeight > 0.0 && enteredWeight <= (currentWeightInPhase + 0.0001)

        val weightToKeep = if (isShiftMode) (currentWeightInPhase - enteredWeight).coerceAtLeast(0.0) else enteredWeight
        val weightToTransfer = if (isShiftMode) enteredWeight else (currentWeightInPhase - enteredWeight).coerceAtLeast(0.0)

        AlertDialog(
            onDismissRequest = { showSplitItemDialog = null },
            title = { Text("تقسيم وتوزيع المادة الكيميائية", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("المادة: ${item.rawMaterialName}", fontWeight = FontWeight.Medium)
                    Text("الوزن الإجمالي في هذه المرحلة: ${formatVal(currentWeightInPhase)} كجم")

                    // Selection Row for Shift vs Keep Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = { isShiftMode = true; splitAmountInput = "" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isShiftMode) RndPurpleAccent else Color.Transparent,
                                contentColor = if (isShiftMode) Color.White else Color.Gray
                            ),
                            elevation = null,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("ترحيل (نقل) كمية", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { isShiftMode = false; splitAmountInput = "" },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isShiftMode) RndPurpleAccent else Color.Transparent,
                                contentColor = if (!isShiftMode) Color.White else Color.Gray
                            ),
                            elevation = null,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Text("تحديد كمية الإبقاء", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedTextField(
                        value = splitAmountInput,
                        onValueChange = { input ->
                            if (com.example.isPartialUserDouble(input)) {
                                splitAmountInput = input
                            }
                        },
                        label = { Text(if (isShiftMode) "الوزن المراد ترحيله للمرحلة الأخرى (كجم)" else "الوزن المطلوب إبقاؤه في هذه المرحلة (كجم)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Dynamic Real-Time Calculations Preview
                    if (splitAmountInput.isNotBlank()) {
                        if (isValid) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "الكمية التي ستبقى في هذه المرحلة: ${formatVal(weightToKeep)} كجم",
                                        color = Color(0xFF166534),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "الكمية التي ستنتقل للمرحلة المستهدفة: ${formatVal(weightToTransfer)} كجم",
                                        color = Color(0xFF166534),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "خطأ: يجب أن تكون القيمة المدخلة أكبر من 0 وأقل من أو تساوي الوزن الإجمالي في هذه المرحلة (${formatVal(currentWeightInPhase)} كجم).",
                                color = Color.Red,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text("اختر المرحلة المستهدفة للمتبقي:")

                    phases.filter { it.id != phase.id }.forEach { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetPhaseId = p.id }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = selectedTargetPhaseId == p.id, onClick = { selectedTargetPhaseId = p.id })
                            Text(p.name, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isValid) {
                            val targetPhaseId = selectedTargetPhaseId
                            if (targetPhaseId.isNotBlank()) {
                                val newCurrentRatio = weightToKeep / originalWeight
                                val remainingRatio = weightToTransfer / originalWeight

                                val newList = phases.map { p ->
                                    when (p.id) {
                                        phase.id -> {
                                            val updatedItems = p.items.map {
                                                if (it.id == item.id) it.copy(ratio = newCurrentRatio) else it
                                            }.filter { it.ratio > 0.0001 }
                                            p.copy(items = updatedItems)
                                        }
                                        targetPhaseId -> {
                                            val existing = p.items.find { it.rawMaterialId == item.rawMaterialId }
                                            if (existing != null) {
                                                p.copy(items = p.items.map {
                                                    if (it.id == existing.id) it.copy(ratio = (existing.ratio + remainingRatio).coerceAtMost(1.0)) else it
                                                })
                                            } else {
                                                p.copy(items = p.items + DevRecipeItem(
                                                    rawMaterialId = item.rawMaterialId,
                                                    rawMaterialName = item.rawMaterialName,
                                                    ratio = remainingRatio,
                                                    sequence = p.items.size
                                                ))
                                            }
                                        }
                                        else -> p
                                    }
                                }
                                triggerSave(newList)
                            }
                        }
                        showSplitItemDialog = null
                    },
                    enabled = isValid && selectedTargetPhaseId.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent)
                ) {
                    Text("تأكيد التوزيع والترحيل", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitItemDialog = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    if (showMergeConfirmationDialog != null) {
        val (currentPair, upperItem) = showMergeConfirmationDialog!!
        val (currentPhase, currentItem) = currentPair
        val upperItemPhase = phases.find { p -> p.items.any { it.id == upperItem.id } }
        
        AlertDialog(
            onDismissRequest = { showMergeConfirmationDialog = null },
            title = {
                Text(
                    text = "دمج المادة وإلغاء التوزيع 🔄",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Text(
                    text = "تم اكتشاف نفس المادة (${currentItem.rawMaterialName}) في المرحلة السابقة.\n\nهل ترغب في دمج الكميتين وإلغاء التوزيع؟",
                    fontSize = 14.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (upperItemPhase != null) {
                            val rawSum = upperItem.ratio + currentItem.ratio
                            val mergedRatio = if (rawSum >= 0.9999) 1.0 else rawSum.coerceAtMost(1.0)
                            
                            val newList = phases.map { p ->
                                val isUpperPhase = (p.id == upperItemPhase.id)
                                val isCurrentPhase = (p.id == currentPhase.id)
                                
                                when {
                                    isUpperPhase && isCurrentPhase -> {
                                        val updatedItems = p.items
                                            .filter { it.id != currentItem.id }
                                            .map { if (it.id == upperItem.id) it.copy(ratio = mergedRatio) else it }
                                            .mapIndexed { idx, itm -> itm.copy(sequence = idx) }
                                        p.copy(items = updatedItems)
                                    }
                                    isUpperPhase -> {
                                        val updatedItems = p.items.map {
                                            if (it.id == upperItem.id) it.copy(ratio = mergedRatio) else it
                                        }
                                        p.copy(items = updatedItems)
                                    }
                                    isCurrentPhase -> {
                                        val updatedItems = p.items
                                            .filter { it.id != currentItem.id }
                                            .mapIndexed { idx, itm -> itm.copy(sequence = idx) }
                                        p.copy(items = updatedItems)
                                    }
                                    else -> p
                                }
                            }
                            triggerSave(newList)
                        }
                        showMergeConfirmationDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent)
                ) {
                    Text("دمج المادة", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmationDialog = null }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showCreatePhaseFromItemDialog != null) {
        val (phase, item) = showCreatePhaseFromItemDialog!!
        AlertDialog(
            onDismissRequest = { showCreatePhaseFromItemDialog = null },
            title = { Text("تفريغ مرحلة جديدة", fontWeight = FontWeight.Bold) },
            text = { Text("هل تريد إنشاء مرحلة تشغيل جديدة مفرعة فوراً بعد هذه المرحلة، ونقل هذه المادة وكافة المواد التي تليها إليها؟") },
            confirmButton = {
                Button(
                    onClick = {
                        val currentSequence = phase.sequence
                        val shifted = phases.map { p ->
                            if (p.sequence > currentSequence) p.copy(sequence = p.sequence + 1) else p
                        }
                        
                        val currentItems = phase.items.toMutableList()
                        val idx = currentItems.indexOfFirst { it.id == item.id }
                        val toMove = currentItems.subList(idx, currentItems.size).toList()
                        val toKeep = currentItems.subList(0, idx).toList()
                        
                        val newPhaseId = java.util.UUID.randomUUID().toString()
                        val nextSeq = currentSequence + 1
                        val newPhaseObj = DevRecipePhase(
                            id = newPhaseId,
                            name = "المرحلة المؤقتة",
                            sequence = nextSeq,
                            mixerRpm = 1000,
                            durationMinutes = 10,
                            instructions = "تفريغ خطوة جديدة",
                            items = toMove.mapIndexed { index, itm -> itm.copy(sequence = index) }
                        )

                        val combined = shifted.map { p ->
                            if (p.id == phase.id) p.copy(items = toKeep) else p
                        } + newPhaseObj
                        
                        val finalPhasesList = combined.sortedBy { it.sequence }.mapIndexed { index, p ->
                            p.copy(sequence = index + 1, name = "المرحلة ${index + 1}")
                        }
                        triggerSave(finalPhasesList)
                        showCreatePhaseFromItemDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))
                ) {
                    Text("إنشاء خطوة تفريغ", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePhaseFromItemDialog = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }
}

// R&D Sample Operating Recipe Classes and Utilities
data class DevRecipeItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val rawMaterialId: String,
    val rawMaterialName: String,
    val ratio: Double,
    val sequence: Int,
    val isExecuted: Boolean = false,
    // R&D save-progress fields (optional; old samples without them still load)
    val actualWeight: String = "",
    val observation: String = "",
    val editReason: String = "",
    val savedAt: Long = 0L
)

data class DevRecipePhase(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val sequence: Int,
    val mixerRpm: Int,
    val durationMinutes: Int,
    val instructions: String,
    val items: List<DevRecipeItem> = emptyList()
)

fun parseRecipeJson(jsonStr: String): List<DevRecipePhase> {
    val list = mutableListOf<DevRecipePhase>()
    if (jsonStr.isBlank() || jsonStr == "[]") return list
    try {
        val jArr = JSONArray(jsonStr)
        for (i in 0 until jArr.length()) {
            val pObj = jArr.getJSONObject(i)
            val itemsList = mutableListOf<DevRecipeItem>()
            val itemsJArr = pObj.optJSONArray("items")
            if (itemsJArr != null) {
                for (j in 0 until itemsJArr.length()) {
                    val iObj = itemsJArr.getJSONObject(j)
                    itemsList.add(
                        DevRecipeItem(
                            id = iObj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            rawMaterialId = iObj.getString("rawMaterialId"),
                            rawMaterialName = iObj.optString("rawMaterialName").ifBlank { "مادة غير معروفة" },
                            ratio = iObj.getDouble("ratio"),
                            sequence = iObj.optInt("sequence", j),
                            isExecuted = iObj.optBoolean("isExecuted", false),
                            actualWeight = iObj.optString("actualWeight", ""),
                            observation = iObj.optString("observation", ""),
                            editReason = iObj.optString("editReason", ""),
                            savedAt = iObj.optLong("savedAt", 0L)
                        )
                    )
                }
            }
            list.add(
                DevRecipePhase(
                    id = pObj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = pObj.getString("name"),
                    sequence = pObj.getInt("sequence"),
                    mixerRpm = pObj.optInt("mixerRpm", 0),
                    durationMinutes = pObj.optInt("durationMinutes", 0),
                    instructions = pObj.optString("instructions", ""),
                    items = itemsList.sortedBy { it.sequence }
                )
            )
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list.sortedBy { it.sequence }
}

fun serializeRecipeJson(phases: List<DevRecipePhase>): String {
    val jArr = JSONArray()
    for (p in phases) {
        val pObj = JSONObject()
        pObj.put("id", p.id)
        pObj.put("name", p.name)
        pObj.put("sequence", p.sequence)
        pObj.put("mixerRpm", p.mixerRpm)
        pObj.put("durationMinutes", p.durationMinutes)
        pObj.put("instructions", p.instructions)
        
        val itemsJArr = JSONArray()
        for (item in p.items) {
            val iObj = JSONObject()
            iObj.put("id", item.id)
            iObj.put("rawMaterialId", item.rawMaterialId)
            iObj.put("rawMaterialName", item.rawMaterialName)
            iObj.put("ratio", item.ratio)
            iObj.put("sequence", item.sequence)
            iObj.put("isExecuted", item.isExecuted)
            if (item.actualWeight.isNotBlank()) iObj.put("actualWeight", item.actualWeight)
            if (item.observation.isNotBlank()) iObj.put("observation", item.observation)
            if (item.editReason.isNotBlank()) iObj.put("editReason", item.editReason)
            if (item.savedAt > 0L) iObj.put("savedAt", item.savedAt)
            itemsJArr.put(iObj)
        }
        pObj.put("items", itemsJArr)
        jArr.put(pObj)
    }
    return jArr.toString()
}

fun getOrInitRecipe(
    sample: DevelopmentSample,
    itemsList: List<Map<String, Any>>,
    recipeJsonOverride: String? = null
): List<DevRecipePhase> {
    val recipeStr = recipeJsonOverride ?: sample.recipeJson
    var phases = parseRecipeJson(recipeStr)
    val idToNameMap = mutableMapOf<String, String>()
    val validItemIds = mutableSetOf<String>()
    
    for (itm in itemsList) {
        val id = (itm["rawMaterialId"] as? String)?.ifBlank { null }
            ?: (itm["rawMaterialId"] as? Number)?.toString()
        val name = itm["rawMaterialName"] as? String ?: ""
        if (!id.isNullOrBlank()) {
            validItemIds.add(id)
            if (name.isNotBlank()) {
                idToNameMap[id] = name
            }
        }
    }

    if (phases.isEmpty()) {
        if (itemsList.isEmpty()) return emptyList()
        val items = itemsList.mapIndexed { idx, itm ->
            val id = (itm["rawMaterialId"] as? String)?.ifBlank { null } ?: (itm["rawMaterialId"] as? Number)?.toString() ?: ""
            val name = itm["rawMaterialName"] as? String ?: ""
            DevRecipeItem(
                rawMaterialId = id,
                rawMaterialName = name,
                ratio = 1.0,
                sequence = idx
            )
        }
        return listOf(
            DevRecipePhase(
                name = "المرحلة الأولى",
                sequence = 1,
                mixerRpm = 0,
                durationMinutes = 0,
                instructions = "",
                items = items
            )
        )
    }

    // 1. Remove recipe items whose rawMaterialId is no longer in itemsList & update rawMaterialName to current name
    val cleanedPhases = phases.mapNotNull { phase ->
        val validItems = phase.items.filter { it.rawMaterialId in validItemIds }.map { item ->
            val currentName = idToNameMap[item.rawMaterialId]
            if (!currentName.isNullOrBlank()) item.copy(rawMaterialName = currentName) else item
        }
        if (validItems.isEmpty() && phases.size > 1) {
            null
        } else {
            phase.copy(items = validItems)
        }
    }

    // 2. Add raw materials from itemsList that are missing in recipe phases
    val existingRecipeRawMatIds = cleanedPhases.flatMap { it.items }.map { it.rawMaterialId }.toSet()
    val missingRawMats = itemsList.filter { 
        val id = (it["rawMaterialId"] as? String)?.ifBlank { null } ?: (it["rawMaterialId"] as? Number)?.toString() ?: ""
        id.isNotBlank() && id !in existingRecipeRawMatIds 
    }

    if (missingRawMats.isNotEmpty()) {
        val newRecipeItems = missingRawMats.mapIndexed { idx, itm ->
            val id = (itm["rawMaterialId"] as? String)?.ifBlank { null } ?: (itm["rawMaterialId"] as? Number)?.toString() ?: ""
            val name = itm["rawMaterialName"] as? String ?: ""
            DevRecipeItem(
                rawMaterialId = id,
                rawMaterialName = name,
                ratio = 1.0,
                sequence = idx
            )
        }

        if (cleanedPhases.isEmpty()) {
            return listOf(
                DevRecipePhase(
                    name = "المرحلة الأولى",
                    sequence = 1,
                    mixerRpm = 0,
                    durationMinutes = 0,
                    instructions = "",
                    items = newRecipeItems
                )
            )
        } else {
            val firstPhase = cleanedPhases[0]
            val updatedFirstPhaseItems = firstPhase.items + newRecipeItems.mapIndexed { idx, item -> 
                item.copy(sequence = firstPhase.items.size + idx) 
            }
            val updatedFirstPhase = firstPhase.copy(items = updatedFirstPhaseItems)
            return listOf(updatedFirstPhase) + cleanedPhases.drop(1)
        }
    }

    if (cleanedPhases.isEmpty() && itemsList.isNotEmpty()) {
        val items = itemsList.mapIndexed { idx, itm ->
            val id = (itm["rawMaterialId"] as? String)?.ifBlank { null } ?: (itm["rawMaterialId"] as? Number)?.toString() ?: ""
            val name = itm["rawMaterialName"] as? String ?: ""
            DevRecipeItem(
                rawMaterialId = id,
                rawMaterialName = name,
                ratio = 1.0,
                sequence = idx
            )
        }
        return listOf(
            DevRecipePhase(
                name = "المرحلة الأولى",
                sequence = 1,
                mixerRpm = 0,
                durationMinutes = 0,
                instructions = "",
                items = items
            )
        )
    }

    return cleanedPhases
}

@Composable
fun SampleExecutionDialog(
    sample: DevelopmentSample,
    itemsList: List<Map<String, Any>>,
    currentRecipeJson: String = sample.recipeJson,
    targetWeight: Double,
    totalOriginalWeightKgValue: Double,
    onExecutionCompleted: (updatedNotes: String, updatedRecipeJson: String) -> Unit,
    onDismiss: () -> Unit
) {
    val phases = remember(currentRecipeJson, itemsList) { getOrInitRecipe(sample, itemsList, currentRecipeJson) }
    
    // Initial executed item IDs parsed from existing recipeJson
    val initialExecutedIds = remember(phases) {
        phases.flatMap { it.items }.filter { it.isExecuted }.map { it.id }.toSet()
    }

    // Dual-Mode Selector State: 0 = Lista Mode (قائمة المواد الكاملة), 1 = Production Floor Single-Item View (وضع صالة الإنتاج الأحادي)
    var executionMode by remember { mutableStateOf(0) }
    
    // Shared States for execution (persisted across modes)
    var executedItemIds by remember { mutableStateOf(initialExecutedIds) }
    var actualWeights by remember(phases, itemsList) {
        val initialMap = mutableMapOf<String, String>()
        phases.forEach { phase ->
            phase.items.forEach { item ->
                val sItem = itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }
                val origW = sItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
                val stdW = origW * item.ratio
                val targetG = if (totalOriginalWeightKgValue > 0) {
                    (stdW / totalOriginalWeightKgValue) * targetWeight * 1000.0
                } else 0.0
                if (item.isExecuted) {
                    initialMap[item.id] = item.actualWeight.ifBlank { formatExactWeight(targetG) }
                }
            }
        }
        mutableStateOf(initialMap.toMap())
    }
    val initialObservations = remember(phases) { phases.flatMap { it.items }.filter { it.isExecuted && it.observation.isNotBlank() }.associate { it.id to it.observation } }
    var itemObservations by remember(phases) { mutableStateOf(initialObservations) }
    // optional reason typed when a quantity is edited (id -> reason)
    val initialEditReasons = remember(phases) { phases.flatMap { it.items }.filter { it.isExecuted && it.editReason.isNotBlank() }.associate { it.id to it.editReason } }
    var editReasons by remember(phases) { mutableStateOf(initialEditReasons) }
    
    // Lifting Text inputs out of LazyColumn to prevent focus loss & scrolling resets
    var inputWeights by remember { mutableStateOf(emptyMap<String, String>()) }
    var inputObservations by remember { mutableStateOf(emptyMap<String, String>()) }

    // Live logger state
    var logs by remember { 
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        mutableStateOf(listOf("🧪 [${sdf.format(java.util.Date())}] تم فتح معمل تحضير العينة وبدء محاكاة التشغيل المخبري")) 
    }

    // Helper to log actions
    val addLog = { message: String ->
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        logs = logs + "[${sdf.format(java.util.Date())}] $message"
    }

    // Count completed items
    val totalItemsCount = remember(phases) { phases.flatMap { it.items }.size }
    val executedItemsCount = remember(executedItemIds) { executedItemIds.size }
    val progress = if (totalItemsCount > 0) executedItemsCount.toFloat() / totalItemsCount else 0f

    // Keep screen awake while in sample execution mode
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val allDone = executedItemsCount >= totalItemsCount

    fun targetGramsOf(item: DevRecipeItem): Double {
        val sItem = itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }
        val origW = sItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
        val stdW = origW * item.ratio
        return if (totalOriginalWeightKgValue > 0) (stdW / totalOriginalWeightKgValue) * targetWeight * 1000.0 else 0.0
    }

    // one line per edited material (net change vs the target); a later save replaces the earlier line, nothing if reverted
    fun applyEditLines(notes: String): String {
        var lines = notes.split("\n")
        val fresh = mutableListOf<String>()
        phases.flatMap { it.items }.forEach { item ->
            val prefix = "✏️ أثناء تنفيذ العينة: تم تعديل مادة (${item.rawMaterialName}) من "
            lines = lines.filterNot { it.startsWith(prefix) }
            if (executedItemIds.contains(item.id)) {
                val tg = targetGramsOf(item)
                val aw = actualWeights[item.id]
                val av = aw?.replace(',', '.')?.toDoubleOrNull()
                if (aw != null && av != null && kotlin.math.abs(av - tg) > 0.0005) {
                    val reason = editReasons[item.id]?.trim().orEmpty()
                    fresh += prefix + formatExactWeight(tg) + " جرام إلى " + aw + " جرام" + (if (reason.isNotBlank()) " — السبب: $reason" else "")
                }
            }
        }
        var out = lines.joinToString("\n").trimEnd()
        if (fresh.isNotEmpty()) out = (if (out.isBlank()) "" else out + "\n") + fresh.joinToString("\n")
        return out
    }

    fun buildReport(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val timestamp = sdf.format(java.util.Date())
        return buildString {
            append("\n\n--- 🧪 تقرير تشغيل وتجربة معملية تفصيلي ($timestamp) ---")
            append("\n• اسم المنتج/العينة: ${sample.sampleName}")
            append("\n• رقم العينة: ${sample.sampleNumber}")
            append("\n• الوزن التجريبي المستهدف: ${formatExactWeight(targetWeight)} كجم (الوزن الأصلي بالصيغة: ${formatExactWeight(totalOriginalWeightKgValue)} كجم)")
            append("\n\n📋 تفاصيل مراحل التشغيل والإضافات المباشرة:")
            phases.forEachIndexed { pIdx, p ->
                append("\n- المرحلة ${pIdx + 1}: ${p.name}")
                p.items.forEachIndexed { iIdx, item ->
                    val targetG = targetGramsOf(item)
                    val isItemExecuted = executedItemIds.contains(item.id)
                    val actW = actualWeights[item.id] ?: formatExactWeight(targetG)
                    val note = itemObservations[item.id]?.ifBlank { "لا يوجد ملاحظات" } ?: "لا يوجد ملاحظات"
                    val statusStr = if (isItemExecuted) "تم الإضافة ✔️" else "لم يتم الإضافة ❌"
                    append("\n   [${iIdx + 1}] ${item.rawMaterialName} -> الوزن المستهدف: ${formatExactWeight(targetG)} جرام | حالة الإضافة: $statusStr | الوزن الفعلي المضاف: $actW جرام | الملاحظة: $note")
                }
            }
            append("\n\n💻 سجل أحداث لوحة التشغيل:")
            logs.forEach { logLine -> append("\n  $logLine") }
            append("\n------------------------------------------------------------\n")
        }
    }

    // "حفظ التقدم": persists per-item state + stop point in recipeJson; notes only get the quantity-edit lines.
    // The long report is a separate optional action (withReport) available once everything is done; an older report is replaced, never duplicated.
    val doSaveEx: (Boolean) -> Unit = { withReport ->
        val now = System.currentTimeMillis()
        val updatedPhases = phases.map { phase ->
            phase.copy(items = phase.items.map { item ->
                val ex = executedItemIds.contains(item.id)
                item.copy(
                    isExecuted = ex,
                    actualWeight = if (ex) (actualWeights[item.id] ?: "") else "",
                    observation = if (ex) (itemObservations[item.id] ?: "") else "",
                    editReason = if (ex) (editReasons[item.id] ?: "") else "",
                    savedAt = now
                )
            })
        }
        var notesToSave = applyEditLines(sample.researchNotes)
        if (withReport && allDone) {
            notesToSave = notesToSave.replace(Regex("\\n\\n--- 🧪 تقرير تشغيل وتجربة معملية تفصيلي.*?-{20,}\\n?", RegexOption.DOT_MATCHES_ALL), "") + buildReport()
        }
        onExecutionCompleted(notesToSave, serializeRecipeJson(updatedPhases))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF8FAFC) // Sleek slate white
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isCompact = maxWidth < 650.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp)
                ) {
                    // Top Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.LightGray.copy(alpha = 0.2f))
                                    .size(if (isCompact) 34.dp else 40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "تراجع",
                                    tint = RndPurpleAccent,
                                    modifier = Modifier.size(if (isCompact) 18.dp else 24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🧪 تنفيذ وتشغيل العينة المخبرية",
                                    fontSize = if (isCompact) 14.sp else 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = RndDarkSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "تشغيل وتتبع عينة: ${sample.sampleName} (${sample.sampleNumber})",
                                    fontSize = if (isCompact) 10.sp else 11.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Done/Finish Button (Compiles and saves report + updates recipeJson state)
                        Button(
                            onClick = { doSaveEx(false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (allDone) RndSuccessGreen else Color(0xFF64748B)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = if (isCompact) 8.dp else 16.dp, vertical = if (isCompact) 4.dp else 8.dp),
                            modifier = if (isCompact) Modifier.height(34.dp) else Modifier
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check, 
                                contentDescription = null, 
                                tint = Color.White, 
                                modifier = Modifier.size(if (isCompact) 14.dp else 16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "حفظ التقدم 💾",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = if (isCompact) 10.sp else 11.sp
                            )
                        }
                    }

                    // saved stop point (from the last "save progress")
                    val savedAtMax = phases.flatMap { it.items }.maxOfOrNull { it.savedAt } ?: 0L
                    if (savedAtMax > 0L && initialExecutedIds.isNotEmpty()) {
                        val nextItem = phases.flatMap { it.items }.firstOrNull { !it.isExecuted }
                        val whenStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(savedAtMax))
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFFEF3C7), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (nextItem != null) "⏸ متوقف عند: ${nextItem.rawMaterialName} — محفوظ $whenStr" else "✔️ اكتملت كل المواد — آخر حفظ $whenStr",
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    if (allDone) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { doSaveEx(true) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📄 حفظ مع تقرير التنفيذ", fontWeight = FontWeight.Bold, color = RndPurpleAccent, fontSize = 12.sp) }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress Bar and Quick Stats Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, RndBorderLight)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "معدل تقدم إضافة ووزن المواد الخام:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RndDarkSlate
                                )
                                Text(
                                    text = "تمت إضافة $executedItemsCount من $totalItemsCount مواد (${(progress * 100).toInt()}%)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = RndPurpleAccent
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = RndPurpleAccent,
                                trackColor = Color(0xFFF1F5F9)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode Switcher Selector Bar (الخيار 1: قائمة جميع المواد | الخيار 2: عرض أحادي لوضع صالة الإنتاج)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFE2E8F0))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val modes = listOf("📋 عرض جميع المواد في قائمة", "عرض احادي")
                        modes.forEachIndexed { index, title ->
                            val selected = executionMode == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) RndPurpleAccent else Color.Transparent)
                                    .clickable { executionMode = index }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (selected) Color.White else RndDarkSlate,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = if (isCompact) 11.sp else 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Main View Content (Mode 0 vs Mode 1)
                    Box(modifier = Modifier.weight(1f)) {
                        if (executionMode == 0) {
                            // Mode 1: List View
                            MaterialsListSection(
                                phases = phases,
                                itemsList = itemsList,
                                targetWeight = targetWeight,
                                totalOriginalWeightKgValue = totalOriginalWeightKgValue,
                                executedItemIds = executedItemIds,
                                actualWeights = actualWeights,
                                itemObservations = itemObservations,
                                inputWeights = inputWeights,
                                inputObservations = inputObservations,
                                isCompact = isCompact,
                                onUpdateWeightInput = { itemId, value -> inputWeights = inputWeights + (itemId to value) },
                                onUpdateObservationInput = { itemId, value -> inputObservations = inputObservations + (itemId to value) },
                                onConfirmItem = { itemId, actW, obs0 ->
                                    var obs = obs0
                                    val item = phases.flatMap { it.items }.find { it.id == itemId }
                                    if (obs0.startsWith("معدل")) {
                                        val reason = obs0.substringAfter("— السبب:", "").trim()
                                        obs = "معدل"
                                        editReasons = if (reason.isBlank()) editReasons - itemId else editReasons + (itemId to reason)
                                        val before = actualWeights[itemId] ?: (item?.let { formatExactWeight(targetGramsOf(it)) } ?: "")
                                        addLog("✏️ تعديل كمية (${item?.rawMaterialName}): من $before إلى $actW جرام" + (if (reason.isNotBlank()) " — السبب: $reason" else ""))
                                    }
                                    executedItemIds = executedItemIds + itemId
                                    actualWeights = actualWeights + (itemId to actW)
                                    itemObservations = itemObservations + (itemId to obs)
                                    addLog("⚖️ تم وزن وإضافة (${item?.rawMaterialName}): الفعلي $actW جرام" + (if (obs.isNotBlank()) " [ملاحظة: $obs]" else ""))
                                },
                                onResetItem = { itemId ->
                                    executedItemIds = executedItemIds - itemId
                                    val item = phases.flatMap { it.items }.find { it.id == itemId }
                                    addLog("✏️ إعادة ضبط وتعديل مادة (${item?.rawMaterialName})")
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Mode 2: Single-Item Production Floor View
                            ProductionFloorSingleItemView(
                                phases = phases,
                                itemsList = itemsList,
                                targetWeight = targetWeight,
                                totalOriginalWeightKgValue = totalOriginalWeightKgValue,
                                executedItemIds = executedItemIds,
                                actualWeights = actualWeights,
                                itemObservations = itemObservations,
                                onConfirmItem = { itemId, actW, obs0 ->
                                    var obs = obs0
                                    val item = phases.flatMap { it.items }.find { it.id == itemId }
                                    if (obs0.startsWith("معدل")) {
                                        val reason = obs0.substringAfter("— السبب:", "").trim()
                                        obs = "معدل"
                                        editReasons = if (reason.isBlank()) editReasons - itemId else editReasons + (itemId to reason)
                                        val before = actualWeights[itemId] ?: (item?.let { formatExactWeight(targetGramsOf(it)) } ?: "")
                                        addLog("✏️ تعديل كمية (${item?.rawMaterialName}): من $before إلى $actW جرام" + (if (reason.isNotBlank()) " — السبب: $reason" else ""))
                                    }
                                    executedItemIds = executedItemIds + itemId
                                    actualWeights = actualWeights + (itemId to actW)
                                    itemObservations = itemObservations + (itemId to obs)
                                    addLog("⚖️ [صالة الإنتاج] تم إضافة (${item?.rawMaterialName}): الفعلي $actW جرام" + (if (obs.isNotBlank()) " [ملاحظة: $obs]" else ""))
                                },
                                onResetItem = { itemId ->
                                    executedItemIds = executedItemIds - itemId
                                    val item = phases.flatMap { it.items }.find { it.id == itemId }
                                    addLog("✏️ [صالة الإنتاج] إلغاء إضافة مادة (${item?.rawMaterialName})")
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhaseSelectorHeader(
    activePhase: DevRecipePhase?,
    phases: List<DevRecipePhase>,
    currentPhaseIndex: Int,
    onPhaseChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), // Elegant Light blue
        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "المرحلة الحالية قيد التشغيل:",
                fontSize = 11.sp,
                color = Color(0xFF1D4ED8),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activePhase?.name ?: "غير محدد",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF1E3A8A)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { onPhaseChanged(currentPhaseIndex - 1) },
                        enabled = currentPhaseIndex > 0,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1D4ED8)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("◀ السابقة", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onPhaseChanged(currentPhaseIndex + 1) },
                        enabled = currentPhaseIndex < phases.size - 1,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1D4ED8)
                        ),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text("التالية ▶", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MixerControlsCard(
    activePhase: DevRecipePhase?,
    timerSecondsLeft: Int,
    timerIsRunning: Boolean,
    rotationAngle: Float,
    onToggleTimer: () -> Unit,
    onResetTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, RndBorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🔄 جهاز خلط صالة الإنتاج المخبري (Simulated Mixer)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = RndDarkSlate,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Visual Mixer Icon (Rotating if mixer is running)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF8FAFC))
                    .border(2.dp, RndPurpleAccent.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = if (timerIsRunning) RndPurpleAccent else Color.Gray,
                    modifier = Modifier
                        .size(68.dp)
                        .graphicsLayer {
                            rotationZ = rotationAngle
                        }
                )
                // Simulated speed tag
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RndDarkSlate)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${activePhase?.mixerRpm ?: 0} RPM",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mixing Timer Display
            val minutes = timerSecondsLeft / 60
            val seconds = timerSecondsLeft % 60
            Text(
                text = String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = if (timerSecondsLeft == 0) RndSuccessGreen else RndDarkSlate
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Play, Pause, Reset Timer Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleTimer,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (timerIsRunning) RndWarningOrange else RndPurpleAccent)
                        .size(48.dp)
                ) {
                    Icon(
                        imageVector = if (timerIsRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (timerIsRunning) "إيقاف مؤقت" else "تشغيل",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = onResetTimer,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("إعادة ضبط 🔄", color = RndDarkSlate, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (timerSecondsLeft == 0 && activePhase != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🎉 تم إنهاء زمن خلط هذه المرحلة بنجاح!",
                    color = RndSuccessGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun InstructionsCard(
    activePhase: DevRecipePhase?,
    modifier: Modifier = Modifier,
    isScrollable: Boolean = true
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, RndBorderLight)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "📝 تعليمات التشغيل وخطوات التحضير:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = RndDarkSlate,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp)
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                    .border(1.dp, RndBorderLight, RoundedCornerShape(12.dp))
                    .padding(10.dp)
                    .then(
                        if (isScrollable) {
                            Modifier.verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        }
                    )
            ) {
                Text(
                    text = activePhase?.instructions?.ifBlank { "لم تحدد أي تعليمات إضافية لهذه المرحلة." } 
                        ?: "لا توجد مرحلة نشطة حالياً",
                    fontSize = 12.sp,
                    color = Color(0xFF475569),
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun LiveLogsTerminalCard(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // Deep black/slate
        border = BorderStroke(1.dp, Color(0xFF334155))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "💻 سجل أحداث تشغيل المعمل الفوري (Live Log):",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Glowing green indicator
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4ADE80))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val logScrollState = rememberScrollState()
                // Auto scroll logs to bottom
                LaunchedEffect(logs.size) {
                    logScrollState.animateScrollTo(logScrollState.maxValue)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(logScrollState)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    logs.forEach { logLine ->
                        Text(
                            text = logLine,
                            color = if (logLine.contains("✅") || logLine.contains("تم وزن") || logLine.contains("تمت إضافة")) Color(0xFF4ADE80) // Green
                                    else if (logLine.contains("🔄")) Color(0xFFFBBF24) // Yellow
                                    else Color(0xFFE2E8F0), // Slate grey
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Clear, bordered, purple-tinted pencil button (orange-filled when the quantity was edited). */
@Composable
fun RndEditQtyButton(modified: Boolean, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier.size(size).clip(shape)
            .background(if (modified) Color(0xFFF59E0B) else Color(0xFFEDE9FE))
            .border(1.dp, if (modified) Color(0xFFD97706) else RndPurpleAccent.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Edit, contentDescription = "تعديل الكمية",
            tint = if (modified) Color.White else RndPurpleAccent, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ProductionFloorSingleItemView(
    phases: List<DevRecipePhase>,
    itemsList: List<Map<String, Any>>,
    targetWeight: Double,
    totalOriginalWeightKgValue: Double,
    executedItemIds: Set<String>,
    actualWeights: Map<String, String>,
    itemObservations: Map<String, String>,
    onConfirmItem: (String, String, String) -> Unit,
    onResetItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allItems = remember(phases) {
        phases.flatMap { phase ->
            phase.items.map { item -> Triple(phase, item, itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }) }
        }
    }

    var currentItemIndex by remember { mutableStateOf(0) }
    var showConfirmModal by remember { mutableStateOf(false) }
    var showEditQtyDialog by remember { mutableStateOf(false) }

    // Auto land on first unexecuted item when opening
    LaunchedEffect(phases, executedItemIds) {
        val firstUnexecuted = allItems.indexOfFirst { (_, item, _) -> !executedItemIds.contains(item.id) }
        if (firstUnexecuted != -1 && currentItemIndex >= allItems.size) {
            currentItemIndex = firstUnexecuted
        }
    }

    if (allItems.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("لا توجد مواد مضافة للتركيبة بعد.", fontSize = 14.sp, color = Color.Gray)
        }
        return
    }

    val safeIndex = currentItemIndex.coerceIn(0, allItems.size - 1)
    val (currentPhase, currentItem, currentSItem) = allItems[safeIndex]

    val origW = currentSItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
    val stdW = origW * currentItem.ratio
    val targetG = if (totalOriginalWeightKgValue > 0) {
        (stdW / totalOriginalWeightKgValue) * targetWeight * 1000.0
    } else 0.0

    val isExecuted = executedItemIds.contains(currentItem.id)

    var inputActualWeight by remember(currentItem.id, actualWeights[currentItem.id]) {
        mutableStateOf(actualWeights[currentItem.id] ?: formatExactWeight(targetG))
    }
    var inputObservation by remember(currentItem.id, itemObservations[currentItem.id]) {
        mutableStateOf(itemObservations[currentItem.id] ?: "")
    }

    var editReasonInput by remember(currentItem.id) { mutableStateOf("") }
    var editQtyInput by remember(currentItem.id, actualWeights[currentItem.id]) {
        mutableStateOf(actualWeights[currentItem.id] ?: formatExactWeight(targetG))
    }

    if (showEditQtyDialog) {
        AlertDialog(
            onDismissRequest = { showEditQtyDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = { Text("تعديل كمية المادة ✏️", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RndDarkSlate) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editQtyInput,
                        onValueChange = { editQtyInput = it },
                        label = { Text("الكمية المعدلة (جرام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = editReasonInput,
                        onValueChange = { editReasonInput = it },
                        label = { Text("سبب التعديل (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        inputActualWeight = editQtyInput
                        onConfirmItem(currentItem.id, editQtyInput, "معدل" + (if (editReasonInput.isNotBlank()) " — السبب: ${editReasonInput.trim()}" else ""))
                        showEditQtyDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حفظ", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditQtyDialog = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, RndBorderLight)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Step Navigation Header & Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = RndPurpleAccent.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "المرحلة: ${currentPhase.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndPurpleAccent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Text(
                    text = "المادة ${safeIndex + 1} من ${allItems.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = RndDarkSlate
                )
            }

            // Step Progress Pills Row (Clickable)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(allItems) { idx, itemTriple ->
                    val itm = itemTriple.second
                    val executed = executedItemIds.contains(itm.id)
                    val isCurrent = idx == safeIndex
                    Box(
                        modifier = Modifier
                            .size(height = 10.dp, width = if (isCurrent) 28.dp else 12.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCurrent -> RndPurpleAccent
                                    executed -> Color(0xFF10B981)
                                    else -> Color(0xFFE2E8F0)
                                }
                            )
                            .clickable { currentItemIndex = idx }
                    )
                }
            }

            Divider(color = Color(0xFFF1F5F9))

            // HUGE Material Display Box (Full Production Floor Style)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isExecuted) Color(0xFFECFDF5) else Color(0xFFF8FAFC))
                    .border(
                        width = if (isExecuted) 2.dp else 1.dp,
                        color = if (isExecuted) Color(0xFF10B981) else Color(0xFFCBD5E1),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isExecuted) {
                        Surface(
                            color = Color(0xFF10B981),
                            shape = CircleShape
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "تمت إضافة المادة بنجاح ✔️",
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "🏷️ المادة المطلوب إضافتها الآن:",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // HUGE Material Name
                    Text(
                        text = currentItem.rawMaterialName,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isExecuted) Color(0xFF065F46) else Color(0xFF0F172A),
                        textAlign = TextAlign.Center
                    )

                    // Target Weight Display Card with Edit Icon
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (isExecuted) Color(0xFFD1FAE5) else Color(0xFFEEF2FF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            RndEditQtyButton(
                                modified = actualWeights[currentItem.id] != null && actualWeights[currentItem.id] != formatExactWeight(targetG),
                                size = 36.dp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            ) { editReasonInput = ""; showEditQtyDialog = true }

                            Column(
                                modifier = Modifier
                                    .padding(vertical = 12.dp, horizontal = 16.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "⚖️ :الوزن المستهدف المطلوب",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isExecuted) Color(0xFF047857) else RndPurpleAccent
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isExecuted) "${actualWeights[currentItem.id]} جرام" else "${formatExactWeight(targetG)} جرام",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isExecuted) Color(0xFF047857) else RndPurpleAccent
                                )
                                if (targetG >= 1000.0) {
                                    Text(
                                        text = "(${formatExactWeight(targetG / 1000.0)} كجم)",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    // Highlight for modified quantity
                    val isQtyModified = actualWeights[currentItem.id] != null && actualWeights[currentItem.id] != formatExactWeight(targetG)
                    if (isQtyModified) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "⚠️ كمية معدلة: ${actualWeights[currentItem.id]} جرام (الافتراضي: ${formatExactWeight(targetG)} جرام)",
                                fontSize = 11.sp,
                                color = Color(0xFFD97706),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // BIG ACTION BUTTON
            if (isExecuted) {
                OutlinedButton(
                    onClick = { onResetItem(currentItem.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                    border = BorderStroke(1.dp, Color(0xFFDC2626))
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إلغاء الإضافة 🔄", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            } else {
                Button(
                    onClick = { showConfirmModal = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("➕ تم إضافة المادة إلى الخلطة", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }

            // Step Navigation Footer (Previous / Next)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (safeIndex > 0) currentItemIndex = safeIndex - 1
                    },
                    enabled = safeIndex > 0,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("المادة السابقة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "${safeIndex + 1} / ${allItems.size}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )

                OutlinedButton(
                    onClick = {
                        if (safeIndex < allItems.size - 1) currentItemIndex = safeIndex + 1
                    },
                    enabled = safeIndex < allItems.size - 1,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("المادة التالية", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    // Confirmation Modal Dialog (نافذة تأكيد أضفت المادة)
    if (showConfirmModal) {
        AlertDialog(
            onDismissRequest = { showConfirmModal = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "تأكيد إضافة المادة إلى الخلطة ⚖️",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = RndDarkSlate
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "هل تؤكد إضافة المادة التالية في وعاء الخلط المخبري؟",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(
                                text = "🏷️ مادة: ${currentItem.rawMaterialName}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚖️ الوزن الفعلي المضاف: $inputActualWeight جرام",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                            if (inputObservation.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "📝 ملاحظة: $inputObservation",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmItem(currentItem.id, inputActualWeight, inputObservation)
                        showConfirmModal = false
                        // Auto-advance to next unexecuted item if available
                        val nextUnexecutedIndex = allItems.indexOfFirst { (p, itm, _) ->
                            itm.id != currentItem.id && !executedItemIds.contains(itm.id)
                        }
                        if (nextUnexecutedIndex != -1) {
                            currentItemIndex = nextUnexecutedIndex
                        } else if (safeIndex < allItems.size - 1) {
                            currentItemIndex = safeIndex + 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("تأكيد الإضافة ✅", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmModal = false }) {
                    Text("إلغاء ❌", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun MaterialsListSection(
    phases: List<DevRecipePhase>,
    itemsList: List<Map<String, Any>>,
    targetWeight: Double,
    totalOriginalWeightKgValue: Double,
    executedItemIds: Set<String>,
    actualWeights: Map<String, String>,
    itemObservations: Map<String, String>,
    inputWeights: Map<String, String>,
    inputObservations: Map<String, String>,
    isCompact: Boolean = false,
    onUpdateWeightInput: (String, String) -> Unit,
    onUpdateObservationInput: (String, String) -> Unit,
    onConfirmItem: (String, String, String) -> Unit,
    onResetItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val phaseItems = phases.flatMap { it.items }
    
    var showEditQtyDialogList by remember { mutableStateOf(false) }
    var editingItemId by remember { mutableStateOf("") }
    var editingItemName by remember { mutableStateOf("") }
    var editQtyInputList by remember { mutableStateOf("") }
    var editReasonInputList by remember { mutableStateOf("") }

    if (showEditQtyDialogList) {
        AlertDialog(
            onDismissRequest = { showEditQtyDialogList = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = { Text("تعديل كمية المادة ✏️", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RndDarkSlate) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("المادة: $editingItemName", fontSize = 13.sp, color = Color.Gray)
                    OutlinedTextField(
                        value = editQtyInputList,
                        onValueChange = { editQtyInputList = it },
                        label = { Text("الكمية المعدلة (جرام)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = editReasonInputList,
                        onValueChange = { editReasonInputList = it },
                        label = { Text("سبب التعديل (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmItem(editingItemId, editQtyInputList, "معدل" + (if (editReasonInputList.isNotBlank()) " — السبب: ${editReasonInputList.trim()}" else ""))
                        showEditQtyDialogList = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RndPurpleAccent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حفظ", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditQtyDialogList = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, RndBorderLight)
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxSize()) {
            Text(
                text = "⚖️ المواد المطلوب وزنها وإضافتها:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = RndDarkSlate,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (phaseItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("لا توجد مواد مضافة للتركيبة بعد.", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(phaseItems) { idx, item ->
                        val sItem = itemsList.find { (it["rawMaterialId"] as? String) == item.rawMaterialId }
                        val origW = sItem?.get("originalQuantityMultiplier") as? Double ?: 0.0
                        val stdW = origW * item.ratio
                        val targetG = if (totalOriginalWeightKgValue > 0) {
                            (stdW / totalOriginalWeightKgValue) * targetWeight * 1000.0
                        } else 0.0

                        val isExecuted = executedItemIds.contains(item.id)
                        val isQtyModified = isExecuted && actualWeights[item.id] != null && actualWeights[item.id] != formatExactWeight(targetG)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        isQtyModified -> Color(0xFFFEF3C7) // Light amber for modified
                                        isExecuted -> Color(0xFFDCFCE7) // Light green for executed
                                        else -> Color(0xFFF8FAFC)
                                    }
                                )
                                .border(
                                    width = if (isExecuted || isQtyModified) 1.2.dp else 1.dp,
                                    color = when {
                                        isQtyModified -> Color(0xFFF59E0B)
                                        isExecuted -> Color(0xFF10B981)
                                        else -> Color(0xFFCBD5E1)
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isExecuted) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "تم",
                                            tint = if (isQtyModified) Color(0xFFD97706) else Color(0xFF10B981),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isExecuted) "${actualWeights[item.id]} جرام" else "${formatExactWeight(targetG)} جرام",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Black,
                                        color = when {
                                            isQtyModified -> Color(0xFFD97706)
                                            isExecuted -> Color(0xFF10B981)
                                            else -> RndPurpleAccent
                                        }
                                    )

                                    RndEditQtyButton(modified = isQtyModified, size = 32.dp) {
                                        editingItemId = item.id
                                        editingItemName = item.rawMaterialName
                                        editQtyInputList = actualWeights[item.id] ?: formatExactWeight(targetG)
                                        editReasonInputList = ""
                                        showEditQtyDialogList = true
                                    }
                                }
                                Text(
                                    text = item.rawMaterialName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isQtyModified -> Color(0xFF92400E)
                                        isExecuted -> Color.Gray
                                        else -> RndDarkSlate
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                if (isQtyModified) {
                                    Text(
                                        text = "⚠️ كمية معدلة (الافتراضي: ${formatExactWeight(targetG)} جرام)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFD97706),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (isExecuted) {
                                        onResetItem(item.id)
                                    } else {
                                        val actualWVal = formatExactWeight(targetG)
                                        onConfirmItem(item.id, actualWVal, "")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isQtyModified -> Color(0xFFFDE68A)
                                        isExecuted -> Color(0xFFD1FAE5)
                                        else -> RndPurpleAccent
                                    }
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                val textBtn = if (isExecuted) "تم ✔️" else "تم التنفيذ"
                                val colorBtn = when {
                                    isQtyModified -> Color(0xFF92400E)
                                    isExecuted -> Color(0xFF047857)
                                    else -> Color.White
                                }
                                Text(textBtn, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colorBtn)
                            }
                        }
                    }
                }
            }
        }
    }
}


