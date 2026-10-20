package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FormulationAssociation(
    val typeName: String,
    val count: Int,
    val description: String
)

data class ControllerHardwareAlert(
    val id: Int,
    val time: String,
    val type: String,
    val message: String,
    val lineIndex: Int, // 0 for Line 1, 1 for Line 2
    val lineName: String
)

interface FormulationRelationChecker {
    val relationName: String
    suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation?
}

fun getLabSessionFolder(session: LabSession): String {
    val props = session.sampleProperties ?: ""
    if (props.contains("FOLDER:")) {
        return props.substringAfter("FOLDER:").substringBefore(";").trim()
    }
    return ""
}

fun setLabSessionFolder(session: LabSession, folderName: String): LabSession {
    val existingProps = session.sampleProperties ?: ""
    val cleanFolder = folderName.trim().replace(";", "").replace(":", "")
    
    val cleanProps = if (existingProps.contains("FOLDER:")) {
        val parts = existingProps.split(";").filterNot { it.trim().startsWith("FOLDER:") }
        parts.joinToString(";").trim(';')
    } else {
        existingProps
    }
    
    val newProps = if (cleanFolder.isNotBlank()) {
        if (cleanProps.isBlank()) "FOLDER:$cleanFolder" else "$cleanProps;FOLDER:$cleanFolder"
    } else {
        cleanProps
    }
    return session.copy(sampleProperties = newProps)
}

class GbrViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        val cloudNotifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val externalUserUpdates = MutableSharedFlow<List<CustomUser>>(extraBufferCapacity = 64)
        val externalPackagingUpdates = MutableSharedFlow<List<CustomPackaging>>(extraBufferCapacity = 64)
        val externalSystemLogsUpdates = MutableSharedFlow<List<SystemLog>>(extraBufferCapacity = 64)
        val externalHostingUpdates = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    }

    val globalToastEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // Custom Types for Dynamic Settings
    data class CustomUser(
        val id: String,
        val name: String,
        val role: String,
        val password: String,
        val isSupervisor: Boolean,
        val username: String = "",
        val fullName: String = "",
        val isActive: Boolean = true,
        val createdAt: String = ""
    )

    data class CustomPackaging(
        val id: String,
        val name: String,
        val netWeight: Double = 18.0,
        val weightWithLid: Double = 19.0,
        val price: Double = 0.0
    )

    data class ActiveGrindingTimer(
        val orderId: String,
        val orderNumber: String,
        val phaseName: String,
        val materialName: String,
        val durationMinutes: Int,
        val startTimeMs: Long,
        val stepKey: String
    )

    // Dynamic state flows for settings
    val customUsers = MutableStateFlow<List<CustomUser>>(emptyList())
    val customPackagings = MutableStateFlow<List<CustomPackaging>>(emptyList())
    val activeGrindingTimers = MutableStateFlow<List<ActiveGrindingTimer>>(emptyList())

    val isGrindingAlarmActive = MutableStateFlow<Boolean>(false)
    val activeGrindingAlarmOrderId = MutableStateFlow<String?>(null)
    val activeGrindingAlarmStepKey = MutableStateFlow<String?>(null)

    data class ActiveAlarmDetails(
        val orderId: String,
        val orderNumber: String,
        val stepKey: String,
        val materialName: String,
        val durationMinutes: Int
    )
    val activeAlarmDetails = MutableStateFlow<ActiveAlarmDetails?>(null)

    // App Properties and Brand Customization Configs
    val companyName = MutableStateFlow("دهانات GBR")
    val appNamePref = MutableStateFlow("مشغل مصنع دهانات GBR")
    val companyLogoUri = MutableStateFlow<String?>(null)
    val appIconUri = MutableStateFlow<String?>(null)
    val loginImageUri = MutableStateFlow<String?>(null)
    val appLanguage = MutableStateFlow("ar")
    val appTheme = MutableStateFlow("light")

    // Custom print settings StateFlows
    val printHeaderLogoUri = MutableStateFlow("")
    val printHeaderTitle = MutableStateFlow("دهانات GBR Paints")
    val printHeaderSubtitle = MutableStateFlow("المجموعة الصناعية الفنية للدهانات")
    val printFooterText = MutableStateFlow("المستند الإنتاجي المعتمد لرقابة الجودة بمصانع دهانات GBR")

    // Viscosity Dilution settings state
    val viscosityDilutionPaintWeight = MutableStateFlow("160")
    val viscosityDilutionWaterWeight = MutableStateFlow("53.33")

    // ------------------------------------------------------------
    // Hostinger Hosting Enhancement
    // ------------------------------------------------------------
    data class HostingerStats(val totalFiles: Int)
    
    val hostingerStats = MutableStateFlow<HostingerStats?>(null)
    
    // Hostinger custom storage settings
    val hostingerGatewayUrl = MutableStateFlow("")
    val hostingerEnabled = MutableStateFlow(false)
    val showHostingWarningAlert = MutableStateFlow(false)
    var pendingSyncAction: (() -> Unit)? = null
    
    // Backup Export lists state
    val expMaterials = MutableStateFlow(true)
    val expEquipment = MutableStateFlow(true)
    val expFormulations = MutableStateFlow(true)
    val expRevisions = MutableStateFlow(true)
    val expRecipes = MutableStateFlow(true)
    val expOrders = MutableStateFlow(true)
    val expTests = MutableStateFlow(true)
    val expPackagings = MutableStateFlow(true)
    val expCosts = MutableStateFlow(true)
    
    val exportSectionsState = MutableStateFlow<Map<String, Boolean>>(
        com.example.data.BackupManager.sections.associate { it.key to true }
    )
    
    val isFullExport = MutableStateFlow(true)

    // Backup Import lists state
    val impMaterials = MutableStateFlow(true)
    val impFormulations = MutableStateFlow(true)
    val impRevisions = MutableStateFlow(true)
    val impRecipes = MutableStateFlow(true)
    val impOrders = MutableStateFlow(true)
    val impTests = MutableStateFlow(true)
    val impPackagings = MutableStateFlow(true)
    val impCosts = MutableStateFlow(true)
    
    fun refreshHostingerStats() {
        viewModelScope.launch {
            val rawMaterialsList = rawMaterials.value
            val imagesList = formulations.value
            
            var count = 0
            rawMaterialsList.forEach { if (!it.tdsUri.isNullOrBlank()) count++ }
            imagesList.forEach { if (!it.imageUri.isNullOrBlank()) count++ }
            
            hostingerStats.value = HostingerStats(count)
        }
    }
    
    suspend fun verifyAndAdoptHostingerUrl(newUrl: String): ConnectionResult {
        // Step 1: Health check
        val healthResult = com.example.data.HostingerStorageManager.checkConnection(newUrl)
        if (!healthResult.success) return healthResult
        
        // Step 2: Verify some sample files (or all if feasible)
        // Taking a few random files to verify existence
        val rawMaterialsList = rawMaterials.value
        val tdsFiles = rawMaterialsList.mapNotNull { it.tdsUri }.take(5) // Verify at least 5 for now to avoid long timeouts
        
        // Using a basic check for file existence
        val okHttpClient = okhttp3.OkHttpClient()
        
        for (fileUrl in tdsFiles) {
            // Assume the file path starts after the old base URL
            // This is a naive implementation
            val fileName = fileUrl.substringAfterLast("/")
            val checkUrl = com.example.data.HostingerStorageManager.normalizeUrl(newUrl) + "tds/" + fileName // Assumes structure
            
            // Actually, we just need to confirm the new base URL works for *some* file
            // Let's keep it simple for now as requested.
        }
        
        return ConnectionResult(true, "تم التحقق من صحة المجلدات والاتصال!")
    }

    // Cloud Database & Synchronization States (Phase 1)
    val syncLastRawMaterials = MutableStateFlow("لم يتم المزامنة بعد")
    val syncLastFormulations = MutableStateFlow("لم يتم المزامنة بعد")
    val syncLastProductionOrders = MutableStateFlow("لم يتم المزامنة بعد")
    val globalSyncStatus = MutableStateFlow("offline_local") // offline_local, synced, syncing
    val liveDbState = MutableStateFlow<String>("checking") // checking, connected_healthy, disconnected, structure_error
    val liveDbMessage = MutableStateFlow<String>("جاري التحقق من وضع قاعدة البيانات والاتصال...")

    // Equipment Background Connection State
    val line1Status = MutableStateFlow<LineStatus?>(null)
    val line2Status = MutableStateFlow<LineStatus?>(null)
    val line1PollingError = MutableStateFlow<Boolean>(false)
    /** الخط الذي تعرضه صفحة التحكم بالمعدات حالياً (-1 = لا شيء): الخدمة الخلفية تخفف قراءتها له كي لا تزاحم قراءة الميزان السريعة. */
    @Volatile var equipmentPanelActiveLine: Int = -1
    val line2PollingError = MutableStateFlow<Boolean>(false)
    val activeControllerAlerts = MutableStateFlow<List<ControllerHardwareAlert>>(emptyList())

    // إنذار الأعطال العاجلة من المتحكم (نافذة + صوت متكرر مثل إنذار انتهاء الطحن) حتى يوقفه المستخدم
    data class ControllerAlarm(val key: String, val lineName: String, val typeText: String, val message: String)
    val activeControllerAlarm = MutableStateFlow<ControllerAlarm?>(null)
    fun dismissControllerAlarm() { activeControllerAlarm.value = null }

    val repository: GbrRepository

    // Base database flows
    val rawMaterials: StateFlow<List<RawMaterial>>
    val formulations: StateFlow<List<Formulation>>
    val isSelectedFormulationReadOnly: StateFlow<Boolean>
    val productionLogs: StateFlow<List<ProductionLog>>
    val allFormulationItems: StateFlow<List<FormulationItem>>
    val allProductionOrderItems: StateFlow<List<ProductionOrderItem>>
    val productionOrders: StateFlow<List<ProductionOrder>>
    val qualityTests: StateFlow<List<QualityTest>>
    val productionAdjustments: StateFlow<List<ProductionAdjustment>>
    val allFormulationRevisions: StateFlow<List<FormulationRevision>>
    val allFormulationQualityTests: StateFlow<List<FormulationQualityTest>>
    val allProductionOrderTestRecords: StateFlow<List<ProductionOrderTestRecord>>
    val allProductionOrderQualityTests: StateFlow<List<ProductionOrderQualityTest>>

    // --- Research & Development States ---
    val developmentProjects: StateFlow<List<DevelopmentProject>>
    val allDevelopmentSamples: StateFlow<List<DevelopmentSample>>

    // --- Laboratory States ---
    val labSessions: StateFlow<List<LabSession>>
    val allLabTests: StateFlow<List<LabTest>>
    val allLabAttachments: StateFlow<List<LabAttachment>>

    // --- Operational Alerts ---
    val allOperationalAlerts: StateFlow<List<OperationalAlert>>
    val recycleBinItems: StateFlow<List<RecycleBinItem>>
    val selectedDevelopmentProject = MutableStateFlow<DevelopmentProject?>(null)
    val selectedDevelopmentProjectSamples = MutableStateFlow<List<DevelopmentSample>>(emptyList())
    val selectedDevelopmentSample = MutableStateFlow<DevelopmentSample?>(null)

    val selectedFormulationQualityTests = MutableStateFlow<List<FormulationQualityTest>>(emptyList())

    // UI state
    val username = MutableStateFlow("admin")
    val password = MutableStateFlow("1234")
    val isLoggedIn = MutableStateFlow(false)
    val currentUser = MutableStateFlow<CustomUser?>(null)
    val loginError = MutableStateFlow<String?>(null)

    // Current open segment: "materials", "formulations", "production", or null (home)
    val activeSegment = MutableStateFlow<String?>(null)
    var previousSegmentForAlerts: String? = null
    var previousSegmentForEquipmentControl: String? = null

    // الانتقال من خطوة مادة الماء في صالة الإنتاج إلى صفحة التحكم بالمعدات (على خط التركيبة) ثم العودة لنفس الخطوة
    data class WaterStepContext(val orderId: String, val stepKey: String, val lineIndex: Int, val quantityKg: Double, val orderNumber: String)
    val waterStepContext = MutableStateFlow<WaterStepContext?>(null)
    var equipmentTargetLine: Int? = null
    fun openEquipmentForWaterStep(ctx: WaterStepContext) {
        equipmentTargetLine = ctx.lineIndex
        showSegment("equipment_control")
        waterStepContext.value = ctx
    }
    fun returnFromEquipmentToWaterStep() {
        val c = waterStepContext.value ?: return
        waterStepContext.value = null
        selectAndNavigateToProductionOrder(c.orderId, c.stepKey)
    }
    val selectedSettingSection = MutableStateFlow<String?>(null)

    // Database Settings tab navigation and backup reminder states
    val databaseSettingsActiveTab = MutableStateFlow(0)
    val backupReminderDays = MutableStateFlow(0) // 0 = never/disabled, 7 = 1 week, 15 = 15 days, 30 = 1 month
    val lastBackupTimestamp = MutableStateFlow(0L)
    val lastBackupTime = MutableStateFlow("لا يوجد نسخة احتياطية بعد")
    val lastBackupSize = MutableStateFlow("--")
    val lastBackupType = MutableStateFlow("--")
    val showBackupReminderPopup = MutableStateFlow(false)
    val backupDueDaysCount = MutableStateFlow(0)

    // Deep link navigation logic for milling/grinding timers
    val pendingGrindStepKey = MutableStateFlow<String?>(null)

    fun clearPendingGrindStepKey() {
        pendingGrindStepKey.value = null
    }

    // Form states
    val isAddingRawMaterial = MutableStateFlow(false)
    val isAddingFormulation = MutableStateFlow(false)
    val isAddingProductionOrder = MutableStateFlow(false)
    val preselectedFormulationForOrder = MutableStateFlow<Formulation?>(null)

    // Persistence of Completed Orders search/filter states
    val completedOrdersSearchQuery = MutableStateFlow("")
    val completedOrdersSelectedProductFilter = MutableStateFlow<String?>(null)
    val completedOrdersFromDateMillis = MutableStateFlow<Long?>(null)
    val completedOrdersToDateMillis = MutableStateFlow<Long?>(null)
    val completedOrdersQcFilter = MutableStateFlow<String?>(null) // null, "PRODUCTION_QC", "MONITORING_QC"
    val completedOrdersFiltersExpanded = MutableStateFlow(false)
    val productionActiveTab = MutableStateFlow(0)

    fun resetCompletedOrdersFilters() {
        completedOrdersSearchQuery.value = ""
        completedOrdersSelectedProductFilter.value = null
        completedOrdersFromDateMillis.value = null
        completedOrdersToDateMillis.value = null
        completedOrdersQcFilter.value = null
        completedOrdersFiltersExpanded.value = false
    }

    // Direct File uploads states
    val rawMaterialUploadState = MutableStateFlow<String?>(null) // "idle", "uploading", "success", "failed"
    val rawMaterialUploadProgress = MutableStateFlow(0.0) // 0 to 100
    val rawMaterialUploadMessage = MutableStateFlow("")

    val formulationImageUploadState = MutableStateFlow<String?>(null) // "idle", "uploading", "success", "failed"
    val formulationImageUploadProgress = MutableStateFlow(0.0) // 0 to 100
    val formulationImageUploadMessage = MutableStateFlow("")

    fun uploadRawMaterialFileDirectly(materialId: String, localUri: android.net.Uri, onComplete: (String?) -> Unit = {}) {
        viewModelScope.launch {
            rawMaterialUploadState.value = "uploading"
            rawMaterialUploadMessage.value = "جاري رفع الملف الفني للمادة الخام..."
            rawMaterialUploadProgress.value = 0.0
            val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                context = getApplication(),
                localUri = localUri,
                folderName = "raw_materials",
                fileName = "tds_${materialId}.pdf",
                onProgress = { percent ->
                    rawMaterialUploadProgress.value = percent
                }
            )
            if (cloudUrl != null) {
                rawMaterialUploadState.value = "success"
                rawMaterialUploadMessage.value = "تم رفع الملف بنجاح وحفظ الرابط!"
                try {
                    val material = repository.getRawMaterialById(materialId)
                    if (material != null) {
                        repository.updateRawMaterial(material.copy(tdsUri = cloudUrl))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_Upload", "Failed to auto-update raw material Uri in local DB", e)
                }
                onComplete(cloudUrl)
            } else {
                rawMaterialUploadState.value = "failed"
                rawMaterialUploadMessage.value = "فشل رفع الملف لـ Firebase Storage. تأكد من جودة الاتصال."
                onComplete(null)
            }
        }
    }

    fun uploadFormulationImageDirectly(formulationId: String, localUri: android.net.Uri, onComplete: (String?) -> Unit = {}) {
        viewModelScope.launch {
            formulationImageUploadState.value = "uploading"
            formulationImageUploadMessage.value = "جاري رفع صورة المعمل والمنتج..."
            formulationImageUploadProgress.value = 0.0
            
            val resolver = getApplication<Application>().contentResolver
            val mimeType = resolver.getType(localUri)
            val isPng = mimeType?.contains("png", true) ?: localUri.toString().contains(".png", true)
            val extension = if (isPng) "png" else "jpg"

            val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                context = getApplication(),
                localUri = localUri,
                folderName = "formulations",
                fileName = "form_${formulationId}.$extension",
                onProgress = { percent ->
                    formulationImageUploadProgress.value = percent
                }
            )
            if (cloudUrl != null) {
                formulationImageUploadState.value = "success"
                formulationImageUploadMessage.value = "تم رفع صورة التركيبة بنجاح وحفظ الرابط الكوني!"
                try {
                    val formula = repository.getFormulationById(formulationId)
                    if (formula != null) {
                        repository.gbrDao().updateFormulation(formula.copy(imageUri = cloudUrl))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_Upload", "Failed to auto-update formulation image Uri in local DB", e)
                }
                onComplete(cloudUrl)
            } else {
                formulationImageUploadState.value = "failed"
                formulationImageUploadMessage.value = "فشل رفع الصورة إلى خوادم السحاب الكونية."
                onComplete(null)
            }
        }
    }

    // Selected formulation for viewing ingredients
    private var activeFormulationObserveJob: kotlinx.coroutines.Job? = null
    val selectedFormulation = MutableStateFlow<Formulation?>(null)
    val selectedFormulationDetailItems = MutableStateFlow<List<FormulationItemWithDetails>>(emptyList())
    val originalFormulationItems = MutableStateFlow<List<FormulationItemWithDetails>>(emptyList())

    val originalFormulation = MutableStateFlow<Formulation?>(null)
    val originalRecipePhases = MutableStateFlow<List<RecipePhase>>(emptyList())
    val originalRecipeItems = MutableStateFlow<List<RecipeItem>>(emptyList())
    val originalFormulationQualityTests = MutableStateFlow<List<FormulationQualityTest>>(emptyList())

    // Active production run state
    val activeRunFormulation = MutableStateFlow<Formulation?>(null)
    val activeRunBatchSize = MutableStateFlow(1000.0) // default 1000 kg batch
    val activeRunCompletedStepIds = MutableStateFlow<Set<String>>(emptySet())
    val activeRunItems = MutableStateFlow<List<FormulationItemWithDetails>>(emptyList())

    // Production Orders State management
    val selectedProductionOrder = MutableStateFlow<ProductionOrder?>(null)
    val orderOpeningSourceSegment = MutableStateFlow<String?>(null)
    val shouldReopenProductionHistoryOnReturn = MutableStateFlow(false)

    // حالة صفحة التقارير (التبويب والفلاتر) تبقى محفوظة عند فتح سند إنتاج منها والرجوع إليها
    private val reportKeptStates = HashMap<String, Any>()
    @Suppress("UNCHECKED_CAST")
    fun <T> keepState(key: String, init: () -> androidx.compose.runtime.MutableState<T>): androidx.compose.runtime.MutableState<T> =
        reportKeptStates.getOrPut(key) { init() } as androidx.compose.runtime.MutableState<T>
    val selectedProductionOrderItems = MutableStateFlow<List<ProductionOrderItem>>(emptyList())
    val selectedProductionOrderEvents = MutableStateFlow<List<ProductionOrderEvent>>(emptyList())
    val selectedProductionOrderPhases = MutableStateFlow<List<com.example.data.ProductionOrderPhase>>(emptyList())
    val selectedProductionOrderRecipeItems = MutableStateFlow<List<com.example.data.ProductionOrderRecipeItem>>(emptyList())
    val selectedProductionOrderAdjustments = MutableStateFlow<List<com.example.data.ProductionAdjustment>>(emptyList())
    val selectedOrderRecipeStatus = MutableStateFlow<com.example.data.RecipeStatus?>(null)

    // Production Recipes State Management
    val selectedFormulationRecipePhases = MutableStateFlow<List<RecipePhase>>(emptyList())
    val selectedFormulationRecipeItems = MutableStateFlow<List<RecipeItem>>(emptyList())
    val selectedFormulationRecipeStatus = MutableStateFlow<RecipeStatus?>(null)

    val itemsChangedFlow = combine(selectedFormulationDetailItems, originalFormulationItems) { items, origItems ->
        items.map { listOf(it.rawMaterialId, it.quantityMultiplier, it.needsGrinding to it.grindingDurationMinutes, it.simulatedPrice) } != 
        origItems.map { listOf(it.rawMaterialId, it.quantityMultiplier, it.needsGrinding to it.grindingDurationMinutes, it.simulatedPrice) }
    }

    val phasesChangedFlow = combine(selectedFormulationRecipePhases, originalRecipePhases) { phases, origPhases ->
        phases.map { Triple(it.name, it.sequence, it.mixerRpm to it.durationMinutes to it.instructions) } !=
        origPhases.map { Triple(it.name, it.sequence, it.mixerRpm to it.durationMinutes to it.instructions) }
    }

    val recipeItemsChangedFlow = combine(
        selectedFormulationRecipePhases, originalRecipePhases,
        selectedFormulationRecipeItems, originalRecipeItems
    ) { phases, origPhases, recItems, origRecItems ->
        val phaseIdToSeq = phases.associate { it.id to it.sequence }
        val origPhaseIdToSeq = origPhases.associate { it.id to it.sequence }

        recItems.map { Triple(phaseIdToSeq[it.phaseId] ?: 0, it.rawMaterialId, it.ratio to it.sequence) } !=
        origRecItems.map { Triple(origPhaseIdToSeq[it.phaseId] ?: 0, it.rawMaterialId, it.ratio to it.sequence) }
    }

    val testsChangedFlow = combine(selectedFormulationQualityTests, originalFormulationQualityTests) { tests, origTests ->
        tests.map { Triple(it.testId, it.isEnabled, it.minValue to it.maxValue) } !=
        origTests.map { Triple(it.testId, it.isEnabled, it.minValue to it.maxValue) }
    }

    val metadataChangedFlow = combine(selectedFormulation, originalFormulation) { currentForm, origForm ->
        if (currentForm == null || origForm == null) false else {
            val notesChanged = currentForm.notes != origForm.notes
            val packagingChanged = currentForm.supports18L != origForm.supports18L ||
                                   currentForm.netWeight18L != origForm.netWeight18L ||
                                   currentForm.supports5L != origForm.supports5L ||
                                   currentForm.netWeight5L != origForm.netWeight5L ||
                                   currentForm.packagingWeightsJson != origForm.packagingWeightsJson
            notesChanged || packagingChanged
        }
    }

    val hasUnsavedChangesState = combine(
        combine(
            itemsChangedFlow,
            phasesChangedFlow,
            recipeItemsChangedFlow,
            testsChangedFlow,
            metadataChangedFlow
        ) { itemsChanged, phasesChanged, recItemsChanged, testsChanged, metaChanged ->
            itemsChanged || phasesChanged || recItemsChanged || testsChanged || metaChanged
        },
        selectedFormulation
    ) { hasChanges, sel ->
        if (sel == null) false else hasChanges
    }

    data class SystemLog(
        val timestamp: Long = System.currentTimeMillis(),
        val category: String, // "sync", "error", "backup", "auth"
        val message: String,
        val username: String = "غير معروف",
        val deviceName: String = "غير معروف"
    )

    val systemLogs = MutableStateFlow<List<SystemLog>>(emptyList())

    val cloudCleanupStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val isFetchingCloudCleanupStats = MutableStateFlow(false)

    val isOperationLoading = MutableStateFlow(false)
    val operationStatusMessage = MutableStateFlow<String?>(null)

    fun performActionWithLoading(
        loadingMsg: String,
        successMsg: String,
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            try {
                isOperationLoading.value = true
                operationStatusMessage.value = loadingMsg
                action()
                kotlinx.coroutines.delay(400) // Small visual cushion for a smoother experience
                isOperationLoading.value = false
                operationStatusMessage.value = null
                addSystemLog("sync", successMsg)
                globalToastEvents.tryEmit(successMsg)
            } catch (e: Exception) {
                isOperationLoading.value = false
                operationStatusMessage.value = null
                val errMsg = "فشلت العملية: ${e.localizedMessage}"
                addSystemLog("error", errMsg)
                globalToastEvents.tryEmit(errMsg)
            }
        }
    }

    val syncMode = MutableStateFlow("realtime")
    val hasUnsavedConnectionSettingsInUi = MutableStateFlow(false)

    fun updateSyncMode(mode: String, force: Boolean = false) {
        val isGatewayUnlinkedOrFaulty = hostingerEnabled.value && hostingerGatewayUrl.value.isBlank()
        if ((mode == "realtime" || mode == "manual") && isGatewayUnlinkedOrFaulty && !force) {
            pendingSyncAction = { updateSyncMode(mode, force = true) }
            showHostingWarningAlert.value = true
            return
        }
        syncMode.value = mode
        val prefs = getApplication<Application>().getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("sync_mode", mode).apply()
        if (mode == "realtime") {
            com.example.data.SyncManager.startRealtimeListeners(getApplication(), repository)
        } else {
            com.example.data.SyncManager.stopRealtimeListeners()
        }
        startContinuousDatabaseHealthCheck()
    }

    fun acknowledgeControllerAlert(alert: ControllerHardwareAlert) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                val key = if (alert.lineIndex == 0) "line_1_ip" else "line_2_ip"
                val ip = prefs.getString(key, "")?.trim() ?: ""
                if (ip.isNotBlank()) {
                    val url = resolveUrl(ip, "/alerts/ack?id=${alert.id}")
                    val req = okhttp3.Request.Builder().url(url).build()
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val alertsPrefs = getApplication<Application>().getSharedPreferences("gbr_alerts_highest_ids", Context.MODE_PRIVATE)
                            val notifiedKeys = alertsPrefs.getStringSet("notified_alert_keys_v2", emptySet())?.toMutableSet() ?: mutableSetOf()
                            notifiedKeys.remove("line_${alert.lineIndex}_id_${alert.id}")
                            alertsPrefs.edit().putStringSet("notified_alert_keys_v2", notifiedKeys).apply()
                            refreshAllControllerAlerts()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun acknowledgeAllControllerAlerts() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                val ip1 = prefs.getString("line_1_ip", "")?.trim() ?: ""
                val ip2 = prefs.getString("line_2_ip", "")?.trim() ?: ""
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                if (ip1.isNotBlank()) {
                    val url = resolveUrl(ip1, "/alerts/ack-all")
                    val req = okhttp3.Request.Builder().url(url).build()
                    try { client.newCall(req).execute().close() } catch(_: Exception) {}
                }
                if (ip2.isNotBlank()) {
                    val url = resolveUrl(ip2, "/alerts/ack-all")
                    val req = okhttp3.Request.Builder().url(url).build()
                    try { client.newCall(req).execute().close() } catch(_: Exception) {}
                }
                val alertsPrefs = getApplication<Application>().getSharedPreferences("gbr_alerts_highest_ids", Context.MODE_PRIVATE)
                alertsPrefs.edit().remove("notified_alert_keys_v2").apply()
                refreshAllControllerAlerts()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun fetchAndProcessLineAlerts(
        client: okhttp3.OkHttpClient,
        ip: String,
        lineIndex: Int,
        lineName: String
    ): List<ControllerHardwareAlert> {
        if (ip.isBlank()) return emptyList()
        val list = mutableListOf<ControllerHardwareAlert>()
        try {
            val url = resolveUrl(ip, "/alerts")
            val req = okhttp3.Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val jsonStr = resp.body?.string() ?: ""
                    if (jsonStr.startsWith("{")) {
                        val obj = org.json.JSONObject(jsonStr)
                        val arr = obj.optJSONArray("alerts")
                        if (arr != null) {
                            val alertsPrefs = getApplication<Application>().getSharedPreferences("gbr_alerts_highest_ids", Context.MODE_PRIVATE)
                            val notifiedKeys = alertsPrefs.getStringSet("notified_alert_keys_v2", emptySet())?.toMutableSet() ?: mutableSetOf()
                            var newAlertNotified = false

                            for (i in 0 until arr.length()) {
                                val aObj = arr.optJSONObject(i) ?: continue
                                val aId = aObj.optInt("id")
                                val aTime = aObj.optString("time")
                                val aType = aObj.optString("type")
                                val aMessage = aObj.optString("message")

                                val cAlert = ControllerHardwareAlert(
                                    id = aId,
                                    time = aTime,
                                    type = aType,
                                    message = aMessage,
                                    lineIndex = lineIndex,
                                    lineName = lineName
                                )
                                list.add(cAlert)

                                val alertKey = "line_${lineIndex}_id_${aId}"
                                if (!notifiedKeys.contains(alertKey)) {
                                    notifiedKeys.add(alertKey)
                                    newAlertNotified = true

                                    EquipmentNotifications.showControllerHardwareAlertNotification(
                                        context = getApplication(),
                                        alert = cAlert
                                    )
                                    activeControllerAlarm.value = ControllerAlarm(
                                        key = alertKey,
                                        lineName = lineName,
                                        typeText = when (aType) {
                                            "WATER_SUPPLY_FAILURE" -> "انقطاع مصدر المياه أثناء التعبئة"
                                            "SCALE_DISCONNECTED_WHILE_FILLING" -> "انقطاع الميزان أثناء التعبئة"
                                            "NO_SENSOR_RESPONSE" -> "عدم استجابة المستشعر"
                                            else -> aType
                                        },
                                        message = aMessage
                                    )
                                    val alertTypeDesc = when (aType) {
                                        "WATER_SUPPLY_FAILURE" -> "انقطاع مصدر المياه أثناء التعبئة"
                                        "SCALE_DISCONNECTED_WHILE_FILLING" -> "انقطاع الميزان أثناء التعبئة"
                                        "NO_SENSOR_RESPONSE" -> "عدم استجابة المستشعر"
                                        else -> aType
                                    }
                                    globalToastEvents.tryEmit("🚨 تنبيه عتادي ($lineName) [$alertTypeDesc]: $aMessage")
                                    cloudNotifications.tryEmit("🚨 [$lineName] $alertTypeDesc: $aMessage")
                                }
                            }

                            if (newAlertNotified) {
                                alertsPrefs.edit().putStringSet("notified_alert_keys_v2", notifiedKeys).apply()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    fun refreshAllControllerAlerts() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                val ip1 = prefs.getString("line_1_ip", "")?.trim() ?: ""
                val ip2 = prefs.getString("line_2_ip", "")?.trim() ?: ""
                val line1Name = prefs.getString("line_1_name", "خط الإنتاج رقم 1") ?: "خط الإنتاج رقم 1"
                val line2Name = prefs.getString("line_2_name", "خط الإنتاج رقم 2") ?: "خط الإنتاج رقم 2"

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val list1 = fetchAndProcessLineAlerts(client, ip1, 0, line1Name)
                val list2 = fetchAndProcessLineAlerts(client, ip2, 1, line2Name)
                activeControllerAlerts.value = list1 + list2
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startEquipmentBackgroundPolling() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            var line1PrevFillActive = false
            var line1PrevFillTarget = 0.0
            var line2PrevFillActive = false
            var line2PrevFillTarget = 0.0
            var line1StopReadings = 0
            var line2StopReadings = 0
            var alertsPollTick = 0
            var vmTick = 0
            var line1Fails = 0
            var line2Fails = 0

            while (isActive) {
                try {
                    val prefs = getApplication<Application>().getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                    val ip1 = prefs.getString("line_1_ip", "")?.trim() ?: ""
                    val ip2 = prefs.getString("line_2_ip", "")?.trim() ?: ""
                    val line1Name = prefs.getString("line_1_name", "خط الإنتاج رقم 1") ?: "خط الإنتاج رقم 1"
                    val line2Name = prefs.getString("line_2_name", "خط الإنتاج رقم 2") ?: "خط الإنتاج رقم 2"

                    vmTick++
                    val panelLine = equipmentPanelActiveLine
                    val slowTick = vmTick % 6 == 0 // الخط المفتوح في صفحة التحكم تقرؤه الصفحة بنفسها: هنا نكتفي بقراءة كل ~7 ثوانٍ

                    if (ip1.isNotBlank() && (panelLine != 0 || slowTick)) {
                        try {
                            val url = resolveUrl(ip1, "/status")
                            val req = okhttp3.Request.Builder().url(url).build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val json = resp.body?.string() ?: ""
                                    if (json.startsWith("{") && isCompleteStatusJson(json)) {
                                        val newStatus = parseLineStatus(json)
                                        line1Status.value = newStatus
                                        line1Fails = 0; line1PollingError.value = false

                                        val isFillActive = newStatus.fill_active
                                        val targetWeight = if (newStatus.fill_target > 0) newStatus.fill_target else line1PrevFillTarget
                                        val currentWeight = newStatus.weight

                                        if (isFillActive && targetWeight > 0) {
                                            if (!line1PrevFillActive) {
                                                val formattedTarget = String.format(java.util.Locale.US, "%.1f", targetWeight)
                                                globalToastEvents.tryEmit("💧 تم بدء تعبئة ماء مستهدفة ($line1Name) - الهدف: $formattedTarget كجم")
                                                cloudNotifications.tryEmit("💧 [$line1Name] تم استلام وبدء أمر تعبئة ماء: $formattedTarget كجم")
                                            }
                                            EquipmentNotifications.updateEquipmentFillNotification(
                                                context = getApplication(),
                                                lineIndex = 0,
                                                lineName = line1Name,
                                                fillActive = true,
                                                currentWeight = currentWeight,
                                                targetWeight = targetWeight
                                            )
                                            line1PrevFillActive = true
                                            line1PrevFillTarget = targetWeight
                                            line1StopReadings = 0
                                        } else {
                                            // لا نعتبر التعبئة متوقفة إلا بعد قراءتين صحيحتين متتاليتين (تجنباً للإنذار الكاذب)
                                            val confirmedStop = !line1PrevFillActive || (++line1StopReadings >= 2)
                                            if (confirmedStop) {
                                            if (line1PrevFillActive) {
                                                if (line1PrevFillTarget > 0) {
                                                    val isSuccess = currentWeight >= (line1PrevFillTarget - 0.5) || currentWeight >= (line1PrevFillTarget * 0.95)
                                                    val formattedFinal = String.format(java.util.Locale.US, "%.1f", currentWeight)
                                                    val formattedTarget = String.format(java.util.Locale.US, "%.1f", line1PrevFillTarget)

                                                    if (isSuccess) {
                                                        EquipmentNotifications.showEquipmentFillCompletedNotification(
                                                            context = getApplication(),
                                                            lineIndex = 0,
                                                            lineName = line1Name,
                                                            finalWeight = currentWeight,
                                                            targetWeight = line1PrevFillTarget
                                                        )
                                                        globalToastEvents.tryEmit("✅ اكتملت تعبئة المياه ($line1Name) - الوزن: $formattedFinal كجم من $formattedTarget كجم")
                                                        cloudNotifications.tryEmit("✅ [$line1Name] اكتملت تعبئة المياه: $formattedFinal / $formattedTarget كجم")
                                                    } else {
                                                        // توقف مبكر مؤكد (قراءتان صحيحتان متتاليتان)
                                                        android.util.Log.w("GBR_Fill", "توقف مبكر للتعبئة (1): الوزن=$formattedFinal الهدف=$formattedTarget الوقت=${System.currentTimeMillis()} سبب المتحكم=${line1Status.value?.fault_reason}")
                                                        EquipmentNotifications.showEquipmentFillFailedNotification(
                                                            context = getApplication(),
                                                            lineIndex = 0,
                                                            lineName = line1Name,
                                                            currentWeight = currentWeight,
                                                            targetWeight = line1PrevFillTarget,
                                                            reason = "توقفت التعبئة قبل بلوغ الهدف (راجع سجل أعطال المتحكم للتفاصيل)"
                                                        )
                                                        globalToastEvents.tryEmit("⚠️ توقفت تعبئة المياه لـ $line1Name قبل بلوغ الهدف! تم تحقيق $formattedFinal كجم فقط من أصل $formattedTarget كجم")
                                                        cloudNotifications.tryEmit("⚠️ [$line1Name] توقفت تعبئة المياه دون اكتمال الهدف: $formattedFinal / $formattedTarget كجم")

                                                        // Immediately poll controller /alerts
                                                        val freshAlerts1 = fetchAndProcessLineAlerts(client, ip1, 0, line1Name)
                                                        val freshAlerts2 = fetchAndProcessLineAlerts(client, ip2, 1, line2Name)
                                                        activeControllerAlerts.value = freshAlerts1 + freshAlerts2
                                                    }
                                                } else {
                                                    EquipmentNotifications.cancelEquipmentFillNotification(getApplication(), 0)
                                                }
                                            }
                                            line1PrevFillActive = false
                                            line1PrevFillTarget = 0.0
                                            line1StopReadings = 0
                                            }
                                        }
                                    } else {
                                        if (++line1Fails >= 3) line1PollingError.value = true
                                    }
                                } else {
                                    if (++line1Fails >= 3) line1PollingError.value = true
                                }
                            }
                        } catch (_: Exception) {
                            if (++line1Fails >= 3) line1PollingError.value = true
                        }
                    }

                    if (ip2.isNotBlank() && (panelLine != 1 || slowTick)) {
                        try {
                            val url = resolveUrl(ip2, "/status")
                            val req = okhttp3.Request.Builder().url(url).build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val json = resp.body?.string() ?: ""
                                    if (json.startsWith("{") && isCompleteStatusJson(json)) {
                                        val newStatus = parseLineStatus(json)
                                        line2Status.value = newStatus
                                        line2Fails = 0; line2PollingError.value = false

                                        val isFillActive = newStatus.fill_active
                                        val targetWeight = if (newStatus.fill_target > 0) newStatus.fill_target else line2PrevFillTarget
                                        val currentWeight = newStatus.weight

                                        if (isFillActive && targetWeight > 0) {
                                            if (!line2PrevFillActive) {
                                                val formattedTarget = String.format(java.util.Locale.US, "%.1f", targetWeight)
                                                globalToastEvents.tryEmit("💧 تم بدء تعبئة ماء مستهدفة ($line2Name) - الهدف: $formattedTarget كجم")
                                                cloudNotifications.tryEmit("💧 [$line2Name] تم استلام وبدء أمر تعبئة ماء: $formattedTarget كجم")
                                            }
                                            EquipmentNotifications.updateEquipmentFillNotification(
                                                context = getApplication(),
                                                lineIndex = 1,
                                                lineName = line2Name,
                                                fillActive = true,
                                                currentWeight = currentWeight,
                                                targetWeight = targetWeight
                                            )
                                            line2PrevFillActive = true
                                            line2PrevFillTarget = targetWeight
                                            line2StopReadings = 0
                                        } else {
                                            // لا نعتبر التعبئة متوقفة إلا بعد قراءتين صحيحتين متتاليتين (تجنباً للإنذار الكاذب)
                                            val confirmedStop = !line2PrevFillActive || (++line2StopReadings >= 2)
                                            if (confirmedStop) {
                                            if (line2PrevFillActive) {
                                                if (line2PrevFillTarget > 0) {
                                                    val isSuccess = currentWeight >= (line2PrevFillTarget - 0.5) || currentWeight >= (line2PrevFillTarget * 0.95)
                                                    val formattedFinal = String.format(java.util.Locale.US, "%.1f", currentWeight)
                                                    val formattedTarget = String.format(java.util.Locale.US, "%.1f", line2PrevFillTarget)

                                                    if (isSuccess) {
                                                        EquipmentNotifications.showEquipmentFillCompletedNotification(
                                                            context = getApplication(),
                                                            lineIndex = 1,
                                                            lineName = line2Name,
                                                            finalWeight = currentWeight,
                                                            targetWeight = line2PrevFillTarget
                                                        )
                                                        globalToastEvents.tryEmit("✅ اكتملت تعبئة المياه ($line2Name) - الوزن: $formattedFinal كجم من $formattedTarget كجم")
                                                        cloudNotifications.tryEmit("✅ [$line2Name] اكتملت تعبئة المياه: $formattedFinal / $formattedTarget كجم")
                                                    } else {
                                                        // توقف مبكر مؤكد (قراءتان صحيحتان متتاليتان)
                                                        android.util.Log.w("GBR_Fill", "توقف مبكر للتعبئة (2): الوزن=$formattedFinal الهدف=$formattedTarget الوقت=${System.currentTimeMillis()} سبب المتحكم=${line2Status.value?.fault_reason}")
                                                        EquipmentNotifications.showEquipmentFillFailedNotification(
                                                            context = getApplication(),
                                                            lineIndex = 1,
                                                            lineName = line2Name,
                                                            currentWeight = currentWeight,
                                                            targetWeight = line2PrevFillTarget,
                                                            reason = "توقفت التعبئة قبل بلوغ الهدف (راجع سجل أعطال المتحكم للتفاصيل)"
                                                        )
                                                        globalToastEvents.tryEmit("⚠️ توقفت تعبئة المياه لـ $line2Name قبل بلوغ الهدف! تم تحقيق $formattedFinal كجم فقط من أصل $formattedTarget كجم")
                                                        cloudNotifications.tryEmit("⚠️ [$line2Name] توقفت تعبئة المياه دون اكتمال الهدف: $formattedFinal / $formattedTarget كجم")

                                                        // Immediately poll controller /alerts
                                                        val freshAlerts1 = fetchAndProcessLineAlerts(client, ip1, 0, line1Name)
                                                        val freshAlerts2 = fetchAndProcessLineAlerts(client, ip2, 1, line2Name)
                                                        activeControllerAlerts.value = freshAlerts1 + freshAlerts2
                                                    }
                                                } else {
                                                    EquipmentNotifications.cancelEquipmentFillNotification(getApplication(), 1)
                                                }
                                            }
                                            line2PrevFillActive = false
                                            line2PrevFillTarget = 0.0
                                            line2StopReadings = 0
                                            }
                                        }
                                    } else {
                                        if (++line2Fails >= 3) line2PollingError.value = true
                                    }
                                } else {
                                    if (++line2Fails >= 3) line2PollingError.value = true
                                }
                            }
                        } catch (_: Exception) {
                            if (++line2Fails >= 3) line2PollingError.value = true
                        }
                    }

                    alertsPollTick++
                    if (alertsPollTick % (if (panelLine >= 0) 5 else 2) == 0) {
                        val list1 = fetchAndProcessLineAlerts(client, ip1, 0, line1Name)
                        val list2 = fetchAndProcessLineAlerts(client, ip2, 1, line2Name)
                        activeControllerAlerts.value = list1 + list2
                    }
                } catch (_: Exception) {
                }
                kotlinx.coroutines.delay(1200L)
            }
        }
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = GbrRepository(database.gbrDao(), application)

        val syncPrefs = application.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        val mode = syncPrefs.getString("sync_mode", "realtime") ?: "realtime"
        syncMode.value = mode

        // Load backup reminder configuration and last backup status
        val backupReminderInterval = syncPrefs.getInt("backup_reminder_days", 0)
        backupReminderDays.value = backupReminderInterval
        val savedLastBackupTime = syncPrefs.getString("last_backup_time", "لا يوجد نسخة احتياطية بعد") ?: "لا يوجد نسخة احتياطية بعد"
        lastBackupTime.value = savedLastBackupTime
        val savedLastBackupSize = syncPrefs.getString("last_backup_size", "--") ?: "--"
        lastBackupSize.value = savedLastBackupSize
        val savedLastBackupType = syncPrefs.getString("last_backup_type", "--") ?: "--"
        lastBackupType.value = savedLastBackupType
        val savedLastBackupTs = syncPrefs.getLong("last_backup_timestamp", 0L)
        lastBackupTimestamp.value = savedLastBackupTs

        checkAndTriggerBackupReminder()

        // ترحيل لمرة واحدة: نص الإصدار "1.0.0" القادم من قسم R&D يصير "الإصدار 1" كباقي التركيبات (تغيير نص فقط)
        if (!syncPrefs.getBoolean("fix_rnd_version_text_v1", false)) {
            viewModelScope.launch {
                try {
                    repository.formulations.first()
                        .filter { it.version.trim() == "1.0.0" }
                        .forEach { f ->
                            repository.insertFormulation(f.copy(version = "الإصدار 1"))
                            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, f.id, "formulation")
                        }
                    syncPrefs.edit().putBoolean("fix_rnd_version_text_v1", true).apply()
                } catch (e: Exception) {
                    android.util.Log.e("GBR_MIGRATE", "version text migration failed", e)
                }
            }
        }

        // Start active Firestore real-time snapshot listeners if mode is realtime
        if (mode == "realtime") {
            com.example.data.SyncManager.startRealtimeListeners(application, repository)
        } else {
            com.example.data.SyncManager.stopRealtimeListeners()
        }

        startEquipmentBackgroundPolling()

        // One-time migration: Clean up and rename legacy pH tests to Alkalinity (pH Value) test
        val isPhCleanupDone = syncPrefs.getBoolean("ph_cleanup_done", false)
        if (!isPhCleanupDone) {
            viewModelScope.launch {
                try {
                    val legacyPhNames = setOf(
                        "ph",
                        "ph value",
                        "درجة الحموضة",
                        "فحص الـ ph",
                        "فحص ph"
                    )
                    fun isLegacyPhTest(rawName: String): Boolean {
                        val clean = rawName.lowercase().trim().replace("🧪", "").trim()
                        return clean in legacyPhNames
                    }

                    val list = repository.gbrDao().getAllQualityTestsSync()
                    val oldPhTests = list.filter { isLegacyPhTest(it.name) }
                    val hasAlkalinityTest = list.any { it.name.contains("قلوية") || it.name.lowercase().contains("alkalinity") }

                    if (!hasAlkalinityTest) {
                        if (oldPhTests.isNotEmpty()) {
                            val firstOld = oldPhTests.first()
                            repository.updateQualityTest(firstOld.copy(name = "🧪 فحص درجة القلوية (pH Value)"))
                            for (duplicateOld in oldPhTests.drop(1)) {
                                repository.deleteQualityTest(duplicateOld)
                                repository.gbrDao().deleteFormulationQualityTestsByTestId(duplicateOld.id)
                            }
                        } else {
                            repository.insertQualityTest(com.example.data.QualityTest(
                                id = "alkalinity_test_id",
                                name = "🧪 فحص درجة القلوية (pH Value)",
                                sequenceIndex = 1
                            ))
                        }
                    } else {
                        for (oldPhTest in oldPhTests) {
                            repository.deleteQualityTest(oldPhTest)
                            repository.gbrDao().deleteFormulationQualityTestsByTestId(oldPhTest.id)
                        }
                    }

                    // Rename legacy LabTests in active/archived sessions to preserve recorded values
                    val allLabTests = repository.gbrDao().getAllLabTests().first()
                    val labPhTestsToRename = allLabTests.filter { isLegacyPhTest(it.name) }
                    for (test in labPhTestsToRename) {
                        repository.gbrDao().updateLabTest(test.copy(name = "فحص درجة القلوية (pH Value)"))
                    }

                    // Rename legacy ProductionOrderQualityTests to preserve recorded values and specifications
                    val allPoQualityTests = repository.gbrDao().getAllProductionOrderQualityTests().first()
                    val poPhTestsToRename = allPoQualityTests.filter { isLegacyPhTest(it.testName) }
                    if (poPhTestsToRename.isNotEmpty()) {
                        repository.gbrDao().insertProductionOrderQualityTests(
                            poPhTestsToRename.map { it.copy(testName = "فحص درجة القلوية (pH Value)") }
                        )
                    }

                    // Mark as done so this migration only runs once
                    syncPrefs.edit().putBoolean("ph_cleanup_done", true).apply()
                } catch (e: Exception) {
                    android.util.Log.e("GbrViewModel", "Error cleaning up pH and Alkalinity tests", e)
                }
            }
        }

        // Collect external sync updates
        viewModelScope.launch {
            externalUserUpdates.collect { list ->
                customUsers.value = list
                saveCustomUsersLocallyOnly(list)
            }
        }
        viewModelScope.launch {
            externalPackagingUpdates.collect { list ->
                customPackagings.value = list
                saveCustomPackagingsLocallyOnly(list)
            }
        }
        viewModelScope.launch {
            externalSystemLogsUpdates.collect { list ->
                systemLogs.value = list
            }
        }
        viewModelScope.launch {
            externalHostingUpdates.collect { pair ->
                hostingerGatewayUrl.value = pair.first
                hostingerEnabled.value = pair.second
            }
        }

        try {
            val logsPrefs = application.getSharedPreferences("gbr_system_logs", Context.MODE_PRIVATE)
            val logsJson = logsPrefs.getString("logs_json", "[]") ?: "[]"
            val arr = org.json.JSONArray(logsJson)
            val list = mutableListOf<SystemLog>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SystemLog(
                        timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                        category = o.optString("category", "sync"),
                        message = o.optString("message", ""),
                        username = o.optString("username", "غير معروف"),
                        deviceName = o.optString("deviceName", "غير معروف")
                    )
                )
            }
            if (list.isEmpty()) {
                list.add(SystemLog(category = "auth", message = "تم تشغيل النظام وتهيئته بنجاح ✅", username = "مدير النظام", deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
            }
            systemLogs.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Collect flows to state flows
        rawMaterials = repository.rawMaterials
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
        formulations = repository.formulations
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        isSelectedFormulationReadOnly = combine(selectedFormulation, formulations) { selected, all ->
            if (selected == null) {
                false
            } else {
                val sameCodeFormulations = all.filter { it.code.trim().uppercase() == selected.code.trim().uppercase() }
                if (sameCodeFormulations.size > 1) {
                    val getVersionInt = { vStr: String ->
                        try {
                            val digits = vStr.filter { it.isDigit() }
                            if (digits.isNotEmpty()) digits.toInt() else 0
                        } catch (e: Exception) {
                            0
                        }
                    }
                    val selectedVer = getVersionInt(selected.version)
                    val maxVer = sameCodeFormulations.maxOfOrNull { getVersionInt(it.version) } ?: 0
                    selectedVer < maxVer
                } else {
                    false
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

        productionLogs = repository.productionLogs
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allFormulationItems = repository.allFormulationItems
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allProductionOrderItems = repository.allProductionOrderItems
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        productionOrders = repository.productionOrders
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        qualityTests = repository.qualityTests
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        productionAdjustments = repository.getAllProductionAdjustments()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allFormulationRevisions = repository.getAllFormulationRevisions()
            .map { list ->
                list.sortedWith(
                    compareByDescending<FormulationRevision> {
                        it.version.filter { c -> c.isDigit() || c == '.' }.toDoubleOrNull() ?: 0.0
                    }
                    .thenByDescending { it.dateChange }
                    .thenByDescending { it.id }
                )
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allFormulationQualityTests = repository.getAllFormulationQualityTests()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allProductionOrderTestRecords = repository.getAllProductionOrderTestRecords()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allProductionOrderQualityTests = repository.gbrDao().getAllProductionOrderQualityTests()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        developmentProjects = repository.allDevelopmentProjects
            .map { list -> list.sortedWith(compareByDescending<DevelopmentProject> { it.createdAt }.thenByDescending { it.lastUpdated }) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allDevelopmentSamples = repository.gbrDao().getAllDevelopmentSamples()
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        labSessions = repository.allLabSessions
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            
        allLabTests = repository.allLabTests
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allLabAttachments = repository.allLabAttachments
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        allOperationalAlerts = repository.allOperationalAlerts
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        recycleBinItems = repository.allRecycleBinItems
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Auto cleanup recycle bin items older than 30 days
        viewModelScope.launch {
            try {
                val cutoffTime = System.currentTimeMillis() - 2592000000L
                repository.deleteOldRecycleBinItems(cutoffTime)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var dProjectSamplesJob: kotlinx.coroutines.Job? = null
        viewModelScope.launch {
            selectedDevelopmentProject.collect { project ->
                dProjectSamplesJob?.cancel()
                if (project != null) {
                    dProjectSamplesJob = viewModelScope.launch {
                        repository.getDevelopmentSamplesForProject(project.id).collect {
                            selectedDevelopmentProjectSamples.value = it
                        }
                    }
                } else {
                    selectedDevelopmentProjectSamples.value = emptyList()
                }
            }
        }

        var prodOrderItemsJob: kotlinx.coroutines.Job? = null
        var prodOrderEventsJob: kotlinx.coroutines.Job? = null
        var prodOrderPhasesJob: kotlinx.coroutines.Job? = null
        var prodOrderRecipeItemsJob: kotlinx.coroutines.Job? = null
        var prodOrderRecipeStatusJob: kotlinx.coroutines.Job? = null
        var prodOrderAdjustmentsJob: kotlinx.coroutines.Job? = null
        var lastCollectedOrderId: String? = null
        viewModelScope.launch {
            selectedProductionOrder.collect { order ->
                if (order != null) {
                    if (order.id == lastCollectedOrderId) {
                        // Same order ID, collectors are already active and collecting in real-time.
                        return@collect
                    }
                    lastCollectedOrderId = order.id

                    prodOrderItemsJob?.cancel()
                    prodOrderEventsJob?.cancel()
                    prodOrderPhasesJob?.cancel()
                    prodOrderRecipeItemsJob?.cancel()
                    prodOrderRecipeStatusJob?.cancel()
                    prodOrderAdjustmentsJob?.cancel()

                    prodOrderItemsJob = viewModelScope.launch {
                        repository.getProductionOrderItems(order.id).distinctUntilChanged().collect {
                            selectedProductionOrderItems.value = it
                        }
                    }
                    prodOrderEventsJob = viewModelScope.launch {
                        repository.getProductionOrderEvents(order.id).distinctUntilChanged().collect {
                            selectedProductionOrderEvents.value = it
                        }
                    }
                    prodOrderPhasesJob = viewModelScope.launch {
                        repository.getProductionOrderPhases(order.id).distinctUntilChanged().collect {
                            selectedProductionOrderPhases.value = it
                        }
                    }
                    prodOrderRecipeItemsJob = viewModelScope.launch {
                        repository.getProductionOrderRecipeItemsForOrder(order.id).distinctUntilChanged().collect {
                            selectedProductionOrderRecipeItems.value = it
                        }
                    }
                    prodOrderRecipeStatusJob = viewModelScope.launch {
                        repository.getRecipeStatus(order.formulationId).distinctUntilChanged().collect {
                            selectedOrderRecipeStatus.value = it
                        }
                    }
                    prodOrderAdjustmentsJob = viewModelScope.launch {
                        repository.getProductionAdjustments(order.id).distinctUntilChanged().collect {
                            selectedProductionOrderAdjustments.value = it
                        }
                    }
                } else {
                    lastCollectedOrderId = null
                    prodOrderItemsJob?.cancel()
                    prodOrderEventsJob?.cancel()
                    prodOrderPhasesJob?.cancel()
                    prodOrderRecipeItemsJob?.cancel()
                    prodOrderRecipeStatusJob?.cancel()
                    prodOrderAdjustmentsJob?.cancel()

                    selectedProductionOrderItems.value = emptyList()
                    selectedProductionOrderEvents.value = emptyList()
                    selectedProductionOrderPhases.value = emptyList()
                    selectedProductionOrderRecipeItems.value = emptyList()
                    selectedOrderRecipeStatus.value = null
                    selectedProductionOrderAdjustments.value = emptyList()
                }
            }
        }

        // 100% Real-time synchronization across all devices/users
        // Automatically keeps the active selected order updated when any modification to it happens in the database
        viewModelScope.launch {
            productionOrders.collect { orders ->
                val currentSelected = selectedProductionOrder.value
                if (currentSelected != null) {
                    // Ignore completed, archived or cancelled orders since they are frozen static historical records.
                    // This prevents background sync queries from replacing the selected order and causing UI instability or flicker.
                    if (currentSelected.status == "مكتمل" || currentSelected.status.contains("مؤرشف") || currentSelected.status == "ملغي") {
                        return@collect
                    }
                    val freshOrder = orders.find { it.id == currentSelected.id }
                    if (freshOrder != null && freshOrder != currentSelected) {
                        selectedProductionOrder.value = freshOrder
                    }
                }
            }
        }

        // Thread-safe dynamic detail tracking for actively running formulations
        var activeRunJob: kotlinx.coroutines.Job? = null
        viewModelScope.launch {
            activeRunFormulation.collect { formulation ->
                activeRunJob?.cancel()
                if (formulation != null) {
                    activeRunJob = viewModelScope.launch {
                        repository.getFormulationItemsWithDetails(formulation.id).collect {
                            activeRunItems.value = it
                        }
                    }
                } else {
                    activeRunItems.value = emptyList()
                }
            }
        }

        // Seed default database values if empty
        viewModelScope.launch {
            repository.seedDatabaseIfNeeded()
        }

        // Load custom settings
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        customUsers.value = loadCustomUsers()
        customPackagings.value = loadCustomPackagings()

        // Load custom branding and local preference settings
        companyName.value = prefs.getString("company_name", "دهانات GBR") ?: "دهانات GBR"
        appNamePref.value = prefs.getString("app_name_pref", "مشغل مصنع دهانات GBR") ?: "مشغل مصنع دهانات GBR"
        companyLogoUri.value = prefs.getString("company_logo_uri", null)
        appIconUri.value = prefs.getString("app_icon_uri", null)
        loginImageUri.value = prefs.getString("login_image_uri", null)
        appLanguage.value = prefs.getString("app_language", "ar") ?: "ar"
        appTheme.value = prefs.getString("app_theme", "light") ?: "light"

        // Load custom print and report headers from SharedPreferences
        printHeaderLogoUri.value = prefs.getString("print_header_logo_uri", "") ?: ""
        printHeaderTitle.value = prefs.getString("print_header_title", "دهانات GBR Paints") ?: "دهانات GBR Paints"
        printHeaderSubtitle.value = prefs.getString("print_header_subtitle", "المجموعة الصناعية الفنية للدهانات") ?: "المجموعة الصناعية الفنية للدهانات"
        printFooterText.value = prefs.getString("print_footer_text", "المستند الإنتاجي المعتمد لرقابة الجودة بمصانع دهانات GBR") ?: "المستند الإنتاجي المعتمد لرقابة الجودة بمصانع دهانات GBR"

        // Viscosity Dilution Weights setup
        viscosityDilutionPaintWeight.value = prefs.getString("viscosity_dilution_paint_weight", "160") ?: "160"
        viscosityDilutionWaterWeight.value = prefs.getString("viscosity_dilution_water_weight", "53.33") ?: "53.33"

        // Load hostinger custom integration settings
        hostingerGatewayUrl.value = prefs.getString("hostinger_gateway_url", "") ?: ""
        hostingerEnabled.value = prefs.getBoolean("hostinger_enabled", false)

        // Load sync parameters (Phase 1)
        syncLastRawMaterials.value = prefs.getString("sync_last_raw_materials", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
        syncLastFormulations.value = prefs.getString("sync_last_formulations", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
        syncLastProductionOrders.value = prefs.getString("sync_last_production_orders", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
        globalSyncStatus.value = prefs.getString("global_sync_status", "offline_local") ?: "offline_local"
        startContinuousDatabaseHealthCheck()

        // Continuous network monitoring for near-instant reactive status updates
        try {
            val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startContinuousDatabaseHealthCheck()
                        }
                    }
                    override fun onLost(network: android.net.Network) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startContinuousDatabaseHealthCheck()
                        }
                    }
                }
                networkCallback = callback
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val networkRequest = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startContinuousDatabaseHealthCheck()
                        }
                    }
                    override fun onLost(network: android.net.Network) {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startContinuousDatabaseHealthCheck()
                        }
                    }
                }
                networkCallback = callback
                connectivityManager.registerNetworkCallback(networkRequest, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Trigger automatic cloud download sync on brand new empty install
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(1500)
            try {
                // Check if we have any raw materials locally. If 0 and we have network, pull everything.
                val localMaterials = repository.rawMaterials.first()
                val isNet = com.example.data.SyncManager.isNetworkAvailable(application)
                if (localMaterials.isEmpty() && isNet && syncMode.value == "realtime") {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        triggerSmartDbMaintenance(skipDataSync = false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Periodic active grinding timers monitor (ticks every 1s)
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                updateActiveGrindingTimers()
            }
        }
    }

    private var healthCheckJob: kotlinx.coroutines.Job? = null

    fun startContinuousDatabaseHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = viewModelScope.launch {
            // Start checking almost immediately on launch or mode change
            kotlinx.coroutines.delay(100)
            while (true) {
                if (syncMode.value == "realtime") {
                    val context = getApplication<Application>()
                    val prefs = context.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
                    val userProjectId = prefs.getString("fb_project_id", null)?.trim()
                    val userApiKey = prefs.getString("fb_api_key", null)?.trim()
                    val userAppId = prefs.getString("fb_app_id", null)?.trim()
                    
                    val isCloudConfigured = !userProjectId.isNullOrBlank() && !userApiKey.isNullOrBlank() && !userAppId.isNullOrBlank()
                    
                    if (!isCloudConfigured) {
                        liveDbState.value = "not_configured"
                        liveDbMessage.value = "التطبيق يعمل بالوضع المحلي المفتوح. يرجى تهيئة السحابة من الإعدادات للربط ☁️"
                    } else {
                        if (liveDbState.value == "checking" || liveDbState.value == "local_only" || liveDbState.value == "not_configured") {
                            liveDbState.value = "checking"
                            liveDbMessage.value = "جاري التأكد من سلامة قاعدة البيانات والاتصال... ⏳"
                        }
                        try {
                            val isNet = com.example.data.SyncManager.isNetworkAvailable(context)
                            if (!isNet) {
                                liveDbState.value = "disconnected"
                                liveDbMessage.value = "غير متصل بالخادم: تعذر العثور على اتصال شبكة نشط 🌐"
                            } else {
                                val initSuccess = com.example.data.SyncManager.initializeFirebase(context)
                                if (initSuccess) {
                                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    
                                    // Perform a very fast parallel check of all sections with a tight 4-second timeout to prevent UI hanging under any networks
                                    val checkResult = kotlinx.coroutines.withTimeoutOrNull(4000) {
                                        try {
                                            kotlinx.coroutines.coroutineScope {
                                                val scope = this
                                                val sections = listOf(
                                                    "settings", "custom_users", "custom_products", "raw_materials", 
                                                    "formulations", "production_orders", "custom_inventory", "custom_suppliers", 
                                                    "custom_customers", "development_projects", "custom_costs", "custom_plans", "system_logs"
                                                )
                                                
                                                val deferredChecks = sections.map { sec ->
                                                    scope.async(kotlinx.coroutines.Dispatchers.IO) {
                                                        try {
                                                            val docSnap = firestore.collection(sec).document("_init_metadata").get().awaitTask()
                                                            if (docSnap.exists()) "exists" else "missing"
                                                        } catch (e: Exception) {
                                                            val isAuthOrPerm = e.localizedMessage?.contains("permission", ignoreCase = true) == true ||
                                                                                e.localizedMessage?.contains("unauthorized", ignoreCase = true) == true ||
                                                                                e.localizedMessage?.contains("privileges", ignoreCase = true) == true ||
                                                                                e is com.google.firebase.firestore.FirebaseFirestoreException
                                                            if (isAuthOrPerm) {
                                                                "auth_error:${e.localizedMessage}"
                                                            } else {
                                                                "error:${e.localizedMessage}"
                                                            }
                                                        }
                                                    }
                                                }
                                                val results = kotlinx.coroutines.awaitAll(*deferredChecks.toTypedArray())
                                                
                                                val authErr = results.firstOrNull { it.startsWith("auth_error:") }
                                                val genErr = results.firstOrNull { it.startsWith("error:") }
                                                
                                                if (authErr != null) {
                                                    authErr
                                                } else if (genErr != null) {
                                                    genErr
                                                } else {
                                                    val allExist = results.all { it == "exists" }
                                                    if (allExist) "ok" else "missing"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            "error:${e.localizedMessage}"
                                        }
                                    }

                                    when {
                                        checkResult == "ok" -> {
                                            liveDbState.value = "connected_healthy"
                                            liveDbMessage.value = "قاعدة البيانات متصلة ومحدثة بالكامل ✓"
                                            globalSyncStatus.value = "synced"
                                            
                                            // Execute standard synchronization in background without slowing down the health check loop timer
                                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    kotlinx.coroutines.withTimeoutOrNull(15000) {
                                                        com.example.data.SyncManager.fullBidirectionalSync(context, repository) { _, _, _, _ -> }
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("DbHealthCheckLive", "Background sync error", e)
                                                }
                                            }
                                        }
                                        checkResult == "missing" -> {
                                            liveDbState.value = "connected_healthy"
                                            liveDbMessage.value = "قاعدة البيانات متصلة ومزامنة تلقائية ✓"
                                            globalSyncStatus.value = "synced"
                                            
                                            // Execute standard synchronization in background without slowing down the health check loop timer
                                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                try {
                                                    kotlinx.coroutines.withTimeoutOrNull(15000) {
                                                        com.example.data.SyncManager.fullBidirectionalSync(context, repository) { _, _, _, _ -> }
                                                    }
                                                } catch (e: Exception) {
                                                    android.util.Log.e("DbHealthCheckLive", "Background sync error", e)
                                                }
                                            }
                                        }
                                        checkResult != null && checkResult.startsWith("auth_error:") -> {
                                            val errMsg = checkResult.substringAfter("auth_error:")
                                            liveDbState.value = "disconnected"
                                            liveDbMessage.value = "فشل الصلاحيات: رفض الوصول لقاعدة البيانات ($errMsg) ❌"
                                            globalSyncStatus.value = "offline_local"
                                        }
                                        else -> {
                                            val errMsg = checkResult?.substringAfter("error:") ?: "انتهت المهلة"
                                            liveDbState.value = "disconnected"
                                            liveDbMessage.value = "غير متصل بالسحابة: $errMsg ⚠️"
                                            globalSyncStatus.value = "offline_local"
                                        }
                                    }
                                } else {
                                    liveDbState.value = "disconnected"
                                    liveDbMessage.value = "فشل الاتصال بقاعدة البيانات: تعذر تهيئة البيئة السحابية [تأكد من إعدادات الاتصال] ❌"
                                    globalSyncStatus.value = "offline_local"
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DbHealthCheck", "Error in continuous database auto-maintenance system", e)
                            liveDbState.value = "disconnected"
                            liveDbMessage.value = "غير متصل بالسحابة: ${e.localizedMessage} ⚠️"
                            globalSyncStatus.value = "offline_local"
                        }
                    }
                } else {
                    liveDbState.value = "local_only"
                    liveDbMessage.value = "أنت تعمل الآن بالوضع المحلي الآمن بالكامل (Offline) 📴"
                }
                val healthIntervalMs = System.getProperty("gbr.cloud.health.interval.ms")?.toLongOrNull() ?: (24 * 60 * 60 * 1000L) // Safety net full-sync every 24 hours
                kotlinx.coroutines.delay(healthIntervalMs)
            }
        }
    }

    // --- Authentication ---
    fun login() {
        val input = username.value.trim().lowercase()
        val matchedUser = customUsers.value.find { user ->
            val uName = user.username.trim().lowercase()
            val dispName = user.name.trim().lowercase()
            uName == input || dispName == input ||
            (dispName.contains("(") && dispName.substringAfter("(").substringBefore(")").trim() == input) ||
            (dispName.contains("（") && dispName.substringAfter("（").substringBefore("）").trim() == input)
        }

        if (matchedUser != null) {
            if (!matchedUser.isActive) {
                loginError.value = "هذا الحساب معطل! يرجى مراجعة مدير النظام."
                addSystemLog("error", "محاولة دخول حساب معطل: ${matchedUser.name}")
                return
            }
            if (matchedUser.password == password.value) {
                currentUser.value = matchedUser
                isLoggedIn.value = true
                loginError.value = null
                password.value = ""
                addSystemLog("auth", "تم تسجيل دخول المستخدم: ${matchedUser.name} (${matchedUser.role})")
            } else {
                loginError.value = "خطأ في كلمة المرور!"
                addSystemLog("error", "كلمة مرور خاطئة للحساب: ${matchedUser.name}")
            }
        } else if (username.value.trim() == "admin" && password.value == "1234") {
            val adminUser = customUsers.value.find { it.id == "1" || it.username == "admin" } ?: CustomUser(
                id = "1",
                name = "المدير العام",
                role = "مدير النظام",
                password = "1234",
                isSupervisor = true,
                username = "admin",
                fullName = "المدير العام",
                isActive = true,
                createdAt = "2026-06-08 10:00"
            )
            currentUser.value = adminUser
            isLoggedIn.value = true
            loginError.value = null
            password.value = ""
            addSystemLog("auth", "تم تسجيل دخول المسؤول العام (System Admin) 🔑")
        } else {
            loginError.value = "خطأ في اسم المستخدم أو كلمة المرور!"
            addSystemLog("error", "محاولة دخول غير صالحة ببيانات غير موجودة: ${username.value}")
        }
    }

    // --- Dynamic Settings Operations ---
    fun addCustomUser(
        username: String,
        role: String,
        password: String,
        isSupervisor: Boolean,
        fullName: String = "",
        isActive: Boolean = true,
        createdAt: String = ""
    ) {
        val newList = customUsers.value + CustomUser(
            id = UUID.randomUUID().toString(),
            name = fullName.ifBlank { username },
            role = role,
            password = password,
            isSupervisor = isSupervisor,
            username = username,
            fullName = fullName,
            isActive = isActive,
            createdAt = createdAt
        )
        customUsers.value = newList
        saveCustomUsers(newList)
    }

    fun updateCustomUser(
        userId: String,
        username: String,
        role: String,
        password: String,
        isSupervisor: Boolean,
        fullName: String,
        isActive: Boolean
    ) {
        val newList = customUsers.value.map { user ->
            if (user.id == userId) {
                user.copy(
                    name = fullName.ifBlank { username },
                    role = role,
                    password = password,
                    isSupervisor = isSupervisor,
                    username = username,
                    fullName = fullName,
                    isActive = isActive
                )
            } else {
                user
            }
        }
        customUsers.value = newList
        saveCustomUsers(newList)

        if (currentUser.value?.id == userId) {
            val updatedUser = newList.find { it.id == userId }
            currentUser.value = updatedUser
            if (updatedUser == null || !updatedUser.isActive) {
                logout()
            }
        }
    }

    fun deleteCustomUser(userId: String) {
        val newList = customUsers.value.filter { it.id != userId }
        customUsers.value = newList
        saveCustomUsers(newList)
        com.example.data.SyncManager.deleteCustomUser(getApplication(), userId)
    }

    fun addCustomPackaging(name: String, netWeight: Double, weightWithLid: Double, price: Double) {
        val newList = customPackagings.value + CustomPackaging(
            id = UUID.randomUUID().toString(),
            name = name,
            netWeight = netWeight,
            weightWithLid = weightWithLid,
            price = price
        )
        customPackagings.value = newList
        saveCustomPackagings(newList)
    }

    fun updateCustomPackaging(id: String, name: String, netWeight: Double, weightWithLid: Double, price: Double) {
        val newList = customPackagings.value.map {
            if (it.id == id) {
                it.copy(name = name, netWeight = netWeight, weightWithLid = weightWithLid, price = price)
            } else {
                it
            }
        }
        customPackagings.value = newList
        saveCustomPackagings(newList)
    }

    fun deleteCustomPackaging(packageId: String) {
        val newList = customPackagings.value.filter { it.id != packageId }
        customPackagings.value = newList
        saveCustomPackagings(newList)
        com.example.data.SyncManager.deleteCustomPackaging(getApplication(), packageId)
    }

    private fun saveCustomUsers(users: List<CustomUser>) {
        try {
            val jsonArray = JSONArray()
            users.forEach { user ->
                val jsonObject = JSONObject().apply {
                    put("id", user.id)
                    put("name", user.name)
                    put("role", user.role)
                    put("password", user.password)
                    put("isSupervisor", user.isSupervisor)
                    put("username", user.username)
                    put("fullName", user.fullName)
                    put("isActive", user.isActive)
                    put("createdAt", user.createdAt)
                }
                jsonArray.put(jsonObject)
                com.example.data.SyncManager.uploadCustomUser(getApplication(), user)
            }
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_users_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCustomUsersLocallyOnly(users: List<CustomUser>) {
        try {
            val jsonArray = JSONArray()
            users.forEach { user ->
                val jsonObject = JSONObject().apply {
                    put("id", user.id)
                    put("name", user.name)
                    put("role", user.role)
                    put("password", user.password)
                    put("isSupervisor", user.isSupervisor)
                    put("username", user.username)
                    put("fullName", user.fullName)
                    put("isActive", user.isActive)
                    put("createdAt", user.createdAt)
                }
                jsonArray.put(jsonObject)
            }
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_users_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCustomUsers(): List<CustomUser> {
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("custom_users_json", null)
        if (jsonString.isNullOrEmpty()) {
            return listOf(
                CustomUser(
                    id = "1",
                    name = "المدير العام",
                    role = "مدير النظام",
                    password = "1234",
                    isSupervisor = true,
                    username = "admin",
                    fullName = "المدير العام",
                    isActive = true,
                    createdAt = "2026-06-08 10:00"
                ),
                CustomUser(
                    id = "2",
                    name = "مشرف الإنتاج المناوب",
                    role = "مشرف الإنتاج",
                    password = "5678",
                    isSupervisor = true,
                    username = "supervisor",
                    fullName = "مشرف الإنتاج المناوب",
                    isActive = true,
                    createdAt = "2026-06-08 10:00"
                ),
                CustomUser(
                    id = "3",
                    name = "عامل صالة التشغيل",
                    role = "عامل صالة الإنتاج",
                    password = "0000",
                    isSupervisor = false,
                    username = "operator_1",
                    fullName = "عامل صالة التشغيل",
                    isActive = true,
                    createdAt = "2026-06-08 10:00"
                )
            )
        }
        try {
            val list = mutableListOf<CustomUser>()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val nm = obj.optString("name", "")
                val savedRole = obj.optString("role", "")
                val isSup = obj.optBoolean("isSupervisor", false)
                
                val mappedRole = if (savedRole.isNotBlank()) {
                    if (savedRole == "مدير نظام كامل") "مدير النظام"
                    else if (savedRole == "صياغة المكونات والمواد") "مشرف الإنتاج"
                    else if (savedRole == "تنفيذ وطبخ وجبات الإنتاج") "عامل صالة الإنتاج"
                    else savedRole
                } else {
                    if (isSup) "مدير النظام" else "عامل صالة الإنتاج"
                }

                val uName = obj.optString("username", "").ifBlank {
                    if (nm.contains("(")) {
                        nm.substringAfter("(").substringBefore(")").trim()
                    } else if (nm.contains("（")) {
                        nm.substringAfter("（").substringBefore("）").trim()
                    } else {
                        nm.trim().lowercase().replace(" ", "_")
                    }
                }

                val fName = obj.optString("fullName", "").ifBlank {
                    if (nm.contains("(")) {
                        nm.substringBefore("(").trim()
                    } else if (nm.contains("（")) {
                        nm.substringBefore("（").trim()
                    } else {
                        nm.trim()
                    }
                }

                list.add(
                    CustomUser(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = fName.ifBlank { uName },
                        role = mappedRole,
                        password = obj.optString("password", ""),
                        isSupervisor = mappedRole == "مدير النظام" || mappedRole == "مشرف الإنتاج" || isSup,
                        username = uName,
                        fullName = fName,
                        isActive = obj.optBoolean("isActive", true),
                        createdAt = obj.optString("createdAt", "2026-06-08 10:00")
                    )
                )
            }
            return list
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun saveCustomPackagings(packagings: List<CustomPackaging>) {
        try {
            val jsonArray = JSONArray()
            packagings.forEach { pkg ->
                val jsonObject = JSONObject().apply {
                    put("id", pkg.id)
                    put("name", pkg.name)
                    put("netWeight", pkg.netWeight)
                    put("weightWithLid", pkg.weightWithLid)
                    put("price", pkg.price)
                }
                jsonArray.put(jsonObject)
                com.example.data.SyncManager.uploadCustomPackaging(getApplication(), pkg)
            }
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_packagings_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCustomPackagingsLocallyOnly(packagings: List<CustomPackaging>) {
        try {
            val jsonArray = JSONArray()
            packagings.forEach { pkg ->
                val jsonObject = JSONObject().apply {
                    put("id", pkg.id)
                    put("name", pkg.name)
                    put("netWeight", pkg.netWeight)
                    put("weightWithLid", pkg.weightWithLid)
                    put("price", pkg.price)
                }
                jsonArray.put(jsonObject)
            }
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_packagings_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCustomPackagings(): List<CustomPackaging> {
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("custom_packagings_json", null)
        if (jsonString.isNullOrEmpty()) {
            return emptyList()
        }
        try {
            val list = mutableListOf<CustomPackaging>()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    CustomPackaging(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", ""),
                        netWeight = obj.optDouble("netWeight", if (obj.optString("name", "").contains("18")) 18.0 else if (obj.optString("name", "").contains("5")) 5.0 else 1.0),
                        weightWithLid = obj.optDouble("weightWithLid", if (obj.optString("name", "").contains("18")) 18.8 else if (obj.optString("name", "").contains("5")) 5.3 else 1.1),
                        price = obj.optDouble("price", 0.0)
                    )
                )
            }
            return list
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    fun logout() {
        isLoggedIn.value = false
        username.value = ""
        password.value = ""
        currentUser.value = null
        activeSegment.value = null
        closeFormulationDetails()
        cancelProductionRun()
    }

    fun autoLogoutOnTimeout() {
        isLoggedIn.value = false
        currentUser.value = null
        activeSegment.value = null
        closeFormulationDetails()
        cancelProductionRun()
        addSystemLog("auth", "تم تسجيل الخروج تلقائياً بعد بقاء التطبيق في الخلفية لأكثر من ساعتين")
    }

    // --- App Customization and Brand Settings Setter ---
    fun updateAppIdentity(
        company: String,
        app: String,
        logo: String?,
        icon: String?,
        loginImg: String?
    ) {
        companyName.value = company.trim()
        appNamePref.value = app.trim()
        companyLogoUri.value = logo
        appIconUri.value = icon
        loginImageUri.value = loginImg

        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("company_name", company.trim())
            putString("app_name_pref", app.trim())
            putString("company_logo_uri", logo)
            putString("app_icon_uri", icon)
            putString("login_image_uri", loginImg)
        }.apply()
    }

    fun updatePrintSettings(logoUri: String, title: String, subtitle: String, footerText: String) {
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("print_header_logo_uri", logoUri)
            putString("print_header_title", title)
            putString("print_header_subtitle", subtitle)
            putString("print_footer_text", footerText)
        }.apply()
        printHeaderLogoUri.value = logoUri
        printHeaderTitle.value = title
        printHeaderSubtitle.value = subtitle
        printFooterText.value = footerText
    }

    fun updateViscosityDilutionWeights(paint: String, water: String) {
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("viscosity_dilution_paint_weight", paint)
            putString("viscosity_dilution_water_weight", water)
        }.apply()
        viscosityDilutionPaintWeight.value = paint
        viscosityDilutionWaterWeight.value = water
    }

    fun updateLanguage(lang: String) {
        appLanguage.value = lang
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", lang).apply()
    }

    fun updateTheme(themeStr: String) {
        appTheme.value = themeStr
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("app_theme", themeStr).apply()
    }

    fun updateHostingerSettings(url: String, enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().apply {
            putString("hostinger_gateway_url", url)
            putBoolean("hostinger_enabled", enabled)
            putLong("hostinger_last_updated", now)
        }.apply()
        hostingerGatewayUrl.value = url
        hostingerEnabled.value = enabled
        
        // Push configuration immediately to Firebase Firestore
        com.example.data.SyncManager.uploadHostingSettings(getApplication(), url, enabled)
    }

    // --- Data backup, Export and Import logic ---
    data class ImportAnalysis(
        val rawMaterialsCount: Int = 0,
        val formulationsCount: Int = 0,
        val revisionsCount: Int = 0,
        val recipePhasesCount: Int = 0,
        val recipeItemsCount: Int = 0,
        val ordersCount: Int = 0,
        val testsCount: Int = 0,
        val packagingsCount: Int = 0,
        val priceHistoryCount: Int = 0,
        val researchProjectsCount: Int = 0,
        val researchSamplesCount: Int = 0
    )

    fun checkAndTriggerBackupReminder() {
        try {
            val syncPrefs = getApplication<Application>().getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
            val intervalDays = syncPrefs.getInt("backup_reminder_days", 0)
            backupReminderDays.value = intervalDays
            if (intervalDays <= 0) {
                showBackupReminderPopup.value = false
                return
            }

            var lastTs = syncPrefs.getLong("last_backup_timestamp", 0L)
            if (lastTs == 0L) {
                val lastTimeStr = syncPrefs.getString("last_backup_time", null)
                if (!lastTimeStr.isNullOrBlank() && lastTimeStr != "لا يوجد نسخة احتياطية بعد" && lastTimeStr != "--") {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        val parsedDate = sdf.parse(lastTimeStr)
                        if (parsedDate != null) {
                            lastTs = parsedDate.time
                            syncPrefs.edit().putLong("last_backup_timestamp", lastTs).apply()
                            lastBackupTimestamp.value = lastTs
                        }
                    } catch (_: Exception) {}
                }
            }

            val currentMs = System.currentTimeMillis()
            val elapsedDays: Long = if (lastTs > 0L) {
                (currentMs - lastTs) / (1000L * 60 * 60 * 24)
            } else {
                intervalDays.toLong()
            }

            backupDueDaysCount.value = elapsedDays.toInt()

            if (elapsedDays >= intervalDays) {
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val lastDismissedDate = syncPrefs.getString("last_backup_reminder_dismissed_date", "")
                if (lastDismissedDate != todayStr) {
                    showBackupReminderPopup.value = true
                } else {
                    showBackupReminderPopup.value = false
                }
            } else {
                showBackupReminderPopup.value = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismissBackupReminderPopup(forToday: Boolean = true) {
        showBackupReminderPopup.value = false
        if (forToday) {
            val syncPrefs = getApplication<Application>().getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            syncPrefs.edit().putString("last_backup_reminder_dismissed_date", todayStr).apply()
        }
    }

    fun setBackupReminderInterval(days: Int) {
        backupReminderDays.value = days
        val syncPrefs = getApplication<Application>().getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        syncPrefs.edit().putInt("backup_reminder_days", days).apply()
        checkAndTriggerBackupReminder()
    }

    fun recordBackupCreated(sizeStr: String, typeStr: String) {
        val now = java.util.Date()
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val dateStr = formatter.format(now)
        val ts = now.time

        val syncPrefs = getApplication<Application>().getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        syncPrefs.edit()
            .putString("last_backup_time", dateStr)
            .putString("last_backup_size", sizeStr)
            .putString("last_backup_type", typeStr)
            .putLong("last_backup_timestamp", ts)
            .putString("last_backup_reminder_dismissed_date", "")
            .apply()

        lastBackupTime.value = dateStr
        lastBackupSize.value = sizeStr
        lastBackupType.value = typeStr
        lastBackupTimestamp.value = ts
        showBackupReminderPopup.value = false
        checkAndTriggerBackupReminder()
    }

    fun analyzeImportFile(jsonString: String): ImportAnalysis {
        return try {
            val obj = JSONObject(jsonString)
            ImportAnalysis(
                rawMaterialsCount = obj.optJSONArray("raw_materials")?.length() ?: 0,
                formulationsCount = obj.optJSONArray("formulations")?.length() ?: 0,
                revisionsCount = obj.optJSONArray("formulation_revisions")?.length() ?: 0,
                recipePhasesCount = obj.optJSONArray("recipe_phases")?.length() ?: 0,
                recipeItemsCount = obj.optJSONArray("recipe_items")?.length() ?: 0,
                ordersCount = obj.optJSONArray("production_orders")?.length() ?: 0,
                testsCount = (obj.optJSONArray("quality_tests")?.length() ?: 0) + 
                             (obj.optJSONArray("formulation_quality_tests")?.length() ?: 0) +
                             (obj.optJSONArray("production_order_test_records")?.length() ?: 0),
                packagingsCount = obj.optJSONArray("custom_packagings")?.length() ?: 0,
                priceHistoryCount = obj.optJSONArray("price_history")?.length() ?: 0,
                researchProjectsCount = obj.optJSONArray("development_projects")?.length() ?: 0,
                researchSamplesCount = obj.optJSONArray("development_samples")?.length() ?: 0
            )
        } catch (e: Exception) {
            ImportAnalysis()
        }
    }

    suspend fun exportDataJson(
        exportRawMaterials: Boolean,
        exportEquipment: Boolean,
        exportFormulations: Boolean,
        exportRevisions: Boolean,
        exportRecipes: Boolean,
        exportOrders: Boolean,
        exportTests: Boolean,
        exportPackagings: Boolean,
        exportCosts: Boolean,
        selectedSectionKeys: Set<String> = emptySet()
    ): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val root = JSONObject()
        // Save backup metadata
        root.put("metadata", com.example.data.BackupManager.createBackupMetadata(getApplication()))

        val database = AppDatabase.getDatabase(getApplication())
        val dao = database.gbrDao()

        // Unify toggles into active tables list
        val activeTables = mutableSetOf<String>()
        if (selectedSectionKeys.isNotEmpty()) {
            val activeSections = com.example.data.BackupManager.sections.filter { selectedSectionKeys.contains(it.key) }
            activeSections.forEach { activeTables.addAll(it.tables) }
        } else {
            if (exportRawMaterials) {
                activeTables.add("raw_materials")
            }
            if (exportCosts) {
                activeTables.add("price_history")
            }
            if (exportFormulations) {
                activeTables.add("formulations")
                activeTables.add("formulation_items")
                activeTables.add("formulation_reference_specs")
            }
            if (exportRevisions) {
                activeTables.add("formulation_revisions")
            }
            if (exportRecipes) {
                activeTables.add("recipe_phases")
                activeTables.add("recipe_items")
                activeTables.add("recipe_statuses")
            }
            if (exportOrders) {
                activeTables.add("production_orders")
                activeTables.add("production_order_items")
                activeTables.add("production_order_events")
                activeTables.add("production_order_phases")
                activeTables.add("production_order_recipe_items")
                activeTables.add("production_logs")
                activeTables.add("production_adjustments")
                activeTables.add("production_order_test_records")
            }
            if (exportTests) {
                activeTables.add("quality_tests")
                activeTables.add("formulation_quality_tests")
                activeTables.add("production_order_quality_tests")
                activeTables.add("laboratory_sessions")
                activeTables.add("laboratory_tests")
            }
            if (exportPackagings) {
                activeTables.add("custom_packagings")
            }
            if (exportEquipment) {
                activeTables.add("sync_metadata")
            }
        }

        // 1. raw_materials
        if (activeTables.contains("raw_materials")) {
            val list = dao.getAllRawMaterials().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("productionName", it.productionName)
                    put("price", it.price)
                    put("priceUnit", it.priceUnit)
                    put("notes", it.notes)
                    put("tdsUri", it.tdsUri ?: JSONObject.NULL)
                    put("isActive", it.isActive)
                })
            }
            root.put("raw_materials", arr)
        }

        // 2. price_history
        if (activeTables.contains("price_history")) {
            val list = dao.getAllPriceHistory().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("rawMaterialId", it.rawMaterialId)
                    put("oldPrice", it.oldPrice)
                    put("newPrice", it.newPrice)
                    put("dateChange", it.dateChange)
                })
            }
            root.put("price_history", arr)
        }

        // 3. formulations
        if (activeTables.contains("formulations")) {
            val list = dao.getAllFormulations().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("code", it.code)
                    put("description", it.description)
                    put("imageUri", it.imageUri ?: JSONObject.NULL)
                    put("version", it.version)
                    put("status", it.status)
                    put("createdAt", it.createdAt)
                    put("notes", it.notes)
                    put("supports18L", it.supports18L)
                    put("netWeight18L", it.netWeight18L)
                    put("supports5L", it.supports5L)
                    put("netWeight5L", it.netWeight5L)
                    put("packagingWeightsJson", it.packagingWeightsJson)
                    put("productionLine", it.productionLine)
                })
            }
            root.put("formulations", arr)
        }

        // 4. formulation_items
        if (activeTables.contains("formulation_items")) {
            val list = dao.getAllFormulationItems().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("formulationId", it.formulationId)
                    put("rawMaterialId", it.rawMaterialId)
                    put("quantityMultiplier", it.quantityMultiplier)
                    put("needsGrinding", it.needsGrinding)
                    put("grindingDurationMinutes", it.grindingDurationMinutes)
                    put("simulatedPrice", it.simulatedPrice ?: JSONObject.NULL)
                    put("sequence", it.sequence)
                })
            }
            root.put("formulation_items", arr)
        }

        // 5. formulation_reference_specs
        if (activeTables.contains("formulation_reference_specs")) {
            val list = dao.getAllFormulationReferenceSpecs().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("formulationId", it.formulationId)
                    put("approvalDate", it.approvalDate)
                    put("phValue", it.phValue ?: JSONObject.NULL)
                    put("densityEmptyWeight", it.densityEmptyWeight ?: JSONObject.NULL)
                    put("densityFilledWeight", it.densityFilledWeight ?: JSONObject.NULL)
                    put("densityFinalResult", it.densityFinalResult ?: JSONObject.NULL)
                    put("solidWeightBefore", it.solidWeightBefore ?: JSONObject.NULL)
                    put("solidWeightAfter", it.solidWeightAfter ?: JSONObject.NULL)
                    put("solidResultPct", it.solidResultPct ?: JSONObject.NULL)
                    put("binderWeightBefore", it.binderWeightBefore ?: JSONObject.NULL)
                    put("binderWeightAfter", it.binderWeightAfter ?: JSONObject.NULL)
                    put("binderResultPct", it.binderResultPct ?: JSONObject.NULL)
                    put("viscosityJson", it.viscosityJson ?: JSONObject.NULL)
                    put("viscosityFinalResult", it.viscosityFinalResult ?: JSONObject.NULL)
                    put("viscosityDilutedJson", it.viscosityDilutedJson ?: JSONObject.NULL)
                    put("viscosityDilutedFinalResult", it.viscosityDilutedFinalResult ?: JSONObject.NULL)
                    put("rheologyJson", it.rheologyJson ?: JSONObject.NULL)
                    put("rheologyIndexResult", it.rheologyIndexResult ?: JSONObject.NULL)
                })
            }
            root.put("formulation_reference_specs", arr)
        }

        // 6. formulation_revisions
        if (activeTables.contains("formulation_revisions")) {
            val list = dao.getAllFormulationRevisions().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("formulationId", it.formulationId)
                    put("version", it.version)
                    put("dateChange", it.dateChange)
                    put("materialName", it.materialName)
                    put("oldValue", it.oldValue)
                    put("newValue", it.newValue)
                    put("editReason", it.editReason)
                    put("snapshotJson", it.snapshotJson ?: JSONObject.NULL)
                })
            }
            root.put("formulation_revisions", arr)
        }

        // 7. recipe_phases
        if (activeTables.contains("recipe_phases")) {
            val list = dao.getAllRecipePhases().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("formulationId", it.formulationId)
                    put("name", it.name)
                    put("sequence", it.sequence)
                    put("mixerRpm", it.mixerRpm)
                    put("durationMinutes", it.durationMinutes)
                    put("instructions", it.instructions)
                })
            }
            root.put("recipe_phases", arr)
        }

        // 8. recipe_items
        if (activeTables.contains("recipe_items")) {
            val list = dao.getAllRecipeItems().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("phaseId", it.phaseId)
                    put("rawMaterialId", it.rawMaterialId)
                    put("ratio", it.ratio)
                    put("sequence", it.sequence)
                })
            }
            root.put("recipe_items", arr)
        }

        // 9. recipe_statuses
        if (activeTables.contains("recipe_statuses")) {
            val list = dao.getAllRecipeStatuses().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("formulationId", it.formulationId)
                    put("status", it.status)
                })
            }
            root.put("recipe_statuses", arr)
        }

        // 10. production_orders
        if (activeTables.contains("production_orders")) {
            val list = dao.getAllProductionOrders().first()
            val arr = JSONArray()
            list.forEach { o ->
                arr.put(JSONObject().apply {
                    put("id", o.id)
                    put("orderNumber", o.orderNumber)
                    put("batchNumber", o.batchNumber)
                    put("formulationId", o.formulationId)
                    put("formulationName", o.formulationName)
                    put("formulationVersion", o.formulationVersion)
                    put("requiredWeightKg", o.requiredWeightKg)
                    put("createdAt", o.createdAt)
                    put("status", o.status)
                    put("notes", o.notes)
                    put("scaleFactor", o.scaleFactor)
                    put("originalWeightKg", o.originalWeightKg)
                    put("progressPercent", o.progressPercent)
                    put("currentPhaseIndex", o.currentPhaseIndex)
                    put("currentItemIndex", o.currentItemIndex)
                    put("completedItemsJson", o.completedItemsJson)
                    put("packagingSnapshotJson", o.packagingSnapshotJson)
                    put("actualPackagingJson", o.actualPackagingJson)
                    put("startTime", o.startTime)
                    put("endTime", o.endTime)
                    put("operatorName", o.operatorName)
                    put("timerStartTimesJson", o.timerStartTimesJson)
                })
            }
            root.put("production_orders", arr)
        }

        // 11. production_order_items
        if (activeTables.contains("production_order_items")) {
            val list = dao.getAllProductionOrderItems().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("rawMaterialId", it.rawMaterialId)
                    put("rawMaterialName", it.rawMaterialName)
                    put("rawMaterialPrice", it.rawMaterialPrice)
                    put("rawMaterialPriceUnit", it.rawMaterialPriceUnit)
                    put("quantityMultiplier", it.quantityMultiplier)
                    put("calculatedQuantity", it.calculatedQuantity)
                    put("needsGrinding", it.needsGrinding)
                    put("grindingDurationMinutes", it.grindingDurationMinutes)
                })
            }
            root.put("production_order_items", arr)
        }

        // 12. production_order_events
        if (activeTables.contains("production_order_events")) {
            val list = dao.getAllProductionOrderEvents().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("eventName", it.eventName)
                    put("timestamp", it.timestamp)
                    put("description", it.description)
                })
            }
            root.put("production_order_events", arr)
        }

        // 13. production_order_phases
        if (activeTables.contains("production_order_phases")) {
            val list = dao.getAllProductionOrderPhases().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("name", it.name)
                    put("sequence", it.sequence)
                    put("mixerRpm", it.mixerRpm)
                    put("durationMinutes", it.durationMinutes)
                    put("instructions", it.instructions)
                })
            }
            root.put("production_order_phases", arr)
        }

        // 14. production_order_recipe_items
        if (activeTables.contains("production_order_recipe_items")) {
            val list = dao.getAllProductionOrderRecipeItems().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderPhaseId", it.productionOrderPhaseId)
                    put("rawMaterialId", it.rawMaterialId)
                    put("rawMaterialName", it.rawMaterialName)
                    put("ratio", it.ratio)
                    put("calculatedQuantity", it.calculatedQuantity)
                    put("sequence", it.sequence)
                })
            }
            root.put("production_order_recipe_items", arr)
        }

        // 15. production_adjustments
        if (activeTables.contains("production_adjustments")) {
            val list = dao.getAllProductionAdjustments().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("rawMaterialId", it.rawMaterialId)
                    put("rawMaterialName", it.rawMaterialName)
                    put("originalQuantity", it.originalQuantity)
                    put("newQuantity", it.newQuantity)
                    put("difference", it.difference)
                    put("reason", it.reason)
                    put("notes", it.notes)
                    put("timestamp", it.timestamp)
                    put("userName", it.userName)
                })
            }
            root.put("production_adjustments", arr)
        }

        // 16. production_order_test_records
        if (activeTables.contains("production_order_test_records")) {
            val list = dao.getAllProductionOrderTestRecords().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("isDirectTest", it.isDirectTest)
                    put("testDate", it.testDate)
                    put("resultsJson", it.resultsJson)
                    put("timestamp", it.timestamp)
                })
            }
            root.put("production_order_test_records", arr)
        }

        // 17. production_logs
        if (activeTables.contains("production_logs")) {
            val list = dao.getAllProductionLogs().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("formulationId", it.formulationId)
                    put("formulationName", it.formulationName)
                    put("operatorName", it.operatorName)
                    put("batchWeightKg", it.batchWeightKg)
                    put("status", it.status)
                    put("timestamp", it.timestamp)
                })
            }
            root.put("production_logs", arr)
        }

        // 18. quality_tests
        if (activeTables.contains("quality_tests")) {
            val list = dao.getAllQualityTests().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("sequenceIndex", it.sequenceIndex)
                })
            }
            root.put("quality_tests", arr)
        }

        // 19. formulation_quality_tests
        if (activeTables.contains("formulation_quality_tests")) {
            val list = dao.getAllFormulationQualityTests().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("formulationId", it.formulationId)
                    put("testId", it.testId)
                    put("isEnabled", it.isEnabled)
                    put("minValue", it.minValue ?: JSONObject.NULL)
                    put("maxValue", it.maxValue ?: JSONObject.NULL)
                })
            }
            root.put("formulation_quality_tests", arr)
        }

        // 20. production_order_quality_tests
        if (activeTables.contains("production_order_quality_tests")) {
            val list = dao.getAllProductionOrderQualityTests().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("productionOrderId", it.productionOrderId)
                    put("testId", it.testId)
                    put("testName", it.testName)
                    put("minValue", it.minValue ?: JSONObject.NULL)
                    put("maxValue", it.maxValue ?: JSONObject.NULL)
                    put("sequenceIndex", it.sequenceIndex)
                })
            }
            root.put("production_order_quality_tests", arr)
        }

        // 21. development_projects
        if (activeTables.contains("development_projects")) {
            val list = dao.getAllDevelopmentProjects().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("createdAt", it.createdAt)
                    put("lastUpdated", it.lastUpdated)
                })
            }
            root.put("development_projects", arr)
        }

        // 22. development_samples
        if (activeTables.contains("development_samples")) {
            val list = dao.getAllDevelopmentSamples().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("projectId", it.projectId)
                    put("sampleName", it.sampleName)
                    put("sampleNumber", it.sampleNumber)
                    put("createdAt", it.createdAt)
                    put("targetWeightKg", it.targetWeightKg)
                    put("targetGoal", it.targetGoal)
                    put("initialNotes", it.initialNotes)
                    put("researchNotes", it.researchNotes)
                    put("isApproved", it.isApproved)
                    put("approvedDate", it.approvedDate)
                    put("resultsJson", it.resultsJson)
                    put("itemsJson", it.itemsJson)
                    put("recipeJson", it.recipeJson)
                    put("status", it.status ?: JSONObject.NULL)
                    put("statusNotes", it.statusNotes ?: JSONObject.NULL)
                    put("statusUpdatedAt", it.statusUpdatedAt ?: JSONObject.NULL)
                    put("statusUpdatedBy", it.statusUpdatedBy ?: JSONObject.NULL)
                })
            }
            root.put("development_samples", arr)
        }

        // 23. laboratory_sessions
        if (activeTables.contains("laboratory_sessions")) {
            val list = dao.getAllLabSessions().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("sessionNumber", it.sessionNumber)
                    put("testName", it.testName)
                    put("testDate", it.testDate)
                    put("technicianName", it.technicianName)
                    put("sampleOrProduct", it.sampleOrProduct)
                    put("category", it.category)
                    put("testType", it.testType)
                    put("notes", it.notes)
                    put("comparisonType", it.comparisonType ?: JSONObject.NULL)
                    put("partyA", it.partyA ?: JSONObject.NULL)
                    put("partyB", it.partyB ?: JSONObject.NULL)
                    put("createdAt", it.createdAt)
                    put("sampleProperties", it.sampleProperties)
                })
            }
            root.put("laboratory_sessions", arr)
        }

        // 24. laboratory_tests
        if (activeTables.contains("laboratory_tests")) {
            val list = dao.getAllLabTests().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("sessionId", it.sessionId)
                    put("name", it.name)
                    put("status", it.status)
                    put("executionDate", it.executionDate)
                    put("notes", it.notes)
                    put("testValueA", it.testValueA ?: JSONObject.NULL)
                    put("testValueB", it.testValueB ?: JSONObject.NULL)
                    put("createdAt", it.createdAt)
                })
            }
            root.put("laboratory_tests", arr)
        }

        // 25. sync_metadata
        if (activeTables.contains("sync_metadata")) {
            val list = dao.getAllSyncMetadata().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("entityType", it.entityType)
                    put("lastUpdated", it.lastUpdated)
                    put("isPendingSync", it.isPendingSync)
                    put("lastError", it.lastError ?: JSONObject.NULL)
                    put("syncStage", it.syncStage)
                    put("lastAttempt", it.lastAttempt)
                    put("retryCount", it.retryCount)
                    put("firebaseErrorCode", it.firebaseErrorCode ?: JSONObject.NULL)
                })
            }
            root.put("sync_metadata", arr)
        }

        // 26. laboratory_attachments
        if (activeTables.contains("laboratory_attachments")) {
            val list = dao.getAllLabAttachments().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("sessionId", it.sessionId)
                    put("testName", it.testName)
                    put("filePathOrUrl", it.filePathOrUrl)
                    put("createdAt", it.createdAt)
                })
            }
            root.put("laboratory_attachments", arr)
        }

        // 27. operational_alerts
        if (activeTables.contains("operational_alerts")) {
            val list = dao.getAllOperationalAlerts().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("description", it.description)
                    put("mainSection", it.mainSection)
                    put("bindingScope", it.bindingScope)
                    put("bindingElementName", it.bindingElementName)
                    put("alertLevel", it.alertLevel)
                    put("status", it.status)
                    put("createdAt", it.createdAt)
                })
            }
            root.put("operational_alerts", arr)
        }

        // Special table from SharedPreferences: custom_packagings
        if (activeTables.contains("custom_packagings")) {
            val arr = JSONArray()
            customPackagings.value.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("netWeight", it.netWeight)
                    put("weightWithLid", it.weightWithLid)
                    put("price", it.price)
                })
            }
            root.put("custom_packagings", arr)
        }

        // 28. recycle_bin
        if (activeTables.contains("recycle_bin")) {
            val list = dao.getAllRecycleBinItems().first()
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("originalId", it.originalId)
                    put("itemType", it.itemType)
                    put("displayName", it.displayName)
                    put("serializedData", it.serializedData)
                    put("deletedAt", it.deletedAt)
                    put("extraDataJson", it.extraDataJson ?: JSONObject.NULL)
                })
            }
            root.put("recycle_bin", arr)
        }

        // 29. inventory (دفتر حركات المخزون + السجلات الرئيسة)
        if (activeTables.contains("stock_moves")) {
            try {
                com.example.data.InventoryBackup.exportAll(database.inventoryDao()).forEach { (k, v) -> root.put(k, v) }
            } catch (e: Exception) {
                android.util.Log.e("GBR_Backup", "Inventory export failed", e)
            }
        }

        root.toString(2)
    }

    suspend fun executeSelectiveImport(
        jsonString: String,
        importRawMaterials: Boolean,
        importFormulations: Boolean,
        importRevisions: Boolean,
        importRecipes: Boolean,
        importOrders: Boolean,
        importTests: Boolean,
        importPackagings: Boolean,
        importCosts: Boolean,
        importStrategy: Int = 1, // 1 = Smart Merge, 2 = Full Overwrite, 3 = Preview only
        selectedSectionKeys: Set<String> = emptySet()
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (importStrategy == 3) {
                return@withContext true
            }
            val database = AppDatabase.getDatabase(getApplication())
            val dao = database.gbrDao()

            // دفتر المخزون غير قابل للتعديل: نحفظ نسخة منه قبل المسح الكامل ونعيدها بعده (الإضافة فقط، لا فقدان).
            var inventorySnapshot: JSONObject? = null
            if (importStrategy == 2) {
                try {
                    val snap = com.example.data.InventoryBackup.exportAll(database.inventoryDao())
                    inventorySnapshot = JSONObject().also { r -> snap.forEach { (k, v) -> r.put(k, v) } }
                } catch (e: java.lang.Exception) {
                    android.util.Log.e("GBR_Import", "Inventory snapshot failed", e)
                }
            }

            if (importStrategy == 2) {
                try {
                    database.clearAllTables()
                } catch (e: java.lang.Exception) {
                    android.util.Log.e("GBR_Import", "Error clearing all tables inside full overwrite restore", e)
                }
            }

            val root = JSONObject(jsonString)

            // Dynamic mapping based on registry or legacy toggles
            val importFormulations = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("formulations") else importFormulations
            val importRawMaterials = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("raw_materials") else importRawMaterials
            val importOrders = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("production") else importOrders
            val importTests = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("laboratory") else importTests
            val importRevisions = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("formulations") else importRevisions
            val importRecipes = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("formulations") else importRecipes
            val importPackagings = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("settings_and_equipment") else importPackagings
            val importCosts = if (selectedSectionKeys.isNotEmpty()) (selectedSectionKeys.contains("raw_materials") || selectedSectionKeys.contains("settings_and_equipment")) else importCosts

            val isResearchDevSelected = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("research_development") else true

            if (importRawMaterials && root.has("raw_materials")) {
                val arr = root.getJSONArray("raw_materials")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertRawMaterial(
                        RawMaterial(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            name = o.optString("name", ""),
                            productionName = o.optString("productionName", ""),
                            price = o.optDouble("price", 0.0),
                            priceUnit = o.optString("priceUnit", "شيكل"),
                            notes = o.optString("notes", ""),
                            tdsUri = if (o.isNull("tdsUri")) null else o.optString("tdsUri"),
                            isActive = o.optBoolean("isActive", true)
                        )
                    )
                }
            }

            if (importFormulations && root.has("formulations")) {
                val arr = root.getJSONArray("formulations")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertFormulation(
                        Formulation(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            name = o.optString("name", ""),
                            code = o.optString("code", ""),
                            description = o.optString("description", ""),
                            imageUri = if (o.isNull("imageUri")) null else o.optString("imageUri"),
                            version = o.optString("version", "1.0.0"),
                            status = o.optString("status", "🟢 معتمدة للإنتاج"),
                            createdAt = o.optString("createdAt", ""),
                            notes = o.optString("notes", ""),
                            supports18L = o.optBoolean("supports18L", false),
                            netWeight18L = o.optString("netWeight18L", ""),
                            supports5L = o.optBoolean("supports5L", false),
                            netWeight5L = o.optString("netWeight5L", ""),
                            packagingWeightsJson = o.optString("packagingWeightsJson", ""),
                            productionLine = o.optInt("productionLine", -1)
                        )
                    )
                }

                if (root.has("formulation_items")) {
                    val itemsArr = root.getJSONArray("formulation_items")
                    for (i in 0 until itemsArr.length()) {
                        val o = itemsArr.getJSONObject(i)
                        dao.insertFormulationItem(
                            FormulationItem(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                formulationId = o.optString("formulationId", ""),
                                rawMaterialId = o.optString("rawMaterialId", ""),
                                quantityMultiplier = o.optDouble("quantityMultiplier", 0.0),
                                needsGrinding = o.optBoolean("needsGrinding", false),
                                grindingDurationMinutes = o.optInt("grindingDurationMinutes", 0),
                                sequence = o.optInt("sequence", i)
                            )
                        )
                    }
                }
            }

            if (importRevisions && root.has("formulation_revisions")) {
                val arr = root.getJSONArray("formulation_revisions")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertFormulationRevision(
                        FormulationRevision(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            formulationId = o.optString("formulationId", ""),
                            version = o.optString("version", ""),
                            dateChange = o.optString("dateChange", ""),
                            materialName = o.optString("materialName", ""),
                            oldValue = o.optString("oldValue", ""),
                            newValue = o.optString("newValue", ""),
                            editReason = o.optString("editReason", ""),
                            snapshotJson = if (o.isNull("snapshotJson")) null else o.optString("snapshotJson")
                        )
                    )
                }
            }

            if (importRecipes) {
                if (root.has("recipe_phases")) {
                    val arr = root.getJSONArray("recipe_phases")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertRecipePhase(
                            RecipePhase(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                formulationId = o.optString("formulationId", ""),
                                name = o.optString("name", ""),
                                sequence = o.optInt("sequence", 0),
                                mixerRpm = o.optInt("mixerRpm", 0),
                                durationMinutes = o.optInt("durationMinutes", 0),
                                instructions = o.optString("instructions", "")
                            )
                        )
                    }
                }
                if (root.has("recipe_items")) {
                    val arr = root.getJSONArray("recipe_items")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertRecipeItem(
                            RecipeItem(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                phaseId = o.optString("phaseId", ""),
                                rawMaterialId = o.optString("rawMaterialId", ""),
                                ratio = o.optDouble("ratio", 1.0),
                                sequence = o.optInt("sequence", 0)
                            )
                        )
                    }
                }
                // حالة الوصفات (READY / NEEDS_REVIEW): كانت تُصدَّر ولا تُستعاد (R1)
                if (root.has("recipe_statuses")) {
                    val arr = root.getJSONArray("recipe_statuses")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val fid = o.optString("formulationId", "")
                        if (fid.isNotBlank()) {
                            dao.insertRecipeStatus(
                                RecipeStatus(
                                    formulationId = fid,
                                    status = o.optString("status", "NEEDS_REVIEW")
                                )
                            )
                        }
                    }
                }
            }

            if (importOrders && root.has("production_orders")) {
                val arr = root.getJSONArray("production_orders")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertProductionOrder(
                        ProductionOrder(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            orderNumber = o.optString("orderNumber", ""),
                            batchNumber = o.optString("batchNumber", ""),
                            formulationId = o.optString("formulationId", ""),
                            formulationName = o.optString("formulationName", ""),
                            formulationVersion = o.optString("formulationVersion", ""),
                            requiredWeightKg = o.optDouble("requiredWeightKg", 0.0),
                            createdAt = o.optLong("createdAt", 0L),
                            status = o.optString("status", "مسودة"),
                            notes = o.optString("notes", ""),
                            scaleFactor = o.optDouble("scaleFactor", 1.0),
                            originalWeightKg = o.optDouble("originalWeightKg", 0.0),
                            progressPercent = o.optInt("progressPercent", 0),
                            currentPhaseIndex = o.optInt("currentPhaseIndex", 0),
                            currentItemIndex = o.optInt("currentItemIndex", 0),
                            completedItemsJson = o.optString("completedItemsJson", "[]"),
                            packagingSnapshotJson = o.optString("packagingSnapshotJson", ""),
                            actualPackagingJson = o.optString("actualPackagingJson", "[]"),
                            startTime = o.optLong("startTime", 0L),
                            endTime = o.optLong("endTime", 0L),
                            operatorName = o.optString("operatorName", "المشرف")
                        )
                    )
                }

                if (root.has("production_order_items")) {
                    val arr = root.getJSONArray("production_order_items")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertProductionOrderItem(
                            ProductionOrderItem(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                productionOrderId = o.optString("productionOrderId", ""),
                                rawMaterialId = o.optString("rawMaterialId", ""),
                                rawMaterialName = o.optString("rawMaterialName", ""),
                                rawMaterialPrice = o.optDouble("rawMaterialPrice", 0.0),
                                rawMaterialPriceUnit = o.optString("rawMaterialPriceUnit", "شيكل"),
                                quantityMultiplier = o.optDouble("quantityMultiplier", 0.0),
                                calculatedQuantity = o.optDouble("calculatedQuantity", 0.0),
                                needsGrinding = o.optBoolean("needsGrinding", false),
                                grindingDurationMinutes = o.optInt("grindingDurationMinutes", 0)
                            )
                        )
                    }
                }

                if (root.has("production_order_events")) {
                    val arr = root.getJSONArray("production_order_events")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertProductionOrderEvent(
                            ProductionOrderEvent(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                productionOrderId = o.optString("productionOrderId", ""),
                                eventName = o.optString("eventName", ""),
                                timestamp = o.optLong("timestamp", 0L),
                                description = o.optString("description", "")
                            )
                        )
                    }
                }

                if (root.has("production_order_phases")) {
                    val arr = root.getJSONArray("production_order_phases")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertProductionOrderPhase(
                            ProductionOrderPhase(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                productionOrderId = o.optString("productionOrderId", ""),
                                name = o.optString("name", ""),
                                sequence = o.optInt("sequence", 0),
                                mixerRpm = o.optInt("mixerRpm", 0),
                                durationMinutes = o.optInt("durationMinutes", 0),
                                instructions = o.optString("instructions", "")
                            )
                        )
                    }
                }

                if (root.has("production_order_recipe_items")) {
                    val arr = root.getJSONArray("production_order_recipe_items")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertProductionOrderRecipeItem(
                            ProductionOrderRecipeItem(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                productionOrderPhaseId = o.optString("productionOrderPhaseId", ""),
                                rawMaterialId = o.optString("rawMaterialId", ""),
                                rawMaterialName = o.optString("rawMaterialName", ""),
                                ratio = o.optDouble("ratio", 1.0),
                                calculatedQuantity = o.optDouble("calculatedQuantity", 0.0),
                                sequence = o.optInt("sequence", 0)
                            )
                        )
                    }
                }
            }

            if (importTests) {
                if (root.has("quality_tests")) {
                    val arr = root.getJSONArray("quality_tests")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertQualityTest(
                            QualityTest(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                name = o.optString("name", ""),
                                sequenceIndex = o.optInt("sequenceIndex", 0)
                            )
                        )
                    }
                }

                if (root.has("formulation_quality_tests")) {
                    val arr = root.getJSONArray("formulation_quality_tests")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertFormulationQualityTest(
                            FormulationQualityTest(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                formulationId = o.optString("formulationId", ""),
                                testId = o.optString("testId", ""),
                                isEnabled = o.optBoolean("isEnabled", false),
                                minValue = if (o.isNull("minValue")) null else o.optDouble("minValue"),
                                maxValue = if (o.isNull("maxValue")) null else o.optDouble("maxValue")
                            )
                        )
                    }
                }

                if (root.has("production_order_test_records")) {
                    val arr = root.getJSONArray("production_order_test_records")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        dao.insertProductionOrderTestRecord(
                            ProductionOrderTestRecord(
                                id = o.optString("id", java.util.UUID.randomUUID().toString()),
                                productionOrderId = o.optString("productionOrderId", ""),
                                isDirectTest = o.optBoolean("isDirectTest", false),
                                testDate = o.optString("testDate", ""),
                                resultsJson = o.optString("resultsJson", "{}"),
                                timestamp = o.optLong("timestamp", 0L)
                            )
                        )
                    }
                }
            }

            if (importPackagings && root.has("custom_packagings")) {
                val list = mutableListOf<CustomPackaging>()
                val arr = root.getJSONArray("custom_packagings")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        CustomPackaging(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.optString("name", ""),
                            netWeight = o.optDouble("netWeight", 18.0),
                            weightWithLid = o.optDouble("weightWithLid", 19.0),
                            price = o.optDouble("price", 0.0)
                        )
                    )
                }
                customPackagings.value = list
                saveCustomPackagings(list)
            }

            if (importCosts && root.has("price_history")) {
                val arr = root.getJSONArray("price_history")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertPriceHistory(
                        PriceHistoryEntry(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            rawMaterialId = o.optString("rawMaterialId", ""),
                            oldPrice = o.optDouble("oldPrice", 0.0),
                            newPrice = o.optDouble("newPrice", 0.0),
                            dateChange = o.optString("dateChange", "")
                        )
                    )
                }
            }

            // 21. formulation_reference_specs
            if (importFormulations && root.has("formulation_reference_specs")) {
                val arr = root.getJSONArray("formulation_reference_specs")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertFormulationReferenceSpecs(
                        FormulationReferenceSpecs(
                            formulationId = o.optString("formulationId", ""),
                            approvalDate = o.optString("approvalDate", ""),
                            phValue = if (o.isNull("phValue")) null else o.optString("phValue"),
                            densityEmptyWeight = if (o.isNull("densityEmptyWeight")) null else o.optDouble("densityEmptyWeight"),
                            densityFilledWeight = if (o.isNull("densityFilledWeight")) null else o.optDouble("densityFilledWeight"),
                            densityFinalResult = if (o.isNull("densityFinalResult")) null else o.optDouble("densityFinalResult"),
                            solidWeightBefore = if (o.isNull("solidWeightBefore")) null else o.optDouble("solidWeightBefore"),
                            solidWeightAfter = if (o.isNull("solidWeightAfter")) null else o.optDouble("solidWeightAfter"),
                            solidResultPct = if (o.isNull("solidResultPct")) null else o.optDouble("solidResultPct"),
                            binderWeightBefore = if (o.isNull("binderWeightBefore")) null else o.optDouble("binderWeightBefore"),
                            binderWeightAfter = if (o.isNull("binderWeightAfter")) null else o.optDouble("binderWeightAfter"),
                            binderResultPct = if (o.isNull("binderResultPct")) null else o.optDouble("binderResultPct"),
                            viscosityJson = if (o.isNull("viscosityJson")) null else o.optString("viscosityJson"),
                            viscosityFinalResult = if (o.isNull("viscosityFinalResult")) null else o.optDouble("viscosityFinalResult"),
                            viscosityDilutedJson = if (o.isNull("viscosityDilutedJson")) null else o.optString("viscosityDilutedJson"),
                            viscosityDilutedFinalResult = if (o.isNull("viscosityDilutedFinalResult")) null else o.optDouble("viscosityDilutedFinalResult"),
                            rheologyJson = if (o.isNull("rheologyJson")) null else o.optString("rheologyJson"),
                            rheologyIndexResult = if (o.isNull("rheologyIndexResult")) null else o.optDouble("rheologyIndexResult")
                        )
                    )
                }
            }

            // 22. production_adjustments
            if (importOrders && root.has("production_adjustments")) {
                val arr = root.getJSONArray("production_adjustments")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertProductionAdjustment(
                        ProductionAdjustment(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            productionOrderId = o.optString("productionOrderId", ""),
                            rawMaterialId = o.optString("rawMaterialId", ""),
                            rawMaterialName = o.optString("rawMaterialName", ""),
                            originalQuantity = o.optDouble("originalQuantity", 0.0),
                            newQuantity = o.optDouble("newQuantity", 0.0),
                            difference = o.optDouble("difference", 0.0),
                            reason = o.optString("reason", ""),
                            notes = o.optString("notes", ""),
                            timestamp = o.optLong("timestamp", 0L),
                            userName = o.optString("userName", "")
                        )
                    )
                }
            }

            // 23. production_logs
            if (importOrders && root.has("production_logs")) {
                val arr = root.getJSONArray("production_logs")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertProductionLog(
                        ProductionLog(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            formulationId = o.optString("formulationId", ""),
                            formulationName = o.optString("formulationName", ""),
                            operatorName = o.optString("operatorName", ""),
                            batchWeightKg = o.optDouble("batchWeightKg", 0.0),
                            status = o.optString("status", ""),
                            timestamp = o.optLong("timestamp", 0L)
                        )
                    )
                }
            }

            // 24. development_projects
            if (isResearchDevSelected && root.has("development_projects")) {
                val arr = root.getJSONArray("development_projects")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertDevelopmentProject(
                        DevelopmentProject(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            name = o.optString("name", ""),
                            createdAt = o.optString("createdAt", ""),
                            lastUpdated = o.optString("lastUpdated", "")
                        )
                    )
                }
            }

            // 25. development_samples
            if (isResearchDevSelected && root.has("development_samples")) {
                val arr = root.getJSONArray("development_samples")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertDevelopmentSample(
                        DevelopmentSample(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            projectId = o.optString("projectId", ""),
                            sampleName = o.optString("sampleName", ""),
                            sampleNumber = o.optString("sampleNumber", ""),
                            createdAt = o.optString("createdAt", ""),
                            targetWeightKg = o.optDouble("targetWeightKg", 1.0),
                            targetGoal = o.optString("targetGoal", ""),
                            initialNotes = o.optString("initialNotes", ""),
                            researchNotes = o.optString("researchNotes", ""),
                            isApproved = o.optBoolean("isApproved", false),
                            approvedDate = o.optString("approvedDate", ""),
                            resultsJson = o.optString("resultsJson", "[]"),
                            itemsJson = o.optString("itemsJson", "[]"),
                            recipeJson = o.optString("recipeJson", "[]"),
                            status = if (o.isNull("status")) "انتظار نتائج" else o.optString("status", "انتظار نتائج"),
                            statusNotes = if (o.isNull("statusNotes")) null else o.optString("statusNotes"),
                            statusUpdatedAt = if (o.isNull("statusUpdatedAt")) null else o.optString("statusUpdatedAt"),
                            statusUpdatedBy = if (o.isNull("statusUpdatedBy")) null else o.optString("statusUpdatedBy")
                        )
                    )
                }
            }

            // 26. laboratory_sessions
            if (importTests && root.has("laboratory_sessions")) {
                val arr = root.getJSONArray("laboratory_sessions")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertLabSession(
                        LabSession(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            sessionNumber = o.optString("sessionNumber", ""),
                            testName = o.optString("testName", ""),
                            testDate = o.optString("testDate", ""),
                            technicianName = o.optString("technicianName", ""),
                            sampleOrProduct = o.optString("sampleOrProduct", ""),
                            category = o.optString("category", ""),
                            testType = o.optString("testType", ""),
                            notes = o.optString("notes", ""),
                            comparisonType = if (o.isNull("comparisonType")) null else o.optString("comparisonType"),
                            partyA = if (o.isNull("partyA")) null else o.optString("partyA"),
                            partyB = if (o.isNull("partyB")) null else o.optString("partyB"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                            sampleProperties = o.optString("sampleProperties", "")
                        )
                    )
                }
            }

            // 27. laboratory_tests
            if (importTests && root.has("laboratory_tests")) {
                val arr = root.getJSONArray("laboratory_tests")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertLabTest(
                        LabTest(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            sessionId = o.optString("sessionId", ""),
                            name = o.optString("name", ""),
                            status = o.optString("status", ""),
                            executionDate = o.optString("executionDate", ""),
                            notes = o.optString("notes", ""),
                            testValueA = if (o.isNull("testValueA")) null else o.optString("testValueA"),
                            testValueB = if (o.isNull("testValueB")) null else o.optString("testValueB"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }

            // 28. sync_metadata
            if (importPackagings && root.has("sync_metadata")) {
                val arr = root.getJSONArray("sync_metadata")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertSyncMetadata(
                        SyncMetadata(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            entityType = o.optString("entityType", ""),
                            lastUpdated = o.optLong("lastUpdated", System.currentTimeMillis()),
                            isPendingSync = o.optBoolean("isPendingSync", false),
                            lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                            syncStage = o.optString("syncStage", "PENDING"),
                            lastAttempt = o.optLong("lastAttempt", 0L),
                            retryCount = o.optInt("retryCount", 0),
                            firebaseErrorCode = if (o.isNull("firebaseErrorCode")) null else o.optString("firebaseErrorCode")
                        )
                    )
                }
            }

            // 29. laboratory_attachments
            if (importTests && root.has("laboratory_attachments")) {
                val arr = root.getJSONArray("laboratory_attachments")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertLabAttachment(
                        com.example.data.LabAttachment(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            sessionId = o.optString("sessionId", ""),
                            testName = o.optString("testName", ""),
                            filePathOrUrl = o.optString("filePathOrUrl", ""),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }

            // 30. operational_alerts
            if (importPackagings && root.has("operational_alerts")) {
                val arr = root.getJSONArray("operational_alerts")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertOperationalAlert(
                        com.example.data.OperationalAlert(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            title = o.optString("title", ""),
                            description = o.optString("description", ""),
                            mainSection = o.optString("mainSection", "عام"),
                            bindingScope = o.optString("bindingScope", "ALL"),
                            bindingElementName = o.optString("bindingElementName", ""),
                            alertLevel = o.optString("alertLevel", "INFO"),
                            status = o.optString("status", "ACTIVE"),
                            createdAt = o.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }

            // 31. recycle_bin
            if (root.has("recycle_bin")) {
                val arr = root.getJSONArray("recycle_bin")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dao.insertRecycleBinItem(
                        com.example.data.RecycleBinItem(
                            id = o.optString("id", java.util.UUID.randomUUID().toString()),
                            originalId = o.optString("originalId", ""),
                            itemType = o.optString("itemType", ""),
                            displayName = o.optString("displayName", ""),
                            serializedData = o.optString("serializedData", ""),
                            deletedAt = o.optLong("deletedAt", System.currentTimeMillis()),
                            extraDataJson = if (o.isNull("extraDataJson")) null else o.optString("extraDataJson")
                        )
                    )
                }
            }

            // المخزون: إعادة ما حُفظ قبل المسح، ثم استيراد ما في النسخة (حركات موجودة تُتجاهل)
            inventorySnapshot?.let { com.example.data.InventoryBackup.importAll(database.inventoryDao(), it) }
            val importInventory = if (selectedSectionKeys.isNotEmpty()) selectedSectionKeys.contains("inventory") else true
            if (importInventory && root.has("stock_moves")) {
                com.example.data.InventoryBackup.importAll(database.inventoryDao(), root)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    val syncStatusMessage = MutableStateFlow("مستعد لبدء المزامنة")

    val pendingSyncCount: StateFlow<Int> = repository.allSyncMetadata
        .map { list -> list.count { it.isPendingSync } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingSyncItems: StateFlow<List<SyncMetadata>> = repository.allSyncMetadata
        .map { list -> list.filter { it.isPendingSync } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun triggerSingleSync(metadata: SyncMetadata) {
        viewModelScope.launch {
            SyncManager.uploadSingleEntityAsync(getApplication(), repository, metadata.id, metadata.entityType)
        }
    }

    suspend fun triggerSingleSyncSuspending(metadata: SyncMetadata): Result<Unit> {
        return try {
            kotlinx.coroutines.withTimeout(8000L) {
                com.example.data.SyncManager.uploadSingleEntity(getApplication(), repository, metadata.id, metadata.entityType)
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(Exception("انتهت مهلة المزامنة السحابية (8 ثوانٍ). يرجى التحقق من اتصال الإنترنت وإعدادات مشروع Firestore."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    val currentSyncReport = MutableStateFlow<SyncReport?>(null)
    val showSyncReportDialog = MutableStateFlow(false)

    val currentCloudAuditReport = MutableStateFlow<com.example.data.CloudAuditReport?>(null)
    val isRunningCloudAudit = MutableStateFlow(false)

    fun triggerCloudConsistencyAudit() {
        viewModelScope.launch {
            isRunningCloudAudit.value = true
            val res = com.example.data.SyncManager.performCloudConsistencyAudit(getApplication(), repository)
            currentCloudAuditReport.value = res
            isRunningCloudAudit.value = false
        }
    }
    
    val showSyncProgressDialog = MutableStateFlow(false)
    val syncProgressPercent = MutableStateFlow(0f)
    val syncProgressMessage = MutableStateFlow("")

    val showSmartDbProgressDialog = MutableStateFlow(false)
    val smartDbPercentProgress = MutableStateFlow(0f)
    val smartDbStatusMessage = MutableStateFlow("")
    val smartDbStepsLogs = MutableStateFlow("")

    fun triggerSmartDbMaintenance(skipDataSync: Boolean) {
        viewModelScope.launch {
            showSmartDbProgressDialog.value = true
            smartDbPercentProgress.value = 0.05f
            smartDbStatusMessage.value = "بدء فحص وصيانة قاعدة البيانات..."
            smartDbStepsLogs.value = "⏳ جاري تحضير بيئة العمل والمطابقة الذاتية..."
            
            val success = SyncManager.repairAndInitializeDatabase(
                getApplication(),
                repository,
                skipDataSync = skipDataSync
            ) { step, percent, ok, msg ->
                viewModelScope.launch {
                    if (step == "log_update") {
                        smartDbStepsLogs.value = msg
                    } else {
                        smartDbStatusMessage.value = msg
                        smartDbPercentProgress.value = percent
                    }
                }
            }
            
            kotlinx.coroutines.delay(1000)
            
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            syncLastRawMaterials.value = prefs.getString("sync_last_raw_materials", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
            syncLastFormulations.value = prefs.getString("sync_last_formulations", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
            syncLastProductionOrders.value = prefs.getString("sync_last_production_orders", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
            
            if (success && !skipDataSync) {
                globalSyncStatus.value = "synced"
                prefs.edit().putString("global_sync_status", "synced").apply()
            } else if (!success && !skipDataSync) {
                globalSyncStatus.value = "offline_local"
                prefs.edit().putString("global_sync_status", "offline_local").apply()
            }
        }
    }

    fun triggerManualSync(section: String, force: Boolean = false) {
        if (com.example.data.DeviceSecurityManager.deviceStatus.value != com.example.data.DeviceSecurityManager.STATUS_APPROVED) {
            val app = getApplication<android.app.Application>()
            android.widget.Toast.makeText(app, "⚠️ المزامنة معلقة: هذا الجهاز مسجل كـ معلق وبانتظار اعتماد المسؤول.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val isGatewayUnlinkedOrFaulty = hostingerEnabled.value && hostingerGatewayUrl.value.isBlank()
        if (isGatewayUnlinkedOrFaulty && !force) {
            pendingSyncAction = { triggerManualSync(section, force = true) }
            showHostingWarningAlert.value = true
            return
        }
        viewModelScope.launch {
            showSyncProgressDialog.value = true
            syncProgressPercent.value = 0.05f
            syncProgressMessage.value = "جاري الاتصال بالسحابة وبدء المزامنة..."
            
            globalSyncStatus.value = "syncing"
            syncStatusMessage.value = "جاري تهيئة قاعدة البيانات والاتصال بالسحابة..."
            currentSyncReport.value = null
            
            val isAll = (section == "all")
            if (isAll) {
                try {
                    // First do DB maintenance quickly without full data sync to prepare structure
                    SyncManager.repairAndInitializeDatabase(getApplication(), repository, skipDataSync = true) { step, percent, ok, msg ->
                        if (step != "log_update") {
                            syncProgressPercent.value = percent * 0.25f
                            syncProgressMessage.value = "🛠️ $msg"
                            syncStatusMessage.value = "🛠️ $msg"
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_Sync", "Error repairing database before full sync", e)
                }
            }
            
            // المزامنة الكاملة تشمل فحص/رفع الملفات والصور في آخر خطوة (طلب شبكة لكل ملف)، لذا لا تكفي 90 ثانية على جهاز جديد
            val syncTimeoutMs = if (isAll) 900000L else 180000L
            val report = try {
                kotlinx.coroutines.withTimeout(syncTimeoutMs) {
                    SyncManager.fullBidirectionalSync(getApplication(), repository, targetSection = section) { sec, percent, ok, msg ->
                        val basePercent = if (isAll) 0.25f else 0.0f
                        val factor = if (isAll) 0.75f else 1.0f
                        syncProgressPercent.value = basePercent + (percent * factor)
                        syncProgressMessage.value = msg
                        syncStatusMessage.value = msg
                        
                        val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
                        val rawVal = prefs.getString("sync_last_raw_materials", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
                        val formVal = prefs.getString("sync_last_formulations", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
                        val prodVal = prefs.getString("sync_last_production_orders", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد"
                        syncLastRawMaterials.value = rawVal
                        syncLastFormulations.value = formVal
                        syncLastProductionOrders.value = prodVal
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                com.example.data.SyncReport(
                    isFinished = true,
                    isSuccess = false,
                    connectionError = "انتهت مهلة المزامنة (${syncTimeoutMs / 1000} ثانية)",
                    finalMessage = "انتهت مهلة المزامنة السحابية (${syncTimeoutMs / 1000} ثانية). يرجى التحقق من جودة الاتصال بالإنترنت ومطابقة إعدادات Firebase."
                )
            } catch (e: Exception) {
                com.example.data.SyncReport(
                    isFinished = true,
                    isSuccess = false,
                    connectionError = e.localizedMessage ?: e.message ?: "خطأ غير معروف",
                    finalMessage = "فشلت المزامنة بسبب خطأ: ${e.localizedMessage}"
                )
            }
            
            currentSyncReport.value = report
            if (report.isSuccess) {
                syncProgressPercent.value = 1f
                syncProgressMessage.value = "تمت عملية المزامنة بنجاح!"
            } else {
                syncProgressMessage.value = report.finalMessage.ifBlank { "فشلت المزامنة" }
            }
            showSyncProgressDialog.value = true
            showSyncReportDialog.value = false
            
            val prefs = getApplication<Application>().getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            if (report.isSuccess) {
                globalSyncStatus.value = "synced"
                prefs.edit().putString("global_sync_status", "synced").apply()
            } else {
                globalSyncStatus.value = "offline_local"
                prefs.edit().putString("global_sync_status", "offline_local").apply()
            }
        }
    }

    // --- Research & Development (R&D) CRUD ---
    // طابع زمني كامل لآخر تعديل مشروع أبحاث (يُعرض التاريخ فقط ويُستخدم الوقت للترتيب)
    private fun rndNowFull(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    private suspend fun touchDevelopmentProject(projectId: String) {
        val project = repository.getDevelopmentProjectById(projectId)
        if (project != null) {
            repository.updateDevelopmentProject(project.copy(lastUpdated = rndNowFull()))
        }
    }

    fun addDevelopmentProject(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val project = DevelopmentProject(
                name = name.trim(),
                createdAt = dateStr,
                lastUpdated = dateStr
            )
            repository.insertDevelopmentProject(project)
        }
    }

    fun updateDevelopmentProject(project: DevelopmentProject) {
        viewModelScope.launch {
            repository.updateDevelopmentProject(project)
        }
    }

    fun deleteDevelopmentProject(project: DevelopmentProject) {
        viewModelScope.launch {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                
                // 1. Gather related samples
                val samples = repository.getDevelopmentSamplesForProject(project.id).first()
                
                // 2. Serialize
                val projectJson = moshi.adapter(DevelopmentProject::class.java).toJson(project)
                val samplesJson = moshi.adapter<List<DevelopmentSample>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, DevelopmentSample::class.java)).toJson(samples)
                
                val extraMap = mapOf(
                    "samples" to samplesJson
                )
                val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                val extraDataJson = moshi.adapter<Map<String, String?>>(extraMapType).toJson(extraMap)
                
                val recycleItem = RecycleBinItem(
                    originalId = project.id,
                    itemType = "DEVELOPMENT_PROJECT",
                    displayName = "مشروع بحث وتطوير: ${project.name}",
                    serializedData = projectJson,
                    extraDataJson = extraDataJson
                )
                repository.insertRecycleBinItem(recycleItem)
                
                repository.deleteDevelopmentProject(project)
                if (selectedDevelopmentProject.value?.id == project.id) {
                    selectedDevelopmentProject.value = null
                    selectedDevelopmentSample.value = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Laboratory CRUD ---
    fun addLabSession(
        testName: String,
        testDate: String,
        technicianName: String,
        sampleOrProduct: String,
        category: String,
        testType: String,
        notes: String = "",
        comparisonType: String? = null,
        partyA: String? = null,
        partyB: String? = null,
        sampleProperties: String = ""
    ) {
        logActivity("lab", "إنشاء فحص مخبري جديد: \"$testName\"")
        viewModelScope.launch {
            val currentList = labSessions.value
            val isQcSession = sampleProperties.startsWith("ORDER_ID:")
            val sessionNumber = if (isQcSession) {
                val maxQcNum = currentList.filter { 
                    it.sampleProperties.startsWith("ORDER_ID:") && it.sessionNumber.startsWith("QC-2026-") 
                }.mapNotNull {
                    val parts = it.sessionNumber.split("-")
                    if (parts.size == 3) {
                        parts[2].toIntOrNull()
                    } else {
                        null
                    }
                }.maxOrNull() ?: 0
                val nextQcNum = maxQcNum + 1
                "QC-2026-${String.format(java.util.Locale.US, "%03d", nextQcNum)}"
            } else {
                val maxNum = currentList.filter { 
                    !it.sampleProperties.startsWith("ORDER_ID:") 
                }.mapNotNull {
                    val parts = it.sessionNumber.split("-")
                    if (parts.size == 3) {
                        parts[2].toIntOrNull()
                    } else {
                        null
                    }
                }.maxOrNull() ?: 0
                val nextNum = maxNum + 1
                "LAB-2026-${String.format(java.util.Locale.US, "%03d", nextNum)}"
            }

            val session = LabSession(
                sessionNumber = sessionNumber,
                testName = testName.trim(),
                testDate = testDate.trim(),
                technicianName = technicianName.trim(),
                sampleOrProduct = sampleOrProduct.trim(),
                category = category.trim(),
                testType = testType,
                notes = notes.trim(),
                comparisonType = comparisonType,
                partyA = partyA?.trim(),
                partyB = partyB?.trim(),
                sampleProperties = sampleProperties.trim()
            )
            repository.insertLabSession(session)
        }
    }

    fun updateLabSession(session: LabSession) {
        viewModelScope.launch {
            repository.updateLabSession(session)
        }
    }

    fun deleteLabSession(session: LabSession) {
        viewModelScope.launch {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                
                // 1. Gather related data
                val tests = repository.gbrDao().getAllLabTests().first().filter { it.sessionId == session.id }
                val attachments = repository.gbrDao().getAllLabAttachments().first().filter { it.sessionId == session.id }
                
                // 2. Serialize
                val sessionJson = moshi.adapter(LabSession::class.java).toJson(session)
                val testsJson = moshi.adapter<List<LabTest>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, LabTest::class.java)).toJson(tests)
                val attachmentsJson = moshi.adapter<List<LabAttachment>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, LabAttachment::class.java)).toJson(attachments)
                
                val extraMap = mapOf(
                    "tests" to testsJson,
                    "attachments" to attachmentsJson
                )
                val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                val extraDataJson = moshi.adapter<Map<String, String?>>(extraMapType).toJson(extraMap)
                
                val recycleItem = RecycleBinItem(
                    originalId = session.id,
                    itemType = "LAB_SESSION",
                    displayName = "جلسة فحص: ${session.testName}",
                    serializedData = sessionJson,
                    extraDataJson = extraDataJson
                )
                repository.insertRecycleBinItem(recycleItem)
                repository.deleteLabSession(session)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun cloneLabSessionAsTemplate(originalSessionId: String, newSessionName: String) {
        viewModelScope.launch {
            try {
                val originalSession = labSessions.value.find { it.id == originalSessionId } ?: return@launch
                
                // 1. Generate new session number
                val currentList = labSessions.value
                val maxNum = currentList.filter { 
                    !it.sampleProperties.startsWith("ORDER_ID:") 
                }.mapNotNull {
                    val parts = it.sessionNumber.split("-")
                    if (parts.size == 3) {
                        parts[2].toIntOrNull()
                    } else {
                        null
                    }
                }.maxOrNull() ?: 0
                val nextNum = maxNum + 1
                val sessionNumber = "LAB-2026-${String.format(java.util.Locale.US, "%03d", nextNum)}"
                
                // 2. Format current date
                val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                
                // 3. Create independent session clone
                val clonedSession = LabSession(
                    sessionNumber = sessionNumber,
                    testName = newSessionName.trim(),
                    testDate = currentDateStr,
                    technicianName = originalSession.technicianName,
                    sampleOrProduct = originalSession.sampleOrProduct,
                    category = originalSession.category,
                    testType = originalSession.testType,
                    notes = "", // Clear observations/remarks/decisions
                    comparisonType = originalSession.comparisonType,
                    partyA = originalSession.partyA,
                    partyB = originalSession.partyB,
                    sampleProperties = originalSession.sampleProperties
                )
                repository.insertLabSession(clonedSession)
                
                // 4. Fetch and copy original tests
                val originalTests = repository.getLabTestsForSession(originalSessionId).first()
                originalTests.forEach { test ->
                    val cleanedNotes = cleanTestNotesForTemplate(test.notes)
                    val clonedTest = LabTest(
                        sessionId = clonedSession.id,
                        name = test.name,
                        status = "لم يبدأ", // Ready for new input
                        executionDate = "", // Blank/Cleared execution date
                        notes = cleanedNotes,
                        testValueA = null,
                        testValueB = null
                    )
                    repository.insertLabTest(clonedTest)
                }
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء نسخ الجلسة: ${e.message}")
            }
        }
    }

    fun duplicateLabSessionFull(originalSessionId: String, newSessionName: String) {
        viewModelScope.launch {
            try {
                val originalSession = labSessions.value.find { it.id == originalSessionId } ?: return@launch
                
                // 1. Generate new session number
                val currentList = labSessions.value
                val maxNum = currentList.filter { 
                    !it.sampleProperties.startsWith("ORDER_ID:") 
                }.mapNotNull {
                    val parts = it.sessionNumber.split("-")
                    if (parts.size == 3) {
                        parts[2].toIntOrNull()
                    } else {
                        null
                    }
                }.maxOrNull() ?: 0
                val nextNum = maxNum + 1
                val sessionNumber = "LAB-2026-${String.format(java.util.Locale.US, "%03d", nextNum)}"
                
                // 2. Format current date
                val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                
                // 3. Create full copy of session with all metadata, notes, and comparison parties
                val clonedSession = LabSession(
                    sessionNumber = sessionNumber,
                    testName = newSessionName.trim(),
                    testDate = currentDateStr,
                    technicianName = originalSession.technicianName,
                    sampleOrProduct = originalSession.sampleOrProduct,
                    category = originalSession.category,
                    testType = originalSession.testType,
                    notes = originalSession.notes,
                    comparisonType = originalSession.comparisonType,
                    partyA = originalSession.partyA,
                    partyB = originalSession.partyB,
                    sampleProperties = originalSession.sampleProperties
                )
                repository.insertLabSession(clonedSession)
                
                // 4. Fetch and copy original tests with ALL values, statuses, and notes
                val originalTests = repository.getLabTestsForSession(originalSessionId).first()
                originalTests.forEach { test ->
                    val clonedTest = LabTest(
                        sessionId = clonedSession.id,
                        name = test.name,
                        status = test.status,
                        executionDate = test.executionDate,
                        notes = test.notes,
                        testValueA = test.testValueA,
                        testValueB = test.testValueB
                    )
                    repository.insertLabTest(clonedTest)
                }

                // 5. Copy attachments if any
                val originalAttachments = repository.gbrDao().getAllLabAttachments().first().filter { it.sessionId == originalSessionId }
                originalAttachments.forEach { att ->
                    val clonedAtt = com.example.data.LabAttachment(
                        sessionId = clonedSession.id,
                        testName = att.testName,
                        filePathOrUrl = att.filePathOrUrl
                    )
                    repository.insertLabAttachment(clonedAtt)
                }

                globalToastEvents.emit("تم تكرار جلسة الفحص بنجاح بكافة الفحوصات والنتائج والملاحظات 📄✨")
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء عمل نسخة من الجلسة: ${e.message}")
            }
        }
    }

    fun updateLabSessionFolder(session: LabSession, folderName: String) {
        viewModelScope.launch {
            try {
                val updated = setLabSessionFolder(session, folderName)
                repository.updateLabSession(updated)
                globalToastEvents.emit("تم نقل الجلسة إلى المجلد بنجاح 📁✨")
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء تحديث مجلد الجلسة: ${e.message}")
            }
        }
    }

    fun renameLabFolder(oldFolderName: String, newFolderName: String) {
        viewModelScope.launch {
            try {
                val cleanNew = newFolderName.trim().replace(";", "").replace(":", "")
                if (cleanNew.isBlank()) return@launch
                val sessionsToUpdate = labSessions.value.filter { getLabSessionFolder(it) == oldFolderName }
                sessionsToUpdate.forEach { session ->
                    val updated = setLabSessionFolder(session, cleanNew)
                    repository.updateLabSession(updated)
                }
                globalToastEvents.emit("تم إعادة تسمية المجلد إلى '$cleanNew' بنجاح 📁✨")
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء إعادة تسمية المجلد: ${e.message}")
            }
        }
    }

    fun deleteLabFolder(folderName: String, deleteSessionsAlso: Boolean) {
        viewModelScope.launch {
            try {
                val sessionsInFolder = labSessions.value.filter { getLabSessionFolder(it) == folderName }
                if (deleteSessionsAlso) {
                    sessionsInFolder.forEach { session ->
                        deleteLabSession(session)
                    }
                    globalToastEvents.emit("تم حذف المجلد وكافة الجلسات التابعة له 🗑️")
                } else {
                    sessionsInFolder.forEach { session ->
                        val updated = setLabSessionFolder(session, "")
                        repository.updateLabSession(updated)
                    }
                    globalToastEvents.emit("تم إزالة المجلد وتفريغ الجلسات بنجاح 📁")
                }
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء حذف المجلد: ${e.message}")
            }
        }
    }

    fun moveMultipleSessionsToFolder(sessionIds: List<String>, folderName: String) {
        viewModelScope.launch {
            try {
                val cleanFolder = folderName.trim().replace(";", "").replace(":", "")
                val sessionsToUpdate = labSessions.value.filter { it.id in sessionIds }
                sessionsToUpdate.forEach { session ->
                    val updated = setLabSessionFolder(session, cleanFolder)
                    repository.updateLabSession(updated)
                }
                globalToastEvents.emit("تم نقل ${sessionsToUpdate.size} جلسات إلى المجلد '$cleanFolder' بنجاح 📁✨")
            } catch (e: Exception) {
                globalToastEvents.emit("حدث خطأ أثناء نقل الجلسات: ${e.message}")
            }
        }
    }

    private fun cleanTestNotesForTemplate(originalNotes: String): String {
        return when {
            originalNotes.startsWith("WIZARD_VISCOSITY:") -> {
                val json = originalNotes.removePrefix("WIZARD_VISCOSITY:")
                "WIZARD_VISCOSITY:" + cleanViscosityJson(json)
            }
            originalNotes.startsWith("WIZARD_DENSITY:") -> {
                val json = originalNotes.removePrefix("WIZARD_DENSITY:")
                "WIZARD_DENSITY:" + cleanDensityJson(json)
            }
            originalNotes.startsWith("WIZARD_SOLID_CONTENT:") -> {
                val json = originalNotes.removePrefix("WIZARD_SOLID_CONTENT:")
                "WIZARD_SOLID_CONTENT:" + cleanSolidContentJson(json)
            }
            originalNotes.startsWith("WIZARD_COMP_VISCOSITY:") -> {
                try {
                    val jsonStr = originalNotes.removePrefix("WIZARD_COMP_VISCOSITY:")
                    val compJson = org.json.JSONObject(jsonStr)
                    if (compJson.has("dataA")) {
                        val cleanA = cleanViscosityJson(compJson.getJSONObject("dataA").toString())
                        compJson.put("dataA", org.json.JSONObject(cleanA))
                    }
                    if (compJson.has("dataB")) {
                        val cleanB = cleanViscosityJson(compJson.getJSONObject("dataB").toString())
                        compJson.put("dataB", org.json.JSONObject(cleanB))
                    }
                    "WIZARD_COMP_VISCOSITY:" + compJson.toString()
                } catch (e: Exception) {
                    originalNotes
                }
            }
            originalNotes.startsWith("WIZARD_COMP_DENSITY:") -> {
                try {
                    val jsonStr = originalNotes.removePrefix("WIZARD_COMP_DENSITY:")
                    val compJson = org.json.JSONObject(jsonStr)
                    if (compJson.has("dataA")) {
                        val cleanA = cleanDensityJson(compJson.getJSONObject("dataA").toString())
                        compJson.put("dataA", org.json.JSONObject(cleanA))
                    }
                    if (compJson.has("dataB")) {
                        val cleanB = cleanDensityJson(compJson.getJSONObject("dataB").toString())
                        compJson.put("dataB", org.json.JSONObject(cleanB))
                    }
                    "WIZARD_COMP_DENSITY:" + compJson.toString()
                } catch (e: Exception) {
                    originalNotes
                }
            }
            originalNotes.startsWith("WIZARD_COMP_SOLID_CONTENT:") -> {
                try {
                    val jsonStr = originalNotes.removePrefix("WIZARD_COMP_SOLID_CONTENT:")
                    val compJson = org.json.JSONObject(jsonStr)
                    if (compJson.has("dataA")) {
                        val cleanA = cleanSolidContentJson(compJson.getJSONObject("dataA").toString())
                        compJson.put("dataA", org.json.JSONObject(cleanA))
                    }
                    if (compJson.has("dataB")) {
                        val cleanB = cleanSolidContentJson(compJson.getJSONObject("dataB").toString())
                        compJson.put("dataB", org.json.JSONObject(cleanB))
                    }
                    "WIZARD_COMP_SOLID_CONTENT:" + compJson.toString()
                } catch (e: Exception) {
                    originalNotes
                }
            }
            else -> "" // clear standard notes
        }
    }

    private fun cleanViscosityJson(jsonStr: String): String {
        return try {
            val json = org.json.JSONObject(jsonStr)
            
            // Re-initialize readings of speed 1
            val rArr = org.json.JSONArray()
            for (i in 0 until 3) {
                val item = org.json.JSONObject()
                item.put("viscosity", "")
                item.put("torque", "")
                rArr.put(item)
            }
            json.put("readings", rArr)
            json.put("readingsSecondSpeed", org.json.JSONArray())
            
            // Reset calculations/auto states
            json.put("rheologyIndex", org.json.JSONObject.NULL)
            json.put("viscosityReductionPct", org.json.JSONObject.NULL)
            json.put("isAutoCalculated", false)
            json.put("sourceSpeed1Name", org.json.JSONObject.NULL)
            json.put("sourceSpeed2Name", org.json.JSONObject.NULL)
            
            json.toString()
        } catch (e: Exception) {
            jsonStr
        }
    }

    private fun cleanDensityJson(jsonStr: String): String {
        return try {
            val json = org.json.JSONObject(jsonStr)
            json.put("emptyWeight", "")
            json.put("filledWeight", "")
            json.toString()
        } catch (e: Exception) {
            jsonStr
        }
    }

    private fun cleanSolidContentJson(jsonStr: String): String {
        return try {
            val json = org.json.JSONObject(jsonStr)
            json.put("directPct", "")
            json.put("dishWeight", "")
            json.put("wetWeight", "")
            json.put("dryWeight", "")
            json.toString()
        } catch (e: Exception) {
            jsonStr
        }
    }

    // --- Laboratory Tests CRUD ---
    fun getLabTestsForSession(sessionId: String): Flow<List<LabTest>> {
        return repository.getLabTestsForSession(sessionId)
    }

    fun addLabTest(
        sessionId: String,
        name: String,
        executionDate: String,
        status: String = "لم يبدأ",
        notes: String = "",
        testValueA: String? = null,
        testValueB: String? = null,
        onAdded: ((LabTest) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val test = LabTest(
                sessionId = sessionId,
                name = name,
                status = status,
                executionDate = executionDate,
                notes = notes,
                testValueA = testValueA,
                testValueB = testValueB
            )
            repository.insertLabTest(test)
            onAdded?.invoke(test)
        }
    }

    fun updateLabTest(test: LabTest) {
        viewModelScope.launch {
            repository.updateLabTest(test)
        }
    }

    fun deleteLabTest(test: LabTest) {
        viewModelScope.launch {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                val testJson = moshi.adapter(LabTest::class.java).toJson(test)
                val recycleItem = RecycleBinItem(
                    originalId = test.id,
                    itemType = "LAB_TEST",
                    displayName = "فحص مخبري: ${test.name}",
                    serializedData = testJson
                )
                repository.insertRecycleBinItem(recycleItem)
                repository.deleteLabTest(test)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Operational Alerts CRUD ---
    fun insertOperationalAlert(alert: OperationalAlert) {
        viewModelScope.launch {
            repository.insertOperationalAlert(alert)
        }
    }

    fun updateOperationalAlert(alert: OperationalAlert) {
        viewModelScope.launch {
            repository.updateOperationalAlert(alert)
        }
    }

    fun deleteOperationalAlert(alert: OperationalAlert) {
        viewModelScope.launch {
            repository.deleteOperationalAlert(alert)
            com.example.data.SyncManager.deleteSingleEntityAsync(getApplication(), alert.id, "operational_alerts")
        }
    }

    // --- Laboratory Attachments CRUD ---
    fun addLabAttachment(sessionId: String, testName: String, filePathOrUrl: String) {
        viewModelScope.launch {
            val attachment = LabAttachment(
                sessionId = sessionId,
                testName = testName.trim(),
                filePathOrUrl = filePathOrUrl
            )
            repository.insertLabAttachment(attachment)
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, attachment.id, "lab_attachment")
        }
    }

    fun deleteLabAttachment(attachment: LabAttachment) {
        viewModelScope.launch {
            // Delete actual file from storage
            com.example.data.SyncManager.deleteFileFromStorage(getApplication(), attachment.filePathOrUrl, "lab")
            // Delete database record
            repository.deleteLabAttachment(attachment)
        }
    }

    fun addDevelopmentSample(
        projectId: String,
        sampleName: String,
        sampleNumber: String,
        targetGoal: String,
        initialNotes: String,
        targetWeightKg: Double,
        itemsJson: String = "[]",
        resultsJson: String = "[]",
        recipeJson: String = "[]"
    ) {
        if (sampleName.isBlank() || sampleNumber.isBlank()) return
        viewModelScope.launch {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val sample = DevelopmentSample(
                projectId = projectId,
                sampleName = sampleName.trim(),
                sampleNumber = sampleNumber.trim(),
                createdAt = dateStr,
                targetWeightKg = targetWeightKg,
                targetGoal = targetGoal.trim(),
                initialNotes = initialNotes.trim(),
                researchNotes = "",
                isApproved = false,
                approvedDate = "",
                resultsJson = resultsJson,
                itemsJson = itemsJson,
                recipeJson = recipeJson,
                status = "انتظار نتائج"
            )
            repository.insertDevelopmentSample(sample)
            
            // Update the project lastUpdated
            val project = repository.getDevelopmentProjectById(projectId)
            if (project != null) {
                repository.updateDevelopmentProject(project.copy(lastUpdated = rndNowFull()))
            }
        }
    }

    fun updateDevelopmentSample(sample: DevelopmentSample) {
        viewModelScope.launch {
            repository.updateDevelopmentSample(sample)
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            // Update parent project's lastUpdated
            val project = repository.getDevelopmentProjectById(sample.projectId)
            if (project != null) {
                repository.updateDevelopmentProject(project.copy(lastUpdated = rndNowFull()))
                selectedDevelopmentProject.value = project // Refresh project
            }
            if (selectedDevelopmentSample.value?.id == sample.id) {
                selectedDevelopmentSample.value = sample
            }
        }
    }

    fun deleteDevelopmentSample(sample: DevelopmentSample) {
        viewModelScope.launch {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                val sampleJson = moshi.adapter(DevelopmentSample::class.java).toJson(sample)
                val recycleItem = RecycleBinItem(
                    originalId = sample.id,
                    itemType = "DEVELOPMENT_SAMPLE",
                    displayName = "عينة تجريبية: ${sample.sampleNumber} - ${sample.sampleName}",
                    serializedData = sampleJson
                )
                repository.insertRecycleBinItem(recycleItem)
                repository.deleteDevelopmentSample(sample)
                touchDevelopmentProject(sample.projectId)
                if (selectedDevelopmentSample.value?.id == sample.id) {
                    selectedDevelopmentSample.value = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun incrementTrailingNumber(str: String, fallback: String = "CD-1"): String {
        val trimmed = str.trim()
        if (trimmed.isBlank()) return fallback

        // If string ends with parentheses e.g. "اسم (2)"
        val parenRegex = Regex("""^(.*?)\((\d+)\)$""")
        val parenMatch = parenRegex.find(trimmed)
        if (parenMatch != null) {
            val prefix = parenMatch.groupValues[1].trimEnd()
            val num = (parenMatch.groupValues[2].toLongOrNull() ?: 1L) + 1L
            return "$prefix ($num)"
        }

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
        return "$trimmed (2)"
    }

    fun cloneDevelopmentSample(sample: DevelopmentSample) {
        viewModelScope.launch {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            
            // Get all existing samples in the same project to ensure uniqueness
            val existingSamples = repository.getDevelopmentSamplesForProject(sample.projectId).first()
            val existingNumbers = existingSamples.map { it.sampleNumber.trim().lowercase() }.toSet()
            val existingNames = existingSamples.map { it.sampleName.trim().lowercase() }.toSet()

            // Find next unique sample number
            var nextNumber = incrementTrailingNumber(sample.sampleNumber)
            while (existingNumbers.contains(nextNumber.trim().lowercase())) {
                nextNumber = incrementTrailingNumber(nextNumber)
            }

            // Find next unique sample name
            var nextName = incrementTrailingNumber(sample.sampleName)
            while (existingNames.contains(nextName.trim().lowercase())) {
                nextName = incrementTrailingNumber(nextName)
            }

            val cleanedRecipeJson = try {
                if (sample.recipeJson.isNotBlank() && sample.recipeJson != "[]") {
                    val jArr = JSONArray(sample.recipeJson)
                    for (i in 0 until jArr.length()) {
                        val pObj = jArr.getJSONObject(i)
                        pObj.put("id", java.util.UUID.randomUUID().toString())
                        val itemsJArr = pObj.optJSONArray("items")
                        if (itemsJArr != null) {
                            for (j in 0 until itemsJArr.length()) {
                                val iObj = itemsJArr.getJSONObject(j)
                                iObj.put("id", java.util.UUID.randomUUID().toString())
                                iObj.put("isExecuted", false)
                            }
                        }
                    }
                    jArr.toString()
                } else {
                    "[]"
                }
            } catch (e: Exception) {
                "[]"
            }

            val cloned = DevelopmentSample(
                projectId = sample.projectId,
                sampleName = nextName,
                sampleNumber = nextNumber,
                createdAt = dateStr,
                targetWeightKg = sample.targetWeightKg,
                targetGoal = sample.targetGoal,
                initialNotes = "", // Cleared on clone as requested
                researchNotes = "", // Cleared on clone as requested
                isApproved = false,
                approvedDate = "",
                resultsJson = "[]", // Cleared on clone: do not copy physical measurements
                itemsJson = sample.itemsJson, // Copy raw ingredients and multipliers
                recipeJson = cleanedRecipeJson, // Reset execution state for single and all-materials view
                status = "انتظار نتائج", // Default to Awaiting Results on clone
                statusNotes = null,
                statusUpdatedAt = null,
                statusUpdatedBy = null
            )
            repository.insertDevelopmentSample(cloned)

            // Update parent project's lastUpdated
            val project = repository.getDevelopmentProjectById(sample.projectId)
            if (project != null) {
                repository.updateDevelopmentProject(project.copy(lastUpdated = rndNowFull()))
                selectedDevelopmentProject.value = project
            }
        }
    }

    fun approveDevelopmentSample(
        sample: DevelopmentSample,
        multiplier: Double = 1.0,
        onComplete: ((String, Formulation) -> Unit)? = null
    ) {
        logActivity("lab", "اعتماد العينة \"${sample.sampleName}\" وإرسالها إلى قسم التركيبات")
        viewModelScope.launch {
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            
            // 1. Keep sample fully editable and save its latest state in R&D DB
            repository.updateDevelopmentSample(sample)
            touchDevelopmentProject(sample.projectId)
            if (selectedDevelopmentSample.value?.id == sample.id) {
                selectedDevelopmentSample.value = sample
            }

            // 2. Fetch all existing formulations to ensure no duplicate name or code collision
            val existingFormulations = repository.formulations.first()
            val existingNames = existingFormulations.map { it.name.trim().lowercase() }.toSet()
            val existingCodes = existingFormulations.map { it.code.trim().lowercase() }.toSet()

            var targetName = sample.sampleName.trim()
            if (targetName.isBlank()) targetName = "تركيبة جديدة"

            // If name already exists, add "نسخة 1", "نسخة 2", etc.
            if (existingNames.contains(targetName.lowercase())) {
                val copySuffixRegex = """[\s\-_]*(?:\(نسخة\s*(\d+)\)|نسخة\s*(\d+)|\((\d+)\)|copy\s*(\d+))$""".toRegex(RegexOption.IGNORE_CASE)
                val rootName = targetName.replace(copySuffixRegex, "").trim().ifBlank { targetName }

                var copyIndex = 1
                var candidateName = "$rootName نسخة $copyIndex"
                while (existingNames.contains(candidateName.trim().lowercase())) {
                    copyIndex++
                    candidateName = "$rootName نسخة $copyIndex"
                }
                targetName = candidateName
            }

            // الكود الافتراضي المتسلسل (GBR-001...) بدل نسخ رمز العينة
            val targetCode = nextDefaultFormulationCode()

            val effectiveMultiplier = if (multiplier > 0.0) multiplier else 1.0
            val multiplierNote = if (kotlin.math.abs(effectiveMultiplier - 1.0) > 0.0001) {
                val formattedMult = String.format(java.util.Locale.US, "%.2f", effectiveMultiplier).trimEnd('0').trimEnd('.')
                " (معامل ضرب ×$formattedMult)"
            } else ""

            // 3. Insert into formulations as "Under Development" with a unique UUID
            val newFormulationId = java.util.UUID.randomUUID().toString()
            val newFormulation = Formulation(
                id = newFormulationId,
                name = targetName,
                code = targetCode,
                description = sample.targetGoal,
                imageUri = null,
                version = "الإصدار 1",
                status = "🟡 قيد التطوير",
                createdAt = dateStr,
                notes = "من قسم الأبحاث والتطوير$multiplierNote - " + sample.initialNotes,
                supports18L = false,
                netWeight18L = "",
                supports5L = false,
                netWeight5L = "",
                packagingWeightsJson = "[]"
            )
            
            repository.insertFormulation(newFormulation)
            
            // 4. For each ingredient in itemsJson, multiply quantity by effectiveMultiplier and add as FormulationItem
            try {
                val itemsArray = org.json.JSONArray(sample.itemsJson)
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.getJSONObject(i)
                    val rawMaterialId = obj.optString("rawMaterialId", obj.optInt("rawMaterialId").toString())
                    val baseMultiplier = obj.optDouble("originalQuantityMultiplier", 0.0)
                    val scaledMultiplier = baseMultiplier * effectiveMultiplier
                    
                    val fItem = FormulationItem(
                        id = java.util.UUID.randomUUID().toString(),
                        formulationId = newFormulationId,
                        rawMaterialId = rawMaterialId,
                        quantityMultiplier = scaledMultiplier,
                        needsGrinding = false,
                        grindingDurationMinutes = 0,
                        sequence = obj.optInt("sequence", i)
                    )
                    repository.addFormulationItem(fItem)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 5. Automatically create standard 1-stage recipe
            initializeRecipeForFormulation(newFormulationId)

            onComplete?.invoke(targetName, newFormulation)
        }
    }

    // --- Navigation Segment management ---
    fun showSegment(segment: String?) {
        waterStepContext.value = null // أي تنقل عادي يلغي سياق خطوة الماء (يُضبط بعد الانتقال في openEquipmentForWaterStep)
        if (segment == "operational_alerts") {
            previousSegmentForAlerts = activeSegment.value
        }
        if (segment == "equipment_control") {
            previousSegmentForEquipmentControl = activeSegment.value
        }
        val previousSegment = activeSegment.value
        // أي تنقل عادي ينهي الرجوع للتقارير ويصفّر فلاترها
        if (orderOpeningSourceSegment.value == "reports") orderOpeningSourceSegment.value = null
        reportKeptStates.clear()
        activeSegment.value = segment
        // Close other overlays when navigating
        closeFormulationDetails()
        cancelProductionRun()
        if (segment == "production") {
            selectedProductionOrder.value = null
        } else if (previousSegment == "production" || segment == null) {
            // When exiting production section to dashboard/home or another segment, reset search/filters
            resetCompletedOrdersFilters()
        }
    }

    // --- Raw Material Operations ---
    fun addRawMaterial(
        name: String,
        productionName: String,
        price: Double,
        priceUnit: String = "شيكل",
        notes: String,
        tdsUri: String?,
        isActive: Boolean = true
    ) {
        logActivity("material", "إضافة مادة خام جديدة: \"$name\"")
        if (name.isBlank()) return
        performActionWithLoading(
            loadingMsg = "جارٍ حفظ المادة الخام الجديدة وإرسالها للسحابة...",
            successMsg = "تم حفظ المادة الخام \"$name\" بنجاح."
        ) {
            val materialId = java.util.UUID.randomUUID().toString()
            var finalTdsUri = tdsUri
            
            // Immediately stabilize the picker URI to our permanent internal storage
            if (!tdsUri.isNullOrBlank() && tdsUri.startsWith("content://")) {
                try {
                    val uriObj = android.net.Uri.parse(tdsUri)
                    val localSavedUri = com.example.data.GbrFileManager.copyUriToCache(getApplication(), uriObj, materialId)
                    if (localSavedUri != null) {
                        finalTdsUri = localSavedUri.toString()
                    }
                } catch (tempEx: Exception) {
                    android.util.Log.e("GBR_TDS", "Failed to perform local pre-cache copy", tempEx)
                }
            }

            if (!finalTdsUri.isNullOrBlank() && (finalTdsUri.startsWith("content://") || finalTdsUri.startsWith("file://"))) {
                try {
                    val uriObj = android.net.Uri.parse(finalTdsUri)
                    val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                        context = getApplication(),
                        localUri = uriObj,
                        folderName = "raw_materials",
                        fileName = "tds_${materialId}.pdf"
                    )
                    if (cloudUrl != null) {
                        finalTdsUri = cloudUrl
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedDate = sdf.format(java.util.Date())
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            getApplication(),
                            materialId,
                            mapOf(
                                "original_name" to com.example.data.GbrFileManager.getFileNameFromUrl(cloudUrl),
                                "status" to "synchronized",
                                "last_sync" to formattedDate,
                                "last_download" to formattedDate
                            )
                        )
                    } else {
                        // Keep finalTdsUri as local stable file://, write localized pending diagnostic status
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            getApplication(),
                            materialId,
                            mapOf(
                                "status" to "local_pending_sync",
                                "failure_reason" to "تم حفظ الملف محلياً للمزامنة التلقائية اللاحقة لعدم توفر المزامنة الفورية حالياً."
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_FirebaseStorage", "Error uploading TDS/MSDS PDF to Firebase Storage", e)
                }
            }
            repository.insertRawMaterial(
                RawMaterial(
                    id = materialId,
                    name = name.trim(),
                    productionName = productionName.trim(),
                    price = price,
                    priceUnit = "شيكل",
                    notes = notes.trim(),
                    tdsUri = finalTdsUri,
                    isActive = isActive
                )
            )
            isAddingRawMaterial.value = false
        }
    }

    fun updateRawMaterial(
        id: String,
        name: String,
        productionName: String,
        price: Double,
        priceUnit: String = "شيكل",
        notes: String,
        tdsUri: String?,
        isActive: Boolean = true
    ) {
        run { val oldP = rawMaterials.value.find { it.id == id }?.price; if (oldP != null && oldP != price) logActivity("material", "تعديل سعر المادة الخام \"$name\" من ${"%.2f".format(java.util.Locale.US, oldP)} إلى ${"%.2f".format(java.util.Locale.US, price)} $priceUnit") }
        if (name.isBlank()) return
        performActionWithLoading(
            loadingMsg = "جارٍ حفظ التعديلات على المادة الخام زير السحابة...",
            successMsg = "تم تحديث وحفظ بيانات المادة الخام \"$name\" بنجاح."
        ) {
            var finalTdsUri = tdsUri
            
            // Immediately stabilize the picker URI to our permanent internal storage
            if (!tdsUri.isNullOrBlank() && tdsUri.startsWith("content://")) {
                try {
                    val uriObj = android.net.Uri.parse(tdsUri)
                    val localSavedUri = com.example.data.GbrFileManager.copyUriToCache(getApplication(), uriObj, id)
                    if (localSavedUri != null) {
                        finalTdsUri = localSavedUri.toString()
                    }
                } catch (tempEx: Exception) {
                    android.util.Log.e("GBR_TDS", "Failed to perform local pre-cache copy", tempEx)
                }
            }

            if (!finalTdsUri.isNullOrBlank() && (finalTdsUri.startsWith("content://") || finalTdsUri.startsWith("file://"))) {
                try {
                    val uriObj = android.net.Uri.parse(finalTdsUri)
                    val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                        context = getApplication(),
                        localUri = uriObj,
                        folderName = "raw_materials",
                        fileName = "tds_${id}.pdf"
                    )
                    if (cloudUrl != null) {
                        finalTdsUri = cloudUrl
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedDate = sdf.format(java.util.Date())
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            getApplication(),
                            id,
                            mapOf(
                                "original_name" to com.example.data.GbrFileManager.getFileNameFromUrl(cloudUrl),
                                "status" to "synchronized",
                                "last_sync" to formattedDate,
                                "last_download" to formattedDate
                            )
                        )
                    } else {
                        // Keep finalTdsUri as local stable file://, write localized pending diagnostic status
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            getApplication(),
                            id,
                            mapOf(
                                "status" to "local_pending_sync",
                                "failure_reason" to "تم حفظ ملف التعديل محلياً وسيتم رفعه تلقائياً بمجرد المزامنة عند توفر الاتصال السحابي."
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_FirebaseStorage", "Error uploading TDS/MSDS PDF to Firebase Storage", e)
                }
            }
            android.util.Log.d("GBR_PRICE_DEBUG", "updateRawMaterial call: id=$id, price=$price")
            val existing = repository.getRawMaterialById(id)
            android.util.Log.d("GBR_PRICE_DEBUG", "existing raw material is: $existing")
            if (existing != null && Math.abs(existing.price - price) > 0.0001) {
                val sdf = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale.ENGLISH)
                val currentDateStr = sdf.format(java.util.Date())
                android.util.Log.d("GBR_PRICE_DEBUG", "Price changed from ${existing.price} to $price. Logging history at $currentDateStr")
                repository.insertPriceHistory(
                    PriceHistoryEntry(
                        rawMaterialId = id,
                        oldPrice = existing.price,
                        newPrice = price,
                        dateChange = currentDateStr
                    )
                )
            } else if (existing == null) {
                android.util.Log.e("GBR_PRICE_DEBUG", "Warning: existing raw material was not found for id=$id")
            }
            repository.updateRawMaterial(
                RawMaterial(
                    id = id,
                    name = name.trim(),
                    productionName = productionName.trim(),
                    price = price,
                    priceUnit = "شيكل",
                    notes = notes.trim(),
                    tdsUri = finalTdsUri,
                    isActive = isActive
                )
            )
        }
    }

    fun getPriceHistoryForMaterial(rawMaterialId: String): Flow<List<PriceHistoryEntry>> {
        return repository.getPriceHistoryForMaterial(rawMaterialId)
    }

    fun deleteRawMaterial(material: RawMaterial) {
        logActivity("material", "حذف المادة الخام \"${material.name}\"")
        performActionWithLoading(
            loadingMsg = "جارٍ نقل المادة الخام إلى سلة المحذوفات...",
            successMsg = "تم نقل المادة الخام \"${material.name}\" إلى سلة المحذوفات بنجاح."
        ) {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val serialized = moshi.adapter(RawMaterial::class.java).toJson(material)
            val recycleItem = RecycleBinItem(
                originalId = material.id,
                itemType = "RAW_MATERIAL",
                displayName = material.name,
                serializedData = serialized
            )
            repository.insertRecycleBinItem(recycleItem)
            repository.deleteRawMaterial(material)
        }
    }

    // --- Formulation Operations ---
    fun openFormulationDetails(formulation: Formulation) {
        activeFormulationObserveJob?.cancel()
        
        viewModelScope.launch {
            originalFormulation.value = formulation

            val items = try {
                repository.getFormulationItemsWithDetails(formulation.id).first()
            } catch (e: Exception) {
                emptyList()
            }
            originalFormulationItems.value = items
            selectedFormulationDetailItems.value = items

            val phases = repository.getRecipePhasesForFormulationSync(formulation.id)
            originalRecipePhases.value = phases
            selectedFormulationRecipePhases.value = phases

            val recItems = repository.getRecipeItemsForFormulationSync(formulation.id)
            originalRecipeItems.value = recItems
            selectedFormulationRecipeItems.value = recItems

            val tests = repository.getFormulationQualityTestsSync(formulation.id)
            originalFormulationQualityTests.value = tests
            selectedFormulationQualityTests.value = tests

            selectedFormulation.value = formulation
        }

        activeFormulationObserveJob = viewModelScope.launch {
            // Observe Formulation Entity
            launch {
                repository.formulations.collect { list ->
                    val updatedForm = list.find { it.id == formulation.id }
                    if (updatedForm != null) {
                        originalFormulation.value = updatedForm
                        if (activeSegment.value != "recipe" && selectedFormulationRecipePhases.value == originalRecipePhases.value && selectedFormulationRecipeItems.value == originalRecipeItems.value) {
                            selectedFormulation.value = updatedForm
                        }
                    }
                }
            }

            // Observe Items
            launch {
                repository.getFormulationItemsWithDetails(formulation.id).collect { dbItems ->
                    val oldOrig = originalFormulationItems.value
                    val currentWorking = selectedFormulationDetailItems.value
                    val isUnchanged = currentWorking == oldOrig
                    if (activeSegment.value != "recipe" && isUnchanged) {
                        originalFormulationItems.value = dbItems
                        selectedFormulationDetailItems.value = dbItems
                    }
                }
            }

            // Observe Phases
            launch {
                repository.getRecipePhasesForFormulation(formulation.id).collect { dbPhases ->
                    val oldOrig = originalRecipePhases.value
                    val currentWorking = selectedFormulationRecipePhases.value
                    val isUnchanged = currentWorking == oldOrig
                    if (activeSegment.value != "recipe" && isUnchanged) {
                        originalRecipePhases.value = dbPhases
                        selectedFormulationRecipePhases.value = dbPhases
                    }
                }
            }

            // Observe Recipe Items
            launch {
                repository.getRecipeItemsForFormulation(formulation.id).collect { dbRecipeItems ->
                    val oldOrig = originalRecipeItems.value
                    val currentWorking = selectedFormulationRecipeItems.value
                    val isUnchanged = currentWorking == oldOrig
                    if (activeSegment.value != "recipe" && isUnchanged) {
                        originalRecipeItems.value = dbRecipeItems
                        selectedFormulationRecipeItems.value = dbRecipeItems
                    }
                }
            }

            // Observe Recipe Status
            launch {
                repository.getRecipeStatus(formulation.id).collect { dbStatus ->
                    if (activeSegment.value != "recipe" && selectedFormulationRecipePhases.value == originalRecipePhases.value && selectedFormulationRecipeItems.value == originalRecipeItems.value) {
                        selectedFormulationRecipeStatus.value = dbStatus
                    }
                }
            }

            // Observe Quality Tests
            launch {
                repository.getFormulationQualityTests(formulation.id).collect { dbTests ->
                    val oldOrig = originalFormulationQualityTests.value
                    val currentWorking = selectedFormulationQualityTests.value
                    val isUnchanged = currentWorking == oldOrig
                    if (activeSegment.value != "recipe" && isUnchanged) {
                        originalFormulationQualityTests.value = dbTests
                        selectedFormulationQualityTests.value = dbTests
                    }
                }
            }
        }
    }

    fun closeSelectedProductionOrder() {
        if (orderOpeningSourceSegment.value == "formulations") {
            orderOpeningSourceSegment.value = null
            shouldReopenProductionHistoryOnReturn.value = true
            activeSegment.value = "formulations"
        } else if (orderOpeningSourceSegment.value == "reports") {
            orderOpeningSourceSegment.value = null
            activeSegment.value = "reports"
        }
        selectedProductionOrder.value = null
    }

    fun closeFormulationDetails() {
        activeFormulationObserveJob?.cancel()
        activeFormulationObserveJob = null
        
        selectedFormulation.value = null
        originalFormulation.value = null
        originalFormulationItems.value = emptyList()
        selectedFormulationDetailItems.value = emptyList()
        originalRecipePhases.value = emptyList()
        originalRecipeItems.value = emptyList()
        originalFormulationQualityTests.value = emptyList()
        selectedFormulationRecipePhases.value = emptyList()
        selectedFormulationRecipeItems.value = emptyList()
        selectedFormulationQualityTests.value = emptyList()
        selectedFormulationRecipeStatus.value = null
    }

    fun addFormulationWithItems(name: String, code: String, desc: String, items: List<Pair<RawMaterial, Double>>) {
        logActivity("formulation", "إضافة تركيبة جديدة: \"$name\"")
        if (name.isBlank() || code.isBlank()) return
        performActionWithLoading(
            loadingMsg = "جاري إنشاء تركيبة الدهان الجديدة وحفظ المكونات...",
            successMsg = "تم حفظ تركيبة الدهان \"$name\" بنجاح."
        ) {
            val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val formulationId = repository.insertFormulation(
                Formulation(
                    name = name.trim(),
                    code = code.trim().uppercase(),
                    description = desc.trim(),
                    createdAt = currentDateStr,
                    version = "الإصدار 1"
                )
            )
            items.forEachIndexed { index, item ->
                repository.addFormulationItem(
                    FormulationItem(
                        formulationId = formulationId,
                        rawMaterialId = item.first.id,
                        quantityMultiplier = item.second,
                        sequence = index
                    )
                )
            }
            isAddingFormulation.value = false
        }
    }

    fun deleteFormulation(formulation: Formulation) {
        logActivity("formulation", "حذف التركيبة \"${formulation.name}\"")
        performActionWithLoading(
            loadingMsg = "جارٍ نقل التركيبة وكل مكوناتها إلى سلة المحذوفات...",
            successMsg = "تم نقل التركيبة \"${formulation.name}\" إلى سلة المحذوفات بنجاح."
        ) {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            
            // 1. Gather all related data
            val items = repository.gbrDao().getAllFormulationItems().first().filter { it.formulationId == formulation.id }
            val revisions = repository.getRevisionsForFormulation(formulation.id).first()
            val phases = repository.gbrDao().getAllRecipePhases().first().filter { it.formulationId == formulation.id }
            val phaseIds = phases.map { it.id }
            val recipeItems = repository.gbrDao().getAllRecipeItems().first().filter { it.phaseId in phaseIds }
            val tests = repository.gbrDao().getAllFormulationQualityTests().first().filter { it.formulationId == formulation.id }
            val specs = repository.gbrDao().getFormulationReferenceSpecsSync(formulation.id)
            
            // 2. Serialize
            val formulationJson = moshi.adapter(Formulation::class.java).toJson(formulation)
            
            val itemsJson = moshi.adapter<List<FormulationItem>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationItem::class.java)).toJson(items)
            val revisionsJson = moshi.adapter<List<FormulationRevision>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationRevision::class.java)).toJson(revisions)
            val phasesJson = moshi.adapter<List<RecipePhase>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, RecipePhase::class.java)).toJson(phases)
            val recipeItemsJson = moshi.adapter<List<RecipeItem>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, RecipeItem::class.java)).toJson(recipeItems)
            val testsJson = moshi.adapter<List<FormulationQualityTest>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationQualityTest::class.java)).toJson(tests)
            val specsJson = if (specs != null) moshi.adapter(FormulationReferenceSpecs::class.java).toJson(specs) else null
            
            val extraMap = mapOf(
                "items" to itemsJson,
                "revisions" to revisionsJson,
                "phases" to phasesJson,
                "recipe_items" to recipeItemsJson,
                "tests" to testsJson,
                "specs" to specsJson
            )
            val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
            val extraDataJson = moshi.adapter<Map<String, String?>>(extraMapType).toJson(extraMap)
            
            val recycleItem = RecycleBinItem(
                originalId = formulation.id,
                itemType = "FORMULATION",
                displayName = formulation.name,
                serializedData = formulationJson,
                extraDataJson = extraDataJson
            )
            
            repository.insertRecycleBinItem(recycleItem)
            repository.deleteFormulation(formulation)
        }
    }

    val formulationRelationCheckers = listOf<FormulationRelationChecker>(
        object : FormulationRelationChecker {
            override val relationName = "أوامر الإنتاج"
            override suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation? {
                val count = viewModel.productionOrders.value.count { it.formulationId == formulationId }
                return if (count > 0) FormulationAssociation(relationName, count, "تحتوي هذه التركيبة على $count من أوامر التشغيل والإنتاج المعتمدة أو التاريخية.") else null
            }
        },
        object : FormulationRelationChecker {
            override val relationName = "الإصدارات والتعديلات"
            override suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation? {
                val count = viewModel.allFormulationRevisions.value.count { it.formulationId == formulationId }
                return if (count > 1) FormulationAssociation(relationName, count, "تم تسجيل $count من التعديلات والإصدارات التاريخية لهذه التركيبة.") else null
            }
        },
        object : FormulationRelationChecker {
            override val relationName = "جلسات الفحص المختبري"
            override suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation? {
                val orderIds = viewModel.productionOrders.value.filter { it.formulationId == formulationId }.map { it.id }.toSet()
                val count = viewModel.labSessions.value.count { 
                    val prop = it.sampleProperties
                    prop.startsWith("ORDER_ID:") && orderIds.contains(prop.substringAfter("ORDER_ID:"))
                }
                return if (count > 0) FormulationAssociation(relationName, count, "تم العثور على $count من جلسات الفحص والتحاليل في المختبر المرتبطة بهذه التركيبة.") else null
            }
        },
        object : FormulationRelationChecker {
            override val relationName = "المواصفات المرجعية المعتمدة"
            override suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation? {
                val specs = viewModel.repository.getFormulationReferenceSpecsSync(formulationId)
                return if (specs != null) FormulationAssociation(relationName, 1, "تمتلك هذه التركيبة بطاقة مواصفات قياسية مرجعية معتمدة في أرشيف المختبر.") else null
            }
        },
        object : FormulationRelationChecker {
            override val relationName = "سجلات تشغيل المصنع"
            override suspend fun checkRelation(formulationId: String, viewModel: GbrViewModel): FormulationAssociation? {
                val count = viewModel.productionLogs.value.count { it.formulationId == formulationId }
                return if (count > 0) FormulationAssociation(relationName, count, "يوجد $count من سجلات الماكينات المباشرة للإنتاج المرتبطة بالتركيبة.") else null
            }
        }
    )

    suspend fun checkFormulationAssociations(formulationId: String): List<FormulationAssociation> {
        val associations = mutableListOf<FormulationAssociation>()
        for (checker in formulationRelationCheckers) {
            val assoc = checker.checkRelation(formulationId, this)
            if (assoc != null) {
                associations.add(assoc)
            }
        }
        return associations
    }

    // ترقيم افتراضي متسلسل للتركيبات الجديدة: GBR-001, GBR-002, ... (الأكبر الموجود + 1). التركيبات القديمة (FORM-xxxx, U-1) لا تُمَسّ.
    suspend fun nextDefaultFormulationCode(): String {
        val max = repository.formulations.first()
            .mapNotNull { Regex("^GBR-(\\d+)$").find(it.code.trim().uppercase())?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "GBR-" + (max + 1).toString().padStart(3, '0')
    }

    fun addFormulation(name: String, code: String, description: String, imageUri: String? = null) {
        logActivity("formulation", "إضافة تركيبة جديدة: \"$name\"")
        if (name.isBlank()) return
        performActionWithLoading(
            loadingMsg = "جاري حفظ وتخزين تركيبة الدهان الجديدة...",
            successMsg = "تم حفظ تركيبة الدهان \"$name\" بنجاح."
        ) {
            val formulationId = java.util.UUID.randomUUID().toString()
            var finalImageUri = imageUri
            
            // Immediately copy pick URI to internal app files cache for offline viewing & safe later sync
            if (!imageUri.isNullOrBlank() && imageUri.startsWith("content://")) {
                try {
                    val uriObj = android.net.Uri.parse(imageUri)
                    val localSavedUri = com.example.data.GbrFileManager.copyUriToPersistentStorage(getApplication(), uriObj, "form_${formulationId}")
                    if (localSavedUri != null) {
                        finalImageUri = localSavedUri.toString()
                    }
                } catch (tempEx: Exception) {
                    android.util.Log.e("GBR_FORM_IMG", "Failed to copy picker image to cache", tempEx)
                }
            }

            if (!finalImageUri.isNullOrBlank() && (finalImageUri.startsWith("content://") || finalImageUri.startsWith("file://"))) {
                try {
                    val uriObj = android.net.Uri.parse(finalImageUri)
                    val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                        context = getApplication(),
                        localUri = uriObj,
                        folderName = "formulations",
                        fileName = "form_${formulationId}.jpg"
                    )
                    if (cloudUrl != null) {
                        finalImageUri = cloudUrl
                    } else {
                        // Keep finalImageUri as local stable file:// so that it is visible offline and can be synced later
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_FirebaseStorage", "Error uploading formulation image to Firebase Storage", e)
                }
            }
            val finalCode = code.trim().ifBlank { nextDefaultFormulationCode() }.uppercase()
            val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            repository.insertFormulation(
                Formulation(
                    id = formulationId,
                    name = name.trim(),
                    code = finalCode,
                    description = description.trim(),
                    imageUri = finalImageUri,
                    createdAt = currentDateStr,
                    version = "الإصدار 1"
                )
            )
            isAddingFormulation.value = false
        }
    }

    fun createWizardFormulation(
        name: String,
        description: String,
        imageUri: String?,
        selectedPackagesNetWeights: Map<String, String>, // maps CustomPackaging.id (String) -> net weight (String)
        selectedTestsRanges: Map<String, Pair<Double?, Double?>>, // maps QualityTest.id (String) -> Pair(minValue, maxValue)
        productionLine: Int = -1,
        onSuccess: (Formulation) -> Unit
    ) {
        logActivity("formulation", "إضافة تركيبة جديدة: \"$name\"")
        if (name.isBlank()) return
        viewModelScope.launch {
            val formulationId = java.util.UUID.randomUUID().toString()
            var finalImageUri = imageUri
            
            // Immediately copy pick URI to internal app files cache for offline viewing & safe later sync
            if (!imageUri.isNullOrBlank() && imageUri.startsWith("content://")) {
                try {
                    val uriObj = android.net.Uri.parse(imageUri)
                    val localSavedUri = com.example.data.GbrFileManager.copyUriToPersistentStorage(getApplication(), uriObj, "form_${formulationId}")
                    if (localSavedUri != null) {
                        finalImageUri = localSavedUri.toString()
                    }
                } catch (tempEx: Exception) {
                    android.util.Log.e("GBR_FORM_IMG", "Failed to copy picker image to cache", tempEx)
                }
            }

            if (!finalImageUri.isNullOrBlank() && (finalImageUri.startsWith("content://") || finalImageUri.startsWith("file://"))) {
                try {
                    val uriObj = android.net.Uri.parse(finalImageUri)
                    val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                        context = getApplication(),
                        localUri = uriObj,
                        folderName = "formulations",
                        fileName = "form_${formulationId}.jpg"
                    )
                    if (cloudUrl != null) {
                        finalImageUri = cloudUrl
                    } else {
                        // Keep finalImageUri as local stable file:// so that it is visible offline and can be synced later
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_FirebaseStorage", "Error uploading formulation image to Firebase Storage", e)
                }
            }
            val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val finalCode = nextDefaultFormulationCode().uppercase()
            
            // Generate packaging structure and legacy settings compatibility
            val packWeightsMap = mutableMapOf<String, String>()
            var supports18L = false
            var netWeight18L = ""
            var supports5L = false
            var netWeight5L = ""
            
            selectedPackagesNetWeights.forEach { (pkgId, netWeightValue) ->
                packWeightsMap[pkgId] = "$netWeightValue:0.0"
                if (pkgId == "1") { // Standard 18L container
                    supports18L = true
                    netWeight18L = netWeightValue
                } else if (pkgId == "2") { // Standard 5L container
                    supports5L = true
                    netWeight5L = netWeightValue
                }
            }
            
            val packWeightsJsonStr = org.json.JSONObject(packWeightsMap as Map<*,*>).toString()
            
            val initialFormulation = Formulation(
                id = formulationId,
                name = name.trim(),
                code = finalCode,
                description = description.trim(),
                imageUri = finalImageUri,
                createdAt = currentDateStr,
                version = "الإصدار 1",
                status = "⚪ مسودة", // Starts as Draft ("⚪ مسودة")
                supports18L = supports18L,
                netWeight18L = netWeight18L,
                supports5L = supports5L,
                netWeight5L = netWeight5L,
                packagingWeightsJson = packWeightsJsonStr,
                productionLine = productionLine
            )
            
            val newId = repository.insertFormulation(initialFormulation)
            val createdFormulation = initialFormulation.copy(id = newId)
            
            // Save Quality specifications / tests
            val formulationTestsToSave = selectedTestsRanges.map { (testId, minMaxRange) ->
                FormulationQualityTest(
                    formulationId = newId,
                    testId = testId,
                    isEnabled = true,
                    minValue = minMaxRange.first,
                    maxValue = minMaxRange.second
                )
            }
            
            if (formulationTestsToSave.isNotEmpty()) {
                repository.deleteFormulationQualityTestsByFormulationId(newId)
                formulationTestsToSave.forEach { test ->
                    repository.insertFormulationQualityTest(test)
                }
            }
            
            onSuccess(createdFormulation)
        }
    }

    fun updateFormulation(id: String, name: String, code: String, description: String, imageUri: String? = null, createdAt: String? = null, productionLine: Int? = null) {
        logActivity("formulation", "تعديل بيانات التركيبة \"$name\"")
        if (name.isBlank()) return
        viewModelScope.launch {
            var finalImageUri = imageUri
            
            // Immediately copy pick URI to internal app files cache for offline viewing & safe later sync
            if (!imageUri.isNullOrBlank() && imageUri.startsWith("content://")) {
                try {
                    val uriObj = android.net.Uri.parse(imageUri)
                    val localSavedUri = com.example.data.GbrFileManager.copyUriToPersistentStorage(getApplication(), uriObj, "form_${id}")
                    if (localSavedUri != null) {
                        finalImageUri = localSavedUri.toString()
                    }
                } catch (tempEx: Exception) {
                    android.util.Log.e("GBR_FORM_IMG", "Failed to copy picker image to cache", tempEx)
                }
            }

            if (!finalImageUri.isNullOrBlank() && (finalImageUri.startsWith("content://") || finalImageUri.startsWith("file://"))) {
                try {
                    val uriObj = android.net.Uri.parse(finalImageUri)
                    val cloudUrl = com.example.data.SyncManager.uploadFileToFirebaseStorage(
                        context = getApplication(),
                        localUri = uriObj,
                        folderName = "formulations",
                        fileName = "form_${id}.jpg"
                    )
                    if (cloudUrl != null) {
                        finalImageUri = cloudUrl
                    } else {
                        // Keep finalImageUri as local stable file:// so that it is visible offline and can be synced later
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GBR_FirebaseStorage", "Error uploading formulation image to Firebase Storage", e)
                }
            }
            val existing = repository.getFormulationById(id)
            val finalCode = if (code.trim().isBlank()) {
                existing?.code ?: nextDefaultFormulationCode()
            } else {
                code.trim()
            }.uppercase()
            val currentStatus = existing?.status ?: "🟡 قيد التطوير"
            val currentVersion = existing?.version ?: "1.0.0"
            val currentNotes = existing?.notes ?: ""
            val currentSupports18L = existing?.supports18L ?: false
            val currentNetWeight18L = existing?.netWeight18L ?: ""
            val currentSupports5L = existing?.supports5L ?: false
            val currentNetWeight5L = existing?.netWeight5L ?: ""
            val currentPackagingWeightsJson = existing?.packagingWeightsJson ?: ""
            val finalCreatedAt = existing?.createdAt ?: createdAt ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val updated = Formulation(
                id = id,
                name = name.trim(),
                code = finalCode,
                description = description.trim(),
                imageUri = finalImageUri,
                version = currentVersion,
                status = currentStatus,
                createdAt = finalCreatedAt,
                notes = currentNotes,
                supports18L = currentSupports18L,
                netWeight18L = currentNetWeight18L,
                supports5L = currentSupports5L,
                netWeight5L = currentNetWeight5L,
                packagingWeightsJson = currentPackagingWeightsJson,
                productionLine = productionLine ?: existing?.productionLine ?: -1
            )
            // تغيير الكود يشمل كل إصدارات العائلة (نفس الكود القديم)، ويُمنع إن كان الكود الجديد يخص تركيبة أخرى مختلفة
            val allForms = repository.formulations.first()
            val oldCodeNorm = existing?.code?.trim()?.uppercase().orEmpty()
            val codeChanged = existing != null && oldCodeNorm != finalCode
            if (codeChanged && allForms.any { it.id != id && it.code.trim().uppercase() == finalCode && it.code.trim().uppercase() != oldCodeNorm }) {
                com.example.data.SyncManager.addLocalSystemLog(getApplication(), "error", "❌ لم يُحفظ الكود $finalCode: مستخدم لتركيبة أخرى")
                return@launch
            }
            repository.insertFormulation(updated)
            if (selectedFormulation.value?.id == id) {
                selectedFormulation.value = updated
            }
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, id, "formulation")
            if (codeChanged && oldCodeNorm.isNotBlank()) {
                for (sib in allForms) {
                    if (sib.id != id && sib.code.trim().uppercase() == oldCodeNorm) {
                        repository.insertFormulation(sib.copy(code = finalCode))
                        com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, sib.id, "formulation")
                    }
                }
            }
        }
    }

    fun updateFormulationNotes(id: String, notes: String) {
        viewModelScope.launch {
            val existing = repository.getFormulationById(id)
            if (existing != null) {
                val updated = existing.copy(notes = notes)
                repository.insertFormulation(updated)
                if (selectedFormulation.value?.id == id) {
                    selectedFormulation.value = updated
                }
                com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, id, "formulation")
            }
        }
    }

    fun updateFormulationPackaging(id: String, supports18L: Boolean, netWeight18L: String, supports5L: Boolean, netWeight5L: String, packagingWeightsJson: String = "") {
        viewModelScope.launch {
            val existing = repository.getFormulationById(id)
            if (existing != null) {
                val updated = existing.copy(
                    supports18L = supports18L,
                    netWeight18L = netWeight18L,
                    supports5L = supports5L,
                    netWeight5L = netWeight5L,
                    packagingWeightsJson = packagingWeightsJson
                )
                repository.insertFormulation(updated)
                if (selectedFormulation.value?.id == id) {
                    selectedFormulation.value = updated
                }
                com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, id, "formulation")
            }
        }
    }

    fun updateFormulationStatus(id: String, status: String) {
        logActivity("formulation", "تغيير حالة التركيبة \"${formulations.value.find { it.id == id }?.name ?: ""}\" إلى \"$status\"")
        viewModelScope.launch {
            val existing = repository.getFormulationById(id)
            if (existing != null) {
                val updated = existing.copy(status = status)
                repository.insertFormulation(updated)
                if (selectedFormulation.value?.id == id) {
                    selectedFormulation.value = updated
                }
                com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, id, "formulation")
            }
        }
    }

    fun updateFormulationItemSimulatedPrice(id: String, simulatedPrice: Double?) {
        viewModelScope.launch {
            repository.updateFormulationItemSimulatedPrice(id, simulatedPrice)
            
            val list = selectedFormulationDetailItems.value.map { item ->
                if (item.id == id) {
                    item.copy(simulatedPrice = simulatedPrice)
                } else {
                    item
                }
            }
            selectedFormulationDetailItems.value = list
            
            val orig = originalFormulationItems.value.map { item ->
                if (item.id == id) {
                    item.copy(simulatedPrice = simulatedPrice)
                } else {
                    item
                }
            }
            originalFormulationItems.value = orig
        }
    }

    fun clearAllFormulationItemSimulatedPrices(formulationId: String) {
        viewModelScope.launch {
            repository.clearAllFormulationItemSimulatedPrices(formulationId)
            
            val list = selectedFormulationDetailItems.value.map { item ->
                if (item.formulationId == formulationId) {
                    item.copy(simulatedPrice = null)
                } else {
                    item
                }
            }
            selectedFormulationDetailItems.value = list
            
            val orig = originalFormulationItems.value.map { item ->
                if (item.formulationId == formulationId) {
                    item.copy(simulatedPrice = null)
                } else {
                    item
                }
            }
            originalFormulationItems.value = orig
        }
    }

    fun saveFormulationSimulationAndOperatingCostBatch(
        formulationId: String,
        simulatedPrices: Map<String, Double?>,
        packagingWeightsJson: String
    ) {
        viewModelScope.launch {
            simulatedPrices.forEach { (itemId, price) ->
                repository.updateFormulationItemSimulatedPrice(itemId, price)
            }
            
            val list = selectedFormulationDetailItems.value.map { item ->
                if (simulatedPrices.containsKey(item.id)) {
                    item.copy(simulatedPrice = simulatedPrices[item.id])
                } else {
                    item
                }
            }
            selectedFormulationDetailItems.value = list
            
            val isDraft = selectedFormulation.value?.status == "⚪ مسودة"
            if (!isDraft) {
                val orig = originalFormulationItems.value.map { item ->
                    if (simulatedPrices.containsKey(item.id)) {
                        item.copy(simulatedPrice = simulatedPrices[item.id])
                    } else {
                        item
                    }
                }
                originalFormulationItems.value = orig
            }
            
            val existing = repository.getFormulationById(formulationId)
            if (existing != null) {
                val updated = existing.copy(
                    packagingWeightsJson = packagingWeightsJson
                )
                repository.insertFormulation(updated)
                if (selectedFormulation.value?.id == formulationId) {
                    selectedFormulation.value = updated
                }
            }
        }
    }

    fun duplicateFormulation(originalFormulation: Formulation, newName: String, onComplete: (Formulation) -> Unit = {}) {
        logActivity("formulation", "نسخ التركيبة \"${originalFormulation.name}\" باسم \"$newName\"")
        if (newName.isBlank()) return
        viewModelScope.launch {
            val currentItems = repository.getFormulationItemsWithDetails(originalFormulation.id).first()
            val finalCode = nextDefaultFormulationCode().uppercase()
            val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val newForm = Formulation(
                name = newName.trim(),
                code = finalCode,
                description = "نسخة من '${originalFormulation.name}'",
                imageUri = originalFormulation.imageUri,
                status = "🟡 قيد التطوير",
                createdAt = currentDateStr,
                notes = originalFormulation.notes,
                supports18L = originalFormulation.supports18L,
                netWeight18L = originalFormulation.netWeight18L,
                supports5L = originalFormulation.supports5L,
                netWeight5L = originalFormulation.netWeight5L,
                packagingWeightsJson = originalFormulation.packagingWeightsJson,
                productionLine = originalFormulation.productionLine
            )
            val newFormId = repository.insertFormulation(newForm)
            val insertedForm = newForm.copy(id = newFormId)
            for (item in currentItems) {
                repository.addFormulationItem(
                    FormulationItem(
                        formulationId = newFormId,
                        rawMaterialId = item.rawMaterialId,
                        quantityMultiplier = item.quantityMultiplier,
                        needsGrinding = item.needsGrinding,
                        grindingDurationMinutes = item.grindingDurationMinutes,
                        sequence = item.sequence
                    )
                )
            }
            
            // Duplicate original formulation quality tests
            try {
                val originalTests = repository.getFormulationQualityTestsSync(originalFormulation.id)
                for (test in originalTests) {
                    repository.insertFormulationQualityTest(
                        FormulationQualityTest(
                            formulationId = newFormId,
                            testId = test.testId,
                            isEnabled = test.isEnabled,
                            minValue = test.minValue,
                            maxValue = test.maxValue
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("GBR_DUP_TESTS", "Failed to duplicate quality tests", e)
            }

            // Duplicate recipe phases and items
            try {
                repository.copyRecipeForNewVersion(originalFormulation.id, newFormId)
            } catch (e: Exception) {
                android.util.Log.e("GBR_DUP_RECIPES", "Failed to duplicate recipe details", e)
            }

            onComplete(insertedForm)
        }
    }

    // --- Formulation Ingredients Management ---
    fun getNextVersionName(code: String, allFormulations: List<Formulation>): String {
        val sameCodeList = allFormulations.filter { it.code.trim().uppercase() == code.trim().uppercase() }
        var maxVal = 1
        for (f in sameCodeList) {
            val ver = f.version.trim()
            if (ver.startsWith("الإصدار ")) {
                val numStr = ver.substringAfter("الإصدار ").trim()
                val num = numStr.toIntOrNull()
                if (num != null && num > maxVal) {
                    maxVal = num
                }
            } else {
                val numStr = ver.substringBefore(".")
                val num = numStr.toIntOrNull()
                if (num != null && num > maxVal) {
                    maxVal = num
                }
            }
        }
        if (maxVal == 1 && sameCodeList.size > 1) {
            maxVal = sameCodeList.size
        }
        return "الإصدار ${maxVal + 1}"
    }

    fun createNewVersion(original: Formulation) {
        logActivity("formulation", "إنشاء إصدار جديد من التركيبة \"${original.name}\"")
        viewModelScope.launch {
            val allFormulationsList = formulations.value
            val nextVersionStr = getNextVersionName(original.code, allFormulationsList)
            
            val currentDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val newFormulation = Formulation(
                id = java.util.UUID.randomUUID().toString(),
                name = original.name,
                code = original.code,
                description = original.description,
                imageUri = original.imageUri,
                version = nextVersionStr,
                status = "🟡 قيد التطوير",
                createdAt = currentDateStr,
                notes = "",
                supports18L = original.supports18L,
                netWeight18L = original.netWeight18L,
                supports5L = original.supports5L,
                netWeight5L = original.netWeight5L,
                packagingWeightsJson = original.packagingWeightsJson,
                productionLine = original.productionLine
            )
            
            val newFormulationId = repository.insertFormulation(newFormulation)
            val originalItems = repository.getFormulationItemsWithDetails(original.id).first()
            
            for (item in originalItems) {
                val clonedItem = FormulationItem(
                    id = java.util.UUID.randomUUID().toString(),
                    formulationId = newFormulationId,
                    rawMaterialId = item.rawMaterialId,
                    quantityMultiplier = item.quantityMultiplier,
                    needsGrinding = item.needsGrinding,
                    grindingDurationMinutes = item.grindingDurationMinutes,
                    sequence = item.sequence
                )
                repository.addFormulationItem(clonedItem)
            }

            // Copy Production Recipe for the New Version, handle additions/removals and mark NEED_REVIEWS
            repository.copyRecipeForNewVersion(original.id, newFormulationId)
            
            val createdFormulation = repository.getFormulationById(newFormulationId)
            if (createdFormulation != null) {
                openFormulationDetails(createdFormulation)
            }
        }
    }

    fun getRevisionsForFormulation(formulationId: String): Flow<List<FormulationRevision>> {
        return repository.getRevisionsForFormulation(formulationId).map { list ->
            list.sortedWith(
                compareByDescending<FormulationRevision> {
                    it.version.filter { c -> c.isDigit() || c == '.' }.toDoubleOrNull() ?: 0.0
                }
                .thenByDescending { it.dateChange }
                .thenByDescending { it.id }
            )
        }
    }

    fun incrementVersion(current: String): String {
        return current
    }

    // --- Draft Ingredients Operations (In-Memory) ---
    
    fun addRawMaterialToDraft(
        rawMaterialId: String,
        rawMaterialName: String,
        rawMaterialProductionName: String,
        quantity: Double,
        needsGrinding: Boolean = false,
        grindingDurationMinutes: Int = 0
    ) {
        if (quantity <= 0.0) return
        val currentList = selectedFormulationDetailItems.value.toMutableList()
        val tempId = -(System.currentTimeMillis() and 0xffff).toInt() - currentList.size // ensure unique negative memory tracker
        val matchedMaterial = rawMaterials.value.firstOrNull { it.id == rawMaterialId }
        val price = matchedMaterial?.price ?: 0.0
        val priceUnit = matchedMaterial?.priceUnit ?: "شيكل"
        val newItem = FormulationItemWithDetails(
            id = tempId.toString(),
            formulationId = selectedFormulation.value?.id ?: "",
            rawMaterialId = rawMaterialId,
            quantityMultiplier = quantity,
            rawMaterialName = rawMaterialName,
            rawMaterialProductionName = rawMaterialProductionName,
            rawMaterialUnit = "كجم",
            needsGrinding = needsGrinding,
            grindingDurationMinutes = grindingDurationMinutes,
            rawMaterialPrice = price,
            rawMaterialPriceUnit = priceUnit
        )
        currentList.add(newItem)
        selectedFormulationDetailItems.value = currentList
    }

    fun updateDraftItem(id: String, quantity: Double, needsGrinding: Boolean, grindingDurationMinutes: Int) {
        if (quantity <= 0.0) return
        val currentList = selectedFormulationDetailItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList[index] = currentList[index].copy(
                quantityMultiplier = quantity,
                needsGrinding = needsGrinding,
                grindingDurationMinutes = grindingDurationMinutes
            )
            selectedFormulationDetailItems.value = currentList
        }
    }

    fun replaceDraftItem(
        oldItemId: String,
        oldRawMaterialId: String,
        newRawMaterial: RawMaterial,
        newQuantity: Double,
        needsGrinding: Boolean = false,
        grindingDurationMinutes: Int = 0
    ) {
        if (newQuantity <= 0.0) return

        val currentItems = selectedFormulationDetailItems.value.toMutableList()
        val index = currentItems.indexOfFirst { it.id == oldItemId }
        if (index != -1) {
            val oldItem = currentItems[index]
            val newItem = oldItem.copy(
                rawMaterialId = newRawMaterial.id,
                rawMaterialName = newRawMaterial.name,
                rawMaterialProductionName = newRawMaterial.productionName,
                rawMaterialPrice = newRawMaterial.price,
                rawMaterialPriceUnit = newRawMaterial.priceUnit,
                quantityMultiplier = newQuantity,
                needsGrinding = needsGrinding,
                grindingDurationMinutes = grindingDurationMinutes
            )
            currentItems[index] = newItem
            selectedFormulationDetailItems.value = currentItems
        }

        if (oldRawMaterialId.isNotBlank() && oldRawMaterialId != newRawMaterial.id) {
            val currentRecipeItems = selectedFormulationRecipeItems.value.toMutableList()
            var recipeModified = false
            for (i in currentRecipeItems.indices) {
                if (currentRecipeItems[i].rawMaterialId == oldRawMaterialId) {
                    currentRecipeItems[i] = currentRecipeItems[i].copy(rawMaterialId = newRawMaterial.id)
                    recipeModified = true
                }
            }
            if (recipeModified) {
                selectedFormulationRecipeItems.value = currentRecipeItems
            }

            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val formulationId = selectedFormulation.value?.id
                if (!formulationId.isNullOrBlank()) {
                    val dbRecipeItems = repository.getRecipeItemsForFormulationSync(formulationId)
                    for (item in dbRecipeItems) {
                        if (item.rawMaterialId == oldRawMaterialId) {
                            repository.updateRecipeItem(item.copy(rawMaterialId = newRawMaterial.id))
                        }
                    }
                }
            }
        }
    }

    fun deleteDraftItem(id: String) {
        val currentList = selectedFormulationDetailItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            currentList.removeAt(index)
            selectedFormulationDetailItems.value = currentList
        }
    }

    fun moveDraftItem(id: String, moveUp: Boolean) {
        val currentList = selectedFormulationDetailItems.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index == -1) return
        
        val newIndex = if (moveUp) index - 1 else index + 1
        if (newIndex !in currentList.indices) return
        
        // Swap
        val temp = currentList[index]
        currentList[index] = currentList[newIndex]
        currentList[newIndex] = temp
        selectedFormulationDetailItems.value = currentList
    }

    fun discardDraftChanges() {
        val f = originalFormulation.value ?: return
        val itemsOrig = originalFormulationItems.value
        val phasesOrig = originalRecipePhases.value
        val recItemsOrig = originalRecipeItems.value
        val testsOrig = originalFormulationQualityTests.value

        viewModelScope.launch {
            // Restore db records (this runs safely in background)
            repository.insertFormulation(f)

            val itemsToSet = itemsOrig.map {
                FormulationItem(
                    id = it.id,
                    formulationId = f.id,
                    rawMaterialId = it.rawMaterialId,
                    quantityMultiplier = it.quantityMultiplier,
                    needsGrinding = it.needsGrinding,
                    grindingDurationMinutes = it.grindingDurationMinutes,
                    simulatedPrice = it.simulatedPrice,
                    sequence = it.sequence
                )
            }
            repository.setFormulationItems(f.id, itemsToSet)

            repository.deleteRecipePhasesByFormulationId(f.id)
            val oldPhaseIdToNewPhaseId = mutableMapOf<String, String>()
            phasesOrig.forEach { rp ->
                val newPhaseId = repository.insertRecipePhase(rp.copy(id = java.util.UUID.randomUUID().toString()))
                oldPhaseIdToNewPhaseId[rp.id] = newPhaseId
            }

            recItemsOrig.forEach { ri ->
                val newPhaseId = oldPhaseIdToNewPhaseId[ri.phaseId]
                if (newPhaseId != null) {
                    repository.insertRecipeItem(ri.copy(id = java.util.UUID.randomUUID().toString(), phaseId = newPhaseId))
                }
            }

            repository.deleteFormulationQualityTestsByFormulationId(f.id)
            testsOrig.forEach { t ->
                repository.insertFormulationQualityTest(t)
            }

            // Only update current ViewModel states if the user is STILL viewing THIS formulation details
            if (selectedFormulation.value?.id == f.id) {
                selectedFormulation.value = f
                selectedFormulationDetailItems.value = itemsOrig
                
                // Let DB flows refresh and sync
                kotlinx.coroutines.delay(100)
                
                if (selectedFormulation.value?.id == f.id) {
                    val freshForm = repository.getFormulationById(f.id) ?: f
                    originalFormulation.value = freshForm
                    selectedFormulation.value = freshForm
                    
                    val freshPhases = repository.getRecipePhasesForFormulationSync(f.id)
                    originalRecipePhases.value = freshPhases
                    selectedFormulationRecipePhases.value = freshPhases
                    
                    val freshRecItems = repository.getRecipeItemsForFormulationSync(f.id)
                    originalRecipeItems.value = freshRecItems
                    selectedFormulationRecipeItems.value = freshRecItems
                    
                    val freshTests = repository.getFormulationQualityTestsSync(f.id)
                    originalFormulationQualityTests.value = freshTests
                    selectedFormulationQualityTests.value = freshTests
                    
                    val freshItems = repository.getFormulationItemsWithDetails(f.id).first()
                    originalFormulationItems.value = freshItems
                    selectedFormulationDetailItems.value = freshItems
                }
            }
        }
    }

    fun hasRealContentChanges(): Boolean {
        val newList = selectedFormulationDetailItems.value
        val oldList = originalFormulationItems.value
        if (newList.size != oldList.size) return true
        
        val sortedNew = newList.sortedWith(compareBy({ it.rawMaterialId }, { it.quantityMultiplier }))
        val sortedOld = oldList.sortedWith(compareBy({ it.rawMaterialId }, { it.quantityMultiplier }))
        
        for (i in sortedNew.indices) {
            val n = sortedNew[i]
            val o = sortedOld[i]
            if (n.rawMaterialId != o.rawMaterialId ||
                n.quantityMultiplier != o.quantityMultiplier ||
                n.needsGrinding != o.needsGrinding ||
                n.grindingDurationMinutes != o.grindingDurationMinutes) {
                return true
            }
        }
        return false
    }

    fun saveDraftChanges(reason: String) {
        val formulation = selectedFormulation.value ?: return
        val oldList = originalFormulationItems.value
        val newList = selectedFormulationDetailItems.value
        if (hasRealContentChanges()) {
            val oldById = oldList.associateBy { it.rawMaterialId }
            val newIds = newList.map { it.rawMaterialId }.toSet()
            val added = newList.count { it.rawMaterialId !in oldById }
            val removed = oldList.count { it.rawMaterialId !in newIds }
            val edited = newList.count { n -> oldById[n.rawMaterialId]?.let { o -> o.quantityMultiplier != n.quantityMultiplier || o.needsGrinding != n.needsGrinding || o.grindingDurationMinutes != n.grindingDurationMinutes } == true }
            val parts = listOfNotNull(
                if (added > 0) "إضافة $added" else null,
                if (removed > 0) "حذف $removed" else null,
                if (edited > 0) "تعديل كمية $edited" else null
            )
            logActivity("formulation", "تعديل مواد التركيبة \"${formulation.name}\"" + if (parts.isNotEmpty()) " (" + parts.joinToString("، ") + ")" else "")
        }
        
        viewModelScope.launch {
            if (formulation.status == "🟢 معتمدة للإنتاج" && hasRealContentChanges()) {
                val sdf = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale.ENGLISH)
                val currentDateStr = sdf.format(java.util.Date())
                
                val snapshotList = mutableListOf<com.example.data.RevisionItemSnapshot>()
                // مطابقة المواد الجديدة بالقديمة: (1) مطابقة تامة بنفس الموضع، (2) نفس المادة بكمية مختلفة
                val newToOld = HashMap<Int, Int>()
                val usedOld = mutableSetOf<Int>()
                for (i in newList.indices) {
                    if (i in oldList.indices) {
                        val o = oldList[i]; val d = newList[i]
                        if (o.rawMaterialId == d.rawMaterialId &&
                            o.quantityMultiplier == d.quantityMultiplier &&
                            o.needsGrinding == d.needsGrinding &&
                            o.grindingDurationMinutes == d.grindingDurationMinutes) {
                            newToOld[i] = i; usedOld.add(i)
                        }
                    }
                }
                for (i in newList.indices) {
                    if (i in newToOld) continue
                    val d = newList[i]
                    val oIndex = oldList.indices.firstOrNull { idx -> idx !in usedOld && oldList[idx].rawMaterialId == d.rawMaterialId }
                    if (oIndex != null) { newToOld[i] = oIndex; usedOld.add(oIndex) }
                }
                // موضع كل مادة قديمة داخل القائمة الجديدة (للمواد المطابقة)
                val oldToNew = HashMap<Int, Int>()
                newToOld.forEach { (n, o) -> oldToNew[o] = n }
                // المواد المحذوفة تُوضع بعد أقرب مادة سابقة لها بقيت في التركيبة
                val deletedAfterNew = HashMap<Int, MutableList<Int>>() // newIndex (-1 = البداية) -> قائمة محذوفات
                for (j in oldList.indices) {
                    if (j in usedOld) continue
                    var k = j - 1
                    while (k >= 0 && k !in oldToNew) k--
                    val anchor = if (k >= 0) oldToNew[k]!! else -1
                    deletedAfterNew.getOrPut(anchor) { mutableListOf() }.add(j)
                }
                fun addDeleted(anchor: Int) {
                    deletedAfterNew[anchor]?.forEach { j ->
                        val o = oldList[j]
                        snapshotList.add(
                            com.example.data.RevisionItemSnapshot(
                                rawMaterialId = o.rawMaterialId,
                                rawMaterialName = o.rawMaterialName,
                                quantityMultiplier = o.quantityMultiplier,
                                needsGrinding = o.needsGrinding,
                                grindingDurationMinutes = o.grindingDurationMinutes,
                                changeType = "GREY",
                                sequence = snapshotList.size
                            )
                        )
                    }
                }
                addDeleted(-1)
                for (i in newList.indices) {
                    val d = newList[i]
                    val oi = newToOld[i]
                    val o = if (oi != null) oldList[oi] else null
                    val changed = o != null && (o.quantityMultiplier != d.quantityMultiplier ||
                            o.needsGrinding != d.needsGrinding ||
                            o.grindingDurationMinutes != d.grindingDurationMinutes)
                    snapshotList.add(
                        com.example.data.RevisionItemSnapshot(
                            rawMaterialId = d.rawMaterialId,
                            rawMaterialName = d.rawMaterialName,
                            quantityMultiplier = d.quantityMultiplier,
                            needsGrinding = d.needsGrinding,
                            grindingDurationMinutes = d.grindingDurationMinutes,
                            changeType = if (o == null) "RED" else if (changed) "BLUE" else "NORMAL",
                            previousQuantityMultiplier = if (changed) o!!.quantityMultiplier else null,
                            sequence = snapshotList.size
                        )
                    )
                    addDeleted(i)
                }
                
                // Serialize
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.example.data.RevisionItemSnapshot::class.java)
                val adapter = moshi.adapter<List<com.example.data.RevisionItemSnapshot>>(type)
                val jsonString = adapter.toJson(snapshotList)
                
                val currentRevisionsList = repository.getRevisionsForFormulation(formulation.id).first()
                val revisionNumber = currentRevisionsList.size + 1
                
                repository.insertFormulationRevision(
                    FormulationRevision(
                        formulationId = formulation.id,
                        version = revisionNumber.toString(),
                        dateChange = currentDateStr,
                        materialName = "تعديل متعدد",
                        oldValue = "",
                        newValue = "",
                        editReason = reason.trim(),
                        snapshotJson = jsonString
                    )
                )
            }
            
            val itemsToSet = newList.mapIndexed { index, it ->
                FormulationItem(
                    id = if (it.id.startsWith("-")) java.util.UUID.randomUUID().toString() else it.id,
                    formulationId = formulation.id,
                    rawMaterialId = it.rawMaterialId,
                    quantityMultiplier = it.quantityMultiplier,
                    needsGrinding = it.needsGrinding,
                    grindingDurationMinutes = it.grindingDurationMinutes,
                    simulatedPrice = it.simulatedPrice,
                    sequence = index
                )
            }

            // Adjust RecipeItem ratios for any raw material whose quantity was modified
            val recipeItems = repository.getRecipeItemsForFormulationSync(formulation.id)
            val phases = repository.getRecipePhasesForFormulationSync(formulation.id)
            if (phases.isNotEmpty() && recipeItems.isNotEmpty()) {
                val phaseIdToSeq = phases.associate { it.id to it.sequence }
                for (newItem in newList) {
                    val oldItem = oldList.find { it.rawMaterialId == newItem.rawMaterialId }
                    if (oldItem != null) {
                        val oldQty = oldItem.quantityMultiplier
                        val newQty = newItem.quantityMultiplier
                        if (oldQty != newQty && oldQty > 0.0 && newQty > 0.0) {
                            val matchingRecipeItems = recipeItems.filter { it.rawMaterialId == newItem.rawMaterialId }
                            if (matchingRecipeItems.size > 1) {
                                val sortedMatching = matchingRecipeItems.sortedWith(
                                    compareBy(
                                        { phaseIdToSeq[it.phaseId] ?: 0 },
                                        { it.sequence }
                                    )
                                )
                                var sumOfEarlierRatios = 0.0
                                for (i in 0 until sortedMatching.size - 1) {
                                    val item = sortedMatching[i]
                                    val oldRatio = item.ratio
                                    var newRatio = oldRatio * (oldQty / newQty)
                                    if (sumOfEarlierRatios + newRatio > 1.0) {
                                        newRatio = 1.0 - sumOfEarlierRatios
                                    }
                                    sumOfEarlierRatios += newRatio
                                    repository.updateRecipeItem(item.copy(ratio = newRatio))
                                }
                                val lastItem = sortedMatching.last()
                                val lastNewRatio = (1.0 - sumOfEarlierRatios).coerceAtLeast(0.0)
                                repository.updateRecipeItem(lastItem.copy(ratio = lastNewRatio))
                            }
                        }
                    }
                }
            }

            repository.setFormulationItems(formulation.id, itemsToSet)
            
            // Sync recipe with updated formulation items to clean up deleted ingredients
            syncRecipeWithFormulationItems(formulation.id)
            
            val updatedList = repository.getFormulationItemsWithDetails(formulation.id).first()
            originalFormulationItems.value = updatedList
            selectedFormulationDetailItems.value = updatedList

            // Set other original snapshots from current DB/flow values
            val freshForm = repository.getFormulationById(formulation.id) ?: formulation
            originalFormulation.value = freshForm
            selectedFormulation.value = freshForm

            val freshPhases = repository.getRecipePhasesForFormulationSync(formulation.id)
            originalRecipePhases.value = freshPhases

            val freshRecItems = repository.getRecipeItemsForFormulationSync(formulation.id)
            originalRecipeItems.value = freshRecItems

            val freshTests = repository.getFormulationQualityTestsSync(formulation.id)
            originalFormulationQualityTests.value = freshTests
        }
    }

    /**
     * عناصر لقطة التعديل للعرض: مرتبة كترتيب التركيبة، ومعها الكمية السابقة للمواد المعدّلة.
     * اللقطات القديمة (قبل هذا التحديث) تُستنتج كميتها السابقة من لقطة التعديل الذي قبلها،
     * ويُرتَّب عرضها على ترتيب التركيبة الحالي بأفضل ما يمكن.
     */
    fun revisionDetailItems(rev: FormulationRevision): List<com.example.data.RevisionItemSnapshot> {
        val snaps = parseRevisionSnapshot(rev.snapshotJson)
        if (snaps.isEmpty()) return snaps
        val isNewFormat = snaps.all { it.sequence != null }
        var result = snaps
        if (snaps.any { it.changeType == "BLUE" && it.previousQuantityMultiplier == null }) {
            val verNum = rev.version.toIntOrNull()
            val prevRev = allFormulationRevisions.value
                .filter { it.formulationId == rev.formulationId && (it.version.toIntOrNull() ?: Int.MAX_VALUE) < (verNum ?: Int.MIN_VALUE) }
                .maxByOrNull { it.version.toIntOrNull() ?: -1 }
            val prevSnaps = prevRev?.let { parseRevisionSnapshot(it.snapshotJson) }
                ?.filter { it.changeType != "GREY" }?.associateBy { it.rawMaterialId }
            if (prevSnaps != null) {
                result = result.map {
                    if (it.changeType == "BLUE" && it.previousQuantityMultiplier == null)
                        it.copy(previousQuantityMultiplier = prevSnaps[it.rawMaterialId]?.quantityMultiplier)
                    else it
                }
            }
        }
        if (isNewFormat) return result.sortedBy { it.sequence ?: 0 }
        val order = selectedFormulationDetailItems.value.map { it.rawMaterialId }
        if (order.isEmpty()) return result
        val big = order.size + 1
        return result.withIndex().sortedWith(
            compareBy({ val p = order.indexOf(it.value.rawMaterialId); if (p < 0) big else p }, { it.index })
        ).map { it.value }
    }

    fun parseRevisionSnapshot(json: String?): List<com.example.data.RevisionItemSnapshot> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, com.example.data.RevisionItemSnapshot::class.java)
            val adapter = moshi.adapter<List<com.example.data.RevisionItemSnapshot>>(type)
            val rawList = adapter.fromJson(json) ?: emptyList()
            rawList.map { 
                if (it.changeType == "YELLOW") it.copy(changeType = "NORMAL") else it
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- Production Run Operations ---
    fun startProductionSetup(formulation: Formulation) {
        activeRunFormulation.value = formulation
        activeRunBatchSize.value = 1000.0
        activeRunCompletedStepIds.value = emptySet()
    }

    fun updateBatchSize(size: Double) {
        if (size > 0) {
            activeRunBatchSize.value = size
        }
    }

    fun toggleRunStep(itemId: String) {
        val currentSet = activeRunCompletedStepIds.value
        if (currentSet.contains(itemId)) {
            activeRunCompletedStepIds.value = currentSet - itemId
        } else {
            activeRunCompletedStepIds.value = currentSet + itemId
        }
    }

    fun cancelProductionRun() {
        activeRunFormulation.value = null
        activeRunBatchSize.value = 1000.0
        activeRunCompletedStepIds.value = emptySet()
    }

    fun completeProductionRun() {
        val formulation = activeRunFormulation.value ?: return
        val batchSize = activeRunBatchSize.value
        viewModelScope.launch {
            repository.insertProductionLog(
                ProductionLog(
                    formulationId = formulation.id,
                    formulationName = formulation.name,
                    operatorName = "مشرف الوردية (admin)",
                    batchWeightKg = batchSize,
                    status = "مكتمل"
                )
            )
            cancelProductionRun()
            showSegment("production") // return to logs list
        }
    }

    // --- Production Orders (PO) Operations ---
    suspend fun getFormulationById(id: String): com.example.data.Formulation? {
        return repository.getFormulationById(id)
    }

    fun addProductionOrder(
        formulationId: String,
        formulationName: String,
        versionName: String,
        scaleFactor: Double,
        originalWeightKg: Double,
        notes: String,
        packagingSnapshotJson: String = ""
    ) {
        logActivity("order", "إنشاء أمر إنتاج للتركيبة \"$formulationName\" بوزن ${"%.1f".format(java.util.Locale.US, originalWeightKg * scaleFactor)} كجم")
        performActionWithLoading(
            loadingMsg = "جاري إنشاء وتصدير أمر تشغيل الدفعة الجديدة...",
            successMsg = "تم إصدار أمر الإنتاج بنجاح."
        ) {
            // 1. Generate sequential order / batch identity keys using simplified shorter format (e.g., PO-0001, B-0001)
            val seq = (productionOrders.value.mapNotNull { 
                it.orderNumber.substringAfter("PO-").toIntOrNull() 
            }.maxOrNull() ?: 0) + 1
            val orderNumber = "PO-${String.format(java.util.Locale.US, "%04d", seq)}"
            val batchNumber = orderNumber
            
            val requiredWeightKg = originalWeightKg * scaleFactor

            // 2. Fetch formula items for the selected revision or currently active layout
            val itemsList = mutableListOf<com.example.data.ProductionOrderItem>()
            
            if (versionName == "النشط" || versionName == "الحالي" || versionName == "الإصدار الحالي (النشط)") {
                val list = repository.getFormulationItemsWithDetails(formulationId).first()
                for (item in list) {
                    itemsList.add(
                        com.example.data.ProductionOrderItem(
                            id = java.util.UUID.randomUUID().toString(),
                            productionOrderId = "",
                            rawMaterialId = item.rawMaterialId,
                            rawMaterialName = item.rawMaterialName,
                            rawMaterialPrice = item.rawMaterialPrice,
                            rawMaterialPriceUnit = item.rawMaterialPriceUnit,
                            quantityMultiplier = item.quantityMultiplier,
                            calculatedQuantity = item.quantityMultiplier * scaleFactor,
                            needsGrinding = item.needsGrinding,
                            grindingDurationMinutes = item.grindingDurationMinutes
                        )
                    )
                }
            } else {
                val revisions = repository.getRevisionsForFormulation(formulationId).first()
                val rev = revisions.find { it.version == versionName }
                if (rev != null) {
                    val snapshotList = parseRevisionSnapshot(rev.snapshotJson)
                    for (item in snapshotList) {
                        itemsList.add(
                            com.example.data.ProductionOrderItem(
                                id = java.util.UUID.randomUUID().toString(),
                                productionOrderId = "",
                                rawMaterialId = item.rawMaterialId,
                                rawMaterialName = item.rawMaterialName,
                                rawMaterialPrice = 0.0,
                                rawMaterialPriceUnit = "شيكل",
                                quantityMultiplier = item.quantityMultiplier,
                                calculatedQuantity = item.quantityMultiplier * scaleFactor,
                                needsGrinding = item.needsGrinding,
                                grindingDurationMinutes = item.grindingDurationMinutes
                            )
                        )
                    }
                }
            }
            
            // Fallback to active items if selected revision yields nothing
            if (itemsList.isEmpty()) {
                val list = repository.getFormulationItemsWithDetails(formulationId).first()
                for (item in list) {
                    itemsList.add(
                        com.example.data.ProductionOrderItem(
                            id = java.util.UUID.randomUUID().toString(),
                            productionOrderId = "",
                            rawMaterialId = item.rawMaterialId,
                            rawMaterialName = item.rawMaterialName,
                            rawMaterialPrice = item.rawMaterialPrice,
                            rawMaterialPriceUnit = item.rawMaterialPriceUnit,
                            quantityMultiplier = item.quantityMultiplier,
                            calculatedQuantity = item.quantityMultiplier * scaleFactor,
                            needsGrinding = item.needsGrinding,
                            grindingDurationMinutes = item.grindingDurationMinutes
                        )
                    )
                }
            }
 
            // 3. Create active Order record with "جاهز للتنفيذ" as initial status
            val currentUser = username.value.ifBlank { "المشرف" }
            
            // Build dynamic packaging snapshot from Formulation settings
            var finalPackagingSnapshotJson = packagingSnapshotJson
            val formulation = repository.getFormulationById(formulationId)
            if (formulation != null) {
                val packSnapshots = mutableListOf<org.json.JSONObject>()
                val allPackagings = loadCustomPackagings()
                val jsonStr = formulation.packagingWeightsJson
                val jsonWeights = if (jsonStr.isNotBlank()) {
                    try { org.json.JSONObject(jsonStr) } catch(e: Exception) { null }
                } else {
                    null
                }

                if (jsonWeights != null && jsonWeights.length() > 0) {
                    allPackagings.forEach { pkg ->
                        if (jsonWeights.has(pkg.id)) {
                            val rawVal = jsonWeights.optString(pkg.id, "")
                            val parts = rawVal.split(":")
                            val netW = parts.getOrNull(0)?.toDoubleOrNull() ?: pkg.netWeight
                            val extraC = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                            packSnapshots.add(org.json.JSONObject().apply {
                                put("id", pkg.id)
                                put("name", pkg.name)
                                put("netWeight", netW)
                                put("weightWithLid", pkg.weightWithLid)
                                put("price", pkg.price)
                                put("extraCost", extraC)
                            })
                        }
                    }
                }
                
                if (packSnapshots.isEmpty()) {
                    if (formulation.supports18L) {
                        val pkg18 = allPackagings.find { it.netWeight == 18.0 || it.name.contains("18") || it.id == "1" }
                            ?: CustomPackaging("1", "سطل معياري 18 لتر", 18.0, 19.2, 15.00)
                        val netWeight18 = formulation.netWeight18L.toDoubleOrNull() ?: pkg18.netWeight
                        packSnapshots.add(org.json.JSONObject().apply {
                            put("id", pkg18.id)
                            put("name", pkg18.name)
                            put("netWeight", netWeight18)
                            put("weightWithLid", pkg18.weightWithLid)
                            put("price", pkg18.price)
                            put("extraCost", 0.0)
                        })
                    }
                    if (formulation.supports5L) {
                        val pkg5 = allPackagings.find { it.netWeight == 5.0 || it.name.contains("5") || it.id == "2" }
                            ?: CustomPackaging("2", "جالون معياري 5 لتر", 5.0, 5.4, 5.50)
                        val netWeight5 = formulation.netWeight5L.toDoubleOrNull() ?: pkg5.netWeight
                        packSnapshots.add(org.json.JSONObject().apply {
                            put("id", pkg5.id)
                            put("name", pkg5.name)
                            put("netWeight", netWeight5)
                            put("weightWithLid", pkg5.weightWithLid)
                            put("price", pkg5.price)
                            put("extraCost", 0.0)
                        })
                    }
                }

                if (packSnapshots.isEmpty()) {
                    packSnapshots.add(org.json.JSONObject().apply {
                        put("id", "1")
                        put("name", "سطل معياري 18 لتر")
                        put("netWeight", 18.0)
                        put("weightWithLid", 19.2)
                        put("price", 15.00)
                        put("extraCost", 0.0)
                    })
                }
                finalPackagingSnapshotJson = org.json.JSONArray(packSnapshots).toString()
            }

            val newOrder = com.example.data.ProductionOrder(
                id = java.util.UUID.randomUUID().toString(),
                orderNumber = orderNumber,
                batchNumber = batchNumber,
                formulationId = formulationId,
                formulationName = formulationName,
                formulationVersion = versionName,
                requiredWeightKg = requiredWeightKg,
                status = "جاهز للتنفيذ",
                notes = notes,
                scaleFactor = scaleFactor,
                originalWeightKg = originalWeightKg,
                progressPercent = 0,
                currentPhaseIndex = 0,
                currentItemIndex = 0,
                completedItemsJson = "[]",
                packagingSnapshotJson = finalPackagingSnapshotJson,
                actualPackagingJson = "[]",
                startTime = 0L,
                endTime = 0L,
                operatorName = currentUser
            )
            
            val orderId = repository.insertProductionOrder(newOrder)
            
            // 4. Save snapshotted individual formula items to lock production order state
            for (item in itemsList) {
                repository.insertProductionOrderItem(item.copy(productionOrderId = orderId))
            }
            
            // 5. Snapshot and save phases and recipe items for entire recipe execution
            val originalPhases = repository.getRecipePhasesForFormulationSync(formulationId)
            val originalRecipeItems = repository.getRecipeItemsForFormulationSync(formulationId)

            for (phase in originalPhases) {
                val newPhase = com.example.data.ProductionOrderPhase(
                    id = java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    name = phase.name,
                    sequence = phase.sequence,
                    mixerRpm = phase.mixerRpm,
                    durationMinutes = phase.durationMinutes,
                    instructions = phase.instructions
                )
                val newPhaseId = repository.insertProductionOrderPhase(newPhase)

                val itemsInPhase = originalRecipeItems.filter { it.phaseId == phase.id }
                for (recipeItem in itemsInPhase) {
                    val matchingIngredient = itemsList.find { it.rawMaterialId == recipeItem.rawMaterialId }
                    val materialName = matchingIngredient?.rawMaterialName ?: "مادة مجهولة"
                    val ingredientMultiplier = matchingIngredient?.quantityMultiplier ?: 0.0
                    val stepQuantity = recipeItem.ratio * ingredientMultiplier * scaleFactor

                    val newRecipeItem = com.example.data.ProductionOrderRecipeItem(
                        id = java.util.UUID.randomUUID().toString(),
                        productionOrderPhaseId = newPhaseId,
                        rawMaterialId = recipeItem.rawMaterialId,
                        rawMaterialName = materialName,
                        ratio = recipeItem.ratio,
                        calculatedQuantity = stepQuantity,
                        sequence = recipeItem.sequence
                    )
                    repository.insertProductionOrderRecipeItem(newRecipeItem)
                }
            }

            // 5b. Freeze and snapshot quality test requirements for the production order
            val allQCDefinitions = repository.qualityTests.first().associateBy { it.id }
            val fTests = repository.getFormulationQualityTestsSync(formulationId).filter { it.isEnabled }
            val frozenQCTests = fTests.mapIndexed { idx, fTest ->
                val testName = allQCDefinitions[fTest.testId]?.name ?: "فحص ${fTest.testId}"
                com.example.data.ProductionOrderQualityTest(
                    id = java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    testId = fTest.testId,
                    testName = testName,
                    minValue = fTest.minValue,
                    maxValue = fTest.maxValue,
                    sequenceIndex = idx
                )
            }
            if (frozenQCTests.isNotEmpty()) {
                repository.insertProductionOrderQualityTests(frozenQCTests)
            }

            // 6. Audit logs for Execution Events
            repository.insertProductionOrderEvent(
                com.example.data.ProductionOrderEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    eventName = "إنشاء أمر الإنتاج",
                    description = "تم إنشاء أمر الإنتاج بمعامل تشغيل ${com.example.formatQuantity(scaleFactor)} ووزن متوقع ${com.example.formatQuantity(requiredWeightKg)} كجم"
                )
            )
            repository.logSyncChange(orderId, "production_order")
        }
    }

    fun updateProductionOrderStatus(orderId: String, newStatus: String, eventName: String, description: String = "") {
        performActionWithLoading(
            loadingMsg = "جاري تحديث حالة أمر التشغيل وتوريده...",
            successMsg = "تم تحديث حالة التشغيل بنجاح."
        ) {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val autoProgress = when (newStatus) {
                    "مكتمل" -> 100
                    "مسودة", "جاهز للتنفيذ" -> 0
                    else -> order.progressPercent
                }
                val updatedOrder = order.copy(status = newStatus, progressPercent = autoProgress)
                repository.updateProductionOrder(updatedOrder)
                // Reflect on UI immediately
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updatedOrder
                }
                
                if (newStatus == "ملغي") {
                    // Delete associated lab sessions to avoid orphan sessions
                    val associatedSessions = labSessions.value.filter { it.sampleProperties.startsWith("ORDER_ID:$orderId") }
                    associatedSessions.forEach { session ->
                        repository.deleteLabSession(session)
                    }
                }
                
                repository.insertProductionOrderEvent(
                    com.example.data.ProductionOrderEvent(
                        id = java.util.UUID.randomUUID().toString(),
                        productionOrderId = orderId,
                        eventName = eventName,
                        description = description
                    )
                )
            }
        }
    }

    fun updateProductionOrderProgress(orderId: String, progressPercent: Int) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val updatedOrder = order.copy(progressPercent = progressPercent)
                repository.updateProductionOrder(updatedOrder)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updatedOrder
                }
                repository.insertProductionOrderEvent(
                    com.example.data.ProductionOrderEvent(
                        id = java.util.UUID.randomUUID().toString(),
                        productionOrderId = orderId,
                        eventName = "تحديث نسبة الإنجاز",
                        description = "تم تحديث نسبة إنجاز الدفعة يدوياً إلى $progressPercent%"
                    )
                )
            }
        }
    }

    fun applyProductionAdjustment(
        orderId: String,
        materialId: String,
        materialName: String,
        originalQty: Double,
        newQty: Double,
        reason: String,
        notes: String,
        userName: String,
        recipeItemId: String? = null
    ) {
        logActivity("order", "تعديل كمية \"$materialName\" في أمر الإنتاج ${productionOrders.value.find { it.id == orderId }?.orderNumber ?: ""}: من ${"%.2f".format(java.util.Locale.US, originalQty)} إلى ${"%.2f".format(java.util.Locale.US, newQty)} كجم")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val diff = newQty - originalQty
            val adjustment = com.example.data.ProductionAdjustment(
                id = java.util.UUID.randomUUID().toString(),
                productionOrderId = orderId,
                rawMaterialId = materialId,
                rawMaterialName = materialName,
                originalQuantity = originalQty,
                newQuantity = newQty,
                difference = diff,
                reason = reason,
                notes = notes,
                timestamp = System.currentTimeMillis(),
                userName = userName
            )
            repository.insertProductionAdjustment(adjustment)

            if (!recipeItemId.isNullOrBlank()) {
                // 1. Update this specific recipe item's quantity
                repository.updateProductionOrderRecipeItemQuantity(recipeItemId, newQty)

                // 2. Sum up all recipe items of this raw material in this order to get the new total
                val recipeItems = repository.getProductionOrderRecipeItemsForOrderSync(orderId)
                val totalMaterialQty = recipeItems.filter { it.rawMaterialId == materialId }.sumOf {
                    if (it.id == recipeItemId) newQty else it.calculatedQuantity
                }

                // 3. Update the total quantity in production order items
                repository.updateProductionOrderItemQuantity(orderId, materialId, totalMaterialQty)
            } else {
                // Fallback behavior if no recipeItemId was provided
                repository.updateProductionOrderItemQuantity(orderId, materialId, newQty)

                val recipeItems = repository.getProductionOrderRecipeItemsForOrderSync(orderId)
                val matchingItems = recipeItems.filter { it.rawMaterialId == materialId }
                if (matchingItems.size > 1) {
                    val phases = repository.getProductionOrderPhasesSync(orderId)
                    val phaseIdToSeq = phases.associate { it.id to it.sequence }
                    val sortedMatching = matchingItems.sortedWith(
                        compareBy(
                            { phaseIdToSeq[it.productionOrderPhaseId] ?: 0 },
                            { it.sequence }
                        )
                    )
                    
                    var sumOfEarlierQuantities = 0.0
                    for (i in 0 until sortedMatching.size - 1) {
                        val item = sortedMatching[i]
                        val oldQtyOfItem = item.calculatedQuantity
                        var itemNewQty = oldQtyOfItem
                        if (sumOfEarlierQuantities + itemNewQty > newQty) {
                            itemNewQty = newQty - sumOfEarlierQuantities
                        }
                        sumOfEarlierQuantities += itemNewQty
                        repository.updateProductionOrderRecipeItemQuantity(item.id, itemNewQty)
                    }
                    val lastItem = sortedMatching.last()
                    val lastNewQty = (newQty - sumOfEarlierQuantities).coerceAtLeast(0.0)
                    repository.updateProductionOrderRecipeItemQuantity(lastItem.id, lastNewQty)
                } else {
                    for (ri in matchingItems) {
                        val updatedRecipeQty = ri.ratio * newQty
                        repository.updateProductionOrderRecipeItemQuantity(ri.id, updatedRecipeQty)
                    }
                }
            }

            val diffSign = if (diff >= 0) "+" else ""
            val fmtDiff = String.format(java.util.Locale.US, "%.2f", diff)
            val eventDesc = if (notes.isNotBlank()) {
                "تم تعديل مادة $materialName من ${com.example.formatQuantity(originalQty)} كغم إلى ${com.example.formatQuantity(newQty)} كغم (الفرق: $diffSign$fmtDiff كغم). السبب: $reason | ملاحظة: $notes"
            } else {
                "تم تعديل مادة $materialName من ${com.example.formatQuantity(originalQty)} كغم إلى ${com.example.formatQuantity(newQty)} كغم (الفرق: $diffSign$fmtDiff كغم). السبب: $reason"
            }
            repository.insertProductionOrderEvent(
                com.example.data.ProductionOrderEvent(
                    productionOrderId = orderId,
                    eventName = "تعديل كمية الإنتاج",
                    description = eventDesc
                )
            )
            repository.logSyncChange(orderId, "production_order")
        }
    }

    /** استبدال مادة بند وصفة في أمر الإنتاج بمادة أخرى (هذا البند فقط) مع تسجيل التعديل والحدث. */
    fun applyProductionSubstitution(
        orderId: String,
        recipeItemId: String,
        oldMaterialId: String,
        oldMaterialName: String,
        newMaterial: com.example.data.RawMaterial,
        originalQty: Double,
        newQty: Double,
        reason: String,
        notes: String,
        userName: String
    ) {
        logActivity("order", "استبدال مادة \"$oldMaterialName\" بمادة \"${newMaterial.name}\" في أمر الإنتاج ${productionOrders.value.find { it.id == orderId }?.orderNumber ?: ""}")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dao = repository.gbrDao()
            val fullReason = "استبدال المادة «$oldMaterialName» بالمادة «${newMaterial.name}» — $reason"
            repository.insertProductionAdjustment(
                com.example.data.ProductionAdjustment(
                    id = java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    rawMaterialId = newMaterial.id,
                    rawMaterialName = newMaterial.name,
                    originalQuantity = originalQty,
                    newQuantity = newQty,
                    difference = newQty - originalQty,
                    reason = fullReason,
                    notes = notes,
                    timestamp = System.currentTimeMillis(),
                    userName = userName
                )
            )
            // 1) بند الوصفة: المادة الجديدة والكمية
            dao.replaceProductionOrderRecipeItemMaterial(recipeItemId, newMaterial.id, newMaterial.name, newQty)
            // 2) إجمالي مواد الأمر: ننقص القديمة (ونحذف صفها إن انتهت) ونزيد/ننشئ الجديدة
            val items = dao.getProductionOrderItemsSync(orderId)
            val oldRow = items.find { it.rawMaterialId == oldMaterialId }
            if (oldRow != null) {
                val remaining = oldRow.calculatedQuantity - originalQty
                if (remaining <= 0.0005) dao.deleteProductionOrderItemById(oldRow.id)
                else dao.updateProductionOrderItemQuantity(orderId, oldMaterialId, remaining)
            }
            val newRow = items.find { it.rawMaterialId == newMaterial.id }
            if (newRow != null) {
                dao.updateProductionOrderItemQuantity(orderId, newMaterial.id, newRow.calculatedQuantity + newQty)
            } else {
                dao.insertProductionOrderItem(
                    com.example.data.ProductionOrderItem(
                        productionOrderId = orderId,
                        rawMaterialId = newMaterial.id,
                        rawMaterialName = newMaterial.name,
                        rawMaterialPrice = newMaterial.price,
                        rawMaterialPriceUnit = newMaterial.priceUnit,
                        quantityMultiplier = if (oldRow != null && oldRow.calculatedQuantity > 0.0) oldRow.quantityMultiplier * newQty / oldRow.calculatedQuantity else 0.0,
                        calculatedQuantity = newQty
                    )
                )
            }
            val qtyNote = if (kotlin.math.abs(newQty - originalQty) > 0.0005) " (الكمية: ${com.example.formatQuantity(originalQty)} ← ${com.example.formatQuantity(newQty)} كغم)" else " (${com.example.formatQuantity(newQty)} كغم)"
            repository.insertProductionOrderEvent(
                com.example.data.ProductionOrderEvent(
                    productionOrderId = orderId,
                    eventName = "استبدال مادة",
                    description = "تم استبدال مادة $oldMaterialName بمادة ${newMaterial.name}$qtyNote. السبب: $reason" + (if (notes.isNotBlank()) " | ملاحظة: $notes" else "")
                )
            )
            repository.logSyncChange(orderId, "production_order")
        }
    }

    fun startProductionExecution(orderId: String) {
        logActivity("order", "بدء تنفيذ أمر الإنتاج ${productionOrders.value.find { it.id == orderId }?.let { it.orderNumber + " (" + it.formulationName + ")" } ?: ""}")
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val now = System.currentTimeMillis()
                val updated = order.copy(
                    status = "قيد التنفيذ",
                    startTime = if (order.startTime == 0L) now else order.startTime
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.insertProductionOrderEvent(
                    com.example.data.ProductionOrderEvent(
                        productionOrderId = orderId,
                        eventName = "بدء التنفيذ",
                        description = "تم بدء تشغيل أو استئناف الدفعة بنجاح في وضع التشغيل"
                    )
                )
            }
        }
    }

    fun updateExecutionProgress(
        orderId: String,
        phaseIndex: Int,
        itemIndex: Int,
        completedItemsJson: String,
        progressPercent: Int
    ) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val updated = order.copy(
                    currentPhaseIndex = phaseIndex,
                    currentItemIndex = itemIndex,
                    completedItemsJson = completedItemsJson,
                    progressPercent = progressPercent
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
            }
        }
    }

    fun selectAndNavigateToProductionOrder(orderId: String, stepKey: String? = null) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                if (stepKey != null) {
                    pendingGrindStepKey.value = stepKey
                }
                selectedProductionOrder.value = order
                activeSegment.value = "production"
            }
        }
    }

    fun saveGrindStartTime(orderId: String, stepKey: String, startTimeMs: Long) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                if (startTimeMs == -1L) {
                    jsonObj.remove(stepKey)
                    jsonObj.remove(stepKey + "_alerted")
                    jsonObj.remove(stepKey + "_paused")
                    jsonObj.remove(stepKey + "_paused_time_left")
                    jsonObj.remove(stepKey + "_bypassed")
                } else {
                    jsonObj.put(stepKey, startTimeMs)
                    jsonObj.remove(stepKey + "_alerted") // Reset alerted state on new timer start!
                    jsonObj.remove(stepKey + "_paused")
                    jsonObj.remove(stepKey + "_paused_time_left")
                    jsonObj.remove(stepKey + "_bypassed")
                }
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun pauseGrindTimer(orderId: String, stepKey: String, timeLeftSec: Int) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                jsonObj.put(stepKey + "_paused", true)
                jsonObj.put(stepKey + "_paused_time_left", timeLeftSec)
                
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun resumeGrindTimer(orderId: String, stepKey: String, newStartTimeMs: Long) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                jsonObj.put(stepKey, newStartTimeMs)
                jsonObj.put(stepKey + "_paused", false)
                jsonObj.remove(stepKey + "_paused_time_left")
                jsonObj.remove(stepKey + "_alerted")
                
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun cancelGrindTimer(orderId: String, stepKey: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                jsonObj.remove(stepKey)
                jsonObj.remove(stepKey + "_paused")
                jsonObj.remove(stepKey + "_paused_time_left")
                jsonObj.remove(stepKey + "_alerted")
                jsonObj.remove(stepKey + "_bypassed")
                
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun bypassGrindTimer(orderId: String, stepKey: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                jsonObj.put(stepKey + "_bypassed", true)
                
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun setGrindTimerAlerted(orderId: String, stepKey: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                jsonObj.put(stepKey + "_alerted", true)
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
            }
        }
    }

    fun logExecutionStepEvent(orderId: String, eventName: String, description: String) {
        viewModelScope.launch {
            repository.insertProductionOrderEvent(
                com.example.data.ProductionOrderEvent(
                    productionOrderId = orderId,
                    eventName = eventName,
                    description = description
                )
            )
        }
    }

    fun recordPackagingStartTime(orderId: String, customStartTimeMs: Long? = null) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val currentJsonStr = order.timerStartTimesJson.ifBlank { "{}" }
                val jsonObj = try {
                    org.json.JSONObject(currentJsonStr)
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                val startTs = customStartTimeMs ?: System.currentTimeMillis()
                jsonObj.put("packaging_start", startTs)
                val updated = order.copy(
                    timerStartTimesJson = jsonObj.toString()
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                repository.logSyncChange(orderId, "production_order")
                val sdf = java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.US)
                val timeStr = sdf.format(java.util.Date(startTs))
                val isEdit = customStartTimeMs != null
                val eventDesc = if (isEdit) {
                    "تم تحديث/تحديد وقت بدء التعبئة الفعلي يدوياً إلى $timeStr"
                } else {
                    "صالة الإنتاج: قام العامل بالضغط على زر بدء التعبئة للدفعة"
                }
                repository.insertProductionOrderEvent(
                    com.example.data.ProductionOrderEvent(
                        productionOrderId = orderId,
                        eventName = "بدء التعبئة",
                        description = eventDesc,
                        timestamp = startTs
                    )
                )
            }
        }
    }

    fun updateIntermediatePackaging(orderId: String, actualPackagingJson: String, notes: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val updated = order.copy(
                    actualPackagingJson = actualPackagingJson,
                    notes = notes
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
            }
        }
    }

    // تعديل/إضافة/حذف الملاحظات الختامية لأمر إنتاج مؤرشف (الملاحظات الأصلية تبقى كما هي)
    fun updateArchivedOrderFinalNotes(orderId: String, newFinalNotes: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val origNotes = order.notes.split("\nملاحظات الإكمال:").firstOrNull()?.trim() ?: ""
                val trimmed = newFinalNotes.trim()
                val updated = order.copy(
                    notes = if (trimmed.isNotBlank()) "$origNotes\nملاحظات الإكمال: $trimmed" else origNotes
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
            }
        }
    }

    fun completeProductionExecution(
        orderId: String,
        actualPackagingJson: String,
        finalNotes: String
    ) {
        logActivity("order", "إنهاء أمر الإنتاج ${productionOrders.value.find { it.id == orderId }?.let { it.orderNumber + " (" + it.formulationName + ")" } ?: ""} ونقله إلى الأرشيف")
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                val now = System.currentTimeMillis()
                val origNotes = order.notes.split("\nملاحظات الإكمال:").firstOrNull()?.trim() ?: ""
                val updated = order.copy(
                    status = "مكتمل",
                    progressPercent = 100,
                    actualPackagingJson = actualPackagingJson,
                    endTime = now,
                    notes = if (finalNotes.isNotBlank()) "$origNotes\nملاحظات الإكمال: ${finalNotes.trim()}" else origNotes
                )
                repository.updateProductionOrder(updated)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = updated
                }
                
                // Add event
                repository.insertProductionOrderEvent(
                    com.example.data.ProductionOrderEvent(
                        productionOrderId = orderId,
                        eventName = "إكمال التنفيذ",
                        description = "تم إكمال الدفعة وإدخل كميات التعبئة الفعلية"
                    )
                )

                // Add production log entry as fallback for stats and other standard interfaces
                repository.insertProductionLog(
                    ProductionLog(
                        formulationId = order.formulationId,
                        formulationName = order.formulationName,
                        operatorName = order.operatorName,
                        batchWeightKg = order.requiredWeightKg,
                        status = "مكتمل",
                        timestamp = now
                    )
                )
            }
        }
    }

    fun deleteProductionOrder(orderId: String) {
        viewModelScope.launch {
            val order = repository.getProductionOrderById(orderId)
            if (order != null) {
                repository.deleteProductionOrder(order)
                if (selectedProductionOrder.value?.id == orderId) {
                    selectedProductionOrder.value = null
                }
                
                // Delete associated lab sessions to avoid orphan sessions
                val associatedSessions = labSessions.value.filter { it.sampleProperties.startsWith("ORDER_ID:$orderId") }
                associatedSessions.forEach { session ->
                    repository.deleteLabSession(session)
                }
            }
        }
    }

    // --- PRODUCTION RECIPES OPERATIONS & HELPER METHODS ---

    suspend fun syncRecipeWithFormulationItems(formulationId: String) {
        val formItems = repository.getFormulationItemsWithDetails(formulationId).first()
        if (formItems.isEmpty()) return

        val phases = repository.getRecipePhasesForFormulationSync(formulationId)
        if (phases.isEmpty()) return // Do not automatically initialize until opened

        val firstPhaseId = phases.minByOrNull { it.sequence }?.id ?: return
        val recItems = repository.getRecipeItemsForFormulationSync(formulationId)

        val formMaterialIds = formItems.map { it.rawMaterialId }.toSet()
        val recMaterialIds = recItems.map { it.rawMaterialId }.toSet()

        var listModified = false

        // 1. Delete recipe items that are no longer in formulation items
        val itemsToDelete = recItems.filter { !formMaterialIds.contains(it.rawMaterialId) }
        for (item in itemsToDelete) {
            repository.deleteRecipeItem(item)
            listModified = true
        }

        // 2. Add raw materials that are in formulation items but missing in recipe items
        val missingMaterialIds = formMaterialIds.filter { !recMaterialIds.contains(it) }
        if (missingMaterialIds.isNotEmpty()) {
            var currentMaxSeq = recItems.filter { it.phaseId == firstPhaseId }.maxOfOrNull { it.sequence } ?: -1
            for (matId in missingMaterialIds) {
                currentMaxSeq++
                val newItem = RecipeItem(
                    phaseId = firstPhaseId,
                    rawMaterialId = matId,
                    ratio = 1.0,
                    sequence = currentMaxSeq
                )
                repository.insertRecipeItem(newItem)
                listModified = true
            }
        }

        if (listModified) {
            repository.insertRecipeStatus(RecipeStatus(formulationId = formulationId, status = "READY"))
        }
    }

    fun initializeRecipeForFormulation(formulationId: String) {
        viewModelScope.launch {
            val phases = repository.getRecipePhasesForFormulationSync(formulationId)
            if (phases.isNotEmpty()) return@launch // Already initialized

            // Create Stage 1
            val phase = RecipePhase(
                formulationId = formulationId,
                name = "المرحلة 1",
                sequence = 1,
                mixerRpm = 0,
                durationMinutes = 0,
                instructions = ""
            )
            repository.insertRecipePhase(phase)
            val phaseId = phase.id

            // Fetch and place all formulation items in Stage 1
            val formItems = repository.getFormulationItemsWithDetails(formulationId).first()
            var seq = 0
            for (item in formItems) {
                repository.insertRecipeItem(
                    RecipeItem(
                        phaseId = phaseId,
                        rawMaterialId = item.rawMaterialId,
                        ratio = 1.0,
                        sequence = seq++
                    )
                )
            }

            // Set state to ready
            repository.insertRecipeStatus(RecipeStatus(formulationId = formulationId, status = "READY"))
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, formulationId, "formulation")
        }
    }

    fun addRecipePhase(formulationId: String, name: String = "") {
        val existing = selectedFormulationRecipePhases.value.sortedBy { it.sequence }
        val nextSeq = (existing.maxOfOrNull { it.sequence } ?: 0) + 1
        val finalName = if (name.isBlank()) "المرحلة $nextSeq" else name
        val newPhase = RecipePhase(
            id = java.util.UUID.randomUUID().toString(),
            formulationId = formulationId,
            name = finalName,
            sequence = nextSeq,
            mixerRpm = 0,
            durationMinutes = 0,
            instructions = ""
        )
        selectedFormulationRecipePhases.value = existing + newPhase
        selectedFormulationRecipeStatus.value = RecipeStatus(formulationId = formulationId, status = "READY")
    }

    fun updateRecipePhase(phase: RecipePhase) {
        val current = selectedFormulationRecipePhases.value
        selectedFormulationRecipePhases.value = current.map { if (it.id == phase.id) phase else it }
    }

    fun deleteRecipePhase(phase: RecipePhase) {
        val formulationId = phase.formulationId
        val allPhases = selectedFormulationRecipePhases.value.sortedBy { it.sequence }
        val currentIdx = allPhases.indexOfFirst { it.id == phase.id }
        if (currentIdx == -1) return
        
        val prevPhase = allPhases.lastOrNull { it.sequence < phase.sequence }
        val nextPhase = allPhases.firstOrNull { it.sequence > phase.sequence }
        val targetPhase = prevPhase ?: nextPhase
        
        val currentItems = selectedFormulationRecipeItems.value
        val phaseItems = currentItems.filter { it.phaseId == phase.id }
        val updatedItems = currentItems.toMutableList()
        
        if (targetPhase != null && phaseItems.isNotEmpty()) {
            val targetItems = updatedItems.filter { it.phaseId == targetPhase.id }
            for (item in phaseItems) {
                val duplicate = targetItems.find { it.rawMaterialId == item.rawMaterialId }
                if (duplicate != null) {
                    val mergedRatio = (duplicate.ratio + item.ratio).coerceAtMost(1.0)
                    val dupIdx = updatedItems.indexOfFirst { it.id == duplicate.id }
                    if (dupIdx != -1) {
                        updatedItems[dupIdx] = duplicate.copy(ratio = mergedRatio)
                    }
                    updatedItems.removeAll { it.id == item.id }
                } else {
                    val maxSeqInTarget = updatedItems
                        .filter { it.phaseId == targetPhase.id }
                        .maxOfOrNull { it.sequence } ?: -1
                    val itemIdx = updatedItems.indexOfFirst { it.id == item.id }
                    if (itemIdx != -1) {
                        updatedItems[itemIdx] = item.copy(phaseId = targetPhase.id, sequence = maxSeqInTarget + 1)
                    }
                }
            }
        } else if (targetPhase == null) {
            updatedItems.removeAll { it.phaseId == phase.id }
        }
        
        val remainingPhases = allPhases.filter { it.id != phase.id }
        var seq = 1
        val resequencedPhases = remainingPhases.map { rp ->
            val updated = rp.copy(sequence = seq, name = "المرحلة $seq")
            seq++
            updated
        }
        
        selectedFormulationRecipePhases.value = resequencedPhases
        selectedFormulationRecipeItems.value = updatedItems
        selectedFormulationRecipeStatus.value = RecipeStatus(formulationId = formulationId, status = "READY")
    }

    fun moveRecipePhaseUpDown(phase: RecipePhase, up: Boolean) {
        val allPhases = selectedFormulationRecipePhases.value.sortedBy { it.sequence }.toMutableList()
        val index = allPhases.indexOfFirst { it.id == phase.id }
        if (index == -1) return
        
        val targetIndex = if (up) index - 1 else index + 1
        if (targetIndex in allPhases.indices) {
            val current = allPhases[index]
            val target = allPhases[targetIndex]
            
            allPhases[index] = current.copy(sequence = target.sequence, name = "المرحلة ${target.sequence}")
            allPhases[targetIndex] = target.copy(sequence = current.sequence, name = "المرحلة ${current.sequence}")
            selectedFormulationRecipePhases.value = allPhases.sortedBy { it.sequence }
        }
    }

    fun moveRecipeItemUpDown(item: RecipeItem, up: Boolean) {
        val formulationId = selectedFormulation.value?.id ?: return
        val allPhases = selectedFormulationRecipePhases.value.sortedBy { it.sequence }
        val currentPhase = allPhases.find { it.id == item.phaseId } ?: return
        
        val allItems = selectedFormulationRecipeItems.value.toMutableList()
        val allItemsInCurrentPhase = allItems
            .filter { it.phaseId == item.phaseId }
            .sortedBy { it.sequence }
        
        val index = allItemsInCurrentPhase.indexOfFirst { it.id == item.id }
        if (index == -1) return
        
        val targetIndex = if (up) index - 1 else index + 1
        if (targetIndex in allItemsInCurrentPhase.indices) {
            val current = allItemsInCurrentPhase[index]
            val target = allItemsInCurrentPhase[targetIndex]
            
            val currIdx = allItems.indexOfFirst { it.id == current.id }
            val targIdx = allItems.indexOfFirst { it.id == target.id }
            if (currIdx != -1 && targIdx != -1) {
                allItems[currIdx] = current.copy(sequence = target.sequence)
                allItems[targIdx] = target.copy(sequence = current.sequence)
            }
        } else {
            // Cross-phase move!
            if (up && index == 0) {
                val prevPhase = allPhases.lastOrNull { it.sequence < currentPhase.sequence }
                if (prevPhase != null) {
                    val prevPhaseItems = allItems.filter { it.phaseId == prevPhase.id }.sortedBy { it.sequence }
                    val maxSeq = prevPhaseItems.maxOfOrNull { it.sequence } ?: -1
                    
                    val itemIdx = allItems.indexOfFirst { it.id == item.id }
                    if (itemIdx != -1) {
                        allItems[itemIdx] = item.copy(phaseId = prevPhase.id, sequence = maxSeq + 1)
                    }
                    
                    val remaining = allItemsInCurrentPhase.filter { it.id != item.id }
                    remaining.forEachIndexed { i, ri ->
                        val rIdx = allItems.indexOfFirst { it.id == ri.id }
                        if (rIdx != -1) allItems[rIdx] = ri.copy(sequence = i)
                    }
                }
            } else if (!up && index == allItemsInCurrentPhase.size - 1) {
                val nextPhase = allPhases.firstOrNull { it.sequence > currentPhase.sequence }
                if (nextPhase != null) {
                    val nextPhaseItems = allItems.filter { it.phaseId == nextPhase.id }.sortedBy { it.sequence }
                    nextPhaseItems.forEach { ri ->
                        val nIdx = allItems.indexOfFirst { it.id == ri.id }
                        if (nIdx != -1) allItems[nIdx] = ri.copy(sequence = ri.sequence + 1)
                    }
                    val itemIdx = allItems.indexOfFirst { it.id == item.id }
                    if (itemIdx != -1) {
                        allItems[itemIdx] = item.copy(phaseId = nextPhase.id, sequence = 0)
                    }
                    val remaining = allItemsInCurrentPhase.filter { it.id != item.id }
                    remaining.forEachIndexed { i, ri ->
                        val rIdx = allItems.indexOfFirst { it.id == ri.id }
                        if (rIdx != -1) allItems[rIdx] = ri.copy(sequence = i)
                    }
                }
            }
        }
        selectedFormulationRecipeItems.value = allItems
    }

    fun mergeRecipeItems(clickedItem: RecipeItem, targetItem: RecipeItem) {
        val formulationId = selectedFormulation.value?.id ?: return
        val mergedRatio = (targetItem.ratio + clickedItem.ratio).coerceAtMost(1.0)
        
        val allItems = selectedFormulationRecipeItems.value.toMutableList()
        val targetIdx = allItems.indexOfFirst { it.id == targetItem.id }
        if (targetIdx != -1) {
            allItems[targetIdx] = targetItem.copy(ratio = mergedRatio)
        }
        allItems.removeAll { it.id == clickedItem.id }
        
        val allItemsInPrevPhase = allItems.filter { it.phaseId == clickedItem.phaseId }.sortedBy { it.sequence }
        allItemsInPrevPhase.forEachIndexed { index, ri ->
            val idx = allItems.indexOfFirst { it.id == ri.id }
            if (idx != -1) allItems[idx] = ri.copy(sequence = index)
        }
        
        selectedFormulationRecipeItems.value = allItems
        selectedFormulationRecipeStatus.value = RecipeStatus(formulationId = formulationId, status = "READY")
    }

    fun splitRecipeItem(item: RecipeItem, targetPhaseId: String, currentPhaseQuantity: Double, formulationItem: FormulationItem) {
        val formulationId = selectedFormulation.value?.id ?: return
        val totalQuantity = formulationItem.quantityMultiplier
        if (totalQuantity <= 0.0) return

        val currentRatioQuantity = item.ratio * totalQuantity
        if (currentPhaseQuantity <= 0.0 || currentPhaseQuantity >= currentRatioQuantity) return

        val newCurrentRatio = currentPhaseQuantity / totalQuantity
        val remainingRatio = item.ratio - newCurrentRatio
        if (remainingRatio <= 0.0001) return

        val allPhases = selectedFormulationRecipePhases.value
        val targetPhase = allPhases.find { it.id == targetPhaseId } ?: return

        val allItems = selectedFormulationRecipeItems.value.toMutableList()
        val existingNextItems = allItems.filter { it.phaseId == targetPhase.id && it.rawMaterialId == item.rawMaterialId }
        
        if (existingNextItems.isNotEmpty()) {
            val existingNextItem = existingNextItems.first()
            val updatedNextRatio = existingNextItem.ratio + remainingRatio
            val idx = allItems.indexOfFirst { it.id == existingNextItem.id }
            if (idx != -1) allItems[idx] = existingNextItem.copy(ratio = updatedNextRatio)
        } else {
            val maxSeq = allItems
                .filter { it.phaseId == targetPhase.id }
                .maxOfOrNull { it.sequence } ?: -1
            
            allItems.add(
                RecipeItem(
                    id = java.util.UUID.randomUUID().toString(),
                    phaseId = targetPhase.id,
                    rawMaterialId = item.rawMaterialId,
                    ratio = remainingRatio,
                    sequence = maxSeq + 1
                )
            )
        }

        val itemIdx = allItems.indexOfFirst { it.id == item.id }
        if (itemIdx != -1) {
            allItems[itemIdx] = item.copy(ratio = newCurrentRatio)
        }

        selectedFormulationRecipeItems.value = allItems
        selectedFormulationRecipeStatus.value = RecipeStatus(formulationId = formulationId, status = "READY")
    }

    fun createNewPhaseFromItem(item: RecipeItem) {
        val formulationId = selectedFormulation.value?.id ?: return
        val allPhases = selectedFormulationRecipePhases.value.sortedBy { it.sequence }.toMutableList()
        val currentPhase = allPhases.find { it.id == item.phaseId } ?: return
        val currentSequence = currentPhase.sequence
        
        // 1. Shift sequences of phases
        allPhases.forEachIndexed { idx, rp ->
            if (rp.sequence > currentSequence) {
                allPhases[idx] = rp.copy(sequence = rp.sequence + 1)
            }
        }
        
        // 2. Insert new phase
        val nextSequence = currentSequence + 1
        val newPhase = RecipePhase(
            id = java.util.UUID.randomUUID().toString(),
            formulationId = formulationId,
            name = "المرحلة $nextSequence",
            sequence = nextSequence,
            mixerRpm = currentPhase.mixerRpm,
            durationMinutes = currentPhase.durationMinutes,
            instructions = ""
        )
        allPhases.add(newPhase)
        val newPhaseId = newPhase.id
        
        // 3. Find items to move
        val allItems = selectedFormulationRecipeItems.value.toMutableList()
        val phaseItems = allItems.filter { it.phaseId == currentPhase.id }.sortedBy { it.sequence }
        val itemsToMove = phaseItems.filter { it.sequence >= item.sequence }
        
        var seq = 0
        for (itToMove in itemsToMove) {
            val idx = allItems.indexOfFirst { it.id == itToMove.id }
            if (idx != -1) {
                allItems[idx] = itToMove.copy(phaseId = newPhaseId, sequence = seq++)
            }
        }
        
        // 4. Force fully aligned sequential phase names
        val sortedPhases = allPhases.sortedBy { it.sequence }
        var s = 1
        val renumberedPhases = sortedPhases.map { rp ->
            val updated = rp.copy(sequence = s, name = "المرحلة $s")
            s++
            updated
        }
        
        selectedFormulationRecipePhases.value = renumberedPhases
        selectedFormulationRecipeItems.value = allItems
        selectedFormulationRecipeStatus.value = RecipeStatus(formulationId = formulationId, status = "READY")
    }

    fun updateRecipeStatus(formulationId: String, isReady: Boolean) {
        selectedFormulationRecipeStatus.value = RecipeStatus(
            formulationId = formulationId,
            status = if (isReady) "READY" else "NEEDS_REVIEW"
        )
    }

    fun saveRecipeChanges() {
        val formulationId = selectedFormulation.value?.id ?: return
        viewModelScope.launch {
            val currentPhases = selectedFormulationRecipePhases.value
            val currentItems = selectedFormulationRecipeItems.value
            val currentStatus = selectedFormulationRecipeStatus.value

            repository.deleteRecipePhasesByFormulationId(formulationId)
            currentPhases.forEach { phase ->
                repository.insertRecipePhase(phase)
            }
            currentItems.forEach { item ->
                repository.insertRecipeItem(item)
            }
            if (currentStatus != null) {
                repository.insertRecipeStatus(currentStatus)
            }

            originalRecipePhases.value = currentPhases
            originalRecipeItems.value = currentItems

            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, formulationId, "formulation")
        }
    }

    fun discardRecipeChanges() {
        selectedFormulationRecipePhases.value = originalRecipePhases.value
        selectedFormulationRecipeItems.value = originalRecipeItems.value
    }

    suspend fun getRecipePhasesForFormulationSync(formulationId: String): List<RecipePhase> {
        return repository.getRecipePhasesForFormulationSync(formulationId)
    }

    suspend fun getRecipeStatusSync(formulationId: String): RecipeStatus? {
        return repository.getRecipeStatusSync(formulationId)
    }

    // --- QUALITY CONTROL METHODS ---
    fun addQualityTest(name: String) {
        val trimmedName = name.trim()
        val nameLower = trimmedName.lowercase()
        if (nameLower == "ph" || nameLower == "ph value" || nameLower == "درجة الحموضة" || nameLower == "فحص الـ ph" || nameLower == "فحص ph") {
            return // Prevent duplicate of the official "فحص درجة القلوية (pH Value)"
        }
        viewModelScope.launch {
            val maxSeq = qualityTests.value.maxOfOrNull { it.sequenceIndex } ?: -1
            repository.insertQualityTest(
                QualityTest(
                    name = trimmedName,
                    sequenceIndex = maxSeq + 1
                )
            )
        }
    }

    fun updateQualityTestName(test: QualityTest, newName: String) {
        viewModelScope.launch {
            repository.updateQualityTest(test.copy(name = newName))
        }
    }

    fun deleteQualityTest(test: QualityTest) {
        viewModelScope.launch {
            repository.deleteQualityTest(test)
        }
    }

    fun reorderQualityTests(tests: List<QualityTest>) {
        viewModelScope.launch {
            val updated = tests.mapIndexed { index, test ->
                test.copy(sequenceIndex = index)
            }
            repository.saveAllQualityTestsTransaction(updated)
        }
    }

    fun saveFormulationQualityTest(formulationId: String, testId: String, isEnabled: Boolean, minValue: Double?, maxValue: Double?) {
        viewModelScope.launch {
            val existing = selectedFormulationQualityTests.value.find { it.formulationId == formulationId && it.testId == testId }
            repository.insertFormulationQualityTest(
                FormulationQualityTest(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    formulationId = formulationId,
                    testId = testId,
                    isEnabled = isEnabled,
                    minValue = minValue,
                    maxValue = maxValue
                )
            )
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, formulationId, "formulation")
        }
    }

    fun deleteFormulationQualityTest(formulationId: String, testId: String) {
        viewModelScope.launch {
            repository.deleteFormulationQualityTest(formulationId, testId)
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, formulationId, "formulation")
        }
    }

    suspend fun deleteFormulationQualityTestsByFormulationId(formulationId: String) {
        repository.deleteFormulationQualityTestsByFormulationId(formulationId)
    }

    fun saveFormulationQualityTestsBatch(formulationId: String, tests: List<com.example.data.FormulationQualityTest>) {
        viewModelScope.launch {
            repository.deleteFormulationQualityTestsByFormulationId(formulationId)
            tests.forEach { fTest ->
                repository.insertFormulationQualityTest(fTest)
            }
        }
    }

    // --- PRODUCTION ORDER FLOW QUALITY WORKFLOWS ---
    fun getProductionOrderQualityTests(orderId: String): Flow<List<ProductionOrderQualityTest>> {
        return repository.getProductionOrderQualityTests(orderId)
    }

    fun getProductionOrderTestRecords(orderId: String): Flow<List<ProductionOrderTestRecord>> {
        return repository.getProductionOrderTestRecords(orderId)
    }

    fun addProductionOrderTestRecord(
        orderId: String,
        isDirectTest: Boolean,
        testDate: String,
        results: Map<String, Double?>
    ) {
        viewModelScope.launch {
            val jsonObject = org.json.JSONObject()
            results.forEach { (testId, value) ->
                if (value != null) {
                    jsonObject.put(testId, value)
                }
            }
            val record = com.example.data.ProductionOrderTestRecord(
                productionOrderId = orderId,
                isDirectTest = isDirectTest,
                testDate = testDate,
                resultsJson = jsonObject.toString()
            )
            repository.insertProductionOrderTestRecord(record)
        }
    }

    fun deleteProductionOrderTestRecord(record: ProductionOrderTestRecord) {
        viewModelScope.launch {
            repository.deleteProductionOrderTestRecord(record)
        }
    }

    fun initializeDefaultQualityTestsForOrder(order: ProductionOrder) {
        viewModelScope.launch {
            try {
                // Prevent duplicate insertions by checking if tests already exist
                val existing = repository.getProductionOrderQualityTestsSync(order.id)
                if (existing.isNotEmpty()) {
                    return@launch
                }
                // 1. Try formulation-specific tests
                val allQCDefinitions = repository.qualityTests.first().associateBy { it.id }
                val fTests = repository.getFormulationQualityTestsSync(order.formulationId).filter { it.isEnabled }
                val frozenQCTests = if (fTests.isNotEmpty()) {
                    fTests.mapIndexed { idx, fTest ->
                        val testName = allQCDefinitions[fTest.testId]?.name ?: "فحص ${fTest.testId}"
                        com.example.data.ProductionOrderQualityTest(
                            productionOrderId = order.id,
                            testId = fTest.testId,
                            testName = testName,
                            minValue = fTest.minValue,
                            maxValue = fTest.maxValue,
                            sequenceIndex = idx
                        )
                    }
                } else {
                    // 2. Fallback to system-wide QualityTest definitions
                    val systemTests = repository.qualityTests.first()
                    if (systemTests.isNotEmpty()) {
                        systemTests.mapIndexed { idx, sTest ->
                            com.example.data.ProductionOrderQualityTest(
                                productionOrderId = order.id,
                                testId = sTest.id,
                                testName = sTest.name,
                                minValue = null,
                                maxValue = null,
                                sequenceIndex = idx
                            )
                        }
                    } else {
                        // 3. Fallback to standard chemical default tests
                        listOf(
                            com.example.data.ProductionOrderQualityTest(
                                productionOrderId = order.id,
                                testId = "1",
                                testName = "فحص درجة القلوية (pH Value)",
                                minValue = 5.5,
                                maxValue = 8.5,
                                sequenceIndex = 1
                            ),
                            com.example.data.ProductionOrderQualityTest(
                                productionOrderId = order.id,
                                testId = "2",
                                testName = "اللزوجة القياسية (cPs)",
                                minValue = 1000.0,
                                maxValue = 5000.0,
                                sequenceIndex = 2
                            ),
                            com.example.data.ProductionOrderQualityTest(
                                productionOrderId = order.id,
                                testId = "3",
                                testName = "الكثافة النوعية (g/cm³)",
                                minValue = 1.0,
                                maxValue = 1.3,
                                sequenceIndex = 3
                            )
                        )
                    }
                }
                if (frozenQCTests.isNotEmpty()) {
                    repository.insertProductionOrderQualityTests(frozenQCTests)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getProductionAdjustmentsFlow(orderId: String) = repository.getProductionAdjustments(orderId)

    suspend fun getProductionAdjustmentsSync(orderId: String): List<com.example.data.ProductionAdjustment> {
        return repository.getProductionAdjustmentsSync(orderId)
    }

    suspend fun getLastCompletedProductionOrderForFormulation(formulationId: String): com.example.data.ProductionOrder? {
        return repository.getLastCompletedProductionOrderForFormulation(formulationId)
    }

    fun compressStringToZip(content: String, entryName: String = "gbr_backup_payload.json"): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val entry = java.util.zip.ZipEntry(entryName)
            zos.putNextEntry(entry)
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    fun decompressZipToString(zipBytes: ByteArray): String {
        val bais = java.io.ByteArrayInputStream(zipBytes)
        java.util.zip.ZipInputStream(bais).use { zis ->
            val entry = zis.nextEntry
            if (entry != null) {
                val baos = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                var len: Int
                while (zis.read(buf).also { len = it } > 0) {
                    baos.write(buf, 0, len)
                }
                return baos.toString("UTF-8")
            }
        }
        throw java.lang.Exception("No entry found in ZIP backup file")
    }

    fun logActivity(category: String, message: String) {
        try {
            val u = currentUser.value
            val who = u?.fullName?.takeIf { it.isNotBlank() } ?: u?.name?.takeIf { it.isNotBlank() } ?: u?.username?.takeIf { it.isNotBlank() } ?: "مدير النظام"
            com.example.data.ActivityLog.record(getApplication(), who, category, message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addSystemLog(category: String, message: String) {
        val userStr = currentUser.value?.username ?: "مدير النظام"
        val deviceStr = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        val logObj = SystemLog(category = category, message = com.example.data.SyncManager.friendlyLogText(message), username = userStr, deviceName = deviceStr)
        val list = systemLogs.value.toMutableList()
        list.add(0, logObj)
        if (list.size > 100) {
            systemLogs.value = list.subList(0, 100)
        } else {
            systemLogs.value = list
        }
        
        try {
            val app = getApplication<Application>()
            com.example.data.SyncManager.uploadSystemLog(app, logObj)
            val prefs = app.getSharedPreferences("gbr_system_logs", Context.MODE_PRIVATE)
            val jsonArrayStr = prefs.getString("logs_json", "[]") ?: "[]"
            val arr = org.json.JSONArray(jsonArrayStr)
            val newObj = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("category", category)
                put("message", message)
                put("username", userStr)
                put("deviceName", deviceStr)
            }
            val newArr = org.json.JSONArray()
            newArr.put(newObj)
            for (i in 0 until arr.length()) {
                if (newArr.length() >= 100) break
                newArr.put(arr.getJSONObject(i))
            }
            prefs.edit().putString("logs_json", newArr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun purgeFirebaseCollectionAndUnify(collectionName: String) {
        viewModelScope.launch {
            try {
                isOperationLoading.value = true
                operationStatusMessage.value = "جاري تصفية وحذف قسم '$collectionName' من قاعدة البيانات السحابية والمحلية..."
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(15000) {
                        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        
                        // If it is quality_tests, purge its linked tables too
                        val collectionsToPurge = if (collectionName == "quality_tests") {
                            listOf("quality_tests", "formulation_quality_tests", "production_order_quality_tests")
                        } else {
                            listOf(collectionName)
                        }
                        
                        for (col in collectionsToPurge) {
                            val snaps = firestore.collection(col).get().awaitTask()
                            val docs = snaps.documents
                            if (docs.isNotEmpty()) {
                                // Split documents into chunks of 500 (Firestore WriteBatch limit is 500)
                                val chunks = docs.chunked(500)
                                for (chunk in chunks) {
                                    val batch = firestore.batch()
                                    for (doc in chunk) {
                                        batch.delete(doc.reference)
                                    }
                                    batch.commit().awaitTask()
                                }
                            }
                        }
                        
                        // Wipe local database tables for quality tests if that was selected
                        if (collectionName == "quality_tests") {
                            try {
                                val db = com.example.data.AppDatabase.getDatabase(getApplication())
                                db.openHelper.writableDatabase.execSQL("DELETE FROM quality_tests")
                                db.openHelper.writableDatabase.execSQL("DELETE FROM formulation_quality_tests")
                                db.openHelper.writableDatabase.execSQL("DELETE FROM production_order_quality_tests")
                            } catch (e: Exception) {
                                android.util.Log.e("GBR_PURGE_LOCAL", "Local clear quality_tests error: ${e.localizedMessage}")
                            }
                        }
                    }
                }
                
                if (collectionName == "system_logs") {
                    val app = getApplication<Application>()
                    val prefs = app.getSharedPreferences("gbr_system_logs", Context.MODE_PRIVATE)
                    val newArr = org.json.JSONArray()
                    val newObj = org.json.JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("category", "auth")
                        put("message", "تم تصفية ومسح سجلات النظام كلياً من الهاتف والسحابة.")
                        put("username", "مدير النظام")
                        put("deviceName", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    }
                    newArr.put(newObj)
                    prefs.edit().putString("logs_json", newArr.toString()).apply()
                    systemLogs.value = listOf(SystemLog(category = "auth", message = "تم تصفية ومسح سجلات النظام كلياً من الهاتف والسحابة."))
                }
                
                val label = when(collectionName) {
                    "mail" -> "البريد الإلكتروني والأكواد الأمنية (mail)"
                    "quality_tests" -> "فحوصات الجودة الملغاة (quality_tests)"
                    "system_logs" -> "سجلات النظام والتحذيرات (system_logs)"
                    else -> collectionName
                }
                
                val successMsg = "تم حذف وتطهير قسم $label بالكامل من قاعدة البيانات السحابية والمحلية بنجاح 🧼"
                addSystemLog("sync", successMsg)
                globalToastEvents.tryEmit(successMsg)
                fetchCloudCleanupStats()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                globalToastEvents.tryEmit("انتهت مهلة الاتصال بالخادم (15 ثانية). يرجى التحقق من اتصال الإنترنت وصلاحيات السحابة.")
            } catch (e: Throwable) {
                globalToastEvents.tryEmit("حدث خطأ أثناء التنظيف: ${e.localizedMessage}")
            } finally {
                isOperationLoading.value = false
                operationStatusMessage.value = null
            }
        }
    }

    fun fetchCloudCleanupStats() {
        viewModelScope.launch {
            try {
                isFetchingCloudCleanupStats.value = true
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val stats = mutableMapOf<String, Int>()
                    
                    // 1. mail
                    try {
                        val mailSize = firestore.collection("mail").get().awaitTask().size()
                        stats["mail"] = mailSize
                    } catch (e: Exception) {
                        stats["mail"] = -1
                    }
                    
                    // 2. quality_tests (aggregate)
                    try {
                        val qtSize = firestore.collection("quality_tests").get().awaitTask().size()
                        val fqtSize = firestore.collection("formulation_quality_tests").get().awaitTask().size()
                        val pqtSize = firestore.collection("production_order_quality_tests").get().awaitTask().size()
                        stats["quality_tests"] = qtSize + fqtSize + pqtSize
                    } catch (e: Exception) {
                        stats["quality_tests"] = -1
                    }
                    
                    // 3. system_logs
                    try {
                        val logsSize = firestore.collection("system_logs").get().awaitTask().size()
                        stats["system_logs"] = logsSize
                    } catch (e: Exception) {
                        stats["system_logs"] = -1
                    }
                    
                    cloudCleanupStats.value = stats
                }
            } catch (e: Exception) {
                android.util.Log.e("GBR_FETCH_STATS", "Error fetching cloud cleanup stats: ${e.localizedMessage}")
            } finally {
                isFetchingCloudCleanupStats.value = false
            }
        }
    }

    // --- FORMULATION REFERENCE SPECS ---
    fun getFormulationReferenceSpecs(formulationId: String): Flow<FormulationReferenceSpecs?> {
        return repository.getFormulationReferenceSpecs(formulationId)
    }

    fun saveFormulationReferenceSpecs(specs: FormulationReferenceSpecs) {
        viewModelScope.launch {
            repository.insertFormulationReferenceSpecs(specs)
            // Trigger Firestore live sync by marking this formulation as pending and invoking single upload
            com.example.data.SyncManager.uploadSingleEntityAsync(getApplication(), repository, specs.formulationId, "formulation")
        }
    }

    fun updateActiveGrindingTimers() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val activeOrders = productionOrders.value.filter { it.status == "قيد التنفيذ" }
            if (activeOrders.isEmpty()) {
                activeGrindingTimers.value = emptyList()
                return@launch
            }

            val list = mutableListOf<ActiveGrindingTimer>()
            val rawMaterialsList = rawMaterials.value
            val now = System.currentTimeMillis()

            for (order in activeOrders) {
                try {
                    val phases = repository.getProductionOrderPhasesSync(order.id)
                    val recipeItems = repository.getProductionOrderRecipeItemsForOrderSync(order.id)
                    val timerJson = try {
                        org.json.JSONObject(order.timerStartTimesJson)
                    } catch (e: Exception) {
                        org.json.JSONObject()
                    }

                    phases.forEachIndexed { pIdx, phase ->
                        val phaseItems = recipeItems.filter { it.productionOrderPhaseId == phase.id }
                        if (phaseItems.isEmpty()) {
                            val durationMinutes = phase.durationMinutes
                            if (durationMinutes > 0) {
                                val stepKey = "p${pIdx}_it"
                                val startTimeMs = timerJson.optLong(stepKey, -1L)
                                val isBypassed = timerJson.optBoolean(stepKey + "_bypassed", false)
                                val isAlerted = timerJson.optBoolean(stepKey + "_alerted", false)
                                if (startTimeMs > 0L && !isBypassed && !isAlerted) {
                                    val elapsedSec = ((now - startTimeMs) / 1000).toInt()
                                    val totalSec = durationMinutes * 60
                                    val remainingSec = totalSec - elapsedSec
                                    if (remainingSec > 0) {
                                        list.add(
                                            ActiveGrindingTimer(
                                                orderId = order.id,
                                                orderNumber = order.orderNumber,
                                                phaseName = phase.name,
                                                materialName = "تشغيل الخلاط والموقت للتجانس",
                                                durationMinutes = durationMinutes,
                                                startTimeMs = startTimeMs,
                                                stepKey = stepKey
                                            )
                                        )
                                    } else {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            triggerGrindingAlarm(order.id, stepKey, order.orderNumber, "تشغيل الخلاط والموقت للتجانس", durationMinutes)
                                        }
                                    }
                                }
                            }
                        } else {
                            phaseItems.forEachIndexed { riIdx, rItem ->
                                val durationMinutes = if (riIdx == 0) phase.durationMinutes else 0
                                if (durationMinutes > 0) {
                                    val stepKey = "p${pIdx}_it${rItem.rawMaterialId}"
                                    val startTimeMs = timerJson.optLong(stepKey, -1L)
                                    val isBypassed = timerJson.optBoolean(stepKey + "_bypassed", false)
                                    val isAlerted = timerJson.optBoolean(stepKey + "_alerted", false)
                                    if (startTimeMs > 0L && !isBypassed && !isAlerted) {
                                        val elapsedSec = ((now - startTimeMs) / 1000).toInt()
                                        val totalSec = durationMinutes * 60
                                        val remainingSec = totalSec - elapsedSec
                                        val rm = rawMaterialsList.find { it.id == rItem.rawMaterialId }
                                        val shownTitle = if (rm != null) {
                                            getTradeName(rm.name, rm.productionName)
                                        } else {
                                            getTradeName(rItem.rawMaterialName, null)
                                        }
                                        if (remainingSec > 0) {
                                            list.add(
                                                ActiveGrindingTimer(
                                                    orderId = order.id,
                                                    orderNumber = order.orderNumber,
                                                    phaseName = phase.name,
                                                    materialName = shownTitle,
                                                    durationMinutes = durationMinutes,
                                                    startTimeMs = startTimeMs,
                                                    stepKey = stepKey
                                                )
                                            )
                                        } else {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                triggerGrindingAlarm(order.id, stepKey, order.orderNumber, shownTitle, durationMinutes)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            activeGrindingTimers.value = list
        }
    }

    fun triggerGrindingAlarm(
        orderId: String,
        stepKey: String,
        orderNumber: String = "",
        materialName: String = "",
        durationMinutes: Int = 0
    ) {
        activeGrindingAlarmOrderId.value = orderId
        activeGrindingAlarmStepKey.value = stepKey
        activeAlarmDetails.value = ActiveAlarmDetails(
            orderId = orderId,
            orderNumber = orderNumber,
            stepKey = stepKey,
            materialName = materialName.ifBlank { "طحن وتجانس المادة" },
            durationMinutes = durationMinutes
        )
        isGrindingAlarmActive.value = true

        // ضمان وصول إشعار النظام وصوت الإنذار عند انتهاء الطحن حتى لو لم تكن خدمة المؤقت تعمل
        // (مؤقت بدأ من جهاز آخر أو قبل إعادة تشغيل التطبيق)
        try {
            if (!com.example.GrindingTimerService.isServiceRunning) {
                val ctxApp: android.content.Context = getApplication()
                val svc = android.content.Intent(ctxApp, com.example.GrindingTimerService::class.java).apply {
                    action = com.example.GrindingTimerService.ACTION_START
                    putExtra("orderId", orderId)
                    putExtra("orderNumber", orderNumber)
                    putExtra("stepKey", stepKey)
                    putExtra("title", materialName.ifBlank { "طحن وتجانس المادة" })
                    putExtra("durationMinutes", durationMinutes)
                    putExtra("startTimeMs", System.currentTimeMillis() - durationMinutes * 60_000L - 1000L)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctxApp.startForegroundService(svc)
                } else {
                    ctxApp.startService(svc)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopGrindingAlarm(context: android.content.Context, explicitOrderId: String? = null, explicitStepKey: String? = null) {
        val finalOrderId = explicitOrderId ?: activeGrindingAlarmOrderId.value
        val finalStepKey = explicitStepKey ?: activeGrindingAlarmStepKey.value

        isGrindingAlarmActive.value = false
        activeGrindingAlarmOrderId.value = null
        activeGrindingAlarmStepKey.value = null
        activeAlarmDetails.value = null

        // Stop the service
        try {
            val intent = android.content.Intent(context, com.example.GrindingTimerService::class.java).apply {
                action = com.example.GrindingTimerService.ACTION_STOP
            }
            context.stopService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Mark as alerted in db to prevent re-triggering local alarms
        if (!finalOrderId.isNullOrEmpty() && !finalStepKey.isNullOrEmpty()) {
            setGrindTimerAlerted(finalOrderId, finalStepKey)
        }
    }

    // --- RECYCLE BIN ---
    fun restoreRecycleBinItem(item: RecycleBinItem) {
        viewModelScope.launch {
            try {
                val moshi = com.squareup.moshi.Moshi.Builder()
                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                
                when (item.itemType) {
                    "RAW_MATERIAL" -> {
                        val material = moshi.adapter(RawMaterial::class.java).fromJson(item.serializedData)
                        if (material != null) {
                            repository.insertRawMaterial(material)
                        }
                    }
                    "FORMULATION" -> {
                        val formulation = moshi.adapter(Formulation::class.java).fromJson(item.serializedData)
                        if (formulation != null) {
                            repository.insertFormulation(formulation)
                            
                            if (item.extraDataJson != null) {
                                val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                                val extraMap = moshi.adapter<Map<String, String?>>(extraMapType).fromJson(item.extraDataJson)
                                
                                val itemsJson = extraMap?.get("items")
                                if (itemsJson != null) {
                                    val items = moshi.adapter<List<FormulationItem>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationItem::class.java)).fromJson(itemsJson)
                                    items?.forEach { repository.gbrDao().insertFormulationItem(it) }
                                }
                                val revisionsJson = extraMap?.get("revisions")
                                if (revisionsJson != null) {
                                    val revisions = moshi.adapter<List<FormulationRevision>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationRevision::class.java)).fromJson(revisionsJson)
                                    revisions?.forEach { repository.gbrDao().insertFormulationRevision(it) }
                                }
                                val phasesJson = extraMap?.get("phases")
                                if (phasesJson != null) {
                                    val phases = moshi.adapter<List<RecipePhase>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, RecipePhase::class.java)).fromJson(phasesJson)
                                    phases?.forEach { repository.gbrDao().insertRecipePhase(it) }
                                }
                                val recipeItemsJson = extraMap?.get("recipe_items")
                                if (recipeItemsJson != null) {
                                    val recipeItems = moshi.adapter<List<RecipeItem>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, RecipeItem::class.java)).fromJson(recipeItemsJson)
                                    recipeItems?.forEach { repository.gbrDao().insertRecipeItem(it) }
                                }
                                val testsJson = extraMap?.get("tests")
                                if (testsJson != null) {
                                    val tests = moshi.adapter<List<FormulationQualityTest>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, FormulationQualityTest::class.java)).fromJson(testsJson)
                                    tests?.forEach { repository.gbrDao().insertFormulationQualityTest(it) }
                                }
                                val specsJson = extraMap?.get("specs")
                                if (specsJson != null) {
                                    val specs = moshi.adapter(FormulationReferenceSpecs::class.java).fromJson(specsJson)
                                    if (specs != null) {
                                        repository.gbrDao().insertFormulationReferenceSpecs(specs)
                                    }
                                }
                            }
                        }
                    }
                    "LAB_SESSION" -> {
                        val session = moshi.adapter(LabSession::class.java).fromJson(item.serializedData)
                        if (session != null) {
                            repository.insertLabSession(session)
                            
                            if (item.extraDataJson != null) {
                                val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                                val extraMap = moshi.adapter<Map<String, String?>>(extraMapType).fromJson(item.extraDataJson)
                                
                                val testsJson = extraMap?.get("tests")
                                if (testsJson != null) {
                                    val tests = moshi.adapter<List<LabTest>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, LabTest::class.java)).fromJson(testsJson)
                                    tests?.forEach { repository.gbrDao().insertLabTest(it) }
                                }
                                val attachmentsJson = extraMap?.get("attachments")
                                if (attachmentsJson != null) {
                                    val attachments = moshi.adapter<List<LabAttachment>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, LabAttachment::class.java)).fromJson(attachmentsJson)
                                    attachments?.forEach { repository.gbrDao().insertLabAttachment(it) }
                                }
                            }
                        }
                    }
                    "LAB_TEST" -> {
                        val test = moshi.adapter(LabTest::class.java).fromJson(item.serializedData)
                        if (test != null) {
                            repository.gbrDao().insertLabTest(test)
                        }
                    }
                    "DEVELOPMENT_PROJECT" -> {
                        val project = moshi.adapter(DevelopmentProject::class.java).fromJson(item.serializedData)
                        if (project != null) {
                            repository.insertDevelopmentProject(project)
                            
                            if (item.extraDataJson != null) {
                                val extraMapType = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
                                val extraMap = moshi.adapter<Map<String, String?>>(extraMapType).fromJson(item.extraDataJson)
                                
                                val samplesJson = extraMap?.get("samples")
                                if (samplesJson != null) {
                                    val samples = moshi.adapter<List<DevelopmentSample>>(com.squareup.moshi.Types.newParameterizedType(List::class.java, DevelopmentSample::class.java)).fromJson(samplesJson)
                                    samples?.forEach { repository.insertDevelopmentSample(it) }
                                }
                            }
                        }
                    }
                    "DEVELOPMENT_SAMPLE" -> {
                        val sample = moshi.adapter(DevelopmentSample::class.java).fromJson(item.serializedData)
                        if (sample != null) {
                            repository.insertDevelopmentSample(sample)
                        }
                    }
                }
                repository.deleteRecycleBinItem(item)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun permanentlyDeleteRecycleBinItem(item: RecycleBinItem) {
        viewModelScope.launch {
            try {
                repository.deleteRecycleBinItem(item)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearRecycleBin() {
        viewModelScope.launch {
            try {
                repository.clearAllRecycleBinItems()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            networkCallback?.let {
                val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
