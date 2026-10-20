package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ربط الإنتاج بالمخزون (المرحلة 3) — بلا لمس لمسارات الإنتاج القائمة:
 *  - يراقب الأوامر وبنودها، وعند وجود أمر «مكتمل» يصرف مواده من المخزن الرئيسي إلى V_PRODUCTION بترتيب FEFO.
 *  - الصرف idempotent: معرّف الحركة حتمي (أمر|مادة|دفعة) فلا يتكرر الأثر عند إعادة التنفيذ أو من جهاز آخر.
 *  - لا صرف رجعي: تُصرف المادة فقط إن سُجّل لها رصيد افتتاحي وكان إكمال الأمر بعد تاريخ ذلك الرصيد.
 *  - لا عكس تلقائياً أبداً (إلغاء/حذف/مزامنة قديمة قد تخدع): العكس يدوي من شاشة التدقيق.
 *  - الكمية = calculatedQuantity بعد تعديلات الكمية (تُرمَّم من سجل التعديل في المزامنة).
 */
object InventoryProduction {
    private const val TAG = "GBR_InvProduction"
    const val SYSTEM_USER = "نظام الإنتاج"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appCtx = context.applicationContext
        job = scope.launch {
            try {
                val db = AppDatabase.getDatabase(appCtx)
                val g = db.gbrDao()
                combine(g.getAllProductionOrders(), g.getAllProductionOrderItems()) { orders, items ->
                    // بصمة تتغير بتغير حالة أي أمر أو عدد/كمية بنوده
                    (orders.map { it.id + it.status }.hashCode() * 31) + items.map { it.id + it.calculatedQuantity }.hashCode()
                }.distinctUntilChanged().collectLatest {
                    delay(5000) // تهدئة: ننتظر استقرار المزامنة (بنود الأمر وتعديلاته تصل منفصلة)
                    try {
                        reconcile(appCtx)
                    } catch (e: Exception) {
                        Log.e(TAG, "reconcile failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "watcher failed", e)
            }
        }
    }

    private fun completionTs(o: ProductionOrder): Long =
        if (o.endTime > 0L) o.endTime else if (o.startTime > 0L) o.startTime else o.createdAt

    /** يصرف مواد الأوامر المكتملة التي لم يُصرف لها. يعيد عدد حركات الصرف الجديدة. */
    suspend fun reconcile(ctx: Context): Int = mutex.withLock {
        val db = AppDatabase.getDatabase(ctx)
        val g = db.gbrDao()
        val d = db.inventoryDao()
        val completed = g.getAllProductionOrders().first().filter { it.status == "مكتمل" }
        if (completed.isEmpty()) return 0
        InventoryManager.ensureDefaults(ctx)
        var created = 0
        for (o in completed) {
            val ts = completionTs(o)
            val ref = "ORD:" + o.id
            for (item in g.getProductionOrderItemsSync(o.id)) {
                if (item.calculatedQuantity <= 0.0) continue
                val itemId = item.rawMaterialId
                val opening = d.getMove(InventoryManager.deterministicId("opening|$itemId")) ?: continue
                if (ts < opening.clientCreatedAt) continue
                if (d.countIssuesFor(ref, itemId) > 0) continue
                val profile = d.getProfile(itemId)
                val uom = profile?.baseUom ?: "KG"
                val baseQty: Double = if (uom == "L") {
                    val dn = profile?.densityKgPerL ?: 0.0
                    if (dn <= 0.0) { Log.w(TAG, "لا كثافة للمادة $itemId: لا صرف تلقائي"); continue }
                    item.calculatedQuantity / dn
                } else {
                    item.calculatedQuantity
                }
                var remaining = InventoryMath.toStorage(baseQty)
                if (remaining <= 0L) continue
                // FEFO: دفعات مُفرَج عنها غير منتهية بها رصيد في المخزن الرئيسي، الأقرب انتهاءً أولاً
                val lots = d.getLotsForItem(itemId)
                    .filter { it.status == "RELEASED" && (it.expiryDate == null || it.expiryDate >= ts) }
                    .sortedWith(compareBy<StockLot> { it.expiryDate ?: Long.MAX_VALUE }.thenBy { it.receivedAt })
                for (lot in lots) {
                    if (remaining <= 0L) break
                    val bal = d.getLotOnHandAt(lot.id, InventoryIds.LOC_MAIN)
                    if (bal <= 0L) continue
                    val take = if (bal < remaining) bal else remaining
                    if (InventoryManager.issueToProduction(ctx, itemId, lot.id, take, uom, o.id, SYSTEM_USER, "iss|${o.id}|$itemId|${lot.id}")) created++
                    remaining -= take
                }
                if (remaining > 0L) {
                    // نقص في الدفعات: يُصرف الباقي بلا دفعة (قد يجعل الرصيد سالباً ويظهر في التدقيق)
                    if (InventoryManager.issueToProduction(ctx, itemId, null, remaining, uom, o.id, SYSTEM_USER, "iss|${o.id}|$itemId|NOLOT")) created++
                }
            }
        }
        created += reconcileFinished(ctx, d, completed)
        if (created > 0) Log.i(TAG, "صُرف/استُلم: $created حركة")
        created
    }

    /**
     * استلام المنتج التام: لكل عبوة مسجَّلة فعلاً في أمر مكتمل تُنشأ دفعة (معرّف حتمي) في حجر الجودة بحالة QC_HOLD.
     * تبدأ التتبع من أقدم رصيد افتتاحي في الدفتر؛ ما قبله تاريخي ولا يُستلم. يعتمد على actualPackagingJson (id, name, netWeight, actualCount).
     */
    private suspend fun reconcileFinished(ctx: Context, d: InventoryDao, completed: List<ProductionOrder>): Int {
        val cutoff = d.firstOpeningTs() ?: return 0
        var n = 0
        for (o in completed) {
            val ts = completionTs(o)
            if (ts < cutoff) continue
            val arr = try { org.json.JSONArray(o.actualPackagingJson) } catch (e: Exception) { continue }
            var packedKg = 0.0
            val packs = ArrayList<Triple<String, String, Pair<Int, Double>>>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val count = p.optDouble("actualCount", 0.0).toInt()
                val net = p.optDouble("netWeight", 0.0)
                if (count <= 0) continue
                packs.add(Triple(p.optString("id", i.toString()), p.optString("name", "عبوة"), Pair(count, net)))
                packedKg += count * net
            }
            for ((packId, name, cn) in packs) {
                val (count, net) = cn
                val moveId = InventoryManager.deterministicId("fgr|${o.id}|$packId")
                if (d.getMove(moveId) != null) continue
                val sku = "FG|" + o.formulationId + "|" + packId
                val lotId = InventoryManager.deterministicId("fglot|${o.id}|$packId")
                val meta = "FG#" + o.formulationName.replace('#', ' ') + "#" + name.replace('#', ' ') + "#" + net + "#" +
                    o.orderNumber + "#" + o.requiredWeightKg + "#" + count
                d.upsertLot(
                    StockLot(
                        id = lotId, itemId = sku, lotNo = o.batchNumber + "-" + name.replace(' ', '_'),
                        expiryDate = ts + 730L * 86_400_000L, status = "QC_HOLD", receivedAt = ts, notes = meta
                    )
                )
                if (InventoryManager.receiveFinished(ctx, sku, lotId, count, o.id, packId, SYSTEM_USER)) n++
            }
        }
        return n
    }
}
