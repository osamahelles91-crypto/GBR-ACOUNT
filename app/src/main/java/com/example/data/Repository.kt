package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GbrRepository(private val gbrDao: GbrDao, private val context: android.content.Context) {

    fun gbrDao(): GbrDao = gbrDao

    val rawMaterials: Flow<List<RawMaterial>> = gbrDao.getAllRawMaterials()
    val formulations: Flow<List<Formulation>> = gbrDao.getAllFormulations()
    val productionLogs: Flow<List<ProductionLog>> = gbrDao.getAllProductionLogs()
    val allFormulationItems: Flow<List<FormulationItem>> = gbrDao.getAllFormulationItems()
    val allProductionOrderItems: Flow<List<ProductionOrderItem>> = gbrDao.getAllProductionOrderItems()

    fun getFormulationItemsWithDetails(formulationId: String): Flow<List<FormulationItemWithDetails>> {
        return gbrDao.getFormulationItemsWithDetails(formulationId)
    }

    suspend fun insertRawMaterial(rawMaterial: RawMaterial): String = withContext(Dispatchers.IO) {
        gbrDao.insertRawMaterial(rawMaterial)
        logSyncChange(rawMaterial.id, "raw_material")
        rawMaterial.id
    }

    suspend fun updateRawMaterial(rawMaterial: RawMaterial): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateRawMaterial(rawMaterial)
            logSyncChange(rawMaterial.id, "raw_material")
        }
    }

    suspend fun getRawMaterialById(id: String): RawMaterial? = withContext(Dispatchers.IO) {
        gbrDao.getRawMaterialById(id)
    }

    suspend fun deleteRawMaterial(rawMaterial: RawMaterial): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteRawMaterial(rawMaterial)
            deleteSyncMetadataById(rawMaterial.id)
            SyncManager.deleteSingleEntityAsync(context, rawMaterial.id, "raw_material")
        }
    }

    suspend fun insertFormulation(formulation: Formulation): String = withContext(Dispatchers.IO) {
        val id = gbrDao.insertFormulation(formulation)
        if (id == -1L) {
            gbrDao.updateFormulation(formulation)
        }
        logSyncChange(formulation.id, "formulation")
        formulation.id
    }

    suspend fun getFormulationById(id: String): Formulation? = withContext(Dispatchers.IO) {
        gbrDao.getFormulationById(id)
    }

    suspend fun deleteFormulation(formulation: Formulation): Unit {
        withContext(Dispatchers.IO) {
            // FormulationItems are Cascade-deleted
            gbrDao.deleteFormulation(formulation)
            deleteSyncMetadataById(formulation.id)
            SyncManager.deleteSingleEntityAsync(context, formulation.id, "formulation")
        }
    }

    suspend fun addFormulationItem(item: FormulationItem) = withContext(Dispatchers.IO) {
        gbrDao.insertFormulationItem(item)
    }

    suspend fun deleteFormulationItem(id: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteFormulationItemById(id)
    }

    suspend fun setFormulationItems(formulationId: String, items: List<FormulationItem>) = withContext(Dispatchers.IO) {
        gbrDao.setFormulationItemsTransaction(formulationId, items)
        logSyncChange(formulationId, "formulation")
    }

    suspend fun updateFormulationItemSimulatedPrice(id: String, simulatedPrice: Double?) = withContext(Dispatchers.IO) {
        gbrDao.updateFormulationItemSimulatedPrice(id, simulatedPrice)
    }

    suspend fun clearAllFormulationItemSimulatedPrices(formulationId: String) = withContext(Dispatchers.IO) {
        gbrDao.clearAllFormulationItemSimulatedPrices(formulationId)
    }

    suspend fun insertProductionLog(log: ProductionLog): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionLog(log)
        logSyncChange(log.id, "production_log")
        log.id
    }

    suspend fun updateProductionLog(log: ProductionLog) = withContext(Dispatchers.IO) {
        gbrDao.updateProductionLog(log)
        logSyncChange(log.id, "production_log")
    }

    suspend fun deleteProductionLog(log: ProductionLog) = withContext(Dispatchers.IO) {
        gbrDao.deleteProductionLog(log)
        deleteSyncMetadataById(log.id)
        SyncManager.deleteSingleEntityAsync(context, log.id, "production_log")
    }

    fun getPriceHistoryForMaterial(rawMaterialId: String): Flow<List<PriceHistoryEntry>> {
        return gbrDao.getPriceHistoryForMaterial(rawMaterialId)
    }

    suspend fun insertPriceHistory(entry: PriceHistoryEntry): String = withContext(Dispatchers.IO) {
        gbrDao.insertPriceHistory(entry)
        entry.id
    }

    fun getRevisionsForFormulation(formulationId: String): Flow<List<FormulationRevision>> {
        return gbrDao.getRevisionsForFormulation(formulationId)
    }

    fun getAllFormulationRevisions(): Flow<List<FormulationRevision>> {
        return gbrDao.getAllFormulationRevisions()
    }

    suspend fun insertFormulationRevision(revision: FormulationRevision): String = withContext(Dispatchers.IO) {
        gbrDao.insertFormulationRevision(revision)
        revision.id
    }

    // --- PRODUCTION ORDERS SYSTEM ---
    val productionOrders: Flow<List<ProductionOrder>> = gbrDao.getAllProductionOrders()

    suspend fun getProductionOrderById(id: String): ProductionOrder? = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderById(id)
    }

    suspend fun getLastCompletedProductionOrderForFormulation(formulationId: String): ProductionOrder? = withContext(Dispatchers.IO) {
        gbrDao.getLastCompletedProductionOrderForFormulation(formulationId)
    }

    suspend fun insertProductionOrder(order: ProductionOrder): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrder(order)
        logSyncChange(order.id, "production_order")
        order.id
    }

    suspend fun updateProductionOrder(order: ProductionOrder): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateProductionOrder(order)
            logSyncChange(order.id, "production_order")
        }
    }

    suspend fun deleteProductionOrder(order: ProductionOrder): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteProductionOrder(order)
            deleteSyncMetadataById(order.id)
            SyncManager.deleteSingleEntityAsync(context, order.id, "production_order")
        }
    }

    fun getProductionOrderItems(orderId: String): Flow<List<ProductionOrderItem>> {
        return gbrDao.getProductionOrderItems(orderId)
    }

    suspend fun insertProductionOrderItem(item: ProductionOrderItem): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderItem(item)
        item.id
    }

    fun getProductionOrderEvents(orderId: String): Flow<List<ProductionOrderEvent>> {
        return gbrDao.getProductionOrderEvents(orderId)
    }

    suspend fun insertProductionOrderEvent(event: ProductionOrderEvent): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderEvent(event)
        event.id
    }

    // --- PRODUCTION ADJUSTMENTS ---
    suspend fun insertProductionAdjustment(adjustment: ProductionAdjustment): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionAdjustment(adjustment)
        logSyncChange(adjustment.id, "production_adjustment")
        adjustment.id
    }

    fun getProductionAdjustments(orderId: String): Flow<List<ProductionAdjustment>> {
        return gbrDao.getProductionAdjustments(orderId)
    }

    fun getAllProductionAdjustments(): Flow<List<ProductionAdjustment>> {
        return gbrDao.getAllProductionAdjustments()
    }

    suspend fun getProductionAdjustmentsSync(orderId: String): List<ProductionAdjustment> = withContext(Dispatchers.IO) {
        gbrDao.getProductionAdjustmentsSync(orderId)
    }

    suspend fun updateProductionOrderItemQuantity(orderId: String, materialId: String, newQty: Double) = withContext(Dispatchers.IO) {
        gbrDao.updateProductionOrderItemQuantity(orderId, materialId, newQty)
    }

    suspend fun updateProductionOrderRecipeItemQuantity(recipeItemId: Int, newQty: Double) = withContext(Dispatchers.IO) {
        // Safe cast / conversion if recipeItemId is string or kept as long/int since it uses sqlite's inner row sequencer
        // Let's check GbrDao updateProductionOrderRecipeItemQuantity sequence, wait, the DAO matches: UPDATE ... WHERE id = :recipeItemId
        // But since RecipeItem.id is a String UUID now, the type of recipeItemId should be String UUID!
        // Wait, let's look at updating ProductionOrderRecipeItem. Its id is now a String too!
        // So updateProductionOrderRecipeItemQuantity should take recipeItemId: String!
        // Let's make sure the type of recipeItemId in updateProductionOrderRecipeItemQuantity is String UUID.
        // Wait, let's fix that in DAO too: wait, in Daos.kt, we used @Query("UPDATE... WHERE id = :recipeItemId") with recipeItemId: Int.
        // Let's modify GbrDao as well if we haven't already. Wait, let's first check Daos.kt line 316.
        // In GbrDao we have: `suspend fun updateProductionOrderRecipeItemQuantity(recipeItemId: String, newQty: Double)` (we wrote String in Daos.kt above)!
        // That is perfect!
    }

    suspend fun updateProductionOrderRecipeItemQuantity(recipeItemId: String, newQty: Double) = withContext(Dispatchers.IO) {
        gbrDao.updateProductionOrderRecipeItemQuantity(recipeItemId, newQty)
    }

    // --- PRODUCTION SNAPSHOTTED RECIPE SYSTEM ---
    fun getProductionOrderPhases(orderId: String): Flow<List<ProductionOrderPhase>> = gbrDao.getProductionOrderPhases(orderId)
    
    suspend fun getProductionOrderPhasesSync(orderId: String): List<ProductionOrderPhase> = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderPhasesSync(orderId)
    }
    
    fun getProductionOrderRecipeItems(phaseId: String): Flow<List<ProductionOrderRecipeItem>> = gbrDao.getProductionOrderRecipeItems(phaseId)
    
    suspend fun getProductionOrderRecipeItemsSync(phaseId: String): List<ProductionOrderRecipeItem> = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderRecipeItemsSync(phaseId)
    }

    fun getProductionOrderRecipeItemsForOrder(orderId: String): Flow<List<ProductionOrderRecipeItem>> = gbrDao.getProductionOrderRecipeItemsForOrder(orderId)

    suspend fun getProductionOrderRecipeItemsForOrderSync(orderId: String): List<ProductionOrderRecipeItem> = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderRecipeItemsForOrderSync(orderId)
    }
    
    suspend fun insertProductionOrderPhase(phase: ProductionOrderPhase): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderPhase(phase)
        phase.id
    }
    
    suspend fun insertProductionOrderRecipeItem(item: ProductionOrderRecipeItem): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderRecipeItem(item)
        item.id
    }

    // --- PRODUCTION RECIPES SYSTEM ---
    fun getRecipePhasesForFormulation(formulationId: String): Flow<List<RecipePhase>> = gbrDao.getRecipePhasesForFormulation(formulationId)
    fun getRecipeItemsForFormulation(formulationId: String): Flow<List<RecipeItem>> = gbrDao.getRecipeItemsForFormulation(formulationId)
    fun getRecipeStatus(formulationId: String): Flow<RecipeStatus?> = gbrDao.getRecipeStatus(formulationId)

    suspend fun insertRecipePhase(phase: RecipePhase): String = withContext(Dispatchers.IO) {
        gbrDao.insertRecipePhase(phase)
        phase.id
    }

    suspend fun updateRecipePhase(phase: RecipePhase) = withContext(Dispatchers.IO) {
        gbrDao.updateRecipePhase(phase)
    }

    suspend fun deleteRecipePhase(phase: RecipePhase) = withContext(Dispatchers.IO) {
        gbrDao.deleteRecipePhase(phase)
    }

    suspend fun insertRecipeItem(item: RecipeItem): String = withContext(Dispatchers.IO) {
        gbrDao.insertRecipeItem(item)
        item.id
    }

    suspend fun updateRecipeItem(item: RecipeItem) = withContext(Dispatchers.IO) {
        gbrDao.updateRecipeItem(item)
    }

    suspend fun deleteRecipeItem(item: RecipeItem) = withContext(Dispatchers.IO) {
        gbrDao.deleteRecipeItem(item)
    }

    suspend fun insertRecipeStatus(status: RecipeStatus) = withContext(Dispatchers.IO) {
        gbrDao.insertRecipeStatus(status)
    }

    suspend fun deleteRecipeItemById(id: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteRecipeItemById(id)
    }

    suspend fun deleteRecipeItemsByPhaseId(phaseId: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteRecipeItemsByPhaseId(phaseId)
    }

    suspend fun deleteRecipePhasesByFormulationId(formulationId: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteRecipePhasesByFormulationId(formulationId)
    }

    suspend fun getRecipePhasesForFormulationSync(formulationId: String): List<RecipePhase> = withContext(Dispatchers.IO) {
        gbrDao.getRecipePhasesForFormulationSync(formulationId)
    }

    suspend fun getRecipeItemsForFormulationSync(formulationId: String): List<RecipeItem> = withContext(Dispatchers.IO) {
        gbrDao.getRecipeItemsForFormulationSync(formulationId)
    }

    suspend fun getRecipeStatusSync(formulationId: String): RecipeStatus? = withContext(Dispatchers.IO) {
        gbrDao.getRecipeStatusSync(formulationId)
    }

    suspend fun copyRecipeForNewVersion(oldFormulationId: String, newFormulationId: String) = withContext(Dispatchers.IO) {
        val oldPhases = gbrDao.getRecipePhasesForFormulationSync(oldFormulationId)
        if (oldPhases.isEmpty()) return@withContext

        val oldItemsByPhase = mutableMapOf<String, List<RecipeItem>>()
        for (phase in oldPhases) {
            val items = gbrDao.getRecipeItemsForFormulationSync(oldFormulationId).filter { it.phaseId == phase.id }
            oldItemsByPhase[phase.id] = items
        }

        val newFormulationItems = gbrDao.getFormulationItemsWithDetails(newFormulationId).first()
        val newMaterialIds = newFormulationItems.map { it.rawMaterialId }.toSet()

        var firstNewPhaseId: String? = null
        for (oldPhase in oldPhases) {
            val newPhase = RecipePhase(
                formulationId = newFormulationId,
                name = oldPhase.name,
                sequence = oldPhase.sequence,
                mixerRpm = oldPhase.mixerRpm,
                durationMinutes = oldPhase.durationMinutes,
                instructions = oldPhase.instructions
            )
            val newPhaseId = gbrDao.insertRecipePhase(newPhase)
            gbrDao.insertRecipePhase(newPhase)
            if (firstNewPhaseId == null) firstNewPhaseId = newPhase.id

            val itemsForThisPhase = oldItemsByPhase[oldPhase.id] ?: emptyList()
            var itemSeq = 0
            for (oldItem in itemsForThisPhase) {
                if (newMaterialIds.contains(oldItem.rawMaterialId)) {
                    val newItem = RecipeItem(
                        phaseId = newPhase.id,
                        rawMaterialId = oldItem.rawMaterialId,
                        ratio = oldItem.ratio,
                        sequence = itemSeq++
                    )
                    gbrDao.insertRecipeItem(newItem)
                }
            }
        }

        val copiedItemMaterialIds = gbrDao.getRecipeItemsForFormulationSync(newFormulationId).map { it.rawMaterialId }.toSet()
        val newAddedFormulationItems = newFormulationItems.filter { !copiedItemMaterialIds.contains(it.rawMaterialId) }

        if (newAddedFormulationItems.isNotEmpty() && firstNewPhaseId != null) {
            var currentMaxSeq = gbrDao.getRecipeItemsForFormulationSync(newFormulationId)
                .filter { it.phaseId == firstNewPhaseId }
                .maxOfOrNull { it.sequence } ?: -1
            
            for (newFormItem in newAddedFormulationItems) {
                currentMaxSeq++
                val newItem = RecipeItem(
                    phaseId = firstNewPhaseId,
                    rawMaterialId = newFormItem.rawMaterialId,
                    ratio = 1.0,
                    sequence = currentMaxSeq
                )
                gbrDao.insertRecipeItem(newItem)
            }
        }
        gbrDao.insertRecipeStatus(RecipeStatus(formulationId = newFormulationId, status = "READY"))
    }

    // Seed database logic completely removed to ensure 100% clean/blank database on startup as requested
    suspend fun seedDatabaseIfNeeded() = withContext(Dispatchers.IO) {
        // No auto-seeding of any data (such as raw materials, formulations, orders, QC tests, packages or logs)
    }

    // --- QUALITY CONTROL REPOSITORY DELEGATES ---
    val qualityTests: Flow<List<QualityTest>> = gbrDao.getAllQualityTests()

    fun getFormulationQualityTests(formulationId: String): Flow<List<FormulationQualityTest>> {
        return gbrDao.getFormulationQualityTests(formulationId)
    }

    fun getAllFormulationQualityTests(): Flow<List<FormulationQualityTest>> {
        return gbrDao.getAllFormulationQualityTests()
    }

    suspend fun getFormulationQualityTestsSync(formulationId: String): List<FormulationQualityTest> {
        return gbrDao.getFormulationQualityTestsSync(formulationId)
    }

    suspend fun insertQualityTest(test: QualityTest): String = withContext(Dispatchers.IO) {
        gbrDao.insertQualityTest(test)
        test.id
    }

    suspend fun updateQualityTest(test: QualityTest) = withContext(Dispatchers.IO) {
        gbrDao.updateQualityTest(test)
    }

    suspend fun deleteQualityTest(test: QualityTest) = withContext(Dispatchers.IO) {
        gbrDao.deleteQualityTest(test)
    }

    suspend fun deleteQualityTestById(id: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteQualityTestById(id)
    }

    suspend fun saveAllQualityTestsTransaction(tests: List<QualityTest>) = withContext(Dispatchers.IO) {
        gbrDao.saveAllQualityTestsTransaction(tests)
    }

    suspend fun insertFormulationQualityTest(fTest: FormulationQualityTest) = withContext(Dispatchers.IO) {
        gbrDao.insertFormulationQualityTest(fTest)
    }

    suspend fun deleteFormulationQualityTest(formulationId: String, testId: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteFormulationQualityTest(formulationId, testId)
    }

    suspend fun deleteFormulationQualityTestsByFormulationId(formulationId: String) = withContext(Dispatchers.IO) {
        gbrDao.deleteFormulationQualityTestsByFormulationId(formulationId)
    }

    fun getProductionOrderQualityTests(orderId: String): Flow<List<ProductionOrderQualityTest>> {
        return gbrDao.getProductionOrderQualityTests(orderId)
    }

    suspend fun getProductionOrderQualityTestsSync(orderId: String): List<ProductionOrderQualityTest> = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderQualityTestsSync(orderId)
    }

    suspend fun insertProductionOrderQualityTests(tests: List<ProductionOrderQualityTest>) = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderQualityTests(tests)
    }

    suspend fun deleteProductionOrderQualityTest(test: ProductionOrderQualityTest) = withContext(Dispatchers.IO) {
        gbrDao.deleteProductionOrderQualityTest(test)
    }

    fun getProductionOrderTestRecords(orderId: String): Flow<List<ProductionOrderTestRecord>> {
        return gbrDao.getProductionOrderTestRecords(orderId)
    }

    fun getAllProductionOrderTestRecords(): Flow<List<ProductionOrderTestRecord>> {
        return gbrDao.getAllProductionOrderTestRecords()
    }

    suspend fun getProductionOrderTestRecordsSync(orderId: String): List<ProductionOrderTestRecord> = withContext(Dispatchers.IO) {
        gbrDao.getProductionOrderTestRecordsSync(orderId)
    }

    suspend fun insertProductionOrderTestRecord(record: ProductionOrderTestRecord): String = withContext(Dispatchers.IO) {
        gbrDao.insertProductionOrderTestRecord(record)
        record.id
    }

    suspend fun deleteProductionOrderTestRecord(record: ProductionOrderTestRecord) = withContext(Dispatchers.IO) {
        gbrDao.deleteProductionOrderTestRecord(record)
    }

    // --- RESEARCH & DEVELOPMENT (R&D) ---
    val allDevelopmentProjects: Flow<List<DevelopmentProject>> = gbrDao.getAllDevelopmentProjects()

    suspend fun getDevelopmentProjectById(id: String): DevelopmentProject? = withContext(Dispatchers.IO) {
        gbrDao.getDevelopmentProjectById(id)
    }

    suspend fun insertDevelopmentProject(project: DevelopmentProject): String = withContext(Dispatchers.IO) {
        gbrDao.insertDevelopmentProject(project)
        logSyncChange(project.id, "development_project")
        project.id
    }

    suspend fun updateDevelopmentProject(project: DevelopmentProject): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateDevelopmentProject(project)
            logSyncChange(project.id, "development_project")
        }
    }

    suspend fun deleteDevelopmentProject(project: DevelopmentProject): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteDevelopmentProject(project)
            deleteSyncMetadataById(project.id)
            SyncManager.deleteSingleEntityAsync(context, project.id, "development_project")
        }
    }

    fun getDevelopmentSamplesForProject(projectId: String): Flow<List<DevelopmentSample>> {
        return gbrDao.getDevelopmentSamplesForProject(projectId)
    }

    suspend fun getDevelopmentSampleById(id: String): DevelopmentSample? = withContext(Dispatchers.IO) {
        gbrDao.getDevelopmentSampleById(id)
    }

    suspend fun insertDevelopmentSample(sample: DevelopmentSample): String = withContext(Dispatchers.IO) {
        gbrDao.insertDevelopmentSample(sample)
        logSyncChange(sample.projectId, "development_project")
        sample.id
    }

    suspend fun updateDevelopmentSample(sample: DevelopmentSample): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateDevelopmentSample(sample)
            logSyncChange(sample.projectId, "development_project")
        }
    }

    suspend fun deleteDevelopmentSample(sample: DevelopmentSample): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteDevelopmentSample(sample)
            logSyncChange(sample.projectId, "development_project")
        }
    }

    // --- SYNC METADATA ---
    val allSyncMetadata: Flow<List<SyncMetadata>> = gbrDao.getAllSyncMetadata()
    
    suspend fun getPendingSyncMetadata(): List<SyncMetadata> = withContext(Dispatchers.IO) {
        gbrDao.getPendingSyncMetadata()
    }
    
    suspend fun getSyncMetadataById(id: String): SyncMetadata? = withContext(Dispatchers.IO) {
        gbrDao.getSyncMetadataById(id)
    }
    
    suspend fun insertSyncMetadata(metadata: SyncMetadata) = withContext(Dispatchers.IO) {
        gbrDao.insertSyncMetadata(metadata)
    }

    suspend fun deleteSyncMetadataById(id: String): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteSyncMetadataById(id)
        }
    }

    suspend fun markAsSynced(id: String): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.markAsSynced(id, System.currentTimeMillis())
        }
    }

    // markAsSynced تنفّذ UPDATE فقط؛ فإن لم يوجد صف متابعة للسجل (مثلاً سجل مزروع أو مستورد) بقي «معلّقاً» ضمنياً
    // (meta == null ← يُعدّ معلّقاً) فيُرفع في كل مزامنة. هنا ننشئ الصف إن غاب.
    suspend fun markAsSyncedOrCreate(id: String, entityType: String): Unit {
        withContext(Dispatchers.IO) {
            if (gbrDao.getSyncMetadataById(id) == null) {
                val now = System.currentTimeMillis()
                gbrDao.insertSyncMetadata(
                    SyncMetadata(
                        id = id,
                        entityType = entityType,
                        lastUpdated = now,
                        isPendingSync = false,
                        syncStage = "SUCCESS",
                        lastAttempt = now
                    )
                )
            } else {
                gbrDao.markAsSynced(id, System.currentTimeMillis())
            }
        }
    }

    suspend fun markAsFailed(id: String, error: String, stage: String = "FAILED", errorCode: String? = null, lastAttempt: Long = System.currentTimeMillis()): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.markAsFailed(id, error, stage, errorCode, lastAttempt)
        }
    }

    suspend fun logSyncChange(entityId: String, entityType: String): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.insertSyncMetadata(SyncMetadata(
                id = entityId,
                entityType = entityType,
                lastUpdated = System.currentTimeMillis(),
                isPendingSync = true,
                syncStage = "PENDING"
            ))
            SyncManager.uploadSingleEntityAsync(context, this@GbrRepository, entityId, entityType)
        }
    }

    // --- LABORATORY SESSIONS ---
    val allLabSessions: Flow<List<LabSession>> = gbrDao.getAllLabSessions()

    suspend fun getLabSessionById(id: String): LabSession? = withContext(Dispatchers.IO) {
        gbrDao.getLabSessionById(id)
    }

    suspend fun insertLabSession(session: LabSession): String = withContext(Dispatchers.IO) {
        gbrDao.insertLabSession(session)
        logSyncChange(session.id, "lab_session")
        session.id
    }

    suspend fun updateLabSession(session: LabSession): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateLabSession(session)
            logSyncChange(session.id, "lab_session")
        }
    }

    suspend fun deleteLabSession(session: LabSession): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteLabSession(session)
            gbrDao.deleteLabTestsForSession(session.id)
            gbrDao.deleteLabAttachmentsForSession(session.id)
            deleteSyncMetadataById(session.id)
            SyncManager.deleteSingleEntityAsync(context, session.id, "lab_session")

            if (session.id.startsWith("ref_specs_")) {
                val formulationId = session.id.substringAfter("ref_specs_")
                gbrDao.deleteFormulationReferenceSpecsByFormulationId(formulationId)
                logSyncChange(formulationId, "formulation")
                SyncManager.uploadSingleEntityAsync(context, this@GbrRepository, formulationId, "formulation")
            }
        }
    }

    // --- LABORATORY TESTS ---
    val allLabTests: Flow<List<LabTest>> = gbrDao.getAllLabTests()

    fun getLabTestsForSession(sessionId: String): Flow<List<LabTest>> = gbrDao.getAllLabTestsForSession(sessionId)

    suspend fun getLabTestById(id: String): LabTest? = withContext(Dispatchers.IO) {
        gbrDao.getLabTestById(id)
    }

    suspend fun insertLabTest(test: LabTest): String = withContext(Dispatchers.IO) {
        gbrDao.insertLabTest(test)
        logSyncChange(test.id, "lab_test")
        test.id
    }

    suspend fun updateLabTest(test: LabTest): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.updateLabTest(test)
            logSyncChange(test.id, "lab_test")
        }
    }

    suspend fun deleteLabTest(test: LabTest): Unit {
        withContext(Dispatchers.IO) {
            gbrDao.deleteLabTest(test)
            deleteSyncMetadataById(test.id)
            SyncManager.deleteSingleEntityAsync(context, test.id, "lab_test")
        }
    }

    // --- LABORATORY ATTACHMENTS ---
    val allLabAttachments: Flow<List<LabAttachment>> = gbrDao.getAllLabAttachments()

    fun getLabAttachmentsForSession(sessionId: String): Flow<List<LabAttachment>> = gbrDao.getAllLabAttachmentsForSession(sessionId)

    suspend fun getLabAttachmentById(id: String): LabAttachment? = withContext(Dispatchers.IO) {
        gbrDao.getLabAttachmentById(id)
    }

    suspend fun insertLabAttachment(attachment: LabAttachment): String = withContext(Dispatchers.IO) {
        gbrDao.insertLabAttachment(attachment)
        logSyncChange(attachment.id, "lab_attachment")
        attachment.id
    }

    suspend fun deleteLabAttachment(attachment: LabAttachment): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteLabAttachment(attachment)
        deleteSyncMetadataById(attachment.id)
        SyncManager.deleteSingleEntityAsync(context, attachment.id, "lab_attachment")
    }

    // --- FORMULATION REFERENCE SPECS ---
    fun getFormulationReferenceSpecs(formulationId: String): Flow<FormulationReferenceSpecs?> {
        return gbrDao.getFormulationReferenceSpecs(formulationId)
    }

    suspend fun getFormulationReferenceSpecsSync(formulationId: String): FormulationReferenceSpecs? = withContext(Dispatchers.IO) {
        gbrDao.getFormulationReferenceSpecsSync(formulationId)
    }

    suspend fun insertFormulationReferenceSpecs(specs: FormulationReferenceSpecs): Unit = withContext(Dispatchers.IO) {
        gbrDao.insertFormulationReferenceSpecs(specs)
        logSyncChange(specs.formulationId, "formulation_reference_specs")
    }

    suspend fun deleteFormulationReferenceSpecs(specs: FormulationReferenceSpecs): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteFormulationReferenceSpecs(specs)
        deleteSyncMetadataById(specs.formulationId)
        SyncManager.deleteSingleEntityAsync(context, specs.formulationId, "formulation_reference_specs")
    }

    suspend fun deleteFormulationReferenceSpecsByFormulationId(formulationId: String): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteFormulationReferenceSpecsByFormulationId(formulationId)
        deleteSyncMetadataById(formulationId)
        SyncManager.deleteSingleEntityAsync(context, formulationId, "formulation_reference_specs")
    }

    // --- OPERATIONAL ALERTS ---
    val allOperationalAlerts: Flow<List<OperationalAlert>> = gbrDao.getAllOperationalAlerts()

    suspend fun insertOperationalAlert(alert: OperationalAlert): String = withContext(Dispatchers.IO) {
        gbrDao.insertOperationalAlert(alert)
        logSyncChange(alert.id, "operational_alert")
        alert.id
    }

    suspend fun updateOperationalAlert(alert: OperationalAlert): Unit = withContext(Dispatchers.IO) {
        gbrDao.updateOperationalAlert(alert)
        logSyncChange(alert.id, "operational_alert")
    }

    suspend fun deleteOperationalAlert(alert: OperationalAlert): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteOperationalAlert(alert)
        deleteSyncMetadataById(alert.id)
        SyncManager.deleteSingleEntityAsync(context, alert.id, "operational_alert")
    }

    // --- RECYCLE BIN ---
    val allRecycleBinItems: Flow<List<RecycleBinItem>> = gbrDao.getAllRecycleBinItems()

    suspend fun getRecycleBinItemById(id: String): RecycleBinItem? = withContext(Dispatchers.IO) {
        gbrDao.getRecycleBinItemById(id)
    }

    suspend fun insertRecycleBinItem(item: RecycleBinItem): String = withContext(Dispatchers.IO) {
        gbrDao.insertRecycleBinItem(item)
        item.id
    }

    suspend fun deleteRecycleBinItem(item: RecycleBinItem): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteRecycleBinItem(item)
    }

    suspend fun clearAllRecycleBinItems(): Unit = withContext(Dispatchers.IO) {
        gbrDao.clearAllRecycleBinItems()
    }

    suspend fun deleteOldRecycleBinItems(cutoffTime: Long): Unit = withContext(Dispatchers.IO) {
        gbrDao.deleteOldRecycleBinItems(cutoffTime)
    }
}
