package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "raw_materials")
data class RawMaterial(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String, // الاسم الحقيقي
    val productionName: String = "", // اسم الإنتاج
    val price: Double = 0.0, // سعر المادة
    val priceUnit: String = "شيكل", // وحدة السعر (مثلاً شيكل أو دولار)
    val notes: String = "",
    val tdsUri: String? = null, // مسار ملف PDF لـ TDS
    val isActive: Boolean = true // مادة فعالة
)

@Entity(
    tableName = "price_history",
    foreignKeys = [
        ForeignKey(
            entity = RawMaterial::class,
            parentColumns = ["id"],
            childColumns = ["rawMaterialId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("rawMaterialId")]
)
data class PriceHistoryEntry(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val rawMaterialId: String,
    val oldPrice: Double,
    val newPrice: Double,
    val dateChange: String
)

@Entity(tableName = "formulations")
data class Formulation(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val description: String = "",
    val imageUri: String? = null,
    val version: String = "1.0.0",
    val status: String = "🟡 قيد التطوير",
    val createdAt: String = "",
    val notes: String = "",
    val supports18L: Boolean = false,
    val netWeight18L: String = "",
    val supports5L: Boolean = false,
    val netWeight5L: String = "",
    val packagingWeightsJson: String = "",
    // خط الإنتاج التابع للتركيبة (0 = الخط الأول، 1 = الخط الثاني، -1 = غير محدد): يحدد أيقونة الماء في صالة الإنتاج
    @androidx.room.ColumnInfo(defaultValue = "-1") val productionLine: Int = -1
)

@Entity(
    tableName = "formulation_revisions",
    foreignKeys = [
        ForeignKey(
            entity = Formulation::class,
            parentColumns = ["id"],
            childColumns = ["formulationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formulationId")]
)
data class FormulationRevision(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val formulationId: String,
    val version: String,
    val dateChange: String,
    val materialName: String,
    val oldValue: String,
    val newValue: String,
    val editReason: String,
    val snapshotJson: String? = null
)

@Entity(
    tableName = "formulation_items",
    foreignKeys = [
        ForeignKey(
            entity = Formulation::class,
            parentColumns = ["id"],
            childColumns = ["formulationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RawMaterial::class,
            parentColumns = ["id"],
            childColumns = ["rawMaterialId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formulationId"), Index("rawMaterialId")]
)
data class FormulationItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val formulationId: String,
    val rawMaterialId: String,
    val quantityMultiplier: Double, // Quantity required per 1000 kg of batch
    val needsGrinding: Boolean = false,
    val grindingDurationMinutes: Int = 0,
    val simulatedPrice: Double? = null,
    val sequence: Int = 0
)

@Entity(tableName = "production_logs")
data class ProductionLog(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val formulationId: String,
    val formulationName: String,
    val operatorName: String,
    val batchWeightKg: Double,
    val status: String, // "جاري العمل" (In Progress), "مكتمل" (Completed)
    val timestamp: Long = System.currentTimeMillis()
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RevisionItemSnapshot(
    val rawMaterialId: String,
    val rawMaterialName: String,
    val quantityMultiplier: Double,
    val needsGrinding: Boolean,
    val grindingDurationMinutes: Int,
    val changeType: String, // "NORMAL", "BLUE", "RED", "GREY", "YELLOW"
    val previousQuantityMultiplier: Double? = null, // الكمية قبل التعديل (للمادة المعدّلة BLUE)
    val sequence: Int? = null // ترتيب العرض (null = لقطة قديمة)
)

@Entity(tableName = "production_orders")
data class ProductionOrder(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val orderNumber: String,       // PO-2026-0001
    val batchNumber: String,       // B-2026-0001
    val formulationId: String,
    val formulationName: String,
    val formulationVersion: String,
    val requiredWeightKg: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String,            // "مسودة", "قيد التنفيذ", "مكتمل", "ملغي", "جاهز للتنفيذ"
    val notes: String = "",
    val scaleFactor: Double = 1.0,          // Added operating factor (default 1.0)
    val originalWeightKg: Double = 0.0,     // Total weight of the original formulation
    val progressPercent: Int = 0,           // 0, 25, 50, 75, 100
    val currentPhaseIndex: Int = 0,         // Last active phase index (0-based)
    val currentItemIndex: Int = 0,          // Last active item index within that phase (0-based)
    val completedItemsJson: String = "[]",  // Checked and completed raw materials JSON: "[\"uuid1\",\"uuid2\"]"
    val packagingSnapshotJson: String = "", // Packaging snapshot JSON definition
    val actualPackagingJson: String = "[]", // Actual packaging filled amounts JSON: "[{"name":"18L","weight":...,"actualCount":35}]"
    val timerStartTimesJson: String = "{}", // Map of string step key -> long epoch timestamp of start
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val operatorName: String = "المشرف"
)

@Entity(
    tableName = "production_order_items",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionOrderItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val rawMaterialId: String,
    val rawMaterialName: String,
    val rawMaterialPrice: Double,
    val rawMaterialPriceUnit: String,
    val quantityMultiplier: Double,   // original multiplier per 1000kg
    val calculatedQuantity: Double,   // actual calculated multiplier in kg (e.g. requiredWeightKg * quantityMultiplier / 1000)
    val needsGrinding: Boolean = false,
    val grindingDurationMinutes: Int = 0
)

@Entity(
    tableName = "production_order_events",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionOrderEvent(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val eventName: String, // "إنشاء أمر الإنتاج", "بدء التنفيذ", "إيقاف التنفيذ", "استئناف التنفيذ", "إكمال التنفيذ", "إلغاء التنفيذ"
    val timestamp: Long = System.currentTimeMillis(),
    val description: String = ""
)

@Entity(
    tableName = "recipe_phases",
    foreignKeys = [
        ForeignKey(
            entity = Formulation::class,
            parentColumns = ["id"],
            childColumns = ["formulationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formulationId")]
)
data class RecipePhase(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val formulationId: String,
    val name: String,
    val sequence: Int, // 1, 2, 3...
    val mixerRpm: Int = 0,
    val durationMinutes: Int = 0,
    val instructions: String = ""
)

@Entity(
    tableName = "recipe_items",
    foreignKeys = [
        ForeignKey(
            entity = RecipePhase::class,
            parentColumns = ["id"],
            childColumns = ["phaseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RawMaterial::class,
            parentColumns = ["id"],
            childColumns = ["rawMaterialId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("phaseId"), Index("rawMaterialId")]
)
data class RecipeItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val phaseId: String,
    val rawMaterialId: String,
    val ratio: Double = 1.0, // split ratio (e.g., 0.5) of formulation quantity
    val sequence: Int = 0    // sorting sequence within the phase
)

@Entity(
    tableName = "recipe_statuses",
    foreignKeys = [
        ForeignKey(
            entity = Formulation::class,
            parentColumns = ["id"],
            childColumns = ["formulationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formulationId")]
)
data class RecipeStatus(
    @PrimaryKey val formulationId: String,
    val status: String // "READY" or "NEEDS_REVIEW"
)

@Entity(
    tableName = "production_order_phases",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionOrderPhase(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val name: String,
    val sequence: Int,
    val mixerRpm: Int = 0,
    val durationMinutes: Int = 0,
    val instructions: String = ""
)

@Entity(
    tableName = "production_order_recipe_items",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrderPhase::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderPhaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderPhaseId")]
)
data class ProductionOrderRecipeItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderPhaseId: String,
    val rawMaterialId: String,
    val rawMaterialName: String,
    val ratio: Double = 1.0,
    val calculatedQuantity: Double, // scaled quantity (ratio * multiplier * scaleFactor)
    val sequence: Int = 0
)

@Entity(tableName = "quality_tests")
data class QualityTest(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val sequenceIndex: Int = 0
)

@Entity(
    tableName = "formulation_quality_tests",
    foreignKeys = [
        ForeignKey(
            entity = Formulation::class,
            parentColumns = ["id"],
            childColumns = ["formulationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = QualityTest::class,
            parentColumns = ["id"],
            childColumns = ["testId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formulationId"), Index("testId")]
)
data class FormulationQualityTest(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val formulationId: String,
    val testId: String,
    val isEnabled: Boolean = false,
    val minValue: Double? = null,
    val maxValue: Double? = null
)

@Entity(
    tableName = "production_order_quality_tests",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionOrderQualityTest(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val testId: String,
    val testName: String,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val sequenceIndex: Int = 0
)

@Entity(
    tableName = "production_order_test_records",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionOrderTestRecord(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val isDirectTest: Boolean,
    val testDate: String,
    val resultsJson: String, // Stringified JSON mapping testId as String keys to Double results
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "production_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = ProductionOrder::class,
            parentColumns = ["id"],
            childColumns = ["productionOrderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productionOrderId")]
)
data class ProductionAdjustment(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val productionOrderId: String,
    val rawMaterialId: String,
    val rawMaterialName: String,
    val originalQuantity: Double, // Quantity in KG
    val newQuantity: Double,      // Quantity in KG
    val difference: Double,      // new - original
    val reason: String,
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val userName: String
)

@Entity(tableName = "development_projects")
data class DevelopmentProject(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val createdAt: String,
    val lastUpdated: String
)

@Entity(
    tableName = "development_samples",
    foreignKeys = [
        ForeignKey(
            entity = DevelopmentProject::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class DevelopmentSample(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val projectId: String,
    val sampleName: String,
    val sampleNumber: String, // e.g. "RD-001"
    val createdAt: String,
    val targetWeightKg: Double = 1.0,
    val targetGoal: String = "",
    val initialNotes: String = "",
    val researchNotes: String = "",
    val isApproved: Boolean = false,
    val approvedDate: String = "",
    val resultsJson: String = "[]", // [{"name":"اللزوجة","value":"80 KU"},{"name":"pH","value":"8.5"}]
    val itemsJson: String = "[]", // [{"rawMaterialId":"uuid1","rawMaterialName":"ماء","originalQuantityMultiplier":840.0}]
    val recipeJson: String = "[]", // JSON representation of the sample's operating recipe
    val status: String? = "انتظار نتائج",
    val statusNotes: String? = null,
    val statusUpdatedAt: String? = null,
    val statusUpdatedBy: String? = null
)

@Entity(tableName = "sync_metadata")
data class SyncMetadata(
    @PrimaryKey val id: String,
    val entityType: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isPendingSync: Boolean = true,
    val lastError: String? = null,
    val syncStage: String = "PENDING", // PENDING, UPLOADING, FAILED, SUCCESS
    val lastAttempt: Long = 0L,
    val retryCount: Int = 0,
    val firebaseErrorCode: String? = null
)

@Entity(tableName = "laboratory_sessions")
data class LabSession(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionNumber: String,
    val testName: String,
    val testDate: String,
    val technicianName: String,
    val sampleOrProduct: String,
    val category: String,
    val testType: String,
    val notes: String = "",
    val comparisonType: String? = null,
    val partyA: String? = null,
    val partyB: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sampleProperties: String = ""
)

@Entity(tableName = "laboratory_tests")
data class LabTest(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val name: String,
    val status: String, // لم يبدأ, قيد التنفيذ, مكتمل, بانتظار النتيجة, خارج المواصفة
    val executionDate: String,
    val notes: String = "",
    val testValueA: String? = null,
    val testValueB: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "laboratory_attachments")
data class LabAttachment(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val testName: String,
    val filePathOrUrl: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "formulation_reference_specs")
data class FormulationReferenceSpecs(
    @PrimaryKey val formulationId: String,
    val approvalDate: String = "",
    
    // pH Test
    val phValue: String? = null,
    
    // Density Test
    val densityEmptyWeight: Double? = null,
    val densityFilledWeight: Double? = null,
    val densityFinalResult: Double? = null,
    
    // Solid Content
    val solidWeightBefore: Double? = null,
    val solidWeightAfter: Double? = null,
    val solidResultPct: Double? = null,
    
    // Net Binder Content
    val binderWeightBefore: Double? = null,
    val binderWeightAfter: Double? = null,
    val binderResultPct: Double? = null,
    
    // Viscosity Test
    val viscosityJson: String? = null,
    val viscosityFinalResult: Double? = null,
    
    // Viscosity after Dilution Test
    val viscosityDilutedJson: String? = null,
    val viscosityDilutedFinalResult: Double? = null,
    
    // Rheology Test
    val rheologyJson: String? = null,
    val rheologyIndexResult: Double? = null
)

@Entity(tableName = "operational_alerts")
data class OperationalAlert(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val title: String, // عنوان التنبيه
    val description: String, // الوصف
    val mainSection: String, // القسم الرئيسي: "التركيبات" | "أوامر الإنتاج" | "المختبر" | "المواد الخام" | "عام"
    val bindingScope: String, // التخصيص الاختياري: "ALL" أو معرف العنصر المحدد
    val bindingElementName: String = "", // اسم العنصر المحدد (مثلاً اسم التركيبة أو اسم أمر الإنتاج لسهولة العرض في مركز التنبيهات)
    val alertLevel: String, // مستويات التنبيه: "INFO" (ملاحظة) | "WARNING" (تنبيه) | "MANDATORY" (تنبيه إلزامي)
    val status: String = "ACTIVE", // حالة التنبيه: "ACTIVE" (نشط) | "COMPLETED" (تم التنفيذ) | "ARCHIVED" (مؤرشف)
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recycle_bin")
data class RecycleBinItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val originalId: String,
    val itemType: String, // "RAW_MATERIAL", "FORMULATION", "LAB_SESSION", "LAB_TEST", "DEVELOPMENT_PROJECT", "DEVELOPMENT_SAMPLE"
    val displayName: String,
    val serializedData: String,
    val deletedAt: Long = System.currentTimeMillis(),
    val extraDataJson: String? = null
)


