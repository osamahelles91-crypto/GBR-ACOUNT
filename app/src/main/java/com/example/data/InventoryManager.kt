package com.example.data

import android.content.Context
import java.util.UUID

/** معرّفات المواقع. المواقع الافتراضية تبدأ بـ V_ ولا تُحتسب مخزوناً فعلياً. */
object InventoryIds {
    const val LOC_MAIN = "L_MAIN"
    const val LOC_QUAR = "L_QUAR"
    const val LOC_FG_QC = "L_FG_QC"   // حجر جودة المنتج التام (QC_HOLD)
    const val LOC_FG = "L_FG"         // مخزن المنتج التام القابل للبيع
    const val LOC_FG_RET = "L_FG_RET" // حجر المرتجعات (لا يعود للبيع مباشرة)
    const val V_OPENING = "V_OPENING"
    const val V_ADJUST = "V_ADJUST"
    const val V_SUPPLIER = "V_SUPPLIER"
    const val V_PRODUCTION = "V_PRODUCTION"
    const val V_SCRAP = "V_SCRAP"
    const val V_CUSTOMER = "V_CUSTOMER"
    fun isVirtual(locId: String) = locId.startsWith("V_")
}

object InventoryTypes {
    const val OPENING = "OPENING"
    const val ADJUSTMENT = "ADJUSTMENT"
    const val REVERSAL = "REVERSAL"
    const val RECEIPT = "RECEIPT"
    const val RELEASE = "RELEASE"
    const val REJECT = "REJECT"
    const val ISSUE = "ISSUE"
    const val FG_RECEIPT = "FG_RECEIPT"
    const val FG_RELEASE = "FG_RELEASE"
    const val FG_REJECT = "FG_REJECT"
    const val FG_ADJUST = "FG_ADJUST"
    const val FG_DELIVERY = "FG_DELIVERY"
    const val FG_RETURN = "FG_RETURN"
    const val FG_RETURN_RESTOCK = "FG_RETURN_RESTOCK"
    const val FG_RETURN_SCRAP = "FG_RETURN_SCRAP"
}

/** أكواد أسباب التسوية (إلزامية). */
val INVENTORY_REASONS: List<Pair<String, String>> = listOf(
    "COUNT_ERROR" to "خطأ عدّ",
    "SPILL" to "انسكاب",
    "EVAPORATION" to "تبخر",
    "EXPIRED" to "انتهاء صلاحية",
    "DAMAGED" to "تلف",
    "QC_REJECT" to "رفض جودة",
    "SAMPLE" to "عينة",
    "UNIT_CONVERSION" to "تحويل كجم/لتر",
    "DATA_ENTRY" to "خطأ إدخال",
    "OTHER" to "أخرى"
)

val INVENTORY_CATEGORIES: List<Pair<String, String>> = listOf(
    "PIGMENT" to "صبغات",
    "EXTENDER" to "حشوات",
    "BINDER" to "روابط/مستحلبات",
    "SOLVENT" to "مذيبات",
    "WATER" to "ماء",
    "ADDITIVE" to "إضافات",
    "PACKAGING" to "تعبئة",
    "OTHER" to "أخرى"
)

val INVENTORY_ZONES: List<Pair<String, String>> = listOf(
    "GENERAL" to "عام",
    "HAZMAT" to "مواد خطرة/قابلة للاشتعال",
    "QUARANTINE" to "حجر",
    "FINISHED" to "منتج تام",
    "QC_HOLD" to "حجر الجودة",
    "RETURNS" to "مرتجعات",
    "SAMPLES" to "عينات"
)

fun inventoryReasonLabel(code: String) = INVENTORY_REASONS.firstOrNull { it.first == code }?.second ?: code
fun inventoryCategoryLabel(code: String) = INVENTORY_CATEGORIES.firstOrNull { it.first == code }?.second ?: code

data class OpResult(val ok: Boolean, val message: String)

/** صف بطاقة الصنف: الحركة مع الرصيد الجاري ومتوسط التكلفة بعدها. */
data class LedgerRow(
    val move: StockMove,
    val signedQty: Long,          // + دخول للمخزون الفعلي، − خروج منه، 0 تحويل داخلي/بين افتراضيين
    val runningQty: Long,
    val avgCostAfter: Double,
    val runningValue: Double
)

object InventoryMath {
    /** الكمية تُخزَّن بالغرام/الملّيلتر: العرض بالكجم/اللتر. */
    fun toStorage(display: Double): Long = Math.round(display * 1000.0)
    fun toDisplay(storage: Long): Double = storage / 1000.0

    fun signedQty(m: StockMove): Long {
        val physIn = !InventoryIds.isVirtual(m.toLocId)
        val physOut = !InventoryIds.isVirtual(m.fromLocId)
        return when {
            physIn && !physOut -> m.qtyBase
            physOut && !physIn -> -m.qtyBase
            else -> 0L
        }
    }

    /**
     * يعيد تشغيل الحركات بترتيبها لحساب الرصيد الجاري ومتوسط التكلفة المرجّح المتحرك.
     * الاستلام (من موقع افتراضي إلى فعلي) بتكلفة unitCost يحدّث المتوسط؛ الصرف يُكلَّف بالمتوسط ولا يغيّره؛
     * الحركات بلا تكلفة (تسويات، عكس) تستعمل المتوسط الحالي. fallbackCost لمادة لا تكلفة لها بعد.
     */
    fun replay(moves: List<StockMove>, fallbackCost: Double): List<LedgerRow> {
        var qty = 0L
        var avg = fallbackCost
        val rows = ArrayList<LedgerRow>(moves.size)
        for (m in moves) {
            val s = signedQty(m)
            if (s > 0) {
                val cost = m.unitCost ?: avg
                val newQty = qty + s
                avg = if (qty <= 0L || newQty <= 0L) cost
                else (qty / 1000.0 * avg + s / 1000.0 * cost) / (newQty / 1000.0)
                qty = newQty
            } else if (s < 0) {
                qty += s
            }
            rows.add(LedgerRow(m, s, qty, avg, qty / 1000.0 * avg))
        }
        return rows
    }

    /** تصنيف ABC تراكمي (A حتى 80%، B حتى 95%، الباقي C). المدخل: قيمة لكل معرّف. */
    fun classifyAbc(values: Map<String, Double>): Map<String, String> {
        val positive = values.filter { it.value > 0.0 }.entries.sortedByDescending { it.value }
        val total = positive.sumOf { it.value }
        if (total <= 0.0) return emptyMap()
        var cum = 0.0
        val out = HashMap<String, String>()
        for (e in positive) {
            val before = cum / total
            cum += e.value
            out[e.key] = when {
                before < 0.80 -> "A"
                before < 0.95 -> "B"
                else -> "C"
            }
        }
        return out
    }
}

object InventoryManager {
    private const val PREFS = "gbr_inventory_prefs"
    private const val KEY_DEVICE = "inventory_device_id"
    private const val KEY_SEQ = "inventory_device_seq"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(KEY_DEVICE, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            p.edit().putString(KEY_DEVICE, id).apply()
        }
        return id
    }

    @Synchronized
    private fun nextSeq(ctx: Context): Long {
        val p = prefs(ctx)
        val n = p.getLong(KEY_SEQ, 0L) + 1L
        p.edit().putLong(KEY_SEQ, n).apply()
        return n
    }

    private fun dao(ctx: Context) = AppDatabase.getDatabase(ctx).inventoryDao()

    /** معرّف حتمي: نفس المدخلات تعطي نفس المعرّف على أي جهاز فلا يتكرر الأثر. */
    fun deterministicId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

    /** يضمن وجود موقع «المخزن الرئيسي» (معرّف ثابت فلا يتكرر بين الأجهزة). */
    suspend fun ensureDefaults(ctx: Context) {
        val d = dao(ctx)
        // معرّفان ثابتان فلا يتكرران بين الأجهزة (insertIfAbsent)
        d.insertLocationIfAbsent(StockLocation(id = InventoryIds.LOC_MAIN, name = "المخزن الرئيسي", zone = "GENERAL"))
        d.insertLocationIfAbsent(StockLocation(id = InventoryIds.LOC_QUAR, name = "منطقة الحجر (بانتظار الفحص)", zone = "QUARANTINE"))
        d.insertLocationIfAbsent(StockLocation(id = InventoryIds.LOC_FG_QC, name = "حجر جودة المنتج التام", zone = "QC_HOLD"))
        d.insertLocationIfAbsent(StockLocation(id = InventoryIds.LOC_FG, name = "مخزن المنتج التام", zone = "FINISHED"))
        d.insertLocationIfAbsent(StockLocation(id = InventoryIds.LOC_FG_RET, name = "حجر المرتجعات", zone = "RETURNS"))
    }

    private suspend fun write(ctx: Context, move: StockMove): Boolean {
        val inserted = dao(ctx).insertMove(move)
        if (inserted != -1L) InventorySync.uploadPendingAsync(ctx)
        return inserted != -1L
    }

    private fun newMove(
        ctx: Context, itemId: String, lotId: String?, from: String, to: String, qty: Long, uom: String,
        type: String, reason: String, ref: String, unitCost: Double?, userId: String,
        id: String = UUID.randomUUID().toString(), reverses: String? = null
    ) = StockMove(
        moveId = id, deviceId = deviceId(ctx), deviceSeq = nextSeq(ctx), itemId = itemId, lotId = lotId,
        fromLocId = from, toLocId = to, qtyBase = qty, uom = uom, type = type, reasonCode = reason, refDoc = ref,
        reversesMoveId = reverses, unitCost = unitCost, userId = userId
    )

    /**
     * رصيد افتتاحي لمادة (مرة واحدة فقط): حركة من V_OPENING إلى المخزن الرئيسي بمعرّف حتمي، ودفعة افتتاحية مفرَج عنها.
     * qtyDisplay بوحدة الأساس (كجم أو لتر)؛ unitCost لكل كجم/لتر.
     */
    suspend fun recordOpeningBalance(
        ctx: Context, itemId: String, uom: String, qtyDisplay: Double, unitCost: Double?, expiry: Long?, userId: String
    ): OpResult {
        if (qtyDisplay <= 0.0) return OpResult(false, "الكمية يجب أن تكون أكبر من صفر")
        val qty = InventoryMath.toStorage(qtyDisplay)
        if (qty <= 0L) return OpResult(false, "الكمية صغيرة جداً")
        if (unitCost != null && unitCost < 0.0) return OpResult(false, "التكلفة لا يمكن أن تكون سالبة")
        val d = dao(ctx)
        ensureDefaults(ctx)
        val moveId = deterministicId("opening|$itemId")
        if (d.getMove(moveId) != null) {
            return OpResult(false, "الرصيد الافتتاحي لهذه المادة مسجَّل مسبقاً. للتصحيح استخدم «تسوية» بسبب مكتوب.")
        }
        val lotId = deterministicId("openlot|$itemId")
        val df = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        d.insertLotIfAbsent(
            StockLot(
                id = lotId, itemId = itemId, lotNo = "OPEN-" + df.format(java.util.Date()),
                expiryDate = expiry, status = "RELEASED", notes = "دفعة الرصيد الافتتاحي"
            )
        )
        val ok = write(
            ctx,
            newMove(ctx, itemId, lotId, InventoryIds.V_OPENING, InventoryIds.LOC_MAIN, qty, uom,
                InventoryTypes.OPENING, "OPENING", "opening", unitCost, userId, id = moveId)
        )
        return if (ok) OpResult(true, "تم تسجيل الرصيد الافتتاحي") else OpResult(false, "الرصيد الافتتاحي مسجَّل مسبقاً")
    }

    /** تسوية بسبب إلزامي: deltaDisplay موجب (زيادة) أو سالب (نقص). لا يُسمح بنقص أكبر من الرصيد الحالي. */
    suspend fun adjust(
        ctx: Context, itemId: String, uom: String, deltaDisplay: Double, reasonCode: String, note: String, userId: String
    ): OpResult {
        if (deltaDisplay == 0.0) return OpResult(false, "أدخل كمية التسوية")
        if (reasonCode.isBlank()) return OpResult(false, "سبب التسوية إلزامي")
        val qty = InventoryMath.toStorage(kotlin.math.abs(deltaDisplay))
        if (qty <= 0L) return OpResult(false, "الكمية صغيرة جداً")
        val d = dao(ctx)
        ensureDefaults(ctx)
        val onHand = d.getOnHandAt(itemId, InventoryIds.LOC_MAIN)
        if (deltaDisplay < 0 && qty > onHand) {
            return OpResult(false, "النقص (${InventoryMath.toDisplay(qty)}) أكبر من الرصيد الحالي (${InventoryMath.toDisplay(onHand)})")
        }
        val from = if (deltaDisplay > 0) InventoryIds.V_ADJUST else InventoryIds.LOC_MAIN
        val to = if (deltaDisplay > 0) InventoryIds.LOC_MAIN else InventoryIds.V_ADJUST
        write(ctx, newMove(ctx, itemId, null, from, to, qty, uom, InventoryTypes.ADJUSTMENT, reasonCode, note, null, userId))
        return OpResult(true, "تم تسجيل التسوية")
    }

    /** حركة عكسية لحركة سابقة (لا تعديل ولا حذف). تُرفض إن كانت الحركة عكسية أو مُعكوسة مسبقاً أو يُنتج عكسها رصيداً سالباً. */
    suspend fun reverse(ctx: Context, moveId: String, reason: String, userId: String): OpResult {
        if (reason.isBlank()) return OpResult(false, "سبب العكس إلزامي")
        val d = dao(ctx)
        val orig = d.getMove(moveId) ?: return OpResult(false, "الحركة غير موجودة")
        if (orig.type == InventoryTypes.REVERSAL) return OpResult(false, "لا يمكن عكس حركة عكسية")
        if (d.countReversalsOf(moveId) > 0) return OpResult(false, "هذه الحركة مُعكوسة مسبقاً")
        if (orig.type == InventoryTypes.RELEASE || orig.type == InventoryTypes.REJECT ||
            orig.type == InventoryTypes.FG_RELEASE || orig.type == InventoryTypes.FG_REJECT) {
            return OpResult(false, "لا تُعكس حركة الإفراج/الرفض: تغيّر حالة الدفعة. استخدم تسوية أو استلاماً جديداً")
        }
        // العكس يُخرج الكمية من موقع وصولها الأصلي؛ يجب أن يتسع رصيد ذلك الموقع (والدفعة إن وُجدت)
        if (!InventoryIds.isVirtual(orig.toLocId)) {
            val have = if (orig.lotId != null) d.getLotOnHandAt(orig.lotId, orig.toLocId) else d.getOnHandAt(orig.itemId, orig.toLocId)
            if (have < orig.qtyBase) return OpResult(false, "عكس هذه الحركة يجعل الرصيد سالباً")
        }
        val ok = write(
            ctx,
            newMove(ctx, orig.itemId, orig.lotId, orig.toLocId, orig.fromLocId, orig.qtyBase, orig.uom,
                InventoryTypes.REVERSAL, "REVERSAL", reason, null, userId,
                id = deterministicId("rev|$moveId"), reverses = moveId)
        )
        return if (ok) OpResult(true, "تم عكس الحركة") else OpResult(false, "هذه الحركة مُعكوسة مسبقاً")
    }

    // ======================= المرحلة 2: أوامر الشراء والاستلام والإفراج =======================

    private fun randomDigits(n: Int): String = (1..n).map { ('0'..'9').random() }.joinToString("")

    /** يُنشئ أمر شراء (مسودة). lines: (itemId, الكمية بوحدة الأساس، سعر الوحدة). */
    suspend fun createPo(
        ctx: Context, supplierId: String, notes: String, userId: String, lines: List<Triple<String, Double, Double>>
    ): OpResult {
        val d = dao(ctx)
        val sup = d.supplierOrNull(supplierId) ?: return OpResult(false, "اختر مورداً")
        if (!sup.isActive || !sup.isApproved) return OpResult(false, "المورد غير معتمد أو غير فعّال")
        if (lines.isEmpty()) return OpResult(false, "أضف مادة واحدة على الأقل")
        for ((_, q, pr) in lines) {
            if (q <= 0.0) return OpResult(false, "كل الكميات يجب أن تكون أكبر من صفر")
            if (pr < 0.0) return OpResult(false, "السعر لا يمكن أن يكون سالباً")
        }
        val df = java.text.SimpleDateFormat("yyMMdd", java.util.Locale.US)
        val po = InventoryPurchaseOrder(
            number = "PO-" + df.format(java.util.Date()) + "-" + randomDigits(3),
            supplierId = supplierId, notes = notes.trim(), createdBy = userId
        )
        d.upsertPo(po)
        for ((item, q, pr) in lines) {
            d.upsertPoLine(InventoryPoLine(poId = po.id, itemId = item, qtyOrdered = InventoryMath.toStorage(q), unitPrice = pr))
        }
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم إنشاء أمر الشراء ${po.number}")
    }

    /** انتقال حالة أمر الشراء: DRAFT→APPROVED، وأي حالة غير نهائية→CLOSED/CANCELLED. */
    suspend fun setPoStatus(ctx: Context, poId: String, newStatus: String): OpResult {
        val d = dao(ctx)
        val po = d.poOrNull(poId) ?: return OpResult(false, "أمر الشراء غير موجود")
        val ok = when (newStatus) {
            "APPROVED" -> po.status == "DRAFT"
            "CLOSED" -> po.status == "APPROVED"
            "CANCELLED" -> po.status == "DRAFT" || po.status == "APPROVED"
            else -> false
        }
        if (!ok) return OpResult(false, "لا يمكن نقل الأمر من ${po.status} إلى $newStatus")
        if (newStatus == "CANCELLED") {
            for (l in d.getPoLines(poId)) {
                if (d.getReceivedForRef("PO:" + poId + ":" + l.id) > 0L) return OpResult(false, "يوجد استلام على هذا الأمر؛ أغلقه بدل إلغائه")
            }
        }
        d.upsertPo(po.copy(status = newStatus, updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم تحديث حالة الأمر")
    }

    /**
     * استلام مادة إلى منطقة الحجر بدفعة جديدة (لا تُسحب قبل الإفراج). poLineId اختياري: إن وُجد يُطابَق مع أمر شراء معتمد،
     * ويُحظر تجاوز الكمية المطلوبة بأكثر من 10%. بلا أمر شراء يُسجَّل «DIRECT».
     */
    suspend fun receive(
        ctx: Context, itemId: String, uom: String, qtyDisplay: Double, poLineId: String?, supplierId: String?,
        supplierLotNo: String, mfgDate: Long?, expiryDate: Long?, unitCost: Double?, userId: String
    ): OpResult {
        if (qtyDisplay <= 0.0) return OpResult(false, "الكمية يجب أن تكون أكبر من صفر")
        val qty = InventoryMath.toStorage(qtyDisplay)
        if (qty <= 0L) return OpResult(false, "الكمية صغيرة جداً")
        if (unitCost != null && unitCost < 0.0) return OpResult(false, "التكلفة لا يمكن أن تكون سالبة")
        if (mfgDate != null && expiryDate != null && expiryDate <= mfgDate) return OpResult(false, "تاريخ الانتهاء قبل تاريخ الإنتاج")
        val d = dao(ctx)
        ensureDefaults(ctx)
        var ref = "DIRECT"
        var supId = supplierId
        var cost = unitCost
        if (poLineId != null) {
            val line = d.poLineOrNull(poLineId) ?: return OpResult(false, "سطر أمر الشراء غير موجود")
            val po = d.poOrNull(line.poId) ?: return OpResult(false, "أمر الشراء غير موجود")
            if (po.status != "APPROVED") return OpResult(false, "أمر الشراء غير معتمد أو مغلق")
            if (line.itemId != itemId) return OpResult(false, "المادة لا تطابق سطر أمر الشراء")
            ref = "PO:" + po.id + ":" + line.id
            val already = d.getReceivedForRef(ref)
            val limit = line.qtyOrdered + line.qtyOrdered / 10
            if (already + qty > limit) {
                return OpResult(false, "الاستلام يتجاوز المطلوب بأكثر من 10% (المطلوب ${InventoryMath.toDisplay(line.qtyOrdered)}، المستلم سابقاً ${InventoryMath.toDisplay(already)})")
            }
            supId = po.supplierId
            if (cost == null && line.unitPrice > 0.0) cost = line.unitPrice
        }
        if (supId != null) {
            val sup = d.supplierOrNull(supId)
            if (sup != null && (!sup.isApproved || !sup.isActive)) return OpResult(false, "المورد غير معتمد أو غير فعّال")
        }
        val now = System.currentTimeMillis()
        val profile = d.getProfile(itemId)
        var expiry = expiryDate
        if (expiry == null && profile != null && profile.shelfLifeDays > 0) {
            expiry = (mfgDate ?: now) + profile.shelfLifeDays * 86_400_000L
        }
        val df = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val prefix = "L" + df.format(java.util.Date(now)) + "-"
        val seq = d.countLotsWithPrefix(prefix) + 1
        val lot = StockLot(
            itemId = itemId, lotNo = prefix + seq.toString().padStart(3, '0'), supplierLotNo = supplierLotNo.trim(),
            supplierId = supId, mfgDate = mfgDate, expiryDate = expiry, status = "QUARANTINE", receivedAt = now
        )
        d.upsertLot(lot)
        val ok = write(
            ctx,
            newMove(ctx, itemId, lot.id, InventoryIds.V_SUPPLIER, InventoryIds.LOC_QUAR, qty, uom,
                InventoryTypes.RECEIPT, "RECEIPT", ref, cost, userId)
        )
        if (!ok) return OpResult(false, "تعذّر تسجيل الاستلام")
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم الاستلام في الحجر — الدفعة ${lot.lotNo}")
    }

    /** إفراج دفعة من الحجر إلى المخزن الرئيسي. لا تُفرج دفعة منتهية الصلاحية. */
    suspend fun releaseLot(ctx: Context, lotId: String, userId: String): OpResult {
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "QUARANTINE") return OpResult(false, "الدفعة ليست في الحجر")
        if (lot.expiryDate != null && lot.expiryDate < System.currentTimeMillis()) return OpResult(false, "الدفعة منتهية الصلاحية ولا تُفرج")
        val qty = d.getLotOnHandAt(lotId, InventoryIds.LOC_QUAR)
        if (qty <= 0L) return OpResult(false, "لا رصيد لهذه الدفعة في الحجر")
        val uom = d.getProfile(lot.itemId)?.baseUom ?: "KG"
        val ok = write(
            ctx,
            newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_QUAR, InventoryIds.LOC_MAIN, qty, uom,
                InventoryTypes.RELEASE, "RELEASE", lot.lotNo, null, userId, id = deterministicId("rel|$lotId"))
        )
        d.upsertLot(lot.copy(status = "RELEASED", updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return if (ok) OpResult(true, "أُفرجت الدفعة ${lot.lotNo}") else OpResult(true, "الدفعة مُفرَج عنها مسبقاً")
    }

    /** رفض دفعة في الحجر (تخرج من المخزون الفعلي بسبب مكتوب). */
    suspend fun rejectLot(ctx: Context, lotId: String, reason: String, userId: String): OpResult {
        if (reason.isBlank()) return OpResult(false, "سبب الرفض إلزامي")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "QUARANTINE") return OpResult(false, "الدفعة ليست في الحجر")
        val qty = d.getLotOnHandAt(lotId, InventoryIds.LOC_QUAR)
        if (qty <= 0L) return OpResult(false, "لا رصيد لهذه الدفعة في الحجر")
        val uom = d.getProfile(lot.itemId)?.baseUom ?: "KG"
        val ok = write(
            ctx,
            newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_QUAR, InventoryIds.V_SCRAP, qty, uom,
                InventoryTypes.REJECT, "QC_REJECT", reason.trim(), null, userId, id = deterministicId("rej|$lotId"))
        )
        d.upsertLot(lot.copy(status = "REJECTED", notes = (lot.notes + " | رفض: " + reason.trim()).trim(' ', '|'), updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return if (ok) OpResult(true, "رُفضت الدفعة ${lot.lotNo}") else OpResult(true, "الدفعة مرفوضة مسبقاً")
    }

    /** صرف للإنتاج: من المخزن الرئيسي إلى V_PRODUCTION. يُسمح بالرصيد السالب (الإنتاج وقع فعلاً) وتُعلَّم الفروقات في التدقيق. */
    suspend fun issueToProduction(
        ctx: Context, itemId: String, lotId: String?, qty: Long, uom: String, orderId: String, userId: String, idKey: String
    ): Boolean = write(
        ctx,
        newMove(ctx, itemId, lotId, InventoryIds.LOC_MAIN, InventoryIds.V_PRODUCTION, qty, uom,
            InventoryTypes.ISSUE, "PRODUCTION", "ORD:" + orderId, null, userId, id = deterministicId(idKey))
    )

    /** يعكس كل حركات صرف أمر إنتاج لم تُعكس بعد (يدوياً من التدقيق بعد إلغاء/حذف الأمر). */
    suspend fun reverseOrderIssues(ctx: Context, orderId: String, userId: String): OpResult {
        val d = dao(ctx)
        val all = d.getAllMovesSync()
        val reversed = all.mapNotNull { it.reversesMoveId }.toSet()
        val targets = all.filter { it.type == InventoryTypes.ISSUE && it.refDoc == "ORD:" + orderId && it.moveId !in reversed }
        if (targets.isEmpty()) return OpResult(false, "لا صرف قائم لهذا الأمر")
        var done = 0
        for (m in targets) {
            if (reverse(ctx, m.moveId, "إلغاء/حذف أمر الإنتاج", userId).ok) done++
        }
        return OpResult(done > 0, "عُكست $done من ${targets.size} حركة صرف")
    }

    // ======================= المرحلة 4: المنتج التام =======================
    // SKU = "FG|formulationId|packId". الكمية = عدد العبوات × 1000 (نفس التخزين الصحيح الموحَّد؛ العرض ÷1000) بوحدة "EA".

    /** استلام إنتاجي إلى حجر الجودة (يُستدعى من المراقب بمعرّف حتمي). */
    suspend fun receiveFinished(ctx: Context, sku: String, lotId: String, count: Int, orderId: String, packId: String, userId: String): Boolean =
        write(
            ctx,
            newMove(ctx, sku, lotId, InventoryIds.V_PRODUCTION, InventoryIds.LOC_FG_QC, InventoryMath.toStorage(count.toDouble()), "EA",
                InventoryTypes.FG_RECEIPT, "PRODUCTION", "ORD:" + orderId, null, userId, id = deterministicId("fgr|$orderId|$packId"))
        )

    private fun appendNote(lot: StockLot, line: String): String = if (lot.notes.isBlank()) line else lot.notes + "\n" + line

    /** إفراج دفعة منتج تام من حجر الجودة إلى القابل للبيع. مرجع الفحص إلزامي. لا إفراج لدفعة منتهية. */
    suspend fun releaseFinished(ctx: Context, lotId: String, evidence: String, userId: String): OpResult {
        if (evidence.isBlank()) return OpResult(false, "مرجع/نتيجة الفحص إلزامي للإفراج")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "QC_HOLD" && lot.status != "REWORK") return OpResult(false, "الدفعة ليست في حجر الجودة")
        if (lot.expiryDate != null && lot.expiryDate < System.currentTimeMillis()) return OpResult(false, "الدفعة منتهية الصلاحية")
        val qty = d.getLotOnHandAt(lotId, InventoryIds.LOC_FG_QC)
        if (qty <= 0L) return OpResult(false, "لا رصيد لهذه الدفعة في حجر الجودة")
        val ok = write(
            ctx,
            newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_FG_QC, InventoryIds.LOC_FG, qty, "EA",
                InventoryTypes.FG_RELEASE, "QC_RELEASE", evidence.trim(), null, userId, id = deterministicId("fgrel|$lotId"))
        )
        d.upsertLot(lot.copy(status = "RELEASED", notes = appendNote(lot, "إفراج: " + evidence.trim() + " (" + userId + ")"), updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return if (ok) OpResult(true, "أُفرجت الدفعة ${lot.lotNo} للبيع") else OpResult(true, "الدفعة مُفرَج عنها مسبقاً")
    }

    suspend fun rejectFinished(ctx: Context, lotId: String, reason: String, userId: String): OpResult {
        if (reason.isBlank()) return OpResult(false, "سبب الرفض إلزامي")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "QC_HOLD" && lot.status != "REWORK") return OpResult(false, "الدفعة ليست في حجر الجودة")
        val qty = d.getLotOnHandAt(lotId, InventoryIds.LOC_FG_QC)
        if (qty <= 0L) return OpResult(false, "لا رصيد لهذه الدفعة في حجر الجودة")
        val ok = write(
            ctx,
            newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_FG_QC, InventoryIds.V_SCRAP, qty, "EA",
                InventoryTypes.FG_REJECT, "QC_REJECT", reason.trim(), null, userId, id = deterministicId("fgrej|$lotId"))
        )
        d.upsertLot(lot.copy(status = "REJECTED", notes = appendNote(lot, "رفض: " + reason.trim() + " (" + userId + ")"), updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return if (ok) OpResult(true, "رُفضت الدفعة ${lot.lotNo}") else OpResult(true, "الدفعة مرفوضة مسبقاً")
    }

    /** دفعة راسبة تحتاج إعادة تشغيل/تصحيح: تبقى في الحجر بحالة REWORK ولا تُباع. */
    suspend fun markRework(ctx: Context, lotId: String, reason: String, userId: String): OpResult {
        if (reason.isBlank()) return OpResult(false, "السبب إلزامي")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "QC_HOLD") return OpResult(false, "الدفعة ليست قيد الفحص")
        d.upsertLot(lot.copy(status = "REWORK", notes = appendNote(lot, "إعادة تشغيل: " + reason.trim() + " (" + userId + ")"), updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "وُسمت الدفعة بإعادة تشغيل")
    }

    /** إخراج عبوات من المنتج التام المُفرَج (عينة محتفظ بها، تلف…) بسبب إلزامي. البيع في المرحلة 5. */
    suspend fun adjustFinished(ctx: Context, lotId: String, count: Int, reasonCode: String, note: String, userId: String): OpResult {
        if (count <= 0) return OpResult(false, "أدخل عدد عبوات صحيحاً")
        if (reasonCode.isBlank()) return OpResult(false, "السبب إلزامي")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.status != "RELEASED") return OpResult(false, "الدفعة غير مُفرَج عنها")
        val qty = InventoryMath.toStorage(count.toDouble())
        val have = d.getLotOnHandAt(lotId, InventoryIds.LOC_FG)
        if (qty > have) return OpResult(false, "العدد أكبر من المتاح (" + InventoryMath.toDisplay(have) + ")")
        write(ctx, newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_FG, InventoryIds.V_ADJUST, qty, "EA", InventoryTypes.FG_ADJUST, reasonCode, note, null, userId))
        return OpResult(true, "تم تسجيل الإخراج")
    }

    // ======================= المرحلة 5: البيع والتسليم والمرتجعات =======================

    private fun isSellable(l: StockLot, now: Long): Boolean =
        l.status == "RELEASED" && (l.expiryDate == null || l.expiryDate > now)

    /** المتاح للبيع (بوحدة التخزين) لـSKU: أرصدة الدفعات المُفرَجة غير المنتهية في مخزن المنتج التام. */
    private suspend fun sellableStorage(d: InventoryDao, sku: String): Long {
        val now = System.currentTimeMillis()
        var sum = 0L
        for (l in d.getLotsForItem(sku)) {
            if (isSellable(l, now)) sum += maxOf(0L, d.getLotOnHandAt(l.id, InventoryIds.LOC_FG))
        }
        return sum
    }

    /** المحجوز لـSKU عبر طلبات بيع مؤكَّدة (المتبقي غير المسلَّم)، مع استثناء طلب معيّن. */
    private suspend fun reservedStorage(d: InventoryDao, sku: String, exceptSoId: String?): Long {
        var sum = 0L
        for (so in d.getAllSosSync()) {
            if (so.status != "CONFIRMED" || so.id == exceptSoId) continue
            for (l in d.getSoLines(so.id)) {
                if (l.sku != sku) continue
                val delivered = d.getDeliveredForRef("SO:" + so.id + ":" + l.id)
                sum += maxOf(0L, l.qtyOrdered - delivered)
            }
        }
        return sum
    }

    /** طلب بيع (مسودة). lines: (sku, عدد العبوات). */
    suspend fun createSo(ctx: Context, customer: String, notes: String, userId: String, lines: List<Pair<String, Int>>): OpResult {
        if (customer.isBlank()) return OpResult(false, "اسم العميل مطلوب")
        if (lines.isEmpty()) return OpResult(false, "أضف بنداً واحداً على الأقل")
        for ((sku, c) in lines) {
            if (c <= 0) return OpResult(false, "الأعداد يجب أن تكون أكبر من صفر")
            if (!sku.startsWith("FG|")) return OpResult(false, "صنف غير صالح")
        }
        val d = dao(ctx)
        val df = java.text.SimpleDateFormat("yyMMdd", java.util.Locale.US)
        val so = InventorySalesOrder(
            number = "SO-" + df.format(java.util.Date()) + "-" + randomDigits(3),
            customer = customer.trim(), notes = notes.trim(), createdBy = userId
        )
        d.upsertSo(so)
        for ((sku, c) in lines) {
            d.upsertSoLine(InventorySoLine(soId = so.id, sku = sku, qtyOrdered = InventoryMath.toStorage(c.toDouble())))
        }
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم إنشاء طلب البيع ${so.number}")
    }

    /** DRAFT→CONFIRMED (يحجز، ويُرفض إن لم يكفِ المتاح للوعد)، CONFIRMED→CLOSED، DRAFT/CONFIRMED→CANCELLED بلا تسليم. */
    suspend fun setSoStatus(ctx: Context, soId: String, newStatus: String): OpResult {
        val d = dao(ctx)
        val so = d.soOrNull(soId) ?: return OpResult(false, "الطلب غير موجود")
        val lines = d.getSoLines(soId)
        when (newStatus) {
            "CONFIRMED" -> {
                if (so.status != "DRAFT") return OpResult(false, "الطلب ليس مسودة")
                val bySku = lines.groupBy { it.sku }.mapValues { e -> e.value.sumOf { it.qtyOrdered } }
                for ((sku, need) in bySku) {
                    val atp = sellableStorage(d, sku) - reservedStorage(d, sku, soId)
                    if (need > atp) {
                        return OpResult(false, "المتاح للوعد (" + InventoryMath.toDisplay(maxOf(0L, atp)) + ") لا يكفي المطلوب (" + InventoryMath.toDisplay(need) + ") من دفعات مُفرَج عنها")
                    }
                }
            }
            "CLOSED" -> if (so.status != "CONFIRMED") return OpResult(false, "الطلب غير مؤكَّد")
            "CANCELLED" -> {
                if (so.status != "DRAFT" && so.status != "CONFIRMED") return OpResult(false, "لا يمكن إلغاء هذا الطلب")
                for (l in lines) {
                    if (d.getDeliveredForRef("SO:" + soId + ":" + l.id) > 0L) return OpResult(false, "يوجد تسليم على هذا الطلب؛ أغلقه بدل إلغائه")
                }
            }
            else -> return OpResult(false, "حالة غير معروفة")
        }
        d.upsertSo(so.copy(status = newStatus, updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم تحديث حالة الطلب")
    }

    /**
     * تسليم عدد عبوات من سطر طلب مؤكَّد: FEFO تلقائي عبر دفعات مُفرَجة غير منتهية (حركة لكل دفعة).
     * overrideLotId لتجاوز FEFO بدفعة محددة (يتطلب سبباً مكتوباً؛ الصلاحية تُفحص في الواجهة).
     */
    suspend fun deliver(ctx: Context, soLineId: String, count: Int, overrideLotId: String?, overrideReason: String, userId: String): OpResult {
        if (count <= 0) return OpResult(false, "أدخل عدداً صحيحاً")
        if (overrideLotId != null && overrideReason.isBlank()) return OpResult(false, "سبب تجاوز FEFO إلزامي")
        val d = dao(ctx)
        val line = d.soLineOrNull(soLineId) ?: return OpResult(false, "سطر الطلب غير موجود")
        val so = d.soOrNull(line.soId) ?: return OpResult(false, "الطلب غير موجود")
        if (so.status != "CONFIRMED") return OpResult(false, "الطلب غير مؤكَّد أو مغلق")
        val ref = "SO:" + so.id + ":" + line.id
        val qty = InventoryMath.toStorage(count.toDouble())
        val remaining = line.qtyOrdered - d.getDeliveredForRef(ref)
        if (qty > remaining) return OpResult(false, "العدد أكبر من المتبقي في الطلب (" + InventoryMath.toDisplay(remaining) + ")")
        val now = System.currentTimeMillis()
        val ordered = d.getLotsForItem(line.sku).filter { isSellable(it, now) }
            .sortedWith(compareBy<StockLot> { it.expiryDate ?: Long.MAX_VALUE }.thenBy { it.receivedAt })
        val candidates = if (overrideLotId != null) ordered.filter { it.id == overrideLotId } else ordered
        if (candidates.isEmpty()) return OpResult(false, "لا دفعة مُفرَجة صالحة للتسليم")
        // تخصيص مسبق ثم كتابة: لا حركات جزئية عند النقص
        val plan = ArrayList<Pair<StockLot, Long>>()
        var left = qty
        for (l in candidates) {
            if (left <= 0L) break
            val bal = d.getLotOnHandAt(l.id, InventoryIds.LOC_FG)
            if (bal <= 0L) continue
            val take = if (bal < left) bal else left
            plan.add(Pair(l, take))
            left -= take
        }
        if (left > 0L) return OpResult(false, "المتاح من الدفعات الصالحة لا يكفي (ينقص " + InventoryMath.toDisplay(left) + ")")
        val reasonCode = if (overrideLotId != null) "FEFO_OVERRIDE" else "DELIVERY"
        val note = if (overrideLotId != null) overrideReason.trim() else ""
        for ((l, take) in plan) {
            write(ctx, newMove(ctx, line.sku, l.id, InventoryIds.LOC_FG, InventoryIds.V_CUSTOMER, take, "EA",
                InventoryTypes.FG_DELIVERY, reasonCode, ref, null, userId).copy(userId = if (note.isBlank()) userId else userId + " | " + note))
        }
        return OpResult(true, "تم تسجيل التسليم على " + plan.size + " دفعة")
    }

    /** مرتجع من عميل لدفعة سُلّمت في الطلب نفسه: يدخل حجر المرتجعات ولا يعود للبيع مباشرة. */
    suspend fun returnFromCustomer(ctx: Context, soLineId: String, lotId: String, count: Int, reason: String, userId: String): OpResult {
        if (count <= 0) return OpResult(false, "أدخل عدداً صحيحاً")
        if (reason.isBlank()) return OpResult(false, "سبب المرتجع إلزامي")
        val d = dao(ctx)
        val line = d.soLineOrNull(soLineId) ?: return OpResult(false, "سطر الطلب غير موجود")
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        if (lot.itemId != line.sku) return OpResult(false, "الدفعة لا تطابق صنف السطر")
        val ref = "SO:" + line.soId + ":" + line.id
        val qty = InventoryMath.toStorage(count.toDouble())
        val net = d.getDeliveredForRefLot(ref, lotId) - d.getReturnedForRefLot(ref, lotId)
        if (qty > net) return OpResult(false, "العدد أكبر من المسلَّم من هذه الدفعة في هذا الطلب (" + InventoryMath.toDisplay(maxOf(0L, net)) + ")")
        write(ctx, newMove(ctx, line.sku, lotId, InventoryIds.V_CUSTOMER, InventoryIds.LOC_FG_RET, qty, "EA",
            InventoryTypes.FG_RETURN, "CUSTOMER_RETURN", ref, null, userId).copy(userId = userId + " | " + reason.trim()))
        return OpResult(true, "سُجّل المرتجع في حجر المرتجعات")
    }

    /** قرار المرتجع بعد الفحص: إعادة للمخزن (restock) أو هالك. السبب إلزامي. */
    suspend fun dispositionReturn(ctx: Context, lotId: String, count: Int, restock: Boolean, reason: String, userId: String): OpResult {
        if (count <= 0) return OpResult(false, "أدخل عدداً صحيحاً")
        if (reason.isBlank()) return OpResult(false, "السبب إلزامي")
        val d = dao(ctx)
        val lot = d.getLot(lotId) ?: return OpResult(false, "الدفعة غير موجودة")
        val qty = InventoryMath.toStorage(count.toDouble())
        val have = d.getLotOnHandAt(lotId, InventoryIds.LOC_FG_RET)
        if (qty > have) return OpResult(false, "العدد أكبر من رصيد المرتجعات (" + InventoryMath.toDisplay(maxOf(0L, have)) + ")")
        if (restock && !isSellable(lot, System.currentTimeMillis())) return OpResult(false, "الدفعة منتهية أو غير مُفرَجة: لا تعود للبيع")
        val to = if (restock) InventoryIds.LOC_FG else InventoryIds.V_SCRAP
        val type = if (restock) InventoryTypes.FG_RETURN_RESTOCK else InventoryTypes.FG_RETURN_SCRAP
        write(ctx, newMove(ctx, lot.itemId, lotId, InventoryIds.LOC_FG_RET, to, qty, "EA", type,
            if (restock) "RETURN_RESTOCK" else "RETURN_SCRAP", reason.trim(), null, userId))
        return OpResult(true, if (restock) "أُعيدت العبوات إلى المخزن" else "سُجّلت كهالك")
    }

    // ---------- حفظ السجلات الرئيسة ----------
    suspend fun saveProfile(ctx: Context, p: InventoryItemProfile) {
        dao(ctx).upsertProfile(p.copy(updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
    }

    suspend fun saveConversion(ctx: Context, c: UomConversion): OpResult {
        if (c.name.isBlank()) return OpResult(false, "اسم الوحدة مطلوب")
        if (c.factorToBase <= 0.0) return OpResult(false, "المعامل يجب أن يكون أكبر من صفر")
        dao(ctx).upsertConversion(c.copy(updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم الحفظ")
    }

    suspend fun saveSupplier(ctx: Context, s: InventorySupplier): OpResult {
        if (s.name.isBlank()) return OpResult(false, "اسم المورد مطلوب")
        dao(ctx).upsertSupplier(s.copy(updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم الحفظ")
    }

    suspend fun saveLocation(ctx: Context, l: StockLocation): OpResult {
        if (l.name.isBlank()) return OpResult(false, "اسم الموقع مطلوب")
        dao(ctx).upsertLocation(l.copy(updatedAt = System.currentTimeMillis(), isPending = true))
        InventorySync.uploadPendingAsync(ctx)
        return OpResult(true, "تم الحفظ")
    }
}
