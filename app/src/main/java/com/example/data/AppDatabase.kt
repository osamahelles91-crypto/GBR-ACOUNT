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
        RecycleBinItem::class
    ],
    version = 38, // 38: عمود productionLine في formulations (الخط التابع للتركيبة)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun gbrDao(): GbrDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ترقية آمنة (بدون مسح البيانات المحلية): إضافة عمود الخط التابع للتركيبة
        private val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE formulations ADD COLUMN productionLine INTEGER NOT NULL DEFAULT -1")
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
                .addMigrations(MIGRATION_37_38)
                .fallbackToDestructiveMigration()
                .build()

        // نسخة احتياطية من ملفات القاعدة قبل إعادة إنشائها (تبقى على الجهاز للطوارئ)
        private fun backupDbFiles(appCtx: Context) {
            try {
                val dir = java.io.File(appCtx.filesDir, "db_backup_before_38").apply { mkdirs() }
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
