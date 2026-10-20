package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawMaterial::class,
        Formulation::class,
        FormulationRevision::class,
        FormulationItem::class,
        ProductionLog::class,
        PriceHistoryEntry::class,
        ProductionOrder::class,
        ProductionOrderItem::class,
        ProductionOrderEvent::class,
        RecipePhase::class,
        RecipeItem::class,
        RecipeStatus::class,
        ProductionOrderPhase::class,
        ProductionOrderRecipeItem::class,
        QualityTest::class,
        FormulationQualityTest::class,
        ProductionOrderQualityTest::class,
        ProductionOrderTestRecord::class,
        ProductionAdjustment::class,
        DevelopmentProject::class,
        DevelopmentSample::class,
        SyncMetadata::class,
        LabSession::class,
        LabTest::class,
        LabAttachment::class,
        FormulationReferenceSpecs::class,
        OperationalAlert::class,
        RecycleBinItem::class,
        // وحدة المخزون (39): جداول جديدة فقط، بلا مساس بـ raw_materials
        InventoryItemProfile::class,
        UomConversion::class,
        InventorySupplier::class,
        StockLocation::class,
        StockLot::class,
        StockMove::class,
        InventoryPurchaseOrder::class,
        InventoryPoLine::class,
        InventorySalesOrder::class,
        InventorySoLine::class
    ],
    version = 41, // 41: طلبات البيع (inventory_sales_orders, inventory_so_lines) | // 40: أوامر الشراء (inventory_purchase_orders, inventory_po_lines) | // 39: جداول المخزون (دفتر حركات + سجل المادة + موردون + مواقع + دفعات) | 38: عمود productionLine في formulations
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun gbrDao(): GbrDao

    abstract fun inventoryDao(): InventoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ترقية آمنة (بدون مسح البيانات المحلية): إضافة عمود الخط التابع للتركيبة
        private val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE formulations ADD COLUMN productionLine INTEGER NOT NULL DEFAULT -1")
            }
        }

        // ترقية آمنة (بدون مسح): إنشاء جداول المخزون فقط. أي تعديل على هذه العبارات يجب أن يطابق الكيانات في InventoryEntities.kt حرفياً
        // (الأنواع والقيود والفهارس) وإلا فشل تحقق Room وأُعيد إنشاء القاعدة محلياً.
        private val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_item_profile` (`itemId` TEXT NOT NULL, `category` TEXT NOT NULL, `baseUom` TEXT NOT NULL, `densityKgPerL` REAL NOT NULL, `shelfLifeDays` INTEGER NOT NULL, `retestDays` INTEGER NOT NULL, `minLevel` REAL NOT NULL, `maxLevel` REAL NOT NULL, `reorderPoint` REAL NOT NULL, `safetyStock` REAL NOT NULL, `leadTimeDays` INTEGER NOT NULL, `moq` REAL NOT NULL, `hazardClass` TEXT NOT NULL, `flashPointC` REAL, `storageZone` TEXT NOT NULL, `weighTolerancePct` REAL NOT NULL, `vedClass` TEXT NOT NULL, `preferredSupplierId` TEXT, `sdsNote` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`itemId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `uom_conversions` (`id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `name` TEXT NOT NULL, `factorToBase` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_uom_conversions_itemId` ON `uom_conversions` (`itemId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_suppliers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `notes` TEXT NOT NULL, `isApproved` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stock_locations` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `zone` TEXT NOT NULL, `parentId` TEXT, `isActive` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stock_lots` (`id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `lotNo` TEXT NOT NULL, `supplierLotNo` TEXT NOT NULL, `supplierId` TEXT, `mfgDate` INTEGER, `expiryDate` INTEGER, `status` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_lots_itemId` ON `stock_lots` (`itemId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `stock_moves` (`moveId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `deviceSeq` INTEGER NOT NULL, `itemId` TEXT NOT NULL, `lotId` TEXT, `fromLocId` TEXT NOT NULL, `toLocId` TEXT NOT NULL, `qtyBase` INTEGER NOT NULL, `uom` TEXT NOT NULL, `type` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `refDoc` TEXT NOT NULL, `reversesMoveId` TEXT, `unitCost` REAL, `costCurrency` TEXT NOT NULL, `userId` TEXT NOT NULL, `clientCreatedAt` INTEGER NOT NULL, `serverCreatedAt` INTEGER, `uploaded` INTEGER NOT NULL, PRIMARY KEY(`moveId`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_itemId` ON `stock_moves` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_lotId` ON `stock_moves` (`lotId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_refDoc` ON `stock_moves` (`refDoc`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_moves_uploaded` ON `stock_moves` (`uploaded`)")
            }
        }

        // ترقية آمنة: جدولا أوامر الشراء فقط (يجب أن تطابق الكيانات حرفياً)
        private val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_purchase_orders` (`id` TEXT NOT NULL, `number` TEXT NOT NULL, `supplierId` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_purchase_orders_supplierId` ON `inventory_purchase_orders` (`supplierId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_po_lines` (`id` TEXT NOT NULL, `poId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `qtyOrdered` INTEGER NOT NULL, `unitPrice` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_po_lines_poId` ON `inventory_po_lines` (`poId`)")
            }
        }

        // ترقية آمنة: جدولا طلبات البيع فقط (يجب أن تطابق الكيانات حرفياً)
        private val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_sales_orders` (`id` TEXT NOT NULL, `number` TEXT NOT NULL, `customer` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `inventory_so_lines` (`id` TEXT NOT NULL, `soId` TEXT NOT NULL, `sku` TEXT NOT NULL, `qtyOrdered` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isPending` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_inventory_so_lines_soId` ON `inventory_so_lines` (`soId`)")
            }
        }

        private const val DB_NAME = "gbr_paints_production_db"

        private fun build(appCtx: Context): AppDatabase =
            Room.databaseBuilder(appCtx, AppDatabase::class.java, DB_NAME)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON;")
                    }
                })
                .addMigrations(MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41)
                .fallbackToDestructiveMigration()
                .build()

        // نسخة احتياطية من ملفات القاعدة قبل إعادة إنشائها (تبقى على الجهاز للطوارئ)
        private fun backupDbFiles(appCtx: Context) {
            try {
                val dir = java.io.File(appCtx.filesDir, "db_backup_before_recreate_${System.currentTimeMillis()}").apply { mkdirs() }
                val main = appCtx.getDatabasePath(DB_NAME)
                for (suffix in listOf("", "-wal", "-shm")) {
                    val f = java.io.File(main.path + suffix)
                    if (f.exists()) f.copyTo(java.io.File(dir, f.name), overwrite = true)
                }
            } catch (_: Throwable) {
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appCtx = context.applicationContext
                    var instance = build(appCtx)
                    try {
                        // نفتح القاعدة الآن ليظهر فشل الترقية/التحقق هنا وليس لاحقاً داخل التطبيق
                        instance.openHelper.writableDatabase
                    } catch (e: IllegalStateException) {
                        // «Migration didn't properly handle»: بنية الجدول على الجهاز لا تطابق النموذج الحالي.
                        // الترقية تُلغى تلقائياً (معاملة واحدة) فيبقى الملف سليماً؛ نحتفظ بنسخة منه ثم نعيد إنشاء القاعدة،
                        // والبيانات تعود من السحابة بالمزامنة (نفس سلوك الترقيات السابقة التي كانت تمسح وتعيد الإنشاء).
                        android.util.Log.e("GBR_DB", "Room upgrade failed, recreating database", e)
                        try { instance.close() } catch (_: Throwable) {}
                        backupDbFiles(appCtx)
                        appCtx.deleteDatabase(DB_NAME)
                        instance = build(appCtx)
                    }
                    INSTANCE = instance
                    instance
                }
            }
        }
    }
}
