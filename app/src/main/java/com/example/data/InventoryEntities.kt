package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// =====================================================================================
// وحدة المخزون (المرحلتان 0 و1) — جداول جديدة مستقلة؛ لا تغيير على جدول raw_materials.
// المبدأ: الرصيد لا يُخزَّن كحقل قابل للتعديل. كل تغيير كمية = حركة (StockMove) لا تُعدَّل ولا تُحذف،
// والرصيد يُشتق من مجموع الحركات. لا مفاتيح أجنبية (CASCADE) كي لا يُمحى التاريخ بحذف مادة.
// الكمية تُخزَّن عدداً صحيحاً بوحدة صغيرة: غرام لمواد الكجم، ملّيلتر لمواد اللتر (بلا Double).
// =====================================================================================

/** سجل المادة المخزني الغني (مرتبط بمعرّف المادة الخام الحالي بلا مفتاح أجنبي). */
@Entity(tableName = "inventory_item_profile")
data class InventoryItemProfile(
    @PrimaryKey val itemId: String,                 // = RawMaterial.id
    val category: String = "OTHER",                 // PIGMENT, EXTENDER, BINDER, SOLVENT, WATER, ADDITIVE, PACKAGING, OTHER
    val baseUom: String = "KG",                     // KG | L  (الوحدة الأساسية للعرض؛ التخزين غرام/ملّيلتر)
    val densityKgPerL: Double = 0.0,                // كثافة للتحويل بين اللتر والكجم (0 = غير محددة)
    val shelfLifeDays: Int = 0,                     // مدة الصلاحية (0 = بلا صلاحية)
    val retestDays: Int = 0,                        // فاصل إعادة الفحص (0 = لا يوجد)
    val minLevel: Double = 0.0,                     // الحد الأدنى (بوحدة الأساس: كجم أو لتر)
    val maxLevel: Double = 0.0,
    val reorderPoint: Double = 0.0,                 // نقطة إعادة الطلب
    val safetyStock: Double = 0.0,
    val leadTimeDays: Int = 0,
    val moq: Double = 0.0,                          // أقل كمية طلب
    val hazardClass: String = "",                   // فئة الخطر (GHS/NFPA) وصفياً
    val flashPointC: Double? = null,
    val storageZone: String = "",                   // منطقة التخزين المسموحة
    val weighTolerancePct: Double = 0.0,            // تسامح الوزن %
    val vedClass: String = "",                      // V حيوية / E أساسية / D مرغوبة
    val preferredSupplierId: String? = null,
    val sdsNote: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true                   // لم يُرفع للسحابة بعد
)

/** معامل تحويل وحدة شراء/تخزين إلى الوحدة الأساسية (مثال: كيس = 25 كجم، برميل = 200 كجم). */
@Entity(tableName = "uom_conversions", indices = [Index("itemId")])
data class UomConversion(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val itemId: String,
    val name: String,                               // كيس، برميل، IBC …
    val factorToBase: Double,                       // كم كجم (أو لتر) في الوحدة الواحدة
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)

@Entity(tableName = "inventory_suppliers")
data class InventorySupplier(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val phone: String = "",
    val notes: String = "",
    val isApproved: Boolean = true,                 // مورد معتمد
    val isActive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)

@Entity(tableName = "stock_locations")
data class StockLocation(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val zone: String = "GENERAL",                   // GENERAL, HAZMAT, QUARANTINE, FINISHED, QC_HOLD, RETURNS, SAMPLES
    val parentId: String? = null,
    val isActive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)

/** دفعة (lot) — حالتها: QUARANTINE, RELEASED, ON_HOLD, REJECTED, EXPIRED. */
@Entity(tableName = "stock_lots", indices = [Index("itemId")])
data class StockLot(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val itemId: String,
    val lotNo: String,
    val supplierLotNo: String = "",
    val supplierId: String? = null,
    val mfgDate: Long? = null,
    val expiryDate: Long? = null,
    val status: String = "RELEASED",
    val receivedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)

/**
 * حركة مخزون — لا تُعدَّل ولا تُحذف. التصحيح يكون بحركة عكسية (reversesMoveId) أو بتسوية.
 * qtyBase موجبة دائماً؛ الاتجاه من fromLocId إلى toLocId. المواقع الافتراضية تبدأ بـ "V_" ولا تُحتسب مخزوناً فعلياً.
 */
@Entity(
    tableName = "stock_moves",
    indices = [Index("itemId"), Index("lotId"), Index("refDoc"), Index("uploaded")]
)
data class StockMove(
    @PrimaryKey val moveId: String,                 // UUID: عشوائي أو حتمي (مثل الرصيد الافتتاحي) فلا يتكرر أثره
    val deviceId: String,
    val deviceSeq: Long,                            // عدّاد متزايد لكل جهاز لاكتشاف الفجوات
    val itemId: String,
    val lotId: String? = null,
    val fromLocId: String,
    val toLocId: String,
    val qtyBase: Long,                              // غرام (KG) أو ملّيلتر (L)، موجب دائماً
    val uom: String = "KG",
    val type: String,                               // OPENING, RECEIPT, ISSUE, ADJUSTMENT, REVERSAL, TRANSFER, SCRAP …
    val reasonCode: String = "",
    val refDoc: String = "",                        // مستند مرجعي (أمر إنتاج/استلام/جرد)
    val reversesMoveId: String? = null,
    val unitCost: Double? = null,                   // تكلفة الكجم/اللتر عند الاستلام
    val costCurrency: String = "ILS",
    val userId: String = "",
    val clientCreatedAt: Long = System.currentTimeMillis(),   // تلميح فقط
    val serverCreatedAt: Long? = null,              // يُملأ من السحابة بعد الرفع
    val uploaded: Boolean = false                  // هل رُفعت للسحابة (بيانات وصفية فقط؛ تجاهل التعديل لا يمس الحركة)
)

/** نتيجة استعلام الرصيد الجاري لكل مادة. */
data class ItemQty(
    val itemId: String,
    val qty: Long
)

/** نتيجة استعلام رصيد مادة لكل موقع. */
data class LocQty(
    val locId: String,
    val qty: Long
)

/** رصيد دفعة في موقع. */
data class LotLocQty(
    val lotId: String,
    val locId: String,
    val qty: Long
)

// ---------------- المرحلة 2: أوامر الشراء ----------------

/** أمر شراء بسيط. الحالات المخزَّنة: DRAFT, APPROVED, CLOSED, CANCELLED؛ «مستلم جزئياً/كلياً» تُشتق من الحركات. */
@Entity(tableName = "inventory_purchase_orders", indices = [Index("supplierId")])
data class InventoryPurchaseOrder(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val number: String,
    val supplierId: String,
    val status: String = "DRAFT",
    val notes: String = "",
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)

/** سطر أمر شراء: الكمية المطلوبة بوحدة التخزين (غرام/ملّيلتر). */
@Entity(tableName = "inventory_po_lines", indices = [Index("poId")])
data class InventoryPoLine(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val poId: String,
    val itemId: String,
    val qtyOrdered: Long,
    val unitPrice: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val isPending: Boolean = true
)
