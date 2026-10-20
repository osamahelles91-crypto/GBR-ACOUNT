package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * مزامنة وحدة المخزون مع Firestore — مستقلة عن SyncManager كي لا تلمس مساراته القائمة.
 *  - stock_moves: حركات لا تُعدَّل. المستند معرّفه moveId (UUID) فإعادة الإرسال لا تنتج أثراً مزدوجاً،
 *    والمستمع يتجاهل (IGNORE) أي حركة موجودة محلياً. الرصيد لا يُرفع أبداً: يُشتق من الحركات.
 *  - السجلات الرئيسة (سجل المادة، موردون، مواقع، دفعات، معاملات التحويل): الأحدث updatedAt يفوز،
 *    والسجل المحلي المعلّق يُرفع فقط إن لم تكن السحابة أحدث (تُحسم داخل المستمع).
 */
object InventorySync {
    private const val TAG = "GBR_InventorySync"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uploadMutex = Mutex()

    const val COL_MOVES = "stock_moves"
    const val COL_PROFILES = "inventory_item_profiles"
    const val COL_CONVERSIONS = "uom_conversions"
    const val COL_SUPPLIERS = "inventory_suppliers"
    const val COL_LOCATIONS = "stock_locations"
    const val COL_LOTS = "stock_lots"
    const val COL_POS = "inventory_purchase_orders"
    const val COL_PO_LINES = "inventory_po_lines"
    const val COL_SOS = "inventory_sales_orders"
    const val COL_SO_LINES = "inventory_so_lines"
    const val COL_COUNTS = "inventory_counts"
    const val COL_COUNT_LINES = "inventory_count_lines"

    private fun cloudReady(context: Context): Boolean {
        if (!DeviceSecurityManager.isCloudConfigured(context)) return false
        if (DeviceSecurityManager.deviceStatus.value != DeviceSecurityManager.STATUS_APPROVED) return false
        return SyncManager.isNetworkAvailable(context) && SyncManager.initializeFirebase(context)
    }

    fun uploadPendingAsync(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                uploadPending(appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "uploadPending failed", e)
            }
        }
    }

    /** يرفع الحركات والسجلات الرئيسة المعلّقة. آمن للاستدعاء المتكرر. */
    suspend fun uploadPending(context: Context) {
        if (!cloudReady(context)) return
        uploadMutex.withLock {
            val db = FirebaseFirestore.getInstance()
            val dao = AppDatabase.getDatabase(context).inventoryDao()

            for (m in dao.getPendingMoves()) {
                try {
                    db.collection(COL_MOVES).document(m.moveId).set(
                        hashMapOf<String, Any?>(
                            "moveId" to m.moveId,
                            "deviceId" to m.deviceId,
                            "deviceSeq" to m.deviceSeq,
                            "itemId" to m.itemId,
                            "lotId" to m.lotId,
                            "fromLocId" to m.fromLocId,
                            "toLocId" to m.toLocId,
                            "qtyBase" to m.qtyBase,
                            "uom" to m.uom,
                            "type" to m.type,
                            "reasonCode" to m.reasonCode,
                            "refDoc" to m.refDoc,
                            "reversesMoveId" to m.reversesMoveId,
                            "unitCost" to m.unitCost,
                            "costCurrency" to m.costCurrency,
                            "userId" to m.userId,
                            "clientCreatedAt" to m.clientCreatedAt,
                            "serverCreatedAt" to FieldValue.serverTimestamp()
                        )
                    ).awaitTask()
                    dao.markMoveUploaded(m.moveId, null)
                } catch (e: Exception) {
                    Log.e(TAG, "move upload failed ${m.moveId}", e)
                }
            }

            for (p in dao.getPendingProfiles()) {
                try {
                    db.collection(COL_PROFILES).document(p.itemId).set(profileMap(p), SetOptions.merge()).awaitTask()
                    dao.markProfileSynced(p.itemId, p.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "profile upload failed ${p.itemId}", e) }
            }
            for (c in dao.getPendingConversions()) {
                try {
                    db.collection(COL_CONVERSIONS).document(c.id).set(
                        hashMapOf<String, Any?>("id" to c.id, "itemId" to c.itemId, "name" to c.name,
                            "factorToBase" to c.factorToBase, "updatedAt" to c.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markConversionSynced(c.id, c.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "conversion upload failed ${c.id}", e) }
            }
            for (s in dao.getPendingSuppliers()) {
                try {
                    db.collection(COL_SUPPLIERS).document(s.id).set(
                        hashMapOf<String, Any?>("id" to s.id, "name" to s.name, "phone" to s.phone, "notes" to s.notes,
                            "isApproved" to s.isApproved, "isActive" to s.isActive, "updatedAt" to s.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markSupplierSynced(s.id, s.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "supplier upload failed ${s.id}", e) }
            }
            for (l in dao.getPendingLocations()) {
                try {
                    db.collection(COL_LOCATIONS).document(l.id).set(
                        hashMapOf<String, Any?>("id" to l.id, "name" to l.name, "zone" to l.zone, "parentId" to l.parentId,
                            "isActive" to l.isActive, "updatedAt" to l.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markLocationSynced(l.id, l.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "location upload failed ${l.id}", e) }
            }
            for (l in dao.getPendingLots()) {
                try {
                    db.collection(COL_LOTS).document(l.id).set(
                        hashMapOf<String, Any?>("id" to l.id, "itemId" to l.itemId, "lotNo" to l.lotNo,
                            "supplierLotNo" to l.supplierLotNo, "supplierId" to l.supplierId, "mfgDate" to l.mfgDate,
                            "expiryDate" to l.expiryDate, "status" to l.status, "receivedAt" to l.receivedAt,
                            "notes" to l.notes, "updatedAt" to l.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markLotSynced(l.id, l.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "lot upload failed ${l.id}", e) }
            }
            for (po in dao.getPendingPos()) {
                try {
                    db.collection(COL_POS).document(po.id).set(
                        hashMapOf<String, Any?>("id" to po.id, "number" to po.number, "supplierId" to po.supplierId,
                            "status" to po.status, "notes" to po.notes, "createdBy" to po.createdBy,
                            "createdAt" to po.createdAt, "updatedAt" to po.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markPoSynced(po.id, po.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "po upload failed ${po.id}", e) }
            }
            for (l in dao.getPendingPoLines()) {
                try {
                    db.collection(COL_PO_LINES).document(l.id).set(
                        hashMapOf<String, Any?>("id" to l.id, "poId" to l.poId, "itemId" to l.itemId,
                            "qtyOrdered" to l.qtyOrdered, "unitPrice" to l.unitPrice, "updatedAt" to l.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markPoLineSynced(l.id, l.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "po line upload failed ${l.id}", e) }
            }
            for (so in dao.getPendingSos()) {
                try {
                    db.collection(COL_SOS).document(so.id).set(
                        hashMapOf<String, Any?>("id" to so.id, "number" to so.number, "customer" to so.customer,
                            "status" to so.status, "notes" to so.notes, "createdBy" to so.createdBy,
                            "createdAt" to so.createdAt, "updatedAt" to so.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markSoSynced(so.id, so.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "so upload failed ${so.id}", e) }
            }
            for (l in dao.getPendingSoLines()) {
                try {
                    db.collection(COL_SO_LINES).document(l.id).set(
                        hashMapOf<String, Any?>("id" to l.id, "soId" to l.soId, "sku" to l.sku,
                            "qtyOrdered" to l.qtyOrdered, "updatedAt" to l.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markSoLineSynced(l.id, l.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "so line upload failed ${l.id}", e) }
            }
            for (c in dao.getPendingCounts()) {
                try {
                    db.collection(COL_COUNTS).document(c.id).set(
                        hashMapOf<String, Any?>("id" to c.id, "number" to c.number, "scope" to c.scope, "status" to c.status,
                            "createdBy" to c.createdBy, "countedBy" to c.countedBy, "approvedBy" to c.approvedBy,
                            "createdAt" to c.createdAt, "updatedAt" to c.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markCountSynced(c.id, c.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "count upload failed ${c.id}", e) }
            }
            for (l in dao.getPendingCountLines()) {
                try {
                    db.collection(COL_COUNT_LINES).document(l.id).set(
                        hashMapOf<String, Any?>("id" to l.id, "countId" to l.countId, "itemId" to l.itemId, "hasCount" to l.hasCount,
                            "counted" to l.counted, "expected" to l.expected, "updatedAt" to l.updatedAt), SetOptions.merge()
                    ).awaitTask()
                    dao.markCountLineSynced(l.id, l.updatedAt)
                } catch (e: Exception) { Log.e(TAG, "count line upload failed ${l.id}", e) }
            }
        }
    }

    private fun profileMap(p: InventoryItemProfile): HashMap<String, Any?> = hashMapOf(
        "itemId" to p.itemId, "category" to p.category, "baseUom" to p.baseUom, "densityKgPerL" to p.densityKgPerL,
        "shelfLifeDays" to p.shelfLifeDays, "retestDays" to p.retestDays, "minLevel" to p.minLevel,
        "maxLevel" to p.maxLevel, "reorderPoint" to p.reorderPoint, "safetyStock" to p.safetyStock,
        "leadTimeDays" to p.leadTimeDays, "moq" to p.moq, "hazardClass" to p.hazardClass,
        "flashPointC" to p.flashPointC, "storageZone" to p.storageZone, "weighTolerancePct" to p.weighTolerancePct,
        "vedClass" to p.vedClass, "preferredSupplierId" to p.preferredSupplierId, "sdsNote" to p.sdsNote,
        "updatedAt" to p.updatedAt
    )

    private fun num(d: DocumentSnapshot, f: String): Double = (d.get(f) as? Number)?.toDouble() ?: 0.0
    private fun lng(d: DocumentSnapshot, f: String): Long? = (d.get(f) as? Number)?.toLong()
    private fun int(d: DocumentSnapshot, f: String): Int = (d.get(f) as? Number)?.toInt() ?: 0

    /** يربط مستمعي المخزون. يعيد التسجيلات ليضيفها SyncManager إلى قائمته (فتُزال مع بقية المستمعين). */
    fun attachListeners(db: FirebaseFirestore, context: Context): List<ListenerRegistration> {
        val appCtx = context.applicationContext
        val dao = AppDatabase.getDatabase(appCtx).inventoryDao()
        val regs = mutableListOf<ListenerRegistration>()

        regs += db.collection(COL_MOVES).addSnapshotListener { snaps, err ->
            if (err != null) { Log.e(TAG, "listen moves failed", err); return@addSnapshotListener }
            if (snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue // الحركات لا تُحذف: نتجاهل أي حذف يأتي من السحابة
                    val d = dc.document
                    try {
                        val serverTs = d.getTimestamp("serverCreatedAt")?.toDate()?.time
                        val move = StockMove(
                            moveId = d.id,
                            deviceId = d.getString("deviceId") ?: "",
                            deviceSeq = lng(d, "deviceSeq") ?: 0L,
                            itemId = d.getString("itemId") ?: continue,
                            lotId = d.getString("lotId"),
                            fromLocId = d.getString("fromLocId") ?: continue,
                            toLocId = d.getString("toLocId") ?: continue,
                            qtyBase = lng(d, "qtyBase") ?: continue,
                            uom = d.getString("uom") ?: "KG",
                            type = d.getString("type") ?: "",
                            reasonCode = d.getString("reasonCode") ?: "",
                            refDoc = d.getString("refDoc") ?: "",
                            reversesMoveId = d.getString("reversesMoveId"),
                            unitCost = (d.get("unitCost") as? Number)?.toDouble(),
                            costCurrency = d.getString("costCurrency") ?: "ILS",
                            userId = d.getString("userId") ?: "",
                            clientCreatedAt = lng(d, "clientCreatedAt") ?: System.currentTimeMillis(),
                            serverCreatedAt = serverTs,
                            uploaded = !d.metadata.hasPendingWrites()
                        )
                        val inserted = dao.insertMove(move)
                        if (inserted == -1L && !d.metadata.hasPendingWrites()) {
                            dao.markMoveUploaded(d.id, serverTs)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "apply cloud move ${d.id} failed", e)
                    }
                }
            }
        }

        regs += db.collection(COL_PROFILES).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.profileOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertProfile(
                                InventoryItemProfile(
                                    itemId = d.id,
                                    category = d.getString("category") ?: "OTHER",
                                    baseUom = d.getString("baseUom") ?: "KG",
                                    densityKgPerL = num(d, "densityKgPerL"),
                                    shelfLifeDays = int(d, "shelfLifeDays"),
                                    retestDays = int(d, "retestDays"),
                                    minLevel = num(d, "minLevel"),
                                    maxLevel = num(d, "maxLevel"),
                                    reorderPoint = num(d, "reorderPoint"),
                                    safetyStock = num(d, "safetyStock"),
                                    leadTimeDays = int(d, "leadTimeDays"),
                                    moq = num(d, "moq"),
                                    hazardClass = d.getString("hazardClass") ?: "",
                                    flashPointC = (d.get("flashPointC") as? Number)?.toDouble(),
                                    storageZone = d.getString("storageZone") ?: "",
                                    weighTolerancePct = num(d, "weighTolerancePct"),
                                    vedClass = d.getString("vedClass") ?: "",
                                    preferredSupplierId = d.getString("preferredSupplierId"),
                                    sdsNote = d.getString("sdsNote") ?: "",
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud profile ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_CONVERSIONS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.conversionOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertConversion(
                                UomConversion(
                                    id = d.id,
                                    itemId = d.getString("itemId") ?: continue,
                                    name = d.getString("name") ?: "",
                                    factorToBase = num(d, "factorToBase"),
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud conversion ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_SUPPLIERS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.supplierOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertSupplier(
                                InventorySupplier(
                                    id = d.id,
                                    name = d.getString("name") ?: "",
                                    phone = d.getString("phone") ?: "",
                                    notes = d.getString("notes") ?: "",
                                    isApproved = d.getBoolean("isApproved") ?: true,
                                    isActive = d.getBoolean("isActive") ?: true,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud supplier ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_LOCATIONS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.locationOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertLocation(
                                StockLocation(
                                    id = d.id,
                                    name = d.getString("name") ?: "",
                                    zone = d.getString("zone") ?: "GENERAL",
                                    parentId = d.getString("parentId"),
                                    isActive = d.getBoolean("isActive") ?: true,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud location ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_LOTS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.lotOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertLot(
                                StockLot(
                                    id = d.id,
                                    itemId = d.getString("itemId") ?: continue,
                                    lotNo = d.getString("lotNo") ?: "",
                                    supplierLotNo = d.getString("supplierLotNo") ?: "",
                                    supplierId = d.getString("supplierId"),
                                    mfgDate = lng(d, "mfgDate"),
                                    expiryDate = lng(d, "expiryDate"),
                                    status = d.getString("status") ?: "RELEASED",
                                    receivedAt = lng(d, "receivedAt") ?: System.currentTimeMillis(),
                                    notes = d.getString("notes") ?: "",
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud lot ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_POS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.poOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertPo(
                                InventoryPurchaseOrder(
                                    id = d.id,
                                    number = d.getString("number") ?: "",
                                    supplierId = d.getString("supplierId") ?: continue,
                                    status = d.getString("status") ?: "DRAFT",
                                    notes = d.getString("notes") ?: "",
                                    createdBy = d.getString("createdBy") ?: "",
                                    createdAt = lng(d, "createdAt") ?: cloudTs,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud po ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_PO_LINES).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.poLineOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertPoLine(
                                InventoryPoLine(
                                    id = d.id,
                                    poId = d.getString("poId") ?: continue,
                                    itemId = d.getString("itemId") ?: continue,
                                    qtyOrdered = lng(d, "qtyOrdered") ?: continue,
                                    unitPrice = num(d, "unitPrice"),
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud po line ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_SOS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.soOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertSo(
                                InventorySalesOrder(
                                    id = d.id,
                                    number = d.getString("number") ?: "",
                                    customer = d.getString("customer") ?: "",
                                    status = d.getString("status") ?: "DRAFT",
                                    notes = d.getString("notes") ?: "",
                                    createdBy = d.getString("createdBy") ?: "",
                                    createdAt = lng(d, "createdAt") ?: cloudTs,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud so ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_SO_LINES).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.soLineOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertSoLine(
                                InventorySoLine(
                                    id = d.id,
                                    soId = d.getString("soId") ?: continue,
                                    sku = d.getString("sku") ?: continue,
                                    qtyOrdered = lng(d, "qtyOrdered") ?: continue,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud so line ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_COUNTS).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.countOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertCount(
                                InventoryCount(
                                    id = d.id,
                                    number = d.getString("number") ?: "",
                                    scope = d.getString("scope") ?: "ALL",
                                    status = d.getString("status") ?: "OPEN",
                                    createdBy = d.getString("createdBy") ?: "",
                                    countedBy = d.getString("countedBy") ?: "",
                                    approvedBy = d.getString("approvedBy") ?: "",
                                    createdAt = lng(d, "createdAt") ?: cloudTs,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud count ${d.id} failed", e) }
                }
            }
        }

        regs += db.collection(COL_COUNT_LINES).addSnapshotListener { snaps, err ->
            if (err != null || snaps == null) return@addSnapshotListener
            scope.launch {
                for (dc in snaps.documentChanges) {
                    if (dc.type == DocumentChange.Type.REMOVED) continue
                    val d = dc.document
                    try {
                        val cloudTs = lng(d, "updatedAt") ?: continue
                        val local = dao.countLineOrNull(d.id)
                        if (local == null || cloudTs > local.updatedAt) {
                            dao.upsertCountLine(
                                InventoryCountLine(
                                    id = d.id,
                                    countId = d.getString("countId") ?: continue,
                                    itemId = d.getString("itemId") ?: continue,
                                    hasCount = d.getBoolean("hasCount") ?: false,
                                    counted = lng(d, "counted") ?: 0L,
                                    expected = lng(d, "expected") ?: 0L,
                                    updatedAt = cloudTs,
                                    isPending = false
                                )
                            )
                        }
                    } catch (e: Exception) { Log.e(TAG, "apply cloud count line ${d.id} failed", e) }
                }
            }
        }

        // ما كان معلّقاً محلياً يُرفع بعد ربط المستمعين
        uploadPendingAsync(appCtx)
        return regs
    }
}
