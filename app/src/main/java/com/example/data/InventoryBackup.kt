package com.example.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * نسخ احتياطي/استيراد لجداول المخزون (قسم "inventory" في BackupManager).
 * الحركات تُستورد بـ IGNORE (لا تُعدَّل أبداً)، والسجلات الرئيسة بالأحدث updatedAt.
 */
object InventoryBackup {
    val TABLES = listOf("inventory_item_profile", "uom_conversions", "inventory_suppliers", "stock_locations", "stock_lots", "stock_moves")

    private fun JSONObject.putN(k: String, v: Any?) { put(k, v ?: JSONObject.NULL) }
    private fun JSONObject.strOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
    private fun JSONObject.dblOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)

    suspend fun exportAll(dao: InventoryDao): Map<String, JSONArray> {
        val out = LinkedHashMap<String, JSONArray>()
        out["inventory_item_profile"] = JSONArray().also { a ->
            dao.getAllProfilesSync().forEach { p ->
                a.put(JSONObject().apply {
                    put("itemId", p.itemId); put("category", p.category); put("baseUom", p.baseUom)
                    put("densityKgPerL", p.densityKgPerL); put("shelfLifeDays", p.shelfLifeDays); put("retestDays", p.retestDays)
                    put("minLevel", p.minLevel); put("maxLevel", p.maxLevel); put("reorderPoint", p.reorderPoint)
                    put("safetyStock", p.safetyStock); put("leadTimeDays", p.leadTimeDays); put("moq", p.moq)
                    put("hazardClass", p.hazardClass); putN("flashPointC", p.flashPointC); put("storageZone", p.storageZone)
                    put("weighTolerancePct", p.weighTolerancePct); put("vedClass", p.vedClass)
                    putN("preferredSupplierId", p.preferredSupplierId); put("sdsNote", p.sdsNote); put("updatedAt", p.updatedAt)
                })
            }
        }
        out["uom_conversions"] = JSONArray().also { a ->
            dao.getAllConversionsSync().forEach { c ->
                a.put(JSONObject().apply {
                    put("id", c.id); put("itemId", c.itemId); put("name", c.name)
                    put("factorToBase", c.factorToBase); put("updatedAt", c.updatedAt)
                })
            }
        }
        out["inventory_suppliers"] = JSONArray().also { a ->
            dao.getAllSuppliersSync().forEach { s ->
                a.put(JSONObject().apply {
                    put("id", s.id); put("name", s.name); put("phone", s.phone); put("notes", s.notes)
                    put("isApproved", s.isApproved); put("isActive", s.isActive); put("updatedAt", s.updatedAt)
                })
            }
        }
        out["stock_locations"] = JSONArray().also { a ->
            dao.getAllLocationsSync().forEach { l ->
                a.put(JSONObject().apply {
                    put("id", l.id); put("name", l.name); put("zone", l.zone); putN("parentId", l.parentId)
                    put("isActive", l.isActive); put("updatedAt", l.updatedAt)
                })
            }
        }
        out["stock_lots"] = JSONArray().also { a ->
            dao.getAllLotsSync().forEach { l ->
                a.put(JSONObject().apply {
                    put("id", l.id); put("itemId", l.itemId); put("lotNo", l.lotNo); put("supplierLotNo", l.supplierLotNo)
                    putN("supplierId", l.supplierId); putN("mfgDate", l.mfgDate); putN("expiryDate", l.expiryDate)
                    put("status", l.status); put("receivedAt", l.receivedAt); put("notes", l.notes); put("updatedAt", l.updatedAt)
                })
            }
        }
        out["stock_moves"] = JSONArray().also { a ->
            dao.getAllMovesSync().forEach { m ->
                a.put(JSONObject().apply {
                    put("moveId", m.moveId); put("deviceId", m.deviceId); put("deviceSeq", m.deviceSeq)
                    put("itemId", m.itemId); putN("lotId", m.lotId); put("fromLocId", m.fromLocId); put("toLocId", m.toLocId)
                    put("qtyBase", m.qtyBase); put("uom", m.uom); put("type", m.type); put("reasonCode", m.reasonCode)
                    put("refDoc", m.refDoc); putN("reversesMoveId", m.reversesMoveId); putN("unitCost", m.unitCost)
                    put("costCurrency", m.costCurrency); put("userId", m.userId); put("clientCreatedAt", m.clientCreatedAt)
                    putN("serverCreatedAt", m.serverCreatedAt)
                })
            }
        }
        return out
    }

    /** يستورد ما في root من جداول المخزون. الاستيراد المحلي يُعلَّم «معلّقاً» ليُرفع للسحابة (الحركات الموجودة تُتجاهل). */
    suspend fun importAll(dao: InventoryDao, root: JSONObject) {
        root.optJSONArray("inventory_item_profile")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("itemId", "")
                if (id.isBlank()) continue
                val ts = o.optLong("updatedAt", 0L)
                val local = dao.profileOrNull(id)
                if (local == null || ts > local.updatedAt) {
                    dao.upsertProfile(
                        InventoryItemProfile(
                            itemId = id, category = o.optString("category", "OTHER"), baseUom = o.optString("baseUom", "KG"),
                            densityKgPerL = o.optDouble("densityKgPerL", 0.0), shelfLifeDays = o.optInt("shelfLifeDays", 0),
                            retestDays = o.optInt("retestDays", 0), minLevel = o.optDouble("minLevel", 0.0),
                            maxLevel = o.optDouble("maxLevel", 0.0), reorderPoint = o.optDouble("reorderPoint", 0.0),
                            safetyStock = o.optDouble("safetyStock", 0.0), leadTimeDays = o.optInt("leadTimeDays", 0),
                            moq = o.optDouble("moq", 0.0), hazardClass = o.optString("hazardClass", ""),
                            flashPointC = o.dblOrNull("flashPointC"), storageZone = o.optString("storageZone", ""),
                            weighTolerancePct = o.optDouble("weighTolerancePct", 0.0), vedClass = o.optString("vedClass", ""),
                            preferredSupplierId = o.strOrNull("preferredSupplierId"), sdsNote = o.optString("sdsNote", ""),
                            updatedAt = if (ts > 0L) ts else System.currentTimeMillis(), isPending = true
                        )
                    )
                }
            }
        }
        root.optJSONArray("uom_conversions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val ts = o.optLong("updatedAt", 0L)
                val local = dao.conversionOrNull(id)
                if (local == null || ts > local.updatedAt) {
                    dao.upsertConversion(
                        UomConversion(id = id, itemId = o.optString("itemId", ""), name = o.optString("name", ""),
                            factorToBase = o.optDouble("factorToBase", 1.0), updatedAt = if (ts > 0L) ts else System.currentTimeMillis(), isPending = true)
                    )
                }
            }
        }
        root.optJSONArray("inventory_suppliers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val ts = o.optLong("updatedAt", 0L)
                val local = dao.supplierOrNull(id)
                if (local == null || ts > local.updatedAt) {
                    dao.upsertSupplier(
                        InventorySupplier(id = id, name = o.optString("name", ""), phone = o.optString("phone", ""),
                            notes = o.optString("notes", ""), isApproved = o.optBoolean("isApproved", true),
                            isActive = o.optBoolean("isActive", true), updatedAt = if (ts > 0L) ts else System.currentTimeMillis(), isPending = true)
                    )
                }
            }
        }
        root.optJSONArray("stock_locations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val ts = o.optLong("updatedAt", 0L)
                val local = dao.locationOrNull(id)
                if (local == null || ts > local.updatedAt) {
                    dao.upsertLocation(
                        StockLocation(id = id, name = o.optString("name", ""), zone = o.optString("zone", "GENERAL"),
                            parentId = o.strOrNull("parentId"), isActive = o.optBoolean("isActive", true),
                            updatedAt = if (ts > 0L) ts else System.currentTimeMillis(), isPending = true)
                    )
                }
            }
        }
        root.optJSONArray("stock_lots")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                val ts = o.optLong("updatedAt", 0L)
                val local = dao.lotOrNull(id)
                if (local == null || ts > local.updatedAt) {
                    dao.upsertLot(
                        StockLot(id = id, itemId = o.optString("itemId", ""), lotNo = o.optString("lotNo", ""),
                            supplierLotNo = o.optString("supplierLotNo", ""), supplierId = o.strOrNull("supplierId"),
                            mfgDate = o.longOrNull("mfgDate"), expiryDate = o.longOrNull("expiryDate"),
                            status = o.optString("status", "RELEASED"), receivedAt = o.optLong("receivedAt", System.currentTimeMillis()),
                            notes = o.optString("notes", ""), updatedAt = if (ts > 0L) ts else System.currentTimeMillis(), isPending = true)
                    )
                }
            }
        }
        root.optJSONArray("stock_moves")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("moveId", "")
                val qty = o.optLong("qtyBase", 0L)
                if (id.isBlank() || qty <= 0L) continue
                // IGNORE: حركة موجودة لا تُمسّ. الجديدة تُعلَّم غير مرفوعة لترفعها المزامنة بمعرّفها نفسه.
                dao.insertMove(
                    StockMove(
                        moveId = id, deviceId = o.optString("deviceId", ""), deviceSeq = o.optLong("deviceSeq", 0L),
                        itemId = o.optString("itemId", ""), lotId = o.strOrNull("lotId"),
                        fromLocId = o.optString("fromLocId", InventoryIds.V_ADJUST), toLocId = o.optString("toLocId", InventoryIds.LOC_MAIN),
                        qtyBase = qty, uom = o.optString("uom", "KG"), type = o.optString("type", ""),
                        reasonCode = o.optString("reasonCode", ""), refDoc = o.optString("refDoc", ""),
                        reversesMoveId = o.strOrNull("reversesMoveId"), unitCost = o.dblOrNull("unitCost"),
                        costCurrency = o.optString("costCurrency", "ILS"), userId = o.optString("userId", ""),
                        clientCreatedAt = o.optLong("clientCreatedAt", System.currentTimeMillis()),
                        serverCreatedAt = o.longOrNull("serverCreatedAt"), uploaded = false
                    )
                )
            }
        }
    }
}
