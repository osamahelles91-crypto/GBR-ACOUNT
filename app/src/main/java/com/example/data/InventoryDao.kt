package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    // ---------- الحركات (لا تحديث ولا حذف لمحتواها) ----------
    /** يتجاهل الحركة إن وُجد معرّفها (يمنع الأثر المزدوج عند إعادة التنفيذ). يعيد -1 إن كانت موجودة. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMove(move: StockMove): Long

    @Query("SELECT * FROM stock_moves ORDER BY COALESCE(serverCreatedAt, clientCreatedAt), deviceSeq")
    fun observeAllMoves(): Flow<List<StockMove>>

    @Query("SELECT * FROM stock_moves WHERE itemId = :itemId ORDER BY COALESCE(serverCreatedAt, clientCreatedAt), deviceSeq")
    fun observeMovesForItem(itemId: String): Flow<List<StockMove>>

    @Query("SELECT * FROM stock_moves ORDER BY COALESCE(serverCreatedAt, clientCreatedAt), deviceSeq")
    suspend fun getAllMovesSync(): List<StockMove>

    @Query("SELECT * FROM stock_moves WHERE moveId = :id")
    suspend fun getMove(id: String): StockMove?

    @Query("SELECT * FROM stock_moves WHERE uploaded = 0")
    suspend fun getPendingMoves(): List<StockMove>

    /** ميتا فقط: علامة الرفع وختم الخادم. لا تمس كمية الحركة. */
    @Query("UPDATE stock_moves SET uploaded = 1, serverCreatedAt = COALESCE(:serverTs, serverCreatedAt) WHERE moveId = :id")
    suspend fun markMoveUploaded(id: String, serverTs: Long?)

    @Query("SELECT COUNT(*) FROM stock_moves WHERE reversesMoveId = :moveId")
    suspend fun countReversalsOf(moveId: String): Int

    @Query("SELECT COUNT(*) FROM stock_moves WHERE itemId = :itemId")
    suspend fun countMovesForItem(itemId: String): Int

    /** أرصدة المخزون الفعلي (المواقع الحقيقية فقط؛ المواقع الافتراضية تبدأ بـ V_) لكل مادة. */
    @Query(
        "SELECT itemId, " +
            "SUM(CASE WHEN substr(toLocId,1,2) <> 'V_' THEN qtyBase ELSE 0 END) - " +
            "SUM(CASE WHEN substr(fromLocId,1,2) <> 'V_' THEN qtyBase ELSE 0 END) AS qty " +
            "FROM stock_moves GROUP BY itemId"
    )
    fun observeOnHand(): Flow<List<ItemQty>>

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN substr(toLocId,1,2) <> 'V_' THEN qtyBase ELSE 0 END) - " +
            "SUM(CASE WHEN substr(fromLocId,1,2) <> 'V_' THEN qtyBase ELSE 0 END), 0) " +
            "FROM stock_moves WHERE itemId = :itemId"
    )
    suspend fun getOnHand(itemId: String): Long

    @Query(
        "SELECT locId, SUM(q) AS qty FROM (" +
            "SELECT toLocId AS locId, qtyBase AS q FROM stock_moves WHERE itemId = :itemId " +
            "UNION ALL SELECT fromLocId AS locId, -qtyBase AS q FROM stock_moves WHERE itemId = :itemId" +
            ") WHERE substr(locId,1,2) <> 'V_' GROUP BY locId"
    )
    fun observeOnHandByLocation(itemId: String): Flow<List<LocQty>>

    // ---------- سجلات رئيسة (قابلة للتعديل: الأحدث updatedAt يفوز) ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(p: InventoryItemProfile)

    @Query("SELECT * FROM inventory_item_profile")
    fun observeProfiles(): Flow<List<InventoryItemProfile>>

    @Query("SELECT * FROM inventory_item_profile WHERE itemId = :itemId")
    suspend fun getProfile(itemId: String): InventoryItemProfile?

    @Query("SELECT * FROM inventory_item_profile WHERE isPending = 1")
    suspend fun getPendingProfiles(): List<InventoryItemProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversion(c: UomConversion)

    @Query("DELETE FROM uom_conversions WHERE id = :id")
    suspend fun deleteConversion(id: String)

    @Query("SELECT * FROM uom_conversions")
    fun observeConversions(): Flow<List<UomConversion>>

    @Query("SELECT * FROM uom_conversions WHERE itemId = :itemId")
    suspend fun getConversions(itemId: String): List<UomConversion>

    @Query("SELECT * FROM uom_conversions WHERE isPending = 1")
    suspend fun getPendingConversions(): List<UomConversion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSupplier(s: InventorySupplier)

    @Query("SELECT * FROM inventory_suppliers ORDER BY name")
    fun observeSuppliers(): Flow<List<InventorySupplier>>

    @Query("SELECT * FROM inventory_suppliers WHERE isPending = 1")
    suspend fun getPendingSuppliers(): List<InventorySupplier>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocation(l: StockLocation)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLocationIfAbsent(l: StockLocation): Long

    @Query("SELECT * FROM stock_locations ORDER BY name")
    fun observeLocations(): Flow<List<StockLocation>>

    @Query("SELECT COUNT(*) FROM stock_locations")
    suspend fun countLocations(): Int

    @Query("SELECT * FROM stock_locations WHERE isPending = 1")
    suspend fun getPendingLocations(): List<StockLocation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLot(l: StockLot)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLotIfAbsent(l: StockLot): Long

    @Query("SELECT * FROM stock_lots")
    fun observeLots(): Flow<List<StockLot>>

    @Query("SELECT * FROM stock_lots WHERE isPending = 1")
    suspend fun getPendingLots(): List<StockLot>

    // ---------- أرشفة/استيراد ----------
    @Query("SELECT * FROM inventory_item_profile")
    suspend fun getAllProfilesSync(): List<InventoryItemProfile>

    @Query("SELECT * FROM uom_conversions")
    suspend fun getAllConversionsSync(): List<UomConversion>

    @Query("SELECT * FROM inventory_suppliers")
    suspend fun getAllSuppliersSync(): List<InventorySupplier>

    @Query("SELECT * FROM stock_locations")
    suspend fun getAllLocationsSync(): List<StockLocation>

    @Query("SELECT * FROM stock_lots")
    suspend fun getAllLotsSync(): List<StockLot>

    @Query("UPDATE inventory_item_profile SET isPending = 0 WHERE itemId = :id AND updatedAt = :updatedAt")
    suspend fun markProfileSynced(id: String, updatedAt: Long)

    @Query("UPDATE uom_conversions SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markConversionSynced(id: String, updatedAt: Long)

    @Query("UPDATE inventory_suppliers SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSupplierSynced(id: String, updatedAt: Long)

    @Query("UPDATE stock_locations SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markLocationSynced(id: String, updatedAt: Long)

    @Query("UPDATE stock_lots SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markLotSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM inventory_item_profile WHERE itemId = :id")
    suspend fun profileOrNull(id: String): InventoryItemProfile?

    @Query("SELECT * FROM uom_conversions WHERE id = :id")
    suspend fun conversionOrNull(id: String): UomConversion?

    @Query("SELECT * FROM inventory_suppliers WHERE id = :id")
    suspend fun supplierOrNull(id: String): InventorySupplier?

    @Query("SELECT * FROM stock_locations WHERE id = :id")
    suspend fun locationOrNull(id: String): StockLocation?

    @Query("SELECT * FROM stock_lots WHERE id = :id")
    suspend fun lotOrNull(id: String): StockLot?

    // ---------- المرحلة 2: الدفعات والاستلام وأوامر الشراء ----------
    /** رصيد مادة في موقع محدد (حقيقي أو افتراضي). */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN toLocId = :locId THEN qtyBase ELSE 0 END) - " +
            "SUM(CASE WHEN fromLocId = :locId THEN qtyBase ELSE 0 END), 0) FROM stock_moves WHERE itemId = :itemId"
    )
    suspend fun getOnHandAt(itemId: String, locId: String): Long

    /** رصيد دفعة في موقع محدد. */
    @Query(
        "SELECT COALESCE(SUM(CASE WHEN toLocId = :locId THEN qtyBase ELSE 0 END) - " +
            "SUM(CASE WHEN fromLocId = :locId THEN qtyBase ELSE 0 END), 0) FROM stock_moves WHERE lotId = :lotId"
    )
    suspend fun getLotOnHandAt(lotId: String, locId: String): Long

    /** أرصدة الدفعات في المواقع الحقيقية. */
    @Query(
        "SELECT lotId, locId, SUM(q) AS qty FROM (" +
            "SELECT lotId AS lotId, toLocId AS locId, qtyBase AS q FROM stock_moves WHERE lotId IS NOT NULL " +
            "UNION ALL SELECT lotId AS lotId, fromLocId AS locId, -qtyBase AS q FROM stock_moves WHERE lotId IS NOT NULL" +
            ") WHERE substr(locId,1,2) <> 'V_' GROUP BY lotId, locId"
    )
    fun observeLotBalances(): Flow<List<LotLocQty>>

    /** المستلم فعلاً مقابل مرجع (أمر/سطر) بعد استبعاد الحركات المعكوسة. */
    @Query(
        "SELECT COALESCE(SUM(qtyBase), 0) FROM stock_moves m WHERE m.type = 'RECEIPT' AND m.refDoc = :ref " +
            "AND NOT EXISTS (SELECT 1 FROM stock_moves r WHERE r.reversesMoveId = m.moveId)"
    )
    suspend fun getReceivedForRef(ref: String): Long

    @Query("SELECT * FROM stock_lots WHERE id = :id")
    suspend fun getLot(id: String): StockLot?

    /** أقدم رصيد افتتاحي في الدفتر: بداية تتبع المنتج التام (مشتركة بين الأجهزة لأنها من الدفتر نفسه). */
    @Query("SELECT MIN(clientCreatedAt) FROM stock_moves WHERE type = 'OPENING'")
    suspend fun firstOpeningTs(): Long?

    @Query("SELECT * FROM stock_lots WHERE itemId = :itemId")
    suspend fun getLotsForItem(itemId: String): List<StockLot>

    @Query("SELECT COUNT(*) FROM stock_moves WHERE type = 'ISSUE' AND refDoc = :ref AND itemId = :itemId")
    suspend fun countIssuesFor(ref: String, itemId: String): Int

    @Query("SELECT COUNT(*) FROM stock_lots WHERE lotNo LIKE :prefix || '%'")
    suspend fun countLotsWithPrefix(prefix: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPo(po: InventoryPurchaseOrder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPoLine(l: InventoryPoLine)

    @Query("DELETE FROM inventory_po_lines WHERE id = :id")
    suspend fun deletePoLine(id: String)

    @Query("SELECT * FROM inventory_purchase_orders ORDER BY createdAt DESC")
    fun observePos(): Flow<List<InventoryPurchaseOrder>>

    @Query("SELECT * FROM inventory_po_lines")
    fun observePoLines(): Flow<List<InventoryPoLine>>

    @Query("SELECT * FROM inventory_purchase_orders WHERE id = :id")
    suspend fun poOrNull(id: String): InventoryPurchaseOrder?

    @Query("SELECT * FROM inventory_po_lines WHERE id = :id")
    suspend fun poLineOrNull(id: String): InventoryPoLine?

    @Query("SELECT * FROM inventory_po_lines WHERE poId = :poId")
    suspend fun getPoLines(poId: String): List<InventoryPoLine>

    @Query("SELECT COUNT(*) FROM inventory_purchase_orders")
    suspend fun countPos(): Int

    @Query("SELECT * FROM inventory_purchase_orders WHERE isPending = 1")
    suspend fun getPendingPos(): List<InventoryPurchaseOrder>

    @Query("SELECT * FROM inventory_po_lines WHERE isPending = 1")
    suspend fun getPendingPoLines(): List<InventoryPoLine>

    @Query("UPDATE inventory_purchase_orders SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markPoSynced(id: String, updatedAt: Long)

    @Query("UPDATE inventory_po_lines SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markPoLineSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM inventory_purchase_orders")
    suspend fun getAllPosSync(): List<InventoryPurchaseOrder>

    @Query("SELECT * FROM inventory_po_lines")
    suspend fun getAllPoLinesSync(): List<InventoryPoLine>

    // ---------- المرحلة 5: البيع والتسليم والمرتجعات ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSo(so: InventorySalesOrder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSoLine(l: InventorySoLine)

    @Query("SELECT * FROM inventory_sales_orders ORDER BY createdAt DESC")
    fun observeSos(): Flow<List<InventorySalesOrder>>

    @Query("SELECT * FROM inventory_so_lines")
    fun observeSoLines(): Flow<List<InventorySoLine>>

    @Query("SELECT * FROM inventory_sales_orders WHERE id = :id")
    suspend fun soOrNull(id: String): InventorySalesOrder?

    @Query("SELECT * FROM inventory_so_lines WHERE id = :id")
    suspend fun soLineOrNull(id: String): InventorySoLine?

    @Query("SELECT * FROM inventory_so_lines WHERE soId = :soId")
    suspend fun getSoLines(soId: String): List<InventorySoLine>

    @Query("SELECT * FROM inventory_sales_orders WHERE isPending = 1")
    suspend fun getPendingSos(): List<InventorySalesOrder>

    @Query("SELECT * FROM inventory_so_lines WHERE isPending = 1")
    suspend fun getPendingSoLines(): List<InventorySoLine>

    @Query("UPDATE inventory_sales_orders SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSoSynced(id: String, updatedAt: Long)

    @Query("UPDATE inventory_so_lines SET isPending = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSoLineSynced(id: String, updatedAt: Long)

    @Query("SELECT * FROM inventory_sales_orders")
    suspend fun getAllSosSync(): List<InventorySalesOrder>

    @Query("SELECT * FROM inventory_so_lines")
    suspend fun getAllSoLinesSync(): List<InventorySoLine>

    /** المسلَّم فعلاً مقابل مرجع (سطر) بعد استبعاد الحركات المعكوسة. */
    @Query(
        "SELECT COALESCE(SUM(qtyBase), 0) FROM stock_moves m WHERE m.type = 'FG_DELIVERY' AND m.refDoc = :ref " +
            "AND NOT EXISTS (SELECT 1 FROM stock_moves r WHERE r.reversesMoveId = m.moveId)"
    )
    suspend fun getDeliveredForRef(ref: String): Long

    @Query(
        "SELECT COALESCE(SUM(qtyBase), 0) FROM stock_moves m WHERE m.type = 'FG_DELIVERY' AND m.refDoc = :ref AND m.lotId = :lotId " +
            "AND NOT EXISTS (SELECT 1 FROM stock_moves r WHERE r.reversesMoveId = m.moveId)"
    )
    suspend fun getDeliveredForRefLot(ref: String, lotId: String): Long

    @Query(
        "SELECT COALESCE(SUM(qtyBase), 0) FROM stock_moves m WHERE m.type = 'FG_RETURN' AND m.refDoc = :ref AND m.lotId = :lotId " +
            "AND NOT EXISTS (SELECT 1 FROM stock_moves r WHERE r.reversesMoveId = m.moveId)"
    )
    suspend fun getReturnedForRefLot(ref: String, lotId: String): Long
}
