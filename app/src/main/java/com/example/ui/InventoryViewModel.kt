package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.InventoryIds
import com.example.data.InventoryItemProfile
import com.example.data.InventoryPoLine
import com.example.data.InventorySalesOrder
import com.example.data.InventorySoLine
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

/** نقص مادة لأمر إنتاج مفتوح (الاحتياج بوحدة الأساس مقابل المتاح). */
data class ShortageRow(
    val orderNumber: String,
    val product: String,
    val status: String,
    val itemName: String,
    val required: Double,
    val available: Double,
    val uom: String,
    val noBalance: Boolean
)

/** بند تدقيق ربط الإنتاج: CANCELLED/ORPHAN يُعكس صرفه يدوياً، DIFF للعلم والتسوية. */
data class IssueReview(val kind: String, val orderId: String, val title: String, val detail: String)

/** دفعة منتج تام بتفاصيلها المشتقة من الدفعة والأرصدة (عدد العبوات). */
data class FgLotRow(
    val lot: StockLot,
    val product: String,
    val pack: String,
    val netKg: Double,
    val orderNumber: String,
    val theoreticalKg: Double,
    val producedCount: Int,
    val qcCount: Double,        // في حجر الجودة
    val availCount: Double,     // في مخزن المنتج التام (مُفرَج)
    val orderYieldPct: Double,  // إنتاجية الأمر % = المعبأ ÷ النظري
    val retCount: Double = 0.0  // في حجر المرتجعات
) {
    val skuName: String get() = product + " — " + pack
}

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

    private val openOrderStatuses = setOf("مسودة", "جاهز للتنفيذ", "قيد التنفيذ")

    private fun kgToBase(kg: Double, s: StockRow): Double? {
        if (s.uom != "L") return kg
        val dn = s.profile?.densityKgPerL ?: 0.0
        return if (dn > 0.0) kg / dn else null
    }

    /** فحص التوفر: الأوامر المفتوحة التي يزيد احتياجها عن المتاح، ومواد بلا رصيد مسجَّل. */
    val shortages: StateFlow<List<ShortageRow>> =
        combine(gdao.getAllProductionOrders(), gdao.getAllProductionOrderItems(), stockRows) { orders, items, stock ->
            val byOrder = items.groupBy { it.productionOrderId }
            val stockBy = stock.associateBy { it.item.id }
            val out = ArrayList<ShortageRow>()
            for (o in orders.filter { it.status in openOrderStatuses }) {
                for (line in byOrder[o.id].orEmpty()) {
                    val s = stockBy[line.rawMaterialId] ?: continue
                    val req = kgToBase(line.calculatedQuantity, s) ?: continue
                    if (!s.hasMoves) {
                        out.add(ShortageRow(o.orderNumber, o.formulationName, o.status, line.rawMaterialName, req, 0.0, s.uom, true))
                    } else if (req > s.qtyDisplay + 0.0005) {
                        out.add(ShortageRow(o.orderNumber, o.formulationName, o.status, line.rawMaterialName, req, s.qtyDisplay, s.uom, false))
                    }
                }
            }
            out.sortedWith(compareBy<ShortageRow>({ it.orderNumber }, { it.itemName }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** تدقيق: صرف قائم لأمر ملغى/محذوف، وفروق بين المطلوب والمصروف لأوامر مكتملة. */
    val reviews: StateFlow<List<IssueReview>> =
        combine(gdao.getAllProductionOrders(), gdao.getAllProductionOrderItems(), allMoves) { orders, items, moves ->
            val reversed = moves.mapNotNull { it.reversesMoveId }.toSet()
            val live = moves.filter { it.type == "ISSUE" && it.refDoc.startsWith("ORD:") && it.moveId !in reversed }
            val byOrder = live.groupBy { it.refDoc.removePrefix("ORD:") }
            val orderBy = orders.associateBy { it.id }
            val itemsByOrder = items.groupBy { it.productionOrderId }
            val out = ArrayList<IssueReview>()
            for ((oid, ms) in byOrder) {
                val o = orderBy[oid]
                val totalKg = InventoryMath.toDisplay(ms.sumOf { it.qtyBase })
                if (o == null) {
                    // قائمة الأوامر الفارغة قد تعني قاعدة أُعيد إنشاؤها قبل المزامنة: لا نُنذر حينها
                    if (orders.isNotEmpty()) out.add(IssueReview("ORPHAN", oid, "أمر محذوف", "صرف قائم بإجمالي " + totalKg + " (وحدة الأساس) لأمر لم يعد موجوداً"))
                } else if (o.status == "ملغي") {
                    out.add(IssueReview("CANCELLED", oid, o.orderNumber + " — " + o.formulationName, "أمر ملغى له صرف قائم بإجمالي " + totalKg))
                } else if (o.status == "مكتمل") {
                    for (line in itemsByOrder[oid].orEmpty()) {
                        val issued = ms.filter { it.itemId == line.rawMaterialId }
                        if (issued.isEmpty() || issued.first().uom == "L") continue
                        val issuedKg = InventoryMath.toDisplay(issued.sumOf { it.qtyBase })
                        if (kotlin.math.abs(issuedKg - line.calculatedQuantity) > 0.01) {
                            out.add(IssueReview("DIFF", oid, o.orderNumber + " — " + line.rawMaterialName,
                                "المطلوب " + line.calculatedQuantity + " كجم، المصروف " + issuedKg + " كجم (عدّل بتسوية أو اعكس الحركة من بطاقة الصنف)"))
                        }
                    }
                }
            }
            out
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** دفعات المنتج التام (itemId يبدأ بـFG|) مع بيانات الأمر من السطر الأول للملاحظات. */
    val fgLots: StateFlow<List<FgLotRow>> =
        combine(lots, lotBalances) { ls, bal ->
            val fg = ls.filter { it.itemId.startsWith("FG|") }
            val balMap = bal.associate { (it.lotId + "|" + it.locId) to it.qty }
            val parsed = fg.map { l ->
                val parts = l.notes.lineSequence().firstOrNull().orEmpty().split("#")
                // FG#المنتج#العبوة#صافي#رقم الأمر#النظري#العدد
                val net = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                val cnt = parts.getOrNull(6)?.toIntOrNull() ?: 0
                val order = parts.getOrNull(4) ?: ""
                val theo = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                Triple(l, parts, Triple(net, cnt, Pair(order, theo)))
            }
            val packedByOrder = parsed.groupBy { it.third.third.first }
                .mapValues { e -> e.value.sumOf { it.third.first * it.third.second } }
            parsed.map { (l, parts, nc) ->
                val order = nc.third.first
                val theo = nc.third.second
                FgLotRow(
                    lot = l,
                    product = parts.getOrNull(1) ?: l.itemId,
                    pack = parts.getOrNull(2) ?: "",
                    netKg = nc.first,
                    orderNumber = order,
                    theoreticalKg = theo,
                    producedCount = nc.second,
                    qcCount = InventoryMath.toDisplay(balMap[l.id + "|" + InventoryIds.LOC_FG_QC] ?: 0L),
                    availCount = InventoryMath.toDisplay(balMap[l.id + "|" + InventoryIds.LOC_FG] ?: 0L),
                    orderYieldPct = if (theo > 0.0) (packedByOrder[order] ?: 0.0) / theo * 100.0 else 0.0,
                    retCount = InventoryMath.toDisplay(balMap[l.id + "|" + InventoryIds.LOC_FG_RET] ?: 0L)
                )
            }.sortedWith(compareBy<FgLotRow> { it.lot.expiryDate ?: Long.MAX_VALUE }.thenBy { it.lot.receivedAt })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val salesOrders: StateFlow<List<InventorySalesOrder>> =
        dao.observeSos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val soLines: StateFlow<List<InventorySoLine>> =
        dao.observeSoLines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** المسلَّم لكل سطر طلب (بعد استبعاد المعكوس)، بوحدة التخزين، بمفتاح مرجع السطر. */
    val deliveredByRef: StateFlow<Map<String, Long>> =
        allMoves.map { mv ->
            val reversed = mv.mapNotNull { it.reversesMoveId }.toSet()
            mv.filter { it.type == "FG_DELIVERY" && it.moveId !in reversed }
                .groupBy { it.refDoc }.mapValues { e -> e.value.sumOf { it.qtyBase } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** المتاح للوعد لكل SKU (عبوات): القابل للبيع − المحجوز لطلبات مؤكَّدة. */
    val skuAvailability: StateFlow<Map<String, Pair<Double, Double>>> =
        combine(fgLots, salesOrders, soLines, deliveredByRef) { fg, sos, lines, delivered ->
            val now = System.currentTimeMillis()
            val sellable = HashMap<String, Double>()
            for (r in fg) {
                if (r.lot.status == "RELEASED" && (r.lot.expiryDate == null || r.lot.expiryDate > now) && r.availCount > 0.0) {
                    sellable[r.lot.itemId] = (sellable[r.lot.itemId] ?: 0.0) + r.availCount
                }
            }
            val confirmed = sos.filter { it.status == "CONFIRMED" }.map { it.id }.toSet()
            val reserved = HashMap<String, Double>()
            for (l in lines) {
                if (l.soId !in confirmed) continue
                val rem = l.qtyOrdered - (delivered["SO:" + l.soId + ":" + l.id] ?: 0L)
                if (rem > 0L) reserved[l.sku] = (reserved[l.sku] ?: 0.0) + InventoryMath.toDisplay(rem)
            }
            val skus = (sellable.keys + reserved.keys + fg.map { it.lot.itemId }).toSet()
            skus.associateWith { Pair(sellable[it] ?: 0.0, reserved[it] ?: 0.0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** تتبع دفعة منتج تام: للخلف (الأمر ودفعات المواد المصروفة) وللأمام (العملاء والمرتجعات). */
    fun traceFinished(lotId: String): List<String> {
        val moves = allMoves.value
        val reversed = moves.mapNotNull { it.reversesMoveId }.toSet()
        val live = moves.filter { it.moveId !in reversed }
        val lot = lots.value.firstOrNull { it.id == lotId } ?: return listOf("الدفعة غير موجودة")
        val out = ArrayList<String>()
        val fgRow = fgLots.value.firstOrNull { it.lot.id == lotId }
        out.add("الدفعة: " + lot.lotNo + (if (fgRow != null) " — " + fgRow.skuName else ""))
        val orderRef = live.firstOrNull { it.lotId == lotId && it.type == "FG_RECEIPT" }?.refDoc
        out.add("— للخلف —")
        if (orderRef == null) {
            out.add("لا حركة استلام إنتاجي مرتبطة")
        } else {
            out.add("الأمر: " + (fgRow?.orderNumber ?: orderRef))
            val nameOf = materials.value.associate { it.id to it.name }
            val lotOf = lots.value.associateBy { it.id }
            val issued = live.filter { it.type == "ISSUE" && it.refDoc == orderRef }
            if (issued.isEmpty()) out.add("لا صرف مواد مسجَّل لهذا الأمر")
            issued.forEach { m ->
                val rl = m.lotId?.let { lotOf[it] }
                out.add("• " + (nameOf[m.itemId] ?: m.itemId) + " — " + (rl?.lotNo ?: "بلا دفعة") +
                    (if (rl != null && rl.supplierLotNo.isNotBlank()) " (دفعة المورد " + rl.supplierLotNo + ")" else "") +
                    " — " + InventoryMath.toDisplay(m.qtyBase))
            }
        }
        out.add("— للأمام —")
        val sos = salesOrders.value.associateBy { it.id }
        val deliveries = live.filter { it.lotId == lotId && it.type == "FG_DELIVERY" }
        if (deliveries.isEmpty()) out.add("لا تسليمات بعد")
        deliveries.forEach { m ->
            val so = sos[m.refDoc.split(":").getOrNull(1) ?: ""]
            out.add("• " + (so?.customer ?: "عميل غير معروف") + " (" + (so?.number ?: m.refDoc) + ") — " + InventoryMath.toDisplay(m.qtyBase) + " عبوة — " + fmtDateTime(m.serverCreatedAt ?: m.clientCreatedAt))
        }
        val returned = live.filter { it.lotId == lotId && it.type == "FG_RETURN" }
        if (returned.isNotEmpty()) out.add("مرتجعات: " + InventoryMath.toDisplay(returned.sumOf { it.qtyBase }) + " عبوة")
        return out
    }

    /** استدعاء دفعة مادة خام: الأوامر التي استهلكتها ← دفعات المنتج التام الناتجة ← العملاء. */
    fun traceRawLot(lotId: String): List<String> {
        val moves = allMoves.value
        val reversed = moves.mapNotNull { it.reversesMoveId }.toSet()
        val live = moves.filter { it.moveId !in reversed }
        val lot = lots.value.firstOrNull { it.id == lotId } ?: return listOf("الدفعة غير موجودة")
        val out = ArrayList<String>()
        out.add("الدفعة: " + lot.lotNo + (if (lot.supplierLotNo.isNotBlank()) " (دفعة المورد " + lot.supplierLotNo + ")" else ""))
        val refs = live.filter { it.lotId == lotId && it.type == "ISSUE" }.map { it.refDoc }.toSet()
        if (refs.isEmpty()) out.add("لم تُستهلك في أي أمر إنتاج مسجَّل")
        val sos = salesOrders.value.associateBy { it.id }
        for (ref in refs) {
            val fgReceipts = live.filter { it.type == "FG_RECEIPT" && it.refDoc == ref }
            val orderNo = fgLots.value.firstOrNull { r -> fgReceipts.any { it.lotId == r.lot.id } }?.orderNumber ?: ref
            out.add("أمر: " + orderNo)
            if (fgReceipts.isEmpty()) out.add("   لا دفعات منتج تام مرتبطة")
            fgReceipts.forEach { rc ->
                val row = fgLots.value.firstOrNull { it.lot.id == rc.lotId }
                out.add("   ← منتج تام: " + (row?.lot?.lotNo ?: rc.lotId) + " (" + (row?.skuName ?: "") + ")")
                live.filter { it.lotId == rc.lotId && it.type == "FG_DELIVERY" }.forEach { dm ->
                    val so = sos[dm.refDoc.split(":").getOrNull(1) ?: ""]
                    out.add("      ← عميل: " + (so?.customer ?: "غير معروف") + " (" + (so?.number ?: dm.refDoc) + ") — " + InventoryMath.toDisplay(dm.qtyBase) + " عبوة")
                }
            }
        }
        return out
    }

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

    fun reverseOrderIssues(orderId: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.reverseOrderIssues(getApplication(), orderId, user)) }
    }

    fun releaseFinished(lotId: String, evidence: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.releaseFinished(getApplication(), lotId, evidence, user)) }
    }

    fun rejectFinished(lotId: String, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.rejectFinished(getApplication(), lotId, reason, user)) }
    }

    fun markRework(lotId: String, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.markRework(getApplication(), lotId, reason, user)) }
    }

    fun adjustFinished(lotId: String, count: Int, reason: String, note: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.adjustFinished(getApplication(), lotId, count, reason, note, user)) }
    }

    fun createSo(customer: String, notes: String, user: String, lines: List<Pair<String, Int>>) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.createSo(getApplication(), customer, notes, user, lines)) }
    }

    fun setSoStatus(soId: String, status: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.setSoStatus(getApplication(), soId, status)) }
    }

    fun deliver(soLineId: String, count: Int, overrideLotId: String?, overrideReason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.deliver(getApplication(), soLineId, count, overrideLotId, overrideReason, user)) }
    }

    fun returnFromCustomer(soLineId: String, lotId: String, count: Int, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.returnFromCustomer(getApplication(), soLineId, lotId, count, reason, user)) }
    }

    fun dispositionReturn(lotId: String, count: Int, restock: Boolean, reason: String, user: String) {
        viewModelScope.launch(Dispatchers.IO) { report(InventoryManager.dispositionReturn(getApplication(), lotId, count, restock, reason, user)) }
    }

    fun syncNow() {
        InventorySync.uploadPendingAsync(getApplication())
        message.value = "جارٍ رفع الحركات المعلّقة إن وُجدت"
    }
}
