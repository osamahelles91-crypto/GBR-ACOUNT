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
}
