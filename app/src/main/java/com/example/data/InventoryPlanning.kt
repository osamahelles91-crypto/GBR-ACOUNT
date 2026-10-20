package com.example.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** إحصاءات الطلب اليومي لمادة (بالكجم) من الأوامر المكتملة. */
data class DemandStats(
    val mean: Double,
    val sigma: Double,
    val maxDaily: Double,
    val days: Int,
    val annual: Double,
    val cvMonthly: Double?   // معامل التغير على شرائح 30 يوماً (لتصنيف XYZ) إن توفّر 90 يوماً فأكثر
)

data class PlanRow(
    val itemId: String,
    val name: String,
    val uom: String,
    val available: Double,
    val onOrder: Double,
    val meanDaily: Double,
    val coverageDays: Double?,
    val leadDays: Double?,
    val safetyStock: Double?,
    val rop: Double?,
    val method: String,
    val eoq: Double?,
    val suggested: Double,
    val xyz: String,
    val status: String     // ORDER, OK, NO_DEMAND, NO_LEAD
)

data class MrpRow(
    val itemId: String,
    val name: String,
    val uom: String,
    val gross: Double,
    val available: Double,
    val onOrder: Double,
    val net: Double,             // المطلوب شراؤه (≥ 0)
    val orderByDays: Double?     // بعد كم يوم من الآن يجب إصدار الطلب (سالب = متأخر)
)

data class Kpis(
    val rawValue: Double,
    val consumptionValueWindow: Double,
    val windowDays: Int,
    val daysOfInventory: Double?,
    val turnover: Double?,
    val stagnantCount: Int,
    val stagnantValue: Double,
    val expiring30: Int,
    val expiring60: Int,
    val expiring90: Int,
    val negativeCount: Int,
    val countAccuracyPct: Double?,
    val avgRawReleaseHours: Double?,
    val avgFgReleaseHours: Double?,
    val avgYieldPct: Double?,
    val fgStagnantLots: Int
)

/**
 * التخطيط والذكاء (المرحلة 7): كل شيء مشتق من بيانات قائمة (أوامر مكتملة، دفتر، أوامر شراء)؛ لا جداول جديدة.
 * الصيغ: ROP = μD·L + SS؛ SS الإحصائي = z·√(μL·σD² + μD²·σL²) عند ≥ 6 ملاحظات مهلة وإلا طريقة الحد الأقصى-الأدنى؛
 * EOQ = √(2·D·K/H) (يحتاج تكلفة الطلب K يُدخلها المالك). XYZ بمعامل التغير: X ≤ 10%، Y ≤ 25%، Z أعلى.
 */
object InventoryPlanning {
    const val DAY = 86_400_000L
    const val Z95 = 1.645
    const val MIN_LEAD_OBS = 6

    fun completionTs(o: ProductionOrder): Long =
        if (o.endTime > 0L) o.endTime else if (o.startTime > 0L) o.startTime else o.createdAt

    /** كجم إلى وحدة الأساس (لتر يحتاج كثافة). */
    fun kgToBase(kg: Double, uom: String, density: Double): Double? =
        if (uom == "L") (if (density > 0.0) kg / density else null) else kg

    private fun stddev(values: DoubleArray, mean: Double): Double {
        if (values.size < 2) return 0.0
        var s = 0.0
        for (v in values) s += (v - mean) * (v - mean)
        return sqrt(s / (values.size - 1))
    }

    fun demandByItem(orders: List<ProductionOrder>, items: List<ProductionOrderItem>, now: Long, maxWindow: Int = 180): Map<String, DemandStats> {
        val inWin = orders.filter { it.status == "مكتمل" && now - completionTs(it) <= maxWindow * DAY }
        if (inWin.isEmpty()) return emptyMap()
        val earliest = inWin.minOf { completionTs(it) }
        val days = ((now - earliest) / DAY).toInt().coerceIn(14, maxWindow)
        val orderTs = inWin.associate { it.id to completionTs(it) }
        val daily = HashMap<String, DoubleArray>()
        for (line in items) {
            val ts = orderTs[line.productionOrderId] ?: continue
            val age = ((now - ts) / DAY).toInt()
            val idx = (days - 1 - age).coerceIn(0, days - 1)
            daily.getOrPut(line.rawMaterialId) { DoubleArray(days) }[idx] += line.calculatedQuantity
        }
        val out = HashMap<String, DemandStats>()
        for ((id, arr) in daily) {
            val mean = arr.sum() / days
            var cv: Double? = null
            if (days >= 90) {
                val buckets = days / 30
                val sums = DoubleArray(buckets)
                for (i in 0 until buckets * 30) sums[i / 30] += arr[days - 1 - i]
                val m = sums.average()
                if (m > 0.0) cv = stddev(sums, m) / m
            }
            out[id] = DemandStats(mean, stddev(arr, mean), arr.max(), days, mean * 365.0, cv)
        }
        return out
    }

    /** مهلة التوريد الفعلية (أيام) لكل مادة: من إنشاء أمر الشراء إلى أول استلام على السطر. */
    fun leadObservations(pos: List<InventoryPurchaseOrder>, lines: List<InventoryPoLine>, moves: List<StockMove>): Map<String, List<Double>> {
        val poBy = pos.associateBy { it.id }
        val lineBy = lines.associateBy { it.id }
        val first = HashMap<String, Long>()
        for (m in moves) {
            if (m.type != InventoryTypes.RECEIPT || !m.refDoc.startsWith("PO:")) continue
            val ts = m.serverCreatedAt ?: m.clientCreatedAt
            val cur = first[m.refDoc]
            if (cur == null || ts < cur) first[m.refDoc] = ts
        }
        val out = HashMap<String, MutableList<Double>>()
        for ((ref, ts) in first) {
            val parts = ref.split(":")
            if (parts.size < 3) continue
            val po = poBy[parts[1]] ?: continue
            val line = lineBy[parts[2]] ?: continue
            out.getOrPut(line.itemId) { mutableListOf() }.add(max(0.0, (ts - po.createdAt) / DAY.toDouble()))
        }
        return out
    }

    fun xyzClass(cv: Double?): String = when {
        cv == null -> "—"
        cv <= 0.10 -> "X"
        cv <= 0.25 -> "Y"
        else -> "Z"
    }

    /** جدول التخطيط لكل مادة لها طلب مسجَّل. available وonOrder بوحدة الأساس. */
    fun plan(
        materials: List<RawMaterial>,
        profiles: Map<String, InventoryItemProfile>,
        available: Map<String, Double>,
        onOrder: Map<String, Double>,
        demand: Map<String, DemandStats>,
        leads: Map<String, List<Double>>,
        orderCost: Double,
        holdPct: Double
    ): List<PlanRow> {
        val rows = ArrayList<PlanRow>()
        for (m in materials) {
            val d = demand[m.id] ?: continue
            val p = profiles[m.id]
            val uom = p?.baseUom ?: "KG"
            val f = kgToBase(1.0, uom, p?.densityKgPerL ?: 0.0)
            val avail = available[m.id] ?: 0.0
            val oo = onOrder[m.id] ?: 0.0
            if (f == null) {
                rows.add(PlanRow(m.id, m.name, uom, avail, oo, 0.0, null, null, null, null, "تحتاج كثافة", null, 0.0, "—", "NO_DEMAND"))
                continue
            }
            val mu = d.mean * f
            val sigma = d.sigma * f
            val maxD = d.maxDaily * f
            val annual = d.annual * f
            val obs = leads[m.id].orEmpty()
            val profLead = p?.leadTimeDays ?: 0
            val lead: Double? = when {
                obs.size >= MIN_LEAD_OBS -> obs.average()
                profLead > 0 -> profLead.toDouble()
                obs.isNotEmpty() -> obs.average()
                else -> null
            }
            val coverage = if (mu > 0.0) avail / mu else null
            val xyz = xyzClass(d.cvMonthly)
            if (lead == null) {
                rows.add(PlanRow(m.id, m.name, uom, avail, oo, mu, coverage, null, null, null, "لا مهلة توريد", null, 0.0, xyz, "NO_LEAD"))
                continue
            }
            val ss: Double
            val method: String
            if (obs.size >= MIN_LEAD_OBS) {
                val muL = obs.average()
                var v = 0.0
                for (x in obs) v += (x - muL) * (x - muL)
                val sigL = sqrt(v / (obs.size - 1))
                ss = Z95 * sqrt(muL * sigma * sigma + mu * mu * sigL * sigL)
                method = "إحصائي 95%"
            } else {
                val maxL = max(lead, obs.maxOrNull() ?: lead)
                ss = max(0.0, maxD * maxL - mu * lead)
                method = "أقصى-أدنى"
            }
            val rop = mu * lead + ss
            val price = m.price
            val eoq = if (orderCost > 0.0 && price > 0.0 && holdPct > 0.0 && annual > 0.0 && uom == "KG") sqrt(2.0 * annual * orderCost / (holdPct / 100.0 * price)) else null
            val need = avail + oo <= rop
            val moq = p?.moq ?: 0.0
            val target = if ((p?.maxLevel ?: 0.0) > 0.0) p!!.maxLevel else rop + (eoq ?: (mu * lead))
            val suggested = if (need) max(ceil(target - (avail + oo)), moq) else 0.0
            rows.add(PlanRow(m.id, m.name, uom, avail, oo, mu, coverage, lead, ss, rop, method, eoq, suggested, xyz, if (need) "ORDER" else "OK"))
        }
        return rows.sortedWith(compareBy<PlanRow> { if (it.status == "ORDER") 0 else 1 }.thenBy { it.coverageDays ?: Double.MAX_VALUE })
    }

    /** احتياج صافٍ لدفعات مخططة: (تركيبة، كجم). need after = بعد كم يوم تلزم المواد. */
    fun mrp(
        basket: List<Pair<String, Double>>,
        formulationItems: List<FormulationItem>,
        materials: List<RawMaterial>,
        profiles: Map<String, InventoryItemProfile>,
        available: Map<String, Double>,
        onOrder: Map<String, Double>,
        leadDays: Map<String, Double?>,
        needAfterDays: Int
    ): List<MrpRow> {
        val byForm = formulationItems.groupBy { it.formulationId }
        val grossKg = HashMap<String, Double>()
        for ((fid, kg) in basket) {
            for (fi in byForm[fid].orEmpty()) {
                grossKg[fi.rawMaterialId] = (grossKg[fi.rawMaterialId] ?: 0.0) + fi.quantityMultiplier * kg / 1000.0
            }
        }
        val nameBy = materials.associate { it.id to it.name }
        val out = ArrayList<MrpRow>()
        for ((id, kg) in grossKg) {
            val p = profiles[id]
            val uom = p?.baseUom ?: "KG"
            val gross = kgToBase(kg, uom, p?.densityKgPerL ?: 0.0) ?: continue
            val av = available[id] ?: 0.0
            val oo = onOrder[id] ?: 0.0
            val net = max(0.0, gross - av - oo)
            val lead = leadDays[id]
            out.add(MrpRow(id, nameBy[id] ?: id, uom, gross, av, oo, net, if (net > 0.0 && lead != null) needAfterDays - lead else null))
        }
        return out.sortedWith(compareBy<MrpRow> { if (it.net > 0.0) 0 else 1 }.thenBy { it.name })
    }
}
