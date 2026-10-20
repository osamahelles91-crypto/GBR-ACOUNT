package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.InventoryIds
import com.example.data.InventoryItemProfile
import com.example.data.InventoryPoLine
import com.example.data.InventoryPurchaseOrder
import com.example.data.LotLocQty
import com.example.data.StockLot
import com.example.data.InventoryManager
import com.example.data.InventoryMath
import com.example.data.InventorySupplier
import com.example.data.InventorySync
import com.example.data.LedgerRow
import com.example.data.OpResult
import com.example.data.RawMaterial
import com.example.data.StockLocation
import com.example.data.StockMove
import com.example.data.UomConversion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** صف مخزون لمادة: الرصيد الحالي ومتوسط التكلفة وحالة المستوى. */
data class StockRow(
    val item: RawMaterial,
    val profile: InventoryItemProfile?,
    val hasMoves: Boolean,
    val qtyStorage: Long,
    val avgCost: Double,
    val value: Double,
    val status: String,       // NO_BALANCE, OK, REORDER, LOW, NEGATIVE
    val quarantineStorage: Long = 0L   // في الحجر (لا يُحتسب ضمن المتاح)
) {
    val quarantineDisplay: Double get() = InventoryMath.toDisplay(quarantineStorage)
    val qtyDisplay: Double get() = InventoryMath.toDisplay(qtyStorage)
    val uom: String get() = profile?.baseUom ?: "KG"
}

data class AbcRow(
    val itemId: String,
    val name: String,
    val consumptionValue: Double,
    val sharePct: Double,
    val abcClass: String
)

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.inventoryDao()
    private val gdao = db.gbrDao()

    val materials: StateFlow<List<RawMaterial>> =
        gdao.getAllRawMaterials().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val profiles: StateFlow<Map<String, InventoryItemProfile>> =
        dao.observeProfiles().map { l -> l.associateBy { it.itemId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val conversions: StateFlow<List<UomConversion>> =
        dao.observeConversions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suppliers: StateFlow<List<InventorySupplier>> =
        dao.observeSuppliers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val locations: StateFlow<List<StockLocation>> =
        dao.observeLocations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMoves: StateFlow<List<StockMove>> =
        dao.observeAllMoves().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** الرصيد وحالته لكل مادة نشطة: يُحسب من الحركات فقط (لا حقل رصيد قابل للتعديل). */
    val stockRows: StateFlow<List<StockRow>> =
        combine(materials, profiles, allMoves) { mats, profs, moves ->
            val byItem = moves.groupBy { it.itemId }
            mats.map { m ->
                val itemMoves = byItem[m.id].orEmpty()
                val p = profs[m.id]
                val rows = InventoryMath.replay(itemMoves, m.price)
                val last = rows.lastOrNull()
                val quar = itemMoves.sumOf { mv ->
                    (if (mv.toLocId == InventoryIds.LOC_QUAR) mv.qtyBase else 0L) - (if (mv.fromLocId == InventoryIds.LOC_QUAR) mv.qtyBase else 0L)
                }
                val qty = (last?.runningQty ?: 0L) - quar   // المتاح = الإجمالي الفعلي − الحجر
                val avg = last?.avgCostAfter ?: m.price
                val qtyDisp = InventoryMath.toDisplay(qty)
                val status = when {
                    itemMoves.isEmpty() -> "NO_BALANCE"
                    qty < 0L -> "NEGATIVE"
                    p != null && p.minLevel > 0.0 && qtyDisp <= p.minLevel -> "LOW"
                    p != null && p.reorderPoint > 0.0 && qtyDisp <= p.reorderPoint -> "REORDER"
                    else -> "OK"
                }
                StockRow(m, p, itemMoves.isNotEmpty(), qty, avg, qtyDisp * avg, status, quar)
            }.sortedBy { it.item.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** ABC بقيمة استهلاك آخر 12 شهراً من الأوامر المكتملة؛ وإن لم توجد أوامر فبقيمة المخزون الحالية. */
    val abcRows: StateFlow<List<AbcRow>> =
        combine(materials, gdao.getAllProductionOrders(), gdao.getAllProductionOrderItems(), stockRows) { mats, orders, items, stock ->
            val nameOf = mats.associate { it.id to it.name }
            val cutoff = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
            val completed = orders.filter { it.status == "مكتمل" && (if (it.endTime > 0L) it.endTime else it.createdAt) >= cutoff }
                .map { it.id }.toSet()
            val consumption = HashMap<String, Double>()
            items.filter { it.productionOrderId in completed }.forEach {
                consumption[it.rawMaterialId] = (consumption[it.rawMaterialId] ?: 0.0) + it.calculatedQuantity * it.rawMaterialPrice
            }
            val basis: Map<String, Double> =
                if (consumption.isNotEmpty()) consumption else stock.filter { it.value > 0.0 }.associate { it.item.id to it.value }
            val classes = InventoryMath.classifyAbc(basis)
            val total = basis.values.filter { it > 0.0 }.sum()
            basis.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.map { e ->
                AbcRow(e.key, nameOf[e.key] ?: e.key, e.value, if (total > 0) e.value / total * 100.0 else 0.0, classes[e.key] ?: "C")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** هل الأساس قيمة المخزون (لا أوامر مكتملة)؟ لعرض ملاحظة للمستخدم. */
    val abcIsConsumptionBased: StateFlow<Boolean> =
        combine(gdao.getAllProductionOrders(), gdao.getAllProductionOrderItems()) { orders, items ->
            val cutoff = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
            val ids = orders.filter { it.status == "مكتمل" && (if (it.endTime > 0L) it.endTime else it.createdAt) >= cutoff }.map { it.id }.toSet()
            items.any { it.productionOrderId in ids }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val lots: StateFlow<List<StockLot>> =
        dao.observeLots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lotBalances: StateFlow<List<LotLocQty>> =
        dao.observeLotBalances().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val purchaseOrders: StateFlow<List<InventoryPurchaseOrder>> =
        dao.observePos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val poLines: StateFlow<List<InventoryPoLine>> =
        dao.observePoLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** المستلم لكل سطر أمر (بعد استبعاد المعكوس) يُحسب من الحركات. */
    val receivedByRef: StateFlow<Map<String, Long>> =
        allMoves.map { mv ->
            val reversed = mv.mapNotNull { it.reversesMoveId }.toSet()
            mv.filter { it.type == "RECEIPT" && it.moveId !in reversed }
                .groupBy { it.refDoc }.mapValues { e -> e.value.sumOf { it.qtyBase } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            InventoryManager.ensureDefaults(getApplication())
            InventorySync.uploadPendingAsync(getApplication())
        }
    }

    fun ledgerFor(itemId: String, fallbackCost: Double): StateFlow<List<LedgerRow>> =
        dao.observeMovesForItem(itemId)
            .map { InventoryMath.replay(it, fallbackCost) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun report(r: OpResult) { message.value = r.message }

    fun openingBalance(itemId: String, uom: String, qty: Double, cost: Double?, expiry: Long?, user: String) {
        viewModelScope.launch(Dispatchers.IO) {
            report(InventoryManager.recordOpeningBalance(getApplication(), itemId, uom, qty, cost, expiry, user))
        }
    }

    fun adjust(itemId: String, uom: String, delta: Double, reason: String, note: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) {
            report(InventoryManager.adjust(getApplication(), itemId, uom, delta, reason, note, user))
        }
    }

    fun reverse(moveId: String, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) {
            report(InventoryManager.reverse(getApplication(), moveId, reason, user))
        }
    }

    /** حفظ سجل المادة. تغيير وحدة الأساس بعد وجود حركات مرفوض (يغيّر معنى الكميات المخزّنة). */
    fun saveProfile(p: InventoryItemProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = dao.getProfile(p.itemId)
            val moves = dao.countMovesForItem(p.itemId)
            val effectiveOld = existing?.baseUom ?: "KG"
            if (moves > 0 && effectiveOld != p.baseUom) {
                message.value = "لا يمكن تغيير وحدة الأساس بعد وجود حركات مخزون لهذه المادة"
                return@launch
            }
            if (p.densityKgPerL < 0 || p.minLevel < 0 || p.maxLevel < 0 || p.reorderPoint < 0 || p.safetyStock < 0 || p.moq < 0) {
                message.value = "القيم لا يمكن أن تكون سالبة"
                return@launch
            }
            if (p.maxLevel > 0.0 && p.minLevel > p.maxLevel) {
                message.value = "الحد الأدنى أكبر من الحد الأقصى"
                return@launch
            }
            InventoryManager.saveProfile(getApplication(), p)
            message.value = "تم حفظ سجل المادة"
        }
    }

    fun saveConversion(c: UomConversion) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.saveConversion(getApplication(), c)) }
    }

    fun saveSupplier(s: InventorySupplier) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.saveSupplier(getApplication(), s)) }
    }

    fun saveLocation(l: StockLocation) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.saveLocation(getApplication(), l)) }
    }

    fun createPo(supplierId: String, notes: String, user: String, lines: List<Triple<String, Double, Double>>) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.createPo(getApplication(), supplierId, notes, user, lines)) }
    }

    fun setPoStatus(poId: String, status: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.setPoStatus(getApplication(), poId, status)) }
    }

    fun receive(
        itemId: String, uom: String, qty: Double, poLineId: String?, supplierId: String?, supplierLotNo: String,
        mfg: Long?, expiry: Long?, cost: Double?, user: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            report(InventoryManager.receive(getApplication(), itemId, uom, qty, poLineId, supplierId, supplierLotNo, mfg, expiry, cost, user))
        }
    }

    fun releaseLot(lotId: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.releaseLot(getApplication(), lotId, user)) }
    }

    fun rejectLot(lotId: String, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.rejectLot(getApplication(), lotId, reason, user)) }
    }

    fun syncNow() {
        InventorySync.uploadPendingAsync(getApplication())
        message.value = "جارٍ رفع الحركات المعلّقة إن وُجدت"
    }
}
