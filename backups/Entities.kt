package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "raw_materials")
data class RawMaterial(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val rawMaterialId: Int,
    val oldPrice: Double,
    val newPrice: Double,
    val dateChange: String
)

@Entity(tableName = "formulations")
data class Formulation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    val packagingWeightsJson: String = ""
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val formulationId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val formulationId: Int,
    val rawMaterialId: Int,
    val quantityMultiplier: Double, // Quantity required per 1000 kg of batch
    val needsGrinding: Boolean = false,
    val grindingDurationMinutes: Int = 0,
    val simulatedPrice: Double? = null
)

@Entity(tableName = "production_logs")
data class ProductionLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val formulationId: Int,
    val formulationName: String,
    val operatorName: String,
    val batchWeightKg: Double,
    val status: String, // "جاري العمل" (In Progress), "مكتمل" (Completed)
    val timestamp: Long = System.currentTimeMillis()
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RevisionItemSnapshot(
    val rawMaterialId: Int,
    val rawMaterialName: String,
    val quantityMultiplier: Double,
    val needsGrinding: Boolean,
    val grindingDurationMinutes: Int,
    val changeType: String // "NORMAL", "BLUE", "RED", "GREY", "YELLOW"
)

@Entity(tableName = "production_orders")
data class ProductionOrder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val orderNumber: String,       // PO-2026-0001
    val batchNumber: String,       // B-2026-0001
    val formulationId: Int,
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
    val completedItemsJson: String = "[]",  // Checked and completed raw materials JSON: "[1,2,3]"
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
    val rawMaterialId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val formulationId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phaseId: Int,
    val rawMaterialId: Int,
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
    @PrimaryKey val formulationId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderPhaseId: Int,
    val rawMaterialId: Int,
    val rawMaterialName: String,
    val ratio: Double = 1.0,
    val calculatedQuantity: Double, // scaled quantity (ratio * multiplier * scaleFactor)
    val sequence: Int = 0
)

@Entity(tableName = "quality_tests")
data class QualityTest(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val formulationId: Int,
    val testId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
    val testId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productionOrderId: Int,
    val rawMaterialId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val projectId: Int,
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
    val itemsJson: String = "[]" // [{"rawMaterialId":1,"rawMaterialName":"ماء","originalQuantityMultiplier":840.0}]
)
