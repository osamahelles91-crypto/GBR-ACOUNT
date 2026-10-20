package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class FormulationItemWithDetails(
    val id: String,
    val formulationId: String,
    val rawMaterialId: String,
    val quantityMultiplier: Double, // amount per 1000 kg batch
    val rawMaterialName: String,
    val rawMaterialProductionName: String,
    val rawMaterialUnit: String,
    val needsGrinding: Boolean,
    val grindingDurationMinutes: Int,
    val rawMaterialPrice: Double,
    val rawMaterialPriceUnit: String,
    val simulatedPrice: Double? = null,
    val sequence: Int = 0
)

@Dao
interface GbrDao {
    
    // --- RAW MATERIALS ---
    @Query("SELECT * FROM raw_materials ORDER BY id ASC")
    fun getAllRawMaterials(): Flow<List<RawMaterial>>

    @Query("SELECT * FROM raw_materials WHERE id = :id LIMIT 1")
    suspend fun getRawMaterialById(id: String): RawMaterial?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRawMaterial(rawMaterial: RawMaterial): Long

    @Update
    suspend fun updateRawMaterial(rawMaterial: RawMaterial)

    @Delete
    suspend fun deleteRawMaterial(rawMaterial: RawMaterial)

    // --- FORMULATIONS ---
    @Query("SELECT * FROM formulations ORDER BY id ASC")
    fun getAllFormulations(): Flow<List<Formulation>>

    @Query("SELECT * FROM formulations WHERE id = :id LIMIT 1")
    suspend fun getFormulationById(id: String): Formulation?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFormulation(formulation: Formulation): Long

    @Update
    suspend fun updateFormulation(formulation: Formulation)

    @Delete
    suspend fun deleteFormulation(formulation: Formulation)

    // --- FORMULATION ITEMS (RELATIONS) ---
    @Query("""
        SELECT fi.id, fi.formulationId, fi.rawMaterialId, fi.quantityMultiplier, 
               fi.needsGrinding, fi.grindingDurationMinutes,
               rm.name AS rawMaterialName, rm.productionName AS rawMaterialProductionName, 'كجم' AS rawMaterialUnit,
               rm.price AS rawMaterialPrice, rm.priceUnit AS rawMaterialPriceUnit,
               fi.simulatedPrice AS simulatedPrice, fi.sequence AS sequence
        FROM formulation_items fi 
        INNER JOIN raw_materials rm ON fi.rawMaterialId = rm.id 
        WHERE fi.formulationId = :formulationId
        ORDER BY fi.sequence ASC, fi.id ASC
    """)
    fun getFormulationItemsWithDetails(formulationId: String): Flow<List<FormulationItemWithDetails>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormulationItem(item: FormulationItem)

    @Transaction
    suspend fun setFormulationItemsTransaction(formulationId: String, items: List<FormulationItem>) {
        deleteFormulationItemsByFormulationId(formulationId)
        for (item in items) {
            insertFormulationItem(item)
        }
    }

    @Transaction
    suspend fun saveFormulationDetailsTransaction(
        formulationId: String,
        items: List<FormulationItem>,
        revisions: List<FormulationRevision>,
        phases: List<RecipePhase>,
        recipeItems: List<RecipeItem>,
        qualityTests: List<FormulationQualityTest>,
        recipeStatus: RecipeStatus?,
        referenceSpecs: FormulationReferenceSpecs?
    ) {
        // 1. Items
        deleteFormulationItemsByFormulationId(formulationId)
        for (item in items) {
            insertFormulationItem(item)
        }

        // 2. Revisions
        deleteFormulationRevisionsByFormulationId(formulationId)
        for (rev in revisions) {
            insertFormulationRevision(rev)
        }

        // 3. Phases & cascading deletion of recipe items
        deleteRecipePhasesByFormulationId(formulationId)
        for (phase in phases) {
            insertRecipePhase(phase)
        }

        // 4. Recipe items
        for (ri in recipeItems) {
            insertRecipeItem(ri)
        }

        // 5. Recipe Status
        if (recipeStatus != null) {
            insertRecipeStatus(recipeStatus)
        }

        // 6. Quality Tests
        deleteFormulationQualityTestsByFormulationId(formulationId)
        for (qt in qualityTests) {
            insertFormulationQualityTest(qt)
        }

        // 7. Reference Specs
        if (referenceSpecs != null) {
            deleteFormulationReferenceSpecsByFormulationId(formulationId)
            insertFormulationReferenceSpecs(referenceSpecs)
        }
    }

    @Query("SELECT * FROM formulation_items ORDER BY sequence ASC")
    fun getAllFormulationItems(): Flow<List<FormulationItem>>

    @Query("DELETE FROM formulation_items WHERE formulationId = :formulationId")
    suspend fun deleteFormulationItemsByFormulationId(formulationId: String)

    @Query("DELETE FROM formulation_items WHERE id = :id")
    suspend fun deleteFormulationItemById(id: String)

    @Query("UPDATE formulation_items SET simulatedPrice = :simulatedPrice WHERE id = :id")
    suspend fun updateFormulationItemSimulatedPrice(id: String, simulatedPrice: Double?)

    @Query("UPDATE formulation_items SET simulatedPrice = NULL WHERE formulationId = :formulationId")
    suspend fun clearAllFormulationItemSimulatedPrices(formulationId: String)

    // --- PRODUCTION LOGS ---
    @Query("SELECT * FROM production_logs ORDER BY timestamp DESC")
    fun getAllProductionLogs(): Flow<List<ProductionLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionLog(log: ProductionLog): Long

    @Update
    suspend fun updateProductionLog(log: ProductionLog)

    @Delete
    suspend fun deleteProductionLog(log: ProductionLog)

    // --- PRICE HISTORY ---
    @Query("SELECT * FROM price_history WHERE rawMaterialId = :rawMaterialId ORDER BY id DESC")
    fun getPriceHistoryForMaterial(rawMaterialId: String): Flow<List<PriceHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPriceHistory(entry: PriceHistoryEntry): Long

    // --- FORMULATION REVISIONS ---
    @Query("SELECT * FROM formulation_revisions WHERE formulationId = :formulationId")
    fun getRevisionsForFormulation(formulationId: String): Flow<List<FormulationRevision>>

    @Query("SELECT * FROM formulation_revisions")
    fun getAllFormulationRevisions(): Flow<List<FormulationRevision>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormulationRevision(revision: FormulationRevision): Long

    @Query("DELETE FROM formulation_revisions WHERE formulationId = :formulationId")
    suspend fun deleteFormulationRevisionsByFormulationId(formulationId: String)

    // --- PRODUCTION ORDERS SYSTEM ---
    @Query("SELECT * FROM production_orders ORDER BY createdAt DESC")
    fun getAllProductionOrders(): Flow<List<ProductionOrder>>

    @Query("SELECT * FROM production_orders WHERE id = :id LIMIT 1")
    suspend fun getProductionOrderById(id: String): ProductionOrder?

    @Query("SELECT * FROM production_orders WHERE formulationId = :formulationId AND status = 'مكتمل' ORDER BY CASE WHEN endTime > 0 THEN endTime ELSE createdAt END DESC LIMIT 1")
    suspend fun getLastCompletedProductionOrderForFormulation(formulationId: String): ProductionOrder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrder(order: ProductionOrder): Long

    @Update
    suspend fun updateProductionOrder(order: ProductionOrder)

    @Delete
    suspend fun deleteProductionOrder(order: ProductionOrder)

    @Query("SELECT * FROM production_order_items WHERE productionOrderId = :orderId ORDER BY rowid ASC")
    fun getProductionOrderItems(orderId: String): Flow<List<ProductionOrderItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderItem(item: ProductionOrderItem): Long

    @Query("SELECT * FROM production_order_events WHERE productionOrderId = :orderId ORDER BY timestamp ASC")
    fun getProductionOrderEvents(orderId: String): Flow<List<ProductionOrderEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderEvent(event: ProductionOrderEvent): Long

    // --- PRODUCTION RECIPES SYSTEM ---
    @Query("SELECT * FROM recipe_phases WHERE formulationId = :formulationId ORDER BY sequence ASC")
    fun getRecipePhasesForFormulation(formulationId: String): Flow<List<RecipePhase>>

    @Query("SELECT * FROM recipe_phases WHERE formulationId = :formulationId ORDER BY sequence ASC")
    suspend fun getRecipePhasesForFormulationSync(formulationId: String): List<RecipePhase>

    @Query("SELECT * FROM recipe_items WHERE phaseId IN (SELECT id FROM recipe_phases WHERE formulationId = :formulationId) ORDER BY sequence ASC")
    fun getRecipeItemsForFormulation(formulationId: String): Flow<List<RecipeItem>>

    @Query("SELECT * FROM recipe_items WHERE phaseId IN (SELECT id FROM recipe_phases WHERE formulationId = :formulationId) ORDER BY sequence ASC")
    suspend fun getRecipeItemsForFormulationSync(formulationId: String): List<RecipeItem>

    @Query("SELECT * FROM recipe_statuses WHERE formulationId = :formulationId LIMIT 1")
    fun getRecipeStatus(formulationId: String): Flow<RecipeStatus?>

    @Query("SELECT * FROM recipe_statuses WHERE formulationId = :formulationId LIMIT 1")
    suspend fun getRecipeStatusSync(formulationId: String): RecipeStatus?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipePhase(phase: RecipePhase): Long

    @Update
    suspend fun updateRecipePhase(phase: RecipePhase)

    @Delete
    suspend fun deleteRecipePhase(phase: RecipePhase)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeItem(item: RecipeItem): Long

    @Update
    suspend fun updateRecipeItem(item: RecipeItem)

    @Delete
    suspend fun deleteRecipeItem(item: RecipeItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeStatus(status: RecipeStatus)

    @Query("DELETE FROM recipe_items WHERE id = :id")
    suspend fun deleteRecipeItemById(id: String)

    @Query("DELETE FROM recipe_items WHERE phaseId = :phaseId")
    suspend fun deleteRecipeItemsByPhaseId(phaseId: String)

    @Query("DELETE FROM recipe_phases WHERE formulationId = :formulationId")
    suspend fun deleteRecipePhasesByFormulationId(formulationId: String)

    // --- PRODUCTION SNAPSHOTTED RECIPE SYSTEM ---
    @Query("SELECT * FROM production_order_phases WHERE productionOrderId = :orderId ORDER BY sequence ASC")
    fun getProductionOrderPhases(orderId: String): Flow<List<ProductionOrderPhase>>

    @Query("SELECT * FROM production_order_phases WHERE productionOrderId = :orderId ORDER BY sequence ASC")
    suspend fun getProductionOrderPhasesSync(orderId: String): List<ProductionOrderPhase>

    @Query("SELECT * FROM production_order_recipe_items WHERE productionOrderPhaseId = :phaseId ORDER BY sequence ASC")
    fun getProductionOrderRecipeItems(phaseId: String): Flow<List<ProductionOrderRecipeItem>>

    @Query("SELECT * FROM production_order_recipe_items WHERE productionOrderPhaseId = :phaseId ORDER BY sequence ASC")
    suspend fun getProductionOrderRecipeItemsSync(phaseId: String): List<ProductionOrderRecipeItem>

    @Query("SELECT * FROM production_order_recipe_items WHERE productionOrderPhaseId IN (SELECT id FROM production_order_phases WHERE productionOrderId = :orderId) ORDER BY sequence ASC")
    fun getProductionOrderRecipeItemsForOrder(orderId: String): Flow<List<ProductionOrderRecipeItem>>

    @Query("SELECT * FROM production_order_recipe_items WHERE productionOrderPhaseId IN (SELECT id FROM production_order_phases WHERE productionOrderId = :orderId) ORDER BY sequence ASC")
    suspend fun getProductionOrderRecipeItemsForOrderSync(orderId: String): List<ProductionOrderRecipeItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderPhase(phase: ProductionOrderPhase): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderRecipeItem(item: ProductionOrderRecipeItem): Long

    // --- QUALITY CONTROL TESTS SYSTEM ---
    @Query("SELECT * FROM quality_tests ORDER BY sequenceIndex ASC")
    fun getAllQualityTests(): Flow<List<QualityTest>>

    @Query("SELECT * FROM quality_tests ORDER BY sequenceIndex ASC")
    suspend fun getAllQualityTestsSync(): List<QualityTest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQualityTest(test: QualityTest): Long

    @Update
    suspend fun updateQualityTest(test: QualityTest)

    @Delete
    suspend fun deleteQualityTest(test: QualityTest)

    @Query("DELETE FROM quality_tests WHERE id = :id")
    suspend fun deleteQualityTestById(id: String)

    @Transaction
    suspend fun saveAllQualityTestsTransaction(tests: List<QualityTest>) {
        // Clear and replace or just update sequenceIndex
        for (t in tests) {
            insertQualityTest(t)
        }
    }

    // --- FORMULATION QUALITY TESTS ---
    @Query("SELECT * FROM formulation_quality_tests WHERE formulationId = :formulationId")
    fun getFormulationQualityTests(formulationId: String): Flow<List<FormulationQualityTest>>

    @Query("SELECT * FROM formulation_quality_tests")
    fun getAllFormulationQualityTests(): Flow<List<FormulationQualityTest>>

    @Query("SELECT * FROM formulation_quality_tests WHERE formulationId = :formulationId")
    suspend fun getFormulationQualityTestsSync(formulationId: String): List<FormulationQualityTest>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormulationQualityTest(fTest: FormulationQualityTest): Long

    @Query("DELETE FROM formulation_quality_tests WHERE formulationId = :formulationId AND testId = :testId")
    suspend fun deleteFormulationQualityTest(formulationId: String, testId: String)

    @Query("DELETE FROM formulation_quality_tests WHERE formulationId = :formulationId")
    suspend fun deleteFormulationQualityTestsByFormulationId(formulationId: String)

    @Query("DELETE FROM formulation_quality_tests WHERE testId = :testId")
    suspend fun deleteFormulationQualityTestsByTestId(testId: String)

    // --- PRODUCTION ORDER QUALITY SNAPSHOTS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderQualityTests(tests: List<ProductionOrderQualityTest>)

    @Delete
    suspend fun deleteProductionOrderQualityTest(test: ProductionOrderQualityTest)

    @Query("SELECT * FROM production_order_quality_tests WHERE productionOrderId = :orderId ORDER BY sequenceIndex ASC")
    fun getProductionOrderQualityTests(orderId: String): Flow<List<ProductionOrderQualityTest>>

    @Query("SELECT * FROM production_order_quality_tests WHERE productionOrderId = :orderId ORDER BY sequenceIndex ASC")
    suspend fun getProductionOrderQualityTestsSync(orderId: String): List<ProductionOrderQualityTest>

    // --- PRODUCTION ORDER QUALITY TEST RECORDS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionOrderTestRecord(record: ProductionOrderTestRecord): Long

    @Query("SELECT * FROM production_order_test_records WHERE productionOrderId = :orderId ORDER BY timestamp DESC")
    fun getProductionOrderTestRecords(orderId: String): Flow<List<ProductionOrderTestRecord>>

    @Query("SELECT * FROM production_order_test_records WHERE productionOrderId = :orderId ORDER BY timestamp DESC")
    suspend fun getProductionOrderTestRecordsSync(orderId: String): List<ProductionOrderTestRecord>

    @Delete
    suspend fun deleteProductionOrderTestRecord(record: ProductionOrderTestRecord)

    // --- PRODUCTION ADJUSTMENTS ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductionAdjustment(adjustment: ProductionAdjustment): Long

    @Query("SELECT * FROM production_adjustments WHERE productionOrderId = :orderId ORDER BY timestamp DESC")
    fun getProductionAdjustments(orderId: String): Flow<List<ProductionAdjustment>>

    @Query("SELECT * FROM production_adjustments ORDER BY timestamp DESC")
    fun getAllProductionAdjustments(): Flow<List<ProductionAdjustment>>

    @Query("SELECT * FROM production_adjustments WHERE productionOrderId = :orderId ORDER BY timestamp DESC")
    suspend fun getProductionAdjustmentsSync(orderId: String): List<ProductionAdjustment>

    @Query("UPDATE production_order_items SET calculatedQuantity = :newQty WHERE productionOrderId = :orderId AND rawMaterialId = :materialId")
    suspend fun updateProductionOrderItemQuantity(orderId: String, materialId: String, newQty: Double)

    @Query("UPDATE production_order_recipe_items SET calculatedQuantity = :newQty WHERE id = :recipeItemId")
    suspend fun updateProductionOrderRecipeItemQuantity(recipeItemId: String, newQty: Double)

    // استبدال مادة بند وصفة في أمر الإنتاج بمادة أخرى (تعديل أثناء التنفيذ)
    @Query("UPDATE production_order_recipe_items SET rawMaterialId = :newId, rawMaterialName = :newName, calculatedQuantity = :newQty WHERE id = :recipeItemId")
    suspend fun replaceProductionOrderRecipeItemMaterial(recipeItemId: String, newId: String, newName: String, newQty: Double)

    @Query("SELECT * FROM production_order_items WHERE productionOrderId = :orderId ORDER BY rowid ASC")
    suspend fun getProductionOrderItemsSync(orderId: String): List<ProductionOrderItem>

    @Query("DELETE FROM production_order_items WHERE id = :itemId")
    suspend fun deleteProductionOrderItemById(itemId: String)

    @Query("DELETE FROM production_order_items WHERE productionOrderId = :orderId AND id NOT IN (:keepIds)")
    suspend fun deleteProductionOrderItemsNotIn(orderId: String, keepIds: List<String>)

    // --- RESEARCH & DEVELOPMENT (R&D) ---
    @Query("SELECT * FROM development_projects ORDER BY createdAt DESC, lastUpdated DESC, rowid DESC")
    fun getAllDevelopmentProjects(): Flow<List<DevelopmentProject>>

    @Query("SELECT * FROM development_projects WHERE id = :id LIMIT 1")
    suspend fun getDevelopmentProjectById(id: String): DevelopmentProject?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevelopmentProjectRaw(project: DevelopmentProject): Long

    @Update
    suspend fun updateDevelopmentProject(project: DevelopmentProject)

    suspend fun insertDevelopmentProject(project: DevelopmentProject): Long {
        val rowId = insertDevelopmentProjectRaw(project)
        if (rowId == -1L) {
            updateDevelopmentProject(project)
            return 0L
        }
        return rowId
    }

    @Delete
    suspend fun deleteDevelopmentProject(project: DevelopmentProject)

    @Query("SELECT * FROM development_samples WHERE projectId = :projectId ORDER BY id ASC")
    fun getDevelopmentSamplesForProject(projectId: String): Flow<List<DevelopmentSample>>

    @Query("SELECT * FROM development_samples WHERE id = :id LIMIT 1")
    suspend fun getDevelopmentSampleById(id: String): DevelopmentSample?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDevelopmentSampleRaw(sample: DevelopmentSample): Long

    @Update
    suspend fun updateDevelopmentSample(sample: DevelopmentSample)

    suspend fun insertDevelopmentSample(sample: DevelopmentSample): Long {
        val rowId = insertDevelopmentSampleRaw(sample)
        if (rowId == -1L) {
            updateDevelopmentSample(sample)
            return 0L
        }
        return rowId
    }

    @Delete
    suspend fun deleteDevelopmentSample(sample: DevelopmentSample)

    // --- SYNC METADATA ---
    @Query("SELECT * FROM sync_metadata")
    fun getAllSyncMetadata(): Flow<List<SyncMetadata>>

    @Query("SELECT * FROM sync_metadata WHERE isPendingSync = 1")
    suspend fun getPendingSyncMetadata(): List<SyncMetadata>

    @Query("SELECT * FROM sync_metadata WHERE id = :id LIMIT 1")
    suspend fun getSyncMetadataById(id: String): SyncMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncMetadata(metadata: SyncMetadata)

    @Query("DELETE FROM sync_metadata WHERE id = :id")
    suspend fun deleteSyncMetadataById(id: String)

    @Query("UPDATE sync_metadata SET isPendingSync = 0, lastError = NULL, syncStage = 'SUCCESS', firebaseErrorCode = NULL, lastAttempt = :lastAttempt, retryCount = 0 WHERE id = :id")
    suspend fun markAsSynced(id: String, lastAttempt: Long)

    @Query("UPDATE sync_metadata SET isPendingSync = 1, lastError = :error, syncStage = :stage, firebaseErrorCode = :errorCode, lastAttempt = :lastAttempt, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markAsFailed(id: String, error: String, stage: String, errorCode: String?, lastAttempt: Long)

    // --- LABORATORY SESSIONS ---
    @Query("SELECT * FROM laboratory_sessions ORDER BY createdAt DESC")
    fun getAllLabSessions(): Flow<List<LabSession>>

    @Query("SELECT * FROM laboratory_sessions WHERE id = :id LIMIT 1")
    suspend fun getLabSessionById(id: String): LabSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabSession(session: LabSession): Long

    @Update
    suspend fun updateLabSession(session: LabSession)

    @Delete
    suspend fun deleteLabSession(session: LabSession)

    // --- LABORATORY TESTS ---
    @Query("SELECT * FROM laboratory_tests")
    fun getAllLabTests(): Flow<List<LabTest>>

    @Query("SELECT * FROM laboratory_tests WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getAllLabTestsForSession(sessionId: String): Flow<List<LabTest>>

    @Query("SELECT * FROM laboratory_tests WHERE id = :id LIMIT 1")
    suspend fun getLabTestById(id: String): LabTest?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabTest(test: LabTest): Long

    @Update
    suspend fun updateLabTest(test: LabTest)

    @Delete
    suspend fun deleteLabTest(test: LabTest)

    @Query("DELETE FROM laboratory_tests WHERE sessionId = :sessionId")
    suspend fun deleteLabTestsForSession(sessionId: String)

    // --- LABORATORY ATTACHMENTS ---
    @Query("SELECT * FROM laboratory_attachments ORDER BY createdAt DESC")
    fun getAllLabAttachments(): Flow<List<LabAttachment>>

    @Query("SELECT * FROM laboratory_attachments WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getAllLabAttachmentsForSession(sessionId: String): Flow<List<LabAttachment>>

    @Query("SELECT * FROM laboratory_attachments WHERE id = :id LIMIT 1")
    suspend fun getLabAttachmentById(id: String): LabAttachment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabAttachment(attachment: LabAttachment): Long

    @Delete
    suspend fun deleteLabAttachment(attachment: LabAttachment)

    @Query("DELETE FROM laboratory_attachments WHERE sessionId = :sessionId")
    suspend fun deleteLabAttachmentsForSession(sessionId: String)

    // --- FORMULATION REFERENCE SPECS ---
    @Query("SELECT * FROM formulation_reference_specs WHERE formulationId = :formulationId LIMIT 1")
    fun getFormulationReferenceSpecs(formulationId: String): Flow<FormulationReferenceSpecs?>

    @Query("SELECT * FROM formulation_reference_specs WHERE formulationId = :formulationId LIMIT 1")
    suspend fun getFormulationReferenceSpecsSync(formulationId: String): FormulationReferenceSpecs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFormulationReferenceSpecs(specs: FormulationReferenceSpecs): Long

    @Delete
    suspend fun deleteFormulationReferenceSpecs(specs: FormulationReferenceSpecs)

    @Query("DELETE FROM formulation_reference_specs WHERE formulationId = :formulationId")
    suspend fun deleteFormulationReferenceSpecsByFormulationId(formulationId: String)

    // --- BULK BACKUP QUERIES ---
    @Query("SELECT * FROM price_history ORDER BY id DESC")
    fun getAllPriceHistory(): Flow<List<PriceHistoryEntry>>

    @Query("SELECT * FROM formulation_reference_specs")
    fun getAllFormulationReferenceSpecs(): Flow<List<FormulationReferenceSpecs>>

    @Query("SELECT * FROM development_samples ORDER BY id ASC")
    fun getAllDevelopmentSamples(): Flow<List<DevelopmentSample>>

    @Query("SELECT * FROM production_order_items")
    fun getAllProductionOrderItems(): Flow<List<ProductionOrderItem>>

    @Query("SELECT * FROM production_order_events")
    fun getAllProductionOrderEvents(): Flow<List<ProductionOrderEvent>>

    @Query("SELECT * FROM production_order_phases")
    fun getAllProductionOrderPhases(): Flow<List<ProductionOrderPhase>>

    @Query("SELECT * FROM production_order_recipe_items")
    fun getAllProductionOrderRecipeItems(): Flow<List<ProductionOrderRecipeItem>>

    @Query("SELECT * FROM recipe_phases ORDER BY sequence ASC")
    fun getAllRecipePhases(): Flow<List<RecipePhase>>

    @Query("SELECT * FROM recipe_items ORDER BY sequence ASC")
    fun getAllRecipeItems(): Flow<List<RecipeItem>>

    @Query("SELECT * FROM recipe_statuses")
    fun getAllRecipeStatuses(): Flow<List<RecipeStatus>>

    @Query("SELECT * FROM production_order_quality_tests")
    fun getAllProductionOrderQualityTests(): Flow<List<ProductionOrderQualityTest>>

    @Query("SELECT * FROM production_order_test_records")
    fun getAllProductionOrderTestRecords(): Flow<List<ProductionOrderTestRecord>>

    // --- OPERATIONAL ALERTS ---
    @Query("SELECT * FROM operational_alerts ORDER BY createdAt DESC")
    fun getAllOperationalAlerts(): Flow<List<OperationalAlert>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOperationalAlert(alert: OperationalAlert): Long

    @Update
    suspend fun updateOperationalAlert(alert: OperationalAlert)

    @Delete
    suspend fun deleteOperationalAlert(alert: OperationalAlert)

    // --- RECYCLE BIN ---
    @Query("SELECT * FROM recycle_bin ORDER BY deletedAt DESC")
    fun getAllRecycleBinItems(): Flow<List<RecycleBinItem>>

    @Query("SELECT * FROM recycle_bin WHERE id = :id LIMIT 1")
    suspend fun getRecycleBinItemById(id: String): RecycleBinItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecycleBinItem(item: RecycleBinItem): Long

    @Delete
    suspend fun deleteRecycleBinItem(item: RecycleBinItem)

    @Query("DELETE FROM recycle_bin")
    suspend fun clearAllRecycleBinItems()

    @Query("DELETE FROM recycle_bin WHERE deletedAt < :cutoffTime")
    suspend fun deleteOldRecycleBinItems(cutoffTime: Long)
}
