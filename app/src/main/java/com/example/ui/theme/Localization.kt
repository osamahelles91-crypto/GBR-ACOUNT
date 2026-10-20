package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ArabicStrings }

@Composable
fun s(): AppStrings = LocalAppStrings.current

interface AppStrings {
    // General & Splash
    val appName: String
    val systemVersion: String
    val buildDate: String
    val developerInfo: String
    val splashSubtitle: String
    val back: String
    val close: String
    val confirm: String
    val cancel: String
    val save: String
    val edit: String
    val delete: String
    val add: String
    val action: String
    val status: String
    val search: String
    val unknown: String

    // Login Screen
    val loginTitle: String
    val usernameLabel: String
    val passwordLabel: String
    val loginButton: String
    val usernameHint: String
    val passwordHint: String
    val emptyCredentialsError: String
    val invalidCredentialsError: String
    val factoryOperatorTitle: String

    // Header / Top bar
    val helloUser: String
    val activeShift: String
    val morningShift: String
    val eveningShift: String
    val nightShift: String
    val logoutButton: String
    val logoutConfirmTitle: String
    val logoutConfirmBody: String
    val logoutConfirmBtn: String

    // Main Dashboard Deck Selector
    val deckMaterialsTitle: String
    val deckMaterialsSubtitle: String
    val deckFormulationsTitle: String
    val deckFormulationsSubtitle: String
    val deckProductionTitle: String
    val deckProductionSubtitle: String
    val deckReportsTitle: String
    val deckReportsSubtitle: String
    val deckSettingsTitle: String
    val deckSettingsSubtitle: String
    val deckAppInfoTitle: String
    val deckAppInfoSubtitle: String
    val deckRecipeTitle: String
    val deckRecipeSubtitle: String

    // Materials Panel
    val materialsTitle: String
    val addMaterial: String
    val editMaterial: String
    val materialName: String
    val materialCode: String
    val materialUnit: String
    val defaultCost: String
    val searchMaterialPlaceholder: String
    val materialAddedSuccess: String
    val materialUpdatedSuccess: String
    val materialDeletedSuccess: String
    val materialDeleteConfirmTitle: String
    val materialDeleteConfirmBody: String
    val defaultCostPerKg: String

    // Formulations Panel
    val formulationsTitle: String
    val addFormulation: String
    val editFormulation: String
    val formulationName: String
    val formulationCode: String
    val formulationDesc: String
    val viewComponents: String
    val batchMultiplier: String
    val quantityPerTon: String
    val calculatedQuantity: String
    val formulationCategory: String
    val creationDate: String
    val totalWeight: String
    val formulationAddedSuccess: String
    val formulationUpdatedSuccess: String
    val formulationDeletedSuccess: String
    val formulationDeleteConfirmTitle: String
    val formulationDeleteConfirmBody: String

    // Recipe Management
    val recipeTitle: String
    val addRecipeStep: String
    val mixingSpeed: String
    val mixingTime: String
    val stepInstructions: String
    val recipeSavedSuccess: String

    // Production Scheduler Dashboard
    val productionTitle: String
    val productionSubtitle: String
    val productionTabReady: String
    val productionTabInProgress: String
    val productionTabCompleted: String
    val productionTabStats: String
    val createOrderButton: String
    val startProductionRun: String
    val enterBatchSize: String
    val runStepsInstruction: String
    val stepNotDone: String
    val stepDone: String
    val completeAndSave: String
    fun runningRecipe(name: String): String
    fun batchSizeLabel(size: Double): String

    // Statistics Dashboard
    val statsOutputTotal: String
    val statsSpeedAvg: String
    val statsActiveTime: String
    val statsOperatorsCount: String

    // Quality Control & Laboratory Checks
    val qcTitle: String
    val qcViscosity: String
    val qcDensity: String
    val qcPH: String
    val qcAppearance: String
    val qcPass: String
    val qcFail: String
    val qcSaveResults: String
    val qcSavedSuccess: String

    // Packaging / Container Weights
    val packagingTitle: String
    val customPackagingName: String
    val packagingEmptyWeight: String
    val packagingGrossWeight: String
    val packagingAdhesiveCost: String
    val packagingLidCost: String
    val totalPackagesCount: String

    // Cost & Profitability Analysis
    val costsTitle: String
    val directMaterialsCost: String
    val operatingOverheads: String
    val formulaCostPerKg: String
    val totalBatchFinalCost: String
    val profitPercentage: String

    // Settings Categories & Console
    val settingsTitle: String
    val settingsSubtitle: String
    val settingsCatAppProperties: String
    val settingsCatAppPropertiesSub: String
    val settingsCatAppearanceLanguage: String
    val settingsCatAppearanceLanguageSub: String
    val settingsCatDatabase: String
    val settingsCatDatabaseSub: String
    val settingsCatPackaging: String
    val settingsCatPackagingSub: String
    val settingsCatQualityTests: String
    val settingsCatQualityTestsSub: String
    val settingsCatFactory: String
    val settingsCatFactorySub: String
    val settingsCatUsers: String
    val settingsCatUsersSub: String
    val settingsCatAbout: String
    val settingsCatAboutSub: String

    // Settings Details Panels
    val appIdentityTitle: String
    val companyNameLabel: String
    val appTitleLabel: String
    val companyLogoUrlLabel: String
    val companyLogoUrlSub: String
    val appIconUrlLabel: String
    val appIconUrlSub: String
    val loginBgUrlLabel: String
    val loginBgUrlSub: String
    val saveBrandIdentityButton: String
    val saveBrandConfirmTitle: String
    val saveBrandConfirmBody: String
    val appThemeLabel: String
    val dbBackupTitle: String
    val selectiveExportTitle: String
    val selectiveExportSub: String
    val dbImportTitle: String
    val dbImportSub: String
    val preImportCheckTitle: String
    val preImportCheckSub: String

    // Laboratory Panel
    val labAllBranches: String
    val labInstantSingle: String
    val labCalibrateCompare: String
    val labTechnician: String
    val labDate: String
    val labResetFilters: String
    val labDeleteConfirmTitle: String
    val labDeleteConfirmBody: String
    val labSearchPlaceholder: String

    // Equipment Control Panel
    val eqMinutes: String
    val eqSeconds: String
    val eqStartTimer: String
    val eqRenameTitle: String
    val eqDeleteTitle: String
    val eqConnectionStatus: String
    val eqDeviceStatus: String
    val eqStart: String
    val eqStop: String
    val eqTimer: String
    val eqTimeRemaining: String
    val eqReset: String
    val eqNewDevice: String
    val eqDeleteDevice: String
    val eqDeviceNameLabel: String
    val eqDeviceNamePlaceholder: String

    // Operational Alerts Panel
    val altSectionRelated: String
    val altCustomItem: String
    val altAllElements: String
    val altSelectRecipe: String
    val altRecipeApproved: String
    val altNoRecipe: String
    val altApprovedProduct: String
    val altSelectSession: String
    val altSessionItem: String
    val altSeverityLevel: String
    val altSaveAlert: String
    val altCancel: String

    // Sync Monitoring Panel
    val syncButton: String
    val syncMatchingDb: String
    val syncStartCheck: String
    val syncRawMaterials: String
    val syncFormulations: String
    val syncProductionOrders: String
    val syncProductionLogs: String
    val syncRdProjects: String
    val syncLabSessions: String
    val syncCustomPackagings: String
    val syncCustomUsers: String
    val syncConnectingDb: String
    val syncStartFirestoreCheck: String
    val syncInProgress: String
    val syncBidirectionalStart: String
}

object ArabicStrings : AppStrings {
    override val appName = "جي بي أر للدهانات للتشغيل الذكي"
    override val systemVersion = "v3.6.4 (المؤسسة)"
    override val buildDate = "2026-06-07"
    override val developerInfo = "قسم هندسة النظم والتحكم الآلي بمجموعة GBR"
    override val splashSubtitle = "نظام إدارة التركيبات المصنعية والتعبئة وضبط الجودة"
    override val back = "رجوع"
    override val close = "إغلاق"
    override val confirm = "تأكيد"
    override val cancel = "إلغاء"
    override val save = "حفظ"
    override val edit = "تعديل"
    override val delete = "حذف"
    override val add = "إضافة"
    override val action = "الإجراء"
    override val status = "الحالة"
    override val search = "بحث..."
    override val unknown = "غير معروف"

    override val loginTitle = "تسجيل الدخول للنظام"
    override val usernameLabel = "اسم المستخدم"
    override val passwordLabel = "كلمة المرور"
    override val loginButton = "دخول"
    override val usernameHint = "ادخل اسم المستخدم (admin)"
    override val passwordHint = "ادخل كلمة المرور (1234)"
    override val emptyCredentialsError = "خطأ: الرجاء إدخال اسم المستخدم وكلمة المرور التابع لهما التشغيل!"
    override val invalidCredentialsError = "خطأ: اسم المستخدم أو كلمة المرور غير صحيحة!"
    override val factoryOperatorTitle = "لوحة عمل التشغيل والمصنع"

    override val helloUser = "مرحباً يا"
    override val activeShift = "وردية العمل الحالية"
    override val morningShift = "الصباحية (أ)"
    override val eveningShift = "المسائية (ب)"
    override val nightShift = "الليلية (ج)"
    override val logoutButton = "تسجيل الخروج"
    override val logoutConfirmTitle = "تأكيد تسجيل الخروج"
    override val logoutConfirmBody = "هل أنت متأكد من رغبتك في تسجيل الخروج خارج بيئة التشغيل الحالية؟"
    override val logoutConfirmBtn = "تسجيل الخروج الآن"

    override val deckMaterialsTitle = "المواد الخام"
    override val deckMaterialsSubtitle = "أكواد ومسميات مستلزمات الخلط والمُحسنات"
    override val deckFormulationsTitle = "التركيبات"
    override val deckFormulationsSubtitle = "وصفات معجون ودهان ومخازن النسب"
    override val deckProductionTitle = "الإنتاج"
    override val deckProductionSubtitle = "إدارة ومتابعة أوامر ودفعات الإنتاج"
    override val deckReportsTitle = "التقارير"
    override val deckReportsSubtitle = "سجلات الإنتاج وإحصائيات العمليات والكميات"
    override val deckSettingsTitle = "الإعدادات"
    override val deckSettingsSubtitle = "إدارة العبوات وتكاليف التغليف والمستخدمين للتشغيل"
    override val deckAppInfoTitle = "حول النظام"
    override val deckAppInfoSubtitle = "تفاصيل بناء وإصدار لوحة التحكم الآلي"
    override val deckRecipeTitle = "وصفات التشغيل"
    override val deckRecipeSubtitle = "مراحل خلط وخطوات تصنيع الوجبات الذكية"

    override val materialsTitle = "إدارة قائمة المواد الخام"
    override val addMaterial = "إضافة مادة خام جديدة"
    override val editMaterial = "تعديل بيانات مادة خام"
    override val materialName = "اسم المادة الخام"
    override val materialCode = "كود المادة (SKU)"
    override val materialUnit = "الوحدة الافتراضية"
    override val defaultCost = "التكلفة الافتراضية للكجم"
    override val searchMaterialPlaceholder = "ابحث بالاسم أو الكود..."
    override val materialAddedSuccess = "تمت إضافة المادة الخام بنجاح بقاعدة البيانات!"
    override val materialUpdatedSuccess = "تم تحديث بيانات المادة الخام المحددة!"
    override val materialDeletedSuccess = "تم حذف المادة الخام المحددة نهائياً!"
    override val materialDeleteConfirmTitle = "تأكيد حذف مادة"
    override val materialDeleteConfirmBody = "هل أنت متأكد من رغبتك في حذف هذه المادة؟ سيتم إزالتها نهائياً من مخرجات الحساب."
    override val defaultCostPerKg = "التكلفة (شيكل)"

    override val formulationsTitle = "دليل التركيبات المصنعية"
    override val addFormulation = "إضافة تركيبة إنتاجية جديدة"
    override val editFormulation = "تعديل تركيبة معينة"
    override val formulationName = "اسم التركيبة"
    override val formulationCode = "كود التركيبة الكيميائي"
    override val formulationDesc = "المواصفات والتعليمات الاستشارية"
    override val viewComponents = "استعراض المكونات"
    override val batchMultiplier = "حساب كميات وجبة الإنتاج (كجم)"
    override val quantityPerTon = "الكمية لكل طن (1000 كجم)"
    override val calculatedQuantity = "الكمية المطلوبة للوجبة الحالية"
    override val formulationCategory = "الفئة التصنيفية"
    override val creationDate = "تاريخ الإنشاء"
    override val totalWeight = "إجمالي الوزن"
    override val formulationAddedSuccess = "تم تسجيل وحفظ التركيبة المصنعية الجديدة!"
    override val formulationUpdatedSuccess = "تم تحديث وتعديل التركيبة بنجاح!"
    override val formulationDeletedSuccess = "تم حذف التركيبة بنجاح من الهيكل العام!"
    override val formulationDeleteConfirmTitle = "تأكيد حذف تركيبة"
    override val formulationDeleteConfirmBody = "هل تود تأكيد حذف هذه التركيبة نهائياً من قاعدة البيانات؟"

    override val recipeTitle = "إدارة خطوات ووصفات التشغيل"
    override val addRecipeStep = "إضافة مرحلة خلط جديدة"
    override val mixingSpeed = "سرعة الخلاط المطلوبة (دورة/دقيقة)"
    override val mixingTime = "زمن الخلط المستهدف (دقيقة)"
    override val stepInstructions = "تعليمات وتعليقات المرحلة"
    override val recipeSavedSuccess = "تم حفظ وتعديل وصفات تشغيل الوجبة بنجاح!"

    override val productionTitle = "مركز إدارة وجدولة الإنتاج"
    override val productionSubtitle = "توجيه وتشغيل مباشر ومتابعة سير الدفعات"
    override val productionTabReady = "جاهز وبانتظار البدء"
    override val productionTabInProgress = "قيد المعالجة النشطة"
    override val productionTabCompleted = "منتهي ومؤرشف"
    override val productionTabStats = "إحصائيات اليوم"
    override val createOrderButton = "أمر إنتاج جديد"
    override val startProductionRun = "بدء تشغيل أمر الإنتاج"
    override val enterBatchSize = "حدد وزن الوجبة الكلي المطلوب للإنتاج (كجم):"
    override val runStepsInstruction = "اتبع خطوات الوزن والإضافة بالتتابع وعلم على الخطوة المنتهية لحماية الجودة:"
    override val stepNotDone = "انتظار المعالجة"
    override val stepDone = "تم الإضافة والوزن"
    override val completeAndSave = "إكمال وإنهاء المعالجة وحفظ الوجبة بالكامل"
    override fun runningRecipe(name: String): String = "جاري تحضير خلطة كيميائية: $name"
    override fun batchSizeLabel(size: Double): String = "وزن الوجبة الكلي المستهدف: $size كجم"

    override val statsOutputTotal = "إجمالي مخرجات الدفعات"
    override val statsSpeedAvg = "متوسط سرعة تشغيل الدفعات"
    override val statsActiveTime = "زمن المعالجة الفعلي"
    override val statsOperatorsCount = "العمال المرتبطين بالتشغيل"

    override val qcTitle = "سجل فحوصات مطابقة الجودة الاستباقية"
    override val qcViscosity = "فحص اللزوجة الحركية (cP)"
    override val qcDensity = "فحص الكثافة المطلقة (g/cm³)"
    override val qcPH = "فحص درجة القلوية (pH Value)"
    override val qcAppearance = "اللون والمظهر الميكانيكي"
    override val qcPass = "مقبول ومطابق"
    override val qcFail = "مرفوض ومخالف"
    override val qcSaveResults = "حفظ وطلب الإقرار النهائي للجودة"
    override val qcSavedSuccess = "تم توثيق وحفظ فحوصات مطابقة الجودة بنجاح!"

    override val packagingTitle = "تهيئة مقاسات وأوعية التعبئة"
    override val customPackagingName = "اسم الغلاف أو الوعاء"
    override val packagingEmptyWeight = "الوزن الفارغ للغطاء والوعاء (جم)"
    override val packagingGrossWeight = "الوزن المستهدف الإجمالي المعبأ (كجم)"
    override val packagingAdhesiveCost = "تكلفة الملصق والأشرطة"
    override val packagingLidCost = "تكلفة الغطاء والتشطيب"
    override val totalPackagesCount = "إجمالي العبوات المخرجة للدفعة"

    override val costsTitle = "الاحتساب المالي والأرباح التقديرية"
    override val directMaterialsCost = "تكلفة المواد الخام المباشرة"
    override val operatingOverheads = "مصاريف تشغيلية إضافية للوجبة"
    override val formulaCostPerKg = "التكلفة الكيميائية الصافية لكل كيلو"
    override val totalBatchFinalCost = "إجمالي التكلفة الكلية مع التعبئة"
    override val profitPercentage = "نسبة هامش الربح المحقق (%)"

    override val settingsTitle = "خصائص التشغيل وتهيئة البيئة"
    override val settingsSubtitle = "التحكم بالنظام وإدارة قاعدة البيانات وهوية المصنع"
    override val settingsCatAppProperties = "خصائص التطبيق"
    override val settingsCatAppPropertiesSub = "التحكم باسم الشركة، اسم التطبيق، والشعارات وخلفيات الدخول"
    override val settingsCatAppearanceLanguage = "المظهر واللغة"
    override val settingsCatAppearanceLanguageSub = "تعديل لغة النظام بالكامل للتشغيل واختيار السمة العامة"
    override val settingsCatDatabase = "إدارة قاعدة البيانات"
    override val settingsCatDatabaseSub = "عمل نسخ احتياطي، تصدير واستيراد ذكي دقيق للمكونات والتركيبات"
    override val settingsCatPackaging = "العبوات والتغليف"
    override val settingsCatPackagingSub = "إدارة أوزان التعبئة، وتكاليف الأوعية البلاستيكية المعتمدة"
    override val settingsCatQualityTests = "الفحوصات العادية"
    override val settingsCatQualityTestsSub = "إدارة فحوصات الجودة ومواصفاتها كالمظهر واللزوجة والنطاق الآمن"
    override val settingsCatFactory = "إعدادات المصنع"
    override val settingsCatFactorySub = "ربط وتكوين خطوط الإنتاج والورديات وسجلات الآلات"
    override val settingsCatUsers = "المستخدمون والوصول"
    override val settingsCatUsersSub = "تخصيص مستويات الوصول وصلاحيات الفنيين والمشرفين"
    override val settingsCatAbout = "حول التطبيق"
    override val settingsCatAboutSub = "معلومات البناء، المطور، وحالة النواة وفحص استقرار الـ SQLite"

    override val appIdentityTitle = "تخصيص هوية التطبيق وتعديل الشعار"
    override val companyNameLabel = "اسم الشركة الرسمي"
    override val appTitleLabel = "اسم التطبيق في الواجهات"
    override val companyLogoUrlLabel = "رابط شعار الشركة"
    override val companyLogoUrlSub = "المواصفات: PNG أو JPG | خلفية شفافة | المقاس المقترح 512×512"
    override val appIconUrlLabel = "رابط أيقونة التطبيق"
    override val appIconUrlSub = "المواصفات: PNG فقط | مربعة | المقاس المقترح 1024×1024"
    override val loginBgUrlLabel = "رابط خلفية تسجيل الدخول"
    override val loginBgUrlSub = "المواصفات: نسبة شاشة مناسبة | الحجم المقترح 1920×1080"
    override val saveBrandIdentityButton = "تحديث هوية وشعار التطبيق 💾"
    override val saveBrandConfirmTitle = "تأكيد تعديل الهوية"
    override val saveBrandConfirmBody = "هل أنت متأكد من رغبتك في تطبيق التغييرات على هوية ومسميات التطبيق؟"
    override val appThemeLabel = "مظهر التطبيق (السمة)"
    override val dbBackupTitle = "نسخ احتياطي وإدارة البيانات المتقدمة"
    override val selectiveExportTitle = "تصدير البيانات المحددة الانتقائي"
    override val selectiveExportSub = "حدد الأقسام التي تود تجميعها وتشفيرها في ملف نسخ احتياطي"
    override val dbImportTitle = "محلل ومطابقة ملفات استيراد البيانات"
    override val dbImportSub = "الصق الكود البرمجي لمطابقة ودمج البيانات بشكل آمن متكافئ"
    override val preImportCheckTitle = "المطابقة التبادلية للعناصر المكتشفة بالملف"
    override val preImportCheckSub = "توضيح العناصر ومحتويات النسخة المحملة من المواد والتركيبات"

    // Laboratory Panel
    override val labAllBranches = "🔬 جميع الفروع"
    override val labInstantSingle = "🧪 فحص أحادي فورى"
    override val labCalibrateCompare = "⚖️ معايرة ومقارنة عينات"
    override val labTechnician = "الفني المسؤول 👤"
    override val labDate = "التاريخ YYYY-MM-DD"
    override val labResetFilters = "إعادة تعيين الفلاتر 🔄"
    override val labDeleteConfirmTitle = "تأكيد حذف المرفق ⚠️"
    override val labDeleteConfirmBody = "هل أنت متأكد من حذف هذا المرفق نهائياً؟ سيتم إزالته بالكامل من واجهة الاستخدام، ومن قاعدة البيانات المحلية، ومسحه في نفس اللحظة من السيرفر السحابي (lab) لمنع بقاء أي ملفات يتيمة."
    override val labSearchPlaceholder = "البحث برقم الجلسة، اسم العينة أو القرار... 🔍"

    // Equipment Control Panel
    override val eqMinutes = "دقائق"
    override val eqSeconds = "ثواني"
    override val eqStartTimer = "بدء المؤقت 🧭"
    override val eqRenameTitle = "تعديل اسم الماكينة"
    override val eqDeleteTitle = "حذف الماكينة نهائياً"
    override val eqConnectionStatus = "حالة الاتصال"
    override val eqDeviceStatus = "حالة الجهاز"
    override val eqStart = "تشغيل ⚡"
    override val eqStop = "إيقاف 🛑"
    override val eqTimer = "مؤقت ⏱️"
    override val eqTimeRemaining = "الوقت المتبقي حتى إيقاف الجهاز تلقائياً:"
    override val eqReset = "إعادة ضبط 🔄"
    override val eqNewDevice = "جهاز جديد"
    override val eqDeleteDevice = "حذف الجهاز ×"
    override val eqDeviceNameLabel = "الاسم التعريفي للجهاز بالمصنع"
    override val eqDeviceNamePlaceholder = "مثلاً: الخلاط الميكانيكي الرئيسي"

    // Operational Alerts Panel
    override val altSectionRelated = "القسم المرتبط بالدائرة التشغيلية:"
    override val altCustomItem = "تخصيص لعنصر تشغيلي محدد (اختياري):"
    override val altAllElements = "جميع العناصر في هذا القسم"
    override val altSelectRecipe = "--- اختر تركيبة محددة ---"
    override val altRecipeApproved = "--- التركيبات المعتمدة للإنتاج ---"
    override val altNoRecipe = "لا توجد تركيبات معتمدة حالياً ⚠️"
    override val altApprovedProduct = "منتج:"
    override val altSelectSession = "--- اختر جلسة محددة ---"
    override val altSessionItem = "جلسة:"
    override val altSeverityLevel = "مستوى التنبيه ومستوى الأهمية للعمال:"
    override val altSaveAlert = "حفظ وتعميم التنبيه"
    override val altCancel = "إلغاء"

    // Sync Monitoring Panel
    override val syncButton = "مزامنة 🔄"
    override val syncMatchingDb = "جاري مطابقة اتساق قواعد البيانات..."
    override val syncStartCheck = "بدء تشخيص تدقيق الاتساق السحابي الفوري 🛡️"
    override val syncRawMaterials = "المواد الخام (Raw Materials):"
    override val syncFormulations = "التركيبات (Formulations):"
    override val syncProductionOrders = "طلبات الإنتاج (Production Orders):"
    override val syncProductionLogs = "سجلات وجبات التشغيل (Production Logs):"
    override val syncRdProjects = "أبحاث وعينات التطوير (R&D Projects):"
    override val syncLabSessions = "فحوصات الجودة والمختبر (Lab Sessions):"
    override val syncCustomPackagings = "خيارات عبوات التغليف (Custom Packagings):"
    override val syncCustomUsers = "مستخدمي وصلاحيات النظام (Custom Users):"
    override val syncConnectingDb = "جاري الاتصال بقاعدة البيانات..."
    override val syncStartFirestoreCheck = "بدء فحص الاتصال اللاسلكي الفوري بـ Firestore"
    override val syncInProgress = "مزامنة السحابة جارية..."
    override val syncBidirectionalStart = "بدء مزامنة كاملة فورية ثنائية التوجه (Upload / Download)"
}

object EnglishStrings : AppStrings {
    override val appName = "GBR Paints Smart Operations"
    override val systemVersion = "v3.6.4 (Enterprise)"
    override val buildDate = "2026-06-07"
    override val developerInfo = "GBR Systems Engineering & Automation Division"
    override val splashSubtitle = "Formulation, Packaging & Quality Control Management Suite"
    override val back = "Back"
    override val close = "Close"
    override val confirm = "Confirm"
    override val cancel = "Cancel"
    override val save = "Save"
    override val edit = "Edit"
    override val delete = "Delete"
    override val add = "Add"
    override val action = "Action"
    override val status = "Status"
    override val search = "Search..."
    override val unknown = "Unknown"

    override val loginTitle = "System Secure Login"
    override val usernameLabel = "Username"
    override val passwordLabel = "Password"
    override val loginButton = "Login"
    override val usernameHint = "Enter username (admin)"
    override val passwordHint = "Enter password (1234)"
    override val emptyCredentialsError = "Error: Please enter shift credentials!"
    override val invalidCredentialsError = "Error: Invalid username or password!"
    override val factoryOperatorTitle = "Factory Operator Control Board"

    override val helloUser = "Welcome,"
    override val activeShift = "Current Shift"
    override val morningShift = "Morning (A)"
    override val eveningShift = "Evening (B)"
    override val nightShift = "Night (C)"
    override val logoutButton = "Logout"
    override val logoutConfirmTitle = "Confirm Logout"
    override val logoutConfirmBody = "Are you sure you want to end your current session and logout?"
    override val logoutConfirmBtn = "Logout Now"

    override val deckMaterialsTitle = "Raw Materials"
    override val deckMaterialsSubtitle = "SKUs, names and default chemical prices"
    override val deckFormulationsTitle = "Formulations"
    override val deckFormulationsSubtitle = "Paints, putties recipes and ingredient ratios"
    override val deckProductionTitle = "Production"
    override val deckProductionSubtitle = "Start, monitor and execute batch operations"
    override val deckReportsTitle = "Reports"
    override val deckReportsSubtitle = "Quantitative logs and historical process journals"
    override val deckSettingsTitle = "Settings"
    override val deckSettingsSubtitle = "Manage packaging volumes, direct costs & access controls"
    override val deckAppInfoTitle = "About Suite"
    override val deckAppInfoSubtitle = "Build details, developer info and kernel telemetry"
    override val deckRecipeTitle = "Batch Steps"
    override val deckRecipeSubtitle = "Mixing speeds, recipe durations and step instructions"

    override val materialsTitle = "Materials Inventory Index"
    override val addMaterial = "Add New Raw Material"
    override val editMaterial = "Edit Raw Material Details"
    override val materialName = "Material Name"
    override val materialCode = "Material Code (SKU)"
    override val materialUnit = "Default Base Unit"
    override val defaultCost = "Default Cost per Kg"
    override val searchMaterialPlaceholder = "Search by code or title..."
    override val materialAddedSuccess = "Raw material successfully registered!"
    override val materialUpdatedSuccess = "Raw material details updated!"
    override val materialDeletedSuccess = "Raw material completely deleted!"
    override val materialDeleteConfirmTitle = "Confirm Material Deletion"
    override val materialDeleteConfirmBody = "Are you sure you want to delete this material? This will completely remove it from calculation metrics."
    override val defaultCostPerKg = "Cost (ILS)"

    override val formulationsTitle = "Formulation Specifications"
    override val addFormulation = "Create Production Formulation"
    override val editFormulation = "Edit Formulation Specifications"
    override val formulationName = "Formulation Title"
    override val formulationCode = "Formulation Code"
    override val formulationDesc = "Production & Handling Guide"
    override val viewComponents = "Review Components"
    override val batchMultiplier = "Evaluate Batch Mass (Kg)"
    override val quantityPerTon = "Ratio per Ton (1000 Kg)"
    override val calculatedQuantity = "Calculated Weight for Batch"
    override val formulationCategory = "Category Class"
    override val creationDate = "Creation Date"
    override val totalWeight = "Total Mass"
    override val formulationAddedSuccess = "New chemical formulation created successfully!"
    override val formulationUpdatedSuccess = "Formulation details updated!"
    override val formulationDeletedSuccess = "Formulation removed from general database!"
    override val formulationDeleteConfirmTitle = "Confirm Formulation Deletion"
    override val formulationDeleteConfirmBody = "Are you sure you want to delete this formulation recipe?"

    override val recipeTitle = "Batch Step & Recipe Setup"
    override val addRecipeStep = "Insert Machine Phase Step"
    override val mixingSpeed = "Speed Requirement (RPM)"
    override val mixingTime = "Duration Target (Minutes)"
    override val stepInstructions = "Operational Phase Guidelines"
    override val recipeSavedSuccess = "Batch formulation recipe instructions updated!"

    override val productionTitle = "Scheduling & Production Console"
    override val productionSubtitle = "Live dispatching, operator guides & active runs"
    override val productionTabReady = "Queued Orders"
    override val productionTabInProgress = "Active Operations"
    override val productionTabCompleted = "Archived & Verified"
    override val productionTabStats = "Today's Output"
    override val createOrderButton = "Start Dispatch Order"
    override val startProductionRun = "Dispatch Production Run"
    override val enterBatchSize = "State requested batch size (Kg) for operations:"
    override val runStepsInstruction = "Conclude addition and weighing steps in chronological order to preserve QC bounds:"
    override val stepNotDone = "Awaiting Addition"
    override val stepDone = "Confirmed Added"
    override val completeAndSave = "Finalize Batch Run and Store in System"
    override fun runningRecipe(name: String): String = "Operating chemical blend process: $name"
    override fun batchSizeLabel(size: Double): String = "Requested Batch Target: $size Kg"

    override val statsOutputTotal = "Batch Production Volume"
    override val statsSpeedAvg = "Mean Active Speed Volume"
    override val statsActiveTime = "Active Process Timers"
    override val statsOperatorsCount = "Linked Assigned Operators"

    override val qcTitle = "Preserved Batch Quality Control Records"
    override val qcViscosity = "Dynamic Viscosity (cP)"
    override val qcDensity = "Density Mass (g/cm³)"
    override val qcPH = "Alkalinity Test (pH Value)"
    override val qcAppearance = "Physical Finish & Color"
    override val qcPass = "Acceptable Pass"
    override val qcFail = "Failed Bounds"
    override val qcSaveResults = "Store Results & Request QA Approval"
    override val qcSavedSuccess = "Quality Control measurements documented!"

    override val packagingTitle = "Configure Authorized Standard Pack Sizes"
    override val customPackagingName = "Container Title"
    override val packagingEmptyWeight = "Empty Container Mass (g)"
    override val packagingGrossWeight = "Target Packed Mass (Kg)"
    override val packagingAdhesiveCost = "Label & Adhesive Cost"
    override val packagingLidCost = "Cap & Closure Cost"
    override val totalPackagesCount = "Total Containers Dispatched"

    override val costsTitle = "Financial Metrics & ROI Audit"
    override val directMaterialsCost = "Direct Chemical Costs"
    override val operatingOverheads = "Machine & Labor Overheads"
    override val formulaCostPerKg = "Net Paints Cost per Kilogram"
    override val totalBatchFinalCost = "Final Finished Product Cost"
    override val profitPercentage = "Target Operating Markup (%)"

    override val settingsTitle = "Operation Settings & Management"
    override val settingsSubtitle = "System preferences, database diagnostics and layout identity"
    override val settingsCatAppProperties = "App Characteristics"
    override val settingsCatAppPropertiesSub = "Manage branding titles, corporate logos, and splash landscapes"
    override val settingsCatAppearanceLanguage = "Appearance & Language"
    override val settingsCatAppearanceLanguageSub = "Fully adapt layout, labels, and local colors dynamically"
    override val settingsCatDatabase = "Data Storage Console"
    override val settingsCatDatabaseSub = "Backup exports, precise imports and transaction recovery"
    override val settingsCatPackaging = "Packaging Containers"
    override val settingsCatPackagingSub = "Adjust weights and margins for plastic buckets"
    override val settingsCatQualityTests = "Audit Thresholds"
    override val settingsCatQualityTestsSub = "Limit safe operating bounds for density, viscosity and pH"
    override val settingsCatFactory = "Factory Parameters"
    override val settingsCatFactorySub = "Assign hardware mixers, active lines and shift rosters"
    override val settingsCatUsers = "Security & Accounts"
    override val settingsCatUsersSub = "Configure roles to restrict operator and laboratory scopes"
    override val settingsCatAbout = "About Console"
    override val settingsCatAboutSub = "Validate SQLite integrity check states, kernel levels and developer details"

    override val appIdentityTitle = "App Interface Branding & Identity Design"
    override val companyNameLabel = "Official Corporate Name"
    override val appTitleLabel = "Display Title in System UI"
    override val companyLogoUrlLabel = "Company Logo URL"
    override val companyLogoUrlSub = "Specs: PNG or JPG | transparent canvas | recommended 512x512"
    override val appIconUrlLabel = "Launcher Logo URL"
    override val appIconUrlSub = "Specs: Square PNG Only | recommended 1024x1024 px"
    override val loginBgUrlLabel = "Splash/Login Background Image"
    override val loginBgUrlSub = "Specs: Landscaped wide background | recommended 1920x1080 px"
    override val saveBrandIdentityButton = "Save App Identity & Redraw UI 💾"
    override val saveBrandConfirmTitle = "Confirm App Branding Changes"
    override val saveBrandConfirmBody = "Are you sure you want to apply system rebranding across UI containers?"
    override val appThemeLabel = "General Application Theme"
    override val dbBackupTitle = "Advanced Database Console & Backup Services"
    override val selectiveExportTitle = "Selective Backup Compilation"
    override val selectiveExportSub = "Select specific entity tables to aggregate and compile into text payload"
    override val dbImportTitle = "Smart Data Importer & Parser"
    override val dbImportSub = "Paste verified system backup key below to seamlessly merge components"
    override val preImportCheckTitle = "Pre-Import Conflict Registry"
    override val preImportCheckSub = "Acknowledge quantity of incoming entities from parsed payload"

    // Laboratory Panel
    override val labAllBranches = "🔬 All Branches"
    override val labInstantSingle = "🧪 Instant Single Test"
    override val labCalibrateCompare = "⚖️ Calibrate & Compare Samples"
    override val labTechnician = "Responsible Technician 👤"
    override val labDate = "Date YYYY-MM-DD"
    override val labResetFilters = "Reset Filters 🔄"
    override val labDeleteConfirmTitle = "Confirm Attachment Deletion ⚠️"
    override val labDeleteConfirmBody = "Are you sure you want to delete this attachment permanently? It will be completely removed from the UI, local database, and cloud servers instantly to prevent orphaned files."
    override val labSearchPlaceholder = "Search by session, sample name, or decision... 🔍"

    // Equipment Control Panel
    override val eqMinutes = "Minutes"
    override val eqSeconds = "Seconds"
    override val eqStartTimer = "Start Timer 🧭"
    override val eqRenameTitle = "Edit Device Name"
    override val eqDeleteTitle = "Delete Device Permanently"
    override val eqConnectionStatus = "Connection Status"
    override val eqDeviceStatus = "Device Status"
    override val eqStart = "Start ⚡"
    override val eqStop = "Stop 🛑"
    override val eqTimer = "Timer ⏱️"
    override val eqTimeRemaining = "Time remaining until automatic shutdown:"
    override val eqReset = "Reset 🔄"
    override val eqNewDevice = "New Device"
    override val eqDeleteDevice = "Delete Device ×"
    override val eqDeviceNameLabel = "Device Identifier in Factory"
    override val eqDeviceNamePlaceholder = "e.g., Main Mechanical Mixer"

    // Operational Alerts Panel
    override val altSectionRelated = "Section Related to Operational Department:"
    override val altCustomItem = "Assign to specific operational item (Optional):"
    override val altAllElements = "All elements in this section"
    override val altSelectRecipe = "--- Select Specific Recipe ---"
    override val altRecipeApproved = "--- Approved Recipes for Production ---"
    override val altNoRecipe = "No approved recipes currently ⚠️"
    override val altApprovedProduct = "Product:"
    override val altSelectSession = "--- Select Specific Session ---"
    override val altSessionItem = "Session:"
    override val altSeverityLevel = "Alert level & severity for operators:"
    override val altSaveAlert = "Save & Broadcast Alert"
    override val altCancel = "Cancel"

    // Sync Monitoring Panel
    override val syncButton = "Sync 🔄"
    override val syncMatchingDb = "Matching database consistency..."
    override val syncStartCheck = "Start Instant Cloud Consistency Audit 🛡️"
    override val syncRawMaterials = "Raw Materials:"
    override val syncFormulations = "Formulations:"
    override val syncProductionOrders = "Production Orders:"
    override val syncProductionLogs = "Production Logs:"
    override val syncRdProjects = "R&D Projects:"
    override val syncLabSessions = "Lab Sessions:"
    override val syncCustomPackagings = "Custom Packagings:"
    override val syncCustomUsers = "Custom Users:"
    override val syncConnectingDb = "Connecting to database..."
    override val syncStartFirestoreCheck = "Start instant wireless check with Firestore"
    override val syncInProgress = "Cloud sync in progress..."
    override val syncBidirectionalStart = "Start full instant bidirectional sync (Upload / Download)"
}

object JsonI18n {
    private var currentLang: String = "ar"
    private var stringsMap: Map<String, String> = emptyMap()

    fun load(context: android.content.Context, lang: String) {
        currentLang = lang
        try {
            val fileName = "strings_$lang.json"
            val inputStream: java.io.InputStream = context.assets.open(fileName)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = org.json.JSONObject(jsonString)
            val map = mutableMapOf<String, String>()
            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonObject.getString(key)
            }
            stringsMap = map
            android.util.Log.d("JsonI18n", "Loaded language: $lang. Key deck_materials_title is: ${map["deck_materials_title"]}")
        } catch (e: Exception) {
            android.util.Log.e("JsonI18n", "Error loading language $lang", e)
            e.printStackTrace()
        }
    }

    fun t(key: String, defaultValue: String = ""): String {
        return stringsMap[key] ?: defaultValue
    }

    fun tFormat(key: String, args: Map<String, String>): String {
        var result = stringsMap[key] ?: ""
        args.forEach { (token, value) -> result = result.replace("{$token}", value) }
        return result
    }
}

class JsonAppStrings(val lang: String, context: android.content.Context) : AppStrings {
    init {
        JsonI18n.load(context, lang)
    }
    override val appName: String get() = JsonI18n.t("app_name", ArabicStrings.appName)
    override val systemVersion: String get() = JsonI18n.t("system_version", ArabicStrings.systemVersion)
    override val buildDate: String get() = JsonI18n.t("build_date", ArabicStrings.buildDate)
    override val developerInfo: String get() = JsonI18n.t("developer_info", ArabicStrings.developerInfo)
    override val splashSubtitle: String get() = JsonI18n.t("splash_subtitle", ArabicStrings.splashSubtitle)
    override val back: String get() = JsonI18n.t("back", ArabicStrings.back)
    override val close: String get() = JsonI18n.t("close", ArabicStrings.close)
    override val confirm: String get() = JsonI18n.t("confirm", ArabicStrings.confirm)
    override val cancel: String get() = JsonI18n.t("cancel", ArabicStrings.cancel)
    override val save: String get() = JsonI18n.t("save", ArabicStrings.save)
    override val edit: String get() = JsonI18n.t("edit", ArabicStrings.edit)
    override val delete: String get() = JsonI18n.t("delete", ArabicStrings.delete)
    override val add: String get() = JsonI18n.t("add", ArabicStrings.add)
    override val action: String get() = JsonI18n.t("action", ArabicStrings.action)
    override val status: String get() = JsonI18n.t("status", ArabicStrings.status)
    override val search: String get() = JsonI18n.t("search", ArabicStrings.search)
    override val unknown: String get() = JsonI18n.t("unknown", ArabicStrings.unknown)

    override val loginTitle: String get() = JsonI18n.t("login_title", ArabicStrings.loginTitle)
    override val usernameLabel: String get() = JsonI18n.t("username_label", ArabicStrings.usernameLabel)
    override val passwordLabel: String get() = JsonI18n.t("password_label", ArabicStrings.passwordLabel)
    override val loginButton: String get() = JsonI18n.t("login_button", ArabicStrings.loginButton)
    override val usernameHint: String get() = JsonI18n.t("username_hint", ArabicStrings.usernameHint)
    override val passwordHint: String get() = JsonI18n.t("password_hint", ArabicStrings.passwordHint)
    override val emptyCredentialsError: String get() = JsonI18n.t("empty_credentials_error", ArabicStrings.emptyCredentialsError)
    override val invalidCredentialsError: String get() = JsonI18n.t("invalid_credentials_error", ArabicStrings.invalidCredentialsError)
    override val factoryOperatorTitle: String get() = JsonI18n.t("factory_operator_title", ArabicStrings.factoryOperatorTitle)

    override val helloUser: String get() = JsonI18n.t("hello_user", ArabicStrings.helloUser)
    override val activeShift: String get() = JsonI18n.t("active_shift", ArabicStrings.activeShift)
    override val morningShift: String get() = JsonI18n.t("morning_shift", ArabicStrings.morningShift)
    override val eveningShift: String get() = JsonI18n.t("evening_shift", ArabicStrings.eveningShift)
    override val nightShift: String get() = JsonI18n.t("night_shift", ArabicStrings.nightShift)
    override val logoutButton: String get() = JsonI18n.t("logout_button", ArabicStrings.logoutButton)
    override val logoutConfirmTitle: String get() = JsonI18n.t("logout_confirm_title", ArabicStrings.logoutConfirmTitle)
    override val logoutConfirmBody: String get() = JsonI18n.t("logout_confirm_body", ArabicStrings.logoutConfirmBody)
    override val logoutConfirmBtn: String get() = JsonI18n.t("logout_confirm_btn", ArabicStrings.logoutConfirmBtn)

    override val deckMaterialsTitle: String get() = JsonI18n.t("deck_materials_title", ArabicStrings.deckMaterialsTitle)
    override val deckMaterialsSubtitle: String get() = JsonI18n.t("deck_materials_subtitle", ArabicStrings.deckMaterialsSubtitle)
    override val deckFormulationsTitle: String get() = JsonI18n.t("deck_formulations_title", ArabicStrings.deckFormulationsTitle)
    override val deckFormulationsSubtitle: String get() = JsonI18n.t("deck_formulations_subtitle", ArabicStrings.deckFormulationsSubtitle)
    override val deckProductionTitle: String get() = JsonI18n.t("deck_production_title", ArabicStrings.deckProductionTitle)
    override val deckProductionSubtitle: String get() = JsonI18n.t("deck_production_subtitle", ArabicStrings.deckProductionSubtitle)
    override val deckReportsTitle: String get() = JsonI18n.t("deck_reports_title", ArabicStrings.deckReportsTitle)
    override val deckReportsSubtitle: String get() = JsonI18n.t("deck_reports_subtitle", ArabicStrings.deckReportsSubtitle)
    override val deckSettingsTitle: String get() = JsonI18n.t("deck_settings_title", ArabicStrings.deckSettingsTitle)
    override val deckSettingsSubtitle: String get() = JsonI18n.t("deck_settings_subtitle", ArabicStrings.deckSettingsSubtitle)
    override val deckAppInfoTitle: String get() = JsonI18n.t("deck_app_info_title", ArabicStrings.deckAppInfoTitle)
    override val deckAppInfoSubtitle: String get() = JsonI18n.t("deck_app_info_subtitle", ArabicStrings.deckAppInfoSubtitle)
    override val deckRecipeTitle: String get() = JsonI18n.t("deck_recipe_title", ArabicStrings.deckRecipeTitle)
    override val deckRecipeSubtitle: String get() = JsonI18n.t("deck_recipe_subtitle", ArabicStrings.deckRecipeSubtitle)

    override val materialsTitle: String get() = JsonI18n.t("materials_title", ArabicStrings.materialsTitle)
    override val addMaterial: String get() = JsonI18n.t("add_material", ArabicStrings.addMaterial)
    override val editMaterial: String get() = JsonI18n.t("edit_material", ArabicStrings.editMaterial)
    override val materialName: String get() = JsonI18n.t("material_name", ArabicStrings.materialName)
    override val materialCode: String get() = JsonI18n.t("material_code", ArabicStrings.materialCode)
    override val materialUnit: String get() = JsonI18n.t("material_unit", ArabicStrings.materialUnit)
    override val defaultCost: String get() = JsonI18n.t("default_cost", ArabicStrings.defaultCost)
    override val searchMaterialPlaceholder: String get() = JsonI18n.t("search_material_placeholder", ArabicStrings.searchMaterialPlaceholder)
    override val materialAddedSuccess: String get() = JsonI18n.t("material_added_success", ArabicStrings.materialAddedSuccess)
    override val materialUpdatedSuccess: String get() = JsonI18n.t("material_updated_success", ArabicStrings.materialUpdatedSuccess)
    override val materialDeletedSuccess: String get() = JsonI18n.t("material_deleted_success", ArabicStrings.materialDeletedSuccess)
    override val materialDeleteConfirmTitle: String get() = JsonI18n.t("material_delete_confirm_title", ArabicStrings.materialDeleteConfirmTitle)
    override val materialDeleteConfirmBody: String get() = JsonI18n.t("material_delete_confirm_body", ArabicStrings.materialDeleteConfirmBody)
    override val defaultCostPerKg: String get() = JsonI18n.t("default_cost_per_kg", ArabicStrings.defaultCostPerKg)

    override val formulationsTitle: String get() = JsonI18n.t("formulations_title", ArabicStrings.formulationsTitle)
    override val addFormulation: String get() = JsonI18n.t("add_formulation", ArabicStrings.addFormulation)
    override val editFormulation: String get() = JsonI18n.t("edit_formulation", ArabicStrings.editFormulation)
    override val formulationName: String get() = JsonI18n.t("formulation_name", ArabicStrings.formulationName)
    override val formulationCode: String get() = JsonI18n.t("formulation_code", ArabicStrings.formulationCode)
    override val formulationDesc: String get() = JsonI18n.t("formulation_desc", ArabicStrings.formulationDesc)
    override val viewComponents: String get() = JsonI18n.t("view_components", ArabicStrings.viewComponents)
    override val batchMultiplier: String get() = JsonI18n.t("batch_multiplier", ArabicStrings.batchMultiplier)
    override val quantityPerTon: String get() = JsonI18n.t("quantity_per_ton", ArabicStrings.quantityPerTon)
    override val calculatedQuantity: String get() = JsonI18n.t("calculated_quantity", ArabicStrings.calculatedQuantity)
    override val formulationCategory: String get() = JsonI18n.t("formulation_category", ArabicStrings.formulationCategory)
    override val creationDate: String get() = JsonI18n.t("creation_date", ArabicStrings.creationDate)
    override val totalWeight: String get() = JsonI18n.t("total_weight", ArabicStrings.totalWeight)
    override val formulationAddedSuccess: String get() = JsonI18n.t("formulation_added_success", ArabicStrings.formulationAddedSuccess)
    override val formulationUpdatedSuccess: String get() = JsonI18n.t("formulation_updated_success", ArabicStrings.formulationUpdatedSuccess)
    override val formulationDeletedSuccess: String get() = JsonI18n.t("formulation_deleted_success", ArabicStrings.formulationDeletedSuccess)
    override val formulationDeleteConfirmTitle: String get() = JsonI18n.t("formulation_delete_confirm_title", ArabicStrings.formulationDeleteConfirmTitle)
    override val formulationDeleteConfirmBody: String get() = JsonI18n.t("formulation_delete_confirm_body", ArabicStrings.formulationDeleteConfirmBody)

    override val recipeTitle: String get() = JsonI18n.t("recipe_title", ArabicStrings.recipeTitle)
    override val addRecipeStep: String get() = JsonI18n.t("add_recipe_step", ArabicStrings.addRecipeStep)
    override val mixingSpeed: String get() = JsonI18n.t("mixing_speed", ArabicStrings.mixingSpeed)
    override val mixingTime: String get() = JsonI18n.t("mixing_time", ArabicStrings.mixingTime)
    override val stepInstructions: String get() = JsonI18n.t("step_instructions", ArabicStrings.stepInstructions)
    override val recipeSavedSuccess: String get() = JsonI18n.t("recipe_saved_success", ArabicStrings.recipeSavedSuccess)

    override val productionTitle: String get() = JsonI18n.t("production_title", ArabicStrings.productionTitle)
    override val productionSubtitle: String get() = JsonI18n.t("production_subtitle", ArabicStrings.productionSubtitle)
    override val productionTabReady: String get() = JsonI18n.t("production_tab_ready", ArabicStrings.productionTabReady)
    override val productionTabInProgress: String get() = JsonI18n.t("production_tab_in_progress", ArabicStrings.productionTabInProgress)
    override val productionTabCompleted: String get() = JsonI18n.t("production_tab_completed", ArabicStrings.productionTabCompleted)
    override val productionTabStats: String get() = JsonI18n.t("production_tab_stats", ArabicStrings.productionTabStats)
    override val createOrderButton: String get() = JsonI18n.t("create_order_button", ArabicStrings.createOrderButton)
    override val startProductionRun: String get() = JsonI18n.t("start_production_run", ArabicStrings.startProductionRun)
    override val enterBatchSize: String get() = JsonI18n.t("enter_batch_size", ArabicStrings.enterBatchSize)
    override val runStepsInstruction: String get() = JsonI18n.t("run_steps_instruction", ArabicStrings.runStepsInstruction)
    override val stepNotDone: String get() = JsonI18n.t("step_not_done", ArabicStrings.stepNotDone)
    override val stepDone: String get() = JsonI18n.t("step_done", ArabicStrings.stepDone)
    override val completeAndSave: String get() = JsonI18n.t("complete_and_save", ArabicStrings.completeAndSave)
    override fun runningRecipe(name: String): String = JsonI18n.tFormat("running_recipe", mapOf("name" to name))
    override fun batchSizeLabel(size: Double): String = JsonI18n.tFormat("batch_size_label", mapOf("size" to size.toString()))

    override val statsOutputTotal: String get() = JsonI18n.t("stats_output_total", ArabicStrings.statsOutputTotal)
    override val statsSpeedAvg: String get() = JsonI18n.t("stats_speed_avg", ArabicStrings.statsSpeedAvg)
    override val statsActiveTime: String get() = JsonI18n.t("stats_active_time", ArabicStrings.statsActiveTime)
    override val statsOperatorsCount: String get() = JsonI18n.t("stats_operators_count", ArabicStrings.statsOperatorsCount)

    override val qcTitle: String get() = JsonI18n.t("qc_title", ArabicStrings.qcTitle)
    override val qcViscosity: String get() = JsonI18n.t("qc_viscosity", ArabicStrings.qcViscosity)
    override val qcDensity: String get() = JsonI18n.t("qc_density", ArabicStrings.qcDensity)
    override val qcPH: String get() = JsonI18n.t("qc_ph", ArabicStrings.qcPH)
    override val qcAppearance: String get() = JsonI18n.t("qc_appearance", ArabicStrings.qcAppearance)
    override val qcPass: String get() = JsonI18n.t("qc_pass", ArabicStrings.qcPass)
    override val qcFail: String get() = JsonI18n.t("qc_fail", ArabicStrings.qcFail)
    override val qcSaveResults: String get() = JsonI18n.t("qc_save_results", ArabicStrings.qcSaveResults)
    override val qcSavedSuccess: String get() = JsonI18n.t("qc_saved_success", ArabicStrings.qcSavedSuccess)

    override val packagingTitle: String get() = JsonI18n.t("packaging_title", ArabicStrings.packagingTitle)
    override val customPackagingName: String get() = JsonI18n.t("custom_packaging_name", ArabicStrings.customPackagingName)
    override val packagingEmptyWeight: String get() = JsonI18n.t("packaging_empty_weight", ArabicStrings.packagingEmptyWeight)
    override val packagingGrossWeight: String get() = JsonI18n.t("packaging_gross_weight", ArabicStrings.packagingGrossWeight)
    override val packagingAdhesiveCost: String get() = JsonI18n.t("packaging_adhesive_cost", ArabicStrings.packagingAdhesiveCost)
    override val packagingLidCost: String get() = JsonI18n.t("packaging_lid_cost", ArabicStrings.packagingLidCost)
    override val totalPackagesCount: String get() = JsonI18n.t("total_packages_count", ArabicStrings.totalPackagesCount)

    override val costsTitle: String get() = JsonI18n.t("costs_title", ArabicStrings.costsTitle)
    override val directMaterialsCost: String get() = JsonI18n.t("direct_materials_cost", ArabicStrings.directMaterialsCost)
    override val operatingOverheads: String get() = JsonI18n.t("operating_overheads", ArabicStrings.operatingOverheads)
    override val formulaCostPerKg: String get() = JsonI18n.t("formula_cost_per_kg", ArabicStrings.formulaCostPerKg)
    override val totalBatchFinalCost: String get() = JsonI18n.t("total_batch_final_cost", ArabicStrings.totalBatchFinalCost)
    override val profitPercentage: String get() = JsonI18n.t("profit_percentage", ArabicStrings.profitPercentage)

    override val settingsTitle: String get() = JsonI18n.t("settings_title", ArabicStrings.settingsTitle)
    override val settingsSubtitle: String get() = JsonI18n.t("settings_subtitle", ArabicStrings.settingsSubtitle)
    override val settingsCatAppProperties: String get() = JsonI18n.t("settings_cat_app_properties", ArabicStrings.settingsCatAppProperties)
    override val settingsCatAppPropertiesSub: String get() = JsonI18n.t("settings_cat_app_properties_sub", ArabicStrings.settingsCatAppPropertiesSub)
    override val settingsCatAppearanceLanguage: String get() = JsonI18n.t("settings_cat_appearance_language", ArabicStrings.settingsCatAppearanceLanguage)
    override val settingsCatAppearanceLanguageSub: String get() = JsonI18n.t("settings_cat_appearance_language_sub", ArabicStrings.settingsCatAppearanceLanguageSub)
    override val settingsCatDatabase: String get() = JsonI18n.t("settings_cat_database", ArabicStrings.settingsCatDatabase)
    override val settingsCatDatabaseSub: String get() = JsonI18n.t("settings_cat_database_sub", ArabicStrings.settingsCatDatabaseSub)
    override val settingsCatPackaging: String get() = JsonI18n.t("settings_cat_packaging", ArabicStrings.settingsCatPackaging)
    override val settingsCatPackagingSub: String get() = JsonI18n.t("settings_cat_packaging_sub", ArabicStrings.settingsCatPackagingSub)
    override val settingsCatQualityTests: String get() = JsonI18n.t("settings_cat_quality_tests", ArabicStrings.settingsCatQualityTests)
    override val settingsCatQualityTestsSub: String get() = JsonI18n.t("settings_cat_quality_tests_sub", ArabicStrings.settingsCatQualityTestsSub)
    override val settingsCatFactory: String get() = JsonI18n.t("settings_cat_factory", ArabicStrings.settingsCatFactory)
    override val settingsCatFactorySub: String get() = JsonI18n.t("settings_cat_factory_sub", ArabicStrings.settingsCatFactorySub)
    override val settingsCatUsers: String get() = JsonI18n.t("settings_cat_users", ArabicStrings.settingsCatUsers)
    override val settingsCatUsersSub: String get() = JsonI18n.t("settings_cat_users_sub", ArabicStrings.settingsCatUsersSub)
    override val settingsCatAbout: String get() = JsonI18n.t("settings_cat_about", ArabicStrings.settingsCatAbout)
    override val settingsCatAboutSub: String get() = JsonI18n.t("settings_cat_about_sub", ArabicStrings.settingsCatAboutSub)

    override val appIdentityTitle: String get() = JsonI18n.t("app_identity_title", ArabicStrings.appIdentityTitle)
    override val companyNameLabel: String get() = JsonI18n.t("company_name_label", ArabicStrings.companyNameLabel)
    override val appTitleLabel: String get() = JsonI18n.t("app_title_label", ArabicStrings.appTitleLabel)
    override val companyLogoUrlLabel: String get() = JsonI18n.t("company_logo_url_label", ArabicStrings.companyLogoUrlLabel)
    override val companyLogoUrlSub: String get() = JsonI18n.t("company_logo_url_sub", ArabicStrings.companyLogoUrlSub)
    override val appIconUrlLabel: String get() = JsonI18n.t("app_icon_url_label", ArabicStrings.appIconUrlLabel)
    override val appIconUrlSub: String get() = JsonI18n.t("app_icon_url_sub", ArabicStrings.appIconUrlSub)
    override val loginBgUrlLabel: String get() = JsonI18n.t("login_bg_url_label", ArabicStrings.loginBgUrlLabel)
    override val loginBgUrlSub: String get() = JsonI18n.t("login_bg_url_sub", ArabicStrings.loginBgUrlSub)
    override val saveBrandIdentityButton: String get() = JsonI18n.t("save_brand_identity_button", ArabicStrings.saveBrandIdentityButton)
    override val saveBrandConfirmTitle: String get() = JsonI18n.t("save_brand_confirm_title", ArabicStrings.saveBrandConfirmTitle)
    override val saveBrandConfirmBody: String get() = JsonI18n.t("save_brand_confirm_body", ArabicStrings.saveBrandConfirmBody)
    override val appThemeLabel: String get() = JsonI18n.t("app_theme_label", ArabicStrings.appThemeLabel)
    override val dbBackupTitle: String get() = JsonI18n.t("db_backup_title", ArabicStrings.dbBackupTitle)
    override val selectiveExportTitle: String get() = JsonI18n.t("selective_export_title", ArabicStrings.selectiveExportTitle)
    override val selectiveExportSub: String get() = JsonI18n.t("selective_export_sub", ArabicStrings.selectiveExportSub)
    override val dbImportTitle: String get() = JsonI18n.t("db_import_title", ArabicStrings.dbImportTitle)
    override val dbImportSub: String get() = JsonI18n.t("db_import_sub", ArabicStrings.dbImportSub)
    override val preImportCheckTitle: String get() = JsonI18n.t("pre_import_check_title", ArabicStrings.preImportCheckTitle)
    override val preImportCheckSub: String get() = JsonI18n.t("pre_import_check_sub", ArabicStrings.preImportCheckSub)

    override val labAllBranches: String get() = JsonI18n.t("lab_all_branches", ArabicStrings.labAllBranches)
    override val labInstantSingle: String get() = JsonI18n.t("lab_instant_single", ArabicStrings.labInstantSingle)
    override val labCalibrateCompare: String get() = JsonI18n.t("lab_calibrate_compare", ArabicStrings.labCalibrateCompare)
    override val labTechnician: String get() = JsonI18n.t("lab_technician", ArabicStrings.labTechnician)
    override val labDate: String get() = JsonI18n.t("lab_date", ArabicStrings.labDate)
    override val labResetFilters: String get() = JsonI18n.t("lab_reset_filters", ArabicStrings.labResetFilters)
    override val labDeleteConfirmTitle: String get() = JsonI18n.t("lab_delete_confirm_title", ArabicStrings.labDeleteConfirmTitle)
    override val labDeleteConfirmBody: String get() = JsonI18n.t("lab_delete_confirm_body", ArabicStrings.labDeleteConfirmBody)
    override val labSearchPlaceholder: String get() = JsonI18n.t("lab_search_placeholder", ArabicStrings.labSearchPlaceholder)

    override val eqMinutes: String get() = JsonI18n.t("eq_minutes", ArabicStrings.eqMinutes)
    override val eqSeconds: String get() = JsonI18n.t("eq_seconds", ArabicStrings.eqSeconds)
    override val eqStartTimer: String get() = JsonI18n.t("eq_start_timer", ArabicStrings.eqStartTimer)
    override val eqRenameTitle: String get() = JsonI18n.t("eq_rename_title", ArabicStrings.eqRenameTitle)
    override val eqDeleteTitle: String get() = JsonI18n.t("eq_delete_title", ArabicStrings.eqDeleteTitle)
    override val eqConnectionStatus: String get() = JsonI18n.t("eq_connection_status", ArabicStrings.eqConnectionStatus)
    override val eqDeviceStatus: String get() = JsonI18n.t("eq_device_status", ArabicStrings.eqDeviceStatus)
    override val eqStart: String get() = JsonI18n.t("eq_start", ArabicStrings.eqStart)
    override val eqStop: String get() = JsonI18n.t("eq_stop", ArabicStrings.eqStop)
    override val eqTimer: String get() = JsonI18n.t("eq_timer", ArabicStrings.eqTimer)
    override val eqTimeRemaining: String get() = JsonI18n.t("eq_time_remaining", ArabicStrings.eqTimeRemaining)
    override val eqReset: String get() = JsonI18n.t("eq_reset", ArabicStrings.eqReset)
    override val eqNewDevice: String get() = JsonI18n.t("eq_new_device", ArabicStrings.eqNewDevice)
    override val eqDeleteDevice: String get() = JsonI18n.t("eq_delete_device", ArabicStrings.eqDeleteDevice)
    override val eqDeviceNameLabel: String get() = JsonI18n.t("eq_device_name_label", ArabicStrings.eqDeviceNameLabel)
    override val eqDeviceNamePlaceholder: String get() = JsonI18n.t("eq_device_name_placeholder", ArabicStrings.eqDeviceNamePlaceholder)

    override val altSectionRelated: String get() = JsonI18n.t("alt_section_related", ArabicStrings.altSectionRelated)
    override val altCustomItem: String get() = JsonI18n.t("alt_custom_item", ArabicStrings.altCustomItem)
    override val altAllElements: String get() = JsonI18n.t("alt_all_elements", ArabicStrings.altAllElements)
    override val altSelectRecipe: String get() = JsonI18n.t("alt_select_recipe", ArabicStrings.altSelectRecipe)
    override val altRecipeApproved: String get() = JsonI18n.t("alt_recipe_approved", ArabicStrings.altRecipeApproved)
    override val altNoRecipe: String get() = JsonI18n.t("alt_no_recipe", ArabicStrings.altNoRecipe)
    override val altApprovedProduct: String get() = JsonI18n.t("alt_approved_product", ArabicStrings.altApprovedProduct)
    override val altSelectSession: String get() = JsonI18n.t("alt_select_session", ArabicStrings.altSelectSession)
    override val altSessionItem: String get() = JsonI18n.t("alt_session_item", ArabicStrings.altSessionItem)
    override val altSeverityLevel: String get() = JsonI18n.t("alt_severity_level", ArabicStrings.altSeverityLevel)
    override val altSaveAlert: String get() = JsonI18n.t("alt_save_alert", ArabicStrings.altSaveAlert)
    override val altCancel: String get() = JsonI18n.t("alt_cancel", ArabicStrings.altCancel)

    override val syncButton: String get() = JsonI18n.t("sync_button", ArabicStrings.syncButton)
    override val syncMatchingDb: String get() = JsonI18n.t("sync_matching_db", ArabicStrings.syncMatchingDb)
    override val syncStartCheck: String get() = JsonI18n.t("sync_start_check", ArabicStrings.syncStartCheck)
    override val syncRawMaterials: String get() = JsonI18n.t("sync_raw_materials", ArabicStrings.syncRawMaterials)
    override val syncFormulations: String get() = JsonI18n.t("sync_formulations", ArabicStrings.syncFormulations)
    override val syncProductionOrders: String get() = JsonI18n.t("sync_production_orders", ArabicStrings.syncProductionOrders)
    override val syncProductionLogs: String get() = JsonI18n.t("sync_production_logs", ArabicStrings.syncProductionLogs)
    override val syncRdProjects: String get() = JsonI18n.t("sync_rd_projects", ArabicStrings.syncRdProjects)
    override val syncLabSessions: String get() = JsonI18n.t("sync_lab_sessions", ArabicStrings.syncLabSessions)
    override val syncCustomPackagings: String get() = JsonI18n.t("sync_custom_packagings", ArabicStrings.syncCustomPackagings)
    override val syncCustomUsers: String get() = JsonI18n.t("sync_custom_users", ArabicStrings.syncCustomUsers)
    override val syncConnectingDb: String get() = JsonI18n.t("sync_connecting_db", ArabicStrings.syncConnectingDb)
    override val syncStartFirestoreCheck: String get() = JsonI18n.t("sync_start_firestore_check", ArabicStrings.syncStartFirestoreCheck)
    override val syncInProgress: String get() = JsonI18n.t("sync_in_progress", ArabicStrings.syncInProgress)
    override val syncBidirectionalStart: String get() = JsonI18n.t("sync_bidirectional_start", ArabicStrings.syncBidirectionalStart)
}
