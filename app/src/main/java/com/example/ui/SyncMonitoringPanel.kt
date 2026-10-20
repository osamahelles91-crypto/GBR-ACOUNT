package com.example.ui

import android.app.Application
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.awaitTask
import com.example.data.WriteDiagnostics
import com.example.ui.theme.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// GBR Custom theme colors
val GBRDarkIndigo = Color(0xFF1E1B4B)
val WarningOrange = Color(0xFFF97316)
val SuccessGreen = Color(0xFF10B981)
val GBRBlueMain = Color(0xFF0056B3)
val GBRPinkAccent = Color(0xFFEC4899)
val GBRPurpleAccent = Color(0xFF8B5CF6)
val GBRLightGray = Color(0xFFF1F5F9)

@Composable
fun SyncMonitoringPanel(viewModel: GbrViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // States
    val rawMaterials by viewModel.rawMaterials.collectAsState()
    val formulations by viewModel.formulations.collectAsState()
    val allRevisions by viewModel.allFormulationRevisions.collectAsState()
    val productionOrders by viewModel.productionOrders.collectAsState()
    val systemLogs by viewModel.systemLogs.collectAsState()
    val productionLogs by viewModel.productionLogs.collectAsState()
    val developmentProjects by viewModel.developmentProjects.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val customPackagings by viewModel.customPackagings.collectAsState()
    val customUsers by viewModel.customUsers.collectAsState()
    
    val syncMode by viewModel.syncMode.collectAsState()
    val syncStatusMessage by viewModel.syncStatusMessage.collectAsState()
    val globalSyncStatus by viewModel.globalSyncStatus.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val pendingSyncItems by viewModel.pendingSyncItems.collectAsState()
    val lang by viewModel.appLanguage.collectAsState()
    fun t(ar: String, en: String): String = if (lang == "ar") ar else en
    
    val rawMaterialsLast by viewModel.syncLastRawMaterials.collectAsState()
    val formulationsLast by viewModel.syncLastFormulations.collectAsState() 
    val ordersLast by viewModel.syncLastProductionOrders.collectAsState()

    // Preferences
    val syncPrefs = remember { context.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE) }
    val isRealtime = syncMode == "realtime"
    
    // Firestore setup variables
    val fbProjectId = syncPrefs.getString("fb_project_id", "غير محدد") ?: "غير محدد"
    val lastIncomingUpdate = syncPrefs.getString("last_cloud_incoming_update", "لم يتم تلقي تحديث سحابي مباشر بعد ⏰") ?: "لم يتم تلقي تحديث سحابي مباشر بعد ⏰"

    // Integration Connectivity Tester State
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var connectionTestSuccess by remember { mutableStateOf<Boolean?>(null) }

    // Section Expand/Collapse States (hidden by default as requested)
    var isAuditExpanded by remember { mutableStateOf(false) }
    var isSyncStatsExpanded by remember { mutableStateOf(false) }
    var isQuotaExpanded by remember { mutableStateOf(false) }

    // Intercept back click
    BackHandler(enabled = true) {
        if (viewModel.selectedSettingSection.value != null) {
            viewModel.selectedSettingSection.value = null
        } else {
            viewModel.showSegment(null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC)) // beautiful modern neutral background
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        
        // Header back item
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(
                onClick = {
                    if (viewModel.selectedSettingSection.value != null) {
                        viewModel.selectedSettingSection.value = null
                    } else {
                        viewModel.showSegment(null)
                    }
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.LightGray.copy(alpha = 0.2f))
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = t("الرجوع", "Back"),
                    tint = GBRDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = t("مراقبة ومزامنة البيانات 🔄", "Data Monitoring & Sync 🔄"),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Text(
                    text = t("تتبع فوري وحوكمة التزامن مع Firestore", "Realtime tracking & Firestore governance"),
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        // 1. Connection Mode Card & Indicator
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (syncMode) {
                    "local" -> Color(0xFFF1F5F9)
                    "manual" -> Color(0xFFFFFBEB)
                    else -> Color(0xFFF0FDF4)
                }
            ),
            border = BorderStroke(
                width = 1.dp,
                color = when (syncMode) {
                    "local" -> Color(0xFFCBD5E1)
                    "manual" -> Color(0xFFFDE68A)
                    else -> Color(0xFFBBF7D0)
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            color = when (syncMode) {
                                "local" -> Color.Gray.copy(alpha = 0.1f)
                                "manual" -> WarningOrange.copy(alpha = 0.1f)
                                else -> SuccessGreen.copy(alpha = 0.1f)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRealtime) Icons.Default.CheckCircle else Icons.Default.Refresh,
                        contentDescription = null,
                        tint = when (syncMode) {
                            "local" -> Color.Gray
                            "manual" -> WarningOrange
                            else -> SuccessGreen
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (syncMode) {
                            "realtime" -> t("بث سحابي مباشر متصل نشط (Realtime) ⚡", "Active Realtime Cloud Broadcast ⚡")
                            "manual" -> t("وضع مزامنة يدوي مستقل (On-Demand) ☁️", "Manual On-Demand Sync Mode ☁️")
                            else -> t("وضع تشغيل محلي بالكامل (Offline-Only) 📴", "Local Offline-Only Run Mode 📴")
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${t("معرّف المشروع:", "Project ID:")} ${if (fbProjectId.isBlank() || fbProjectId == "غير محدد") t("غير محدد", "Not specified") else fbProjectId}",
                        fontSize = 11.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }

        // 2. Local Record Audit Card Grid
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
                .clickable { isAuditExpanded = !isAuditExpanded },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "🔢 تدقيق السجلات المحلية (Local Entity Audit)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Icon(
                    imageVector = if (isAuditExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isAuditExpanded) t("إخفاء التفاصيل", "Collapse") else t("عرض التفاصيل", "Expand"),
                    tint = GBRDarkIndigo,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = isAuditExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AuditRowItem(titleAr = "المواد الخام النشطة بالمصنع", titleEn = "Active Raw Materials", count = rawMaterials.size, icon = "🧪", color = GBRBlueMain)
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "سجل تعديلات وحركات أسعار المواد", titleEn = "Material Price Change Logs", count = allRevisions.size, icon = "🏷️", color = Color(0xFFD97706))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "كتالوج تركيبات دهانات المصنع المعتمدة", titleEn = "Paint Formulations Directory", count = formulations.size, icon = "🎨", color = GBRPurpleAccent)
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "طلبات وأوامر الإنتاج والجدولة", titleEn = "Production & Scheduling Orders", count = productionOrders.size, icon = "⚙️", color = WarningOrange)
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "سجلات الإنتاج الإحصائية وتشغيل الوجبات", titleEn = "Actual Machine & Production Logs", count = productionLogs.size, icon = "📊", color = Color(0xFFEC4899))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "أبحاث وعينات التطوير المختبري R&D", titleEn = "R&D Projects & Development Samples", count = developmentProjects.size, icon = "🔬", color = Color(0xFF06B6D4))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "جلسات فحص جودة وجاهزية المنتجات", titleEn = "Quality Control & Lab Sessions", count = labSessions.size, icon = "🧪", color = Color(0xFF10B981))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "مواصفات وأوزان عبوات التعبئة والتغليف", titleEn = "Custom Packaging Configurations", count = customPackagings.size, icon = "📦", color = Color(0xFFF59E0B))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "حسابات مستخدمي النظام وصلاحيات التشغيل", titleEn = "System User Accounts & Roles", count = customUsers.size, icon = "👥", color = Color(0xFF6366F1))
                    Divider(color = Color(0xFFF1F5F9))
                    AuditRowItem(titleAr = "سجل فعاليات وأحداث النظام التفصيلية", titleEn = "Detailed System Operational Logs", count = systemLogs.size, icon = "📜", color = Color.Gray)
                }
            }
        }

        // 3. Synchronization Statistics & Health
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
                .clickable { isSyncStatsExpanded = !isSyncStatsExpanded },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "⏰ إحصائيات وتوقيتات المزامنة الآمنة",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Icon(
                    imageVector = if (isSyncStatsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isSyncStatsExpanded) t("إخفاء التفاصيل", "Collapse") else t("عرض التفاصيل", "Expand"),
                    tint = GBRDarkIndigo,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = isSyncStatsExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SyncStatusRow(
                        title = "مزامنة المواد الخام الأخيرة",
                        time = rawMaterialsLast,
                        badgeText = "Firestore 📥",
                        badgeColor = Color(0xFFDDBEE50).copy(alpha = 0.15f),
                        textColor = GBRBlueMain
                    )
                    Divider(color = Color(0xFFF1F5F9))
                    SyncStatusRow(
                        title = "مزامنة كتالوج التركيبات",
                        time = formulationsLast,
                        badgeText = "Firestore ⚙️",
                        badgeColor = Color(0xFFDDBEE50).copy(alpha = 0.15f),
                        textColor = GBRPurpleAccent
                    )
                    Divider(color = Color(0xFFF1F5F9))
                    SyncStatusRow(
                        title = "مزامنة أوامر الإنتاج",
                        time = ordersLast,
                        badgeText = "Firestore 📤",
                        badgeColor = Color(0xFFDDBEE50).copy(alpha = 0.15f),
                        textColor = WarningOrange
                    )
                    Divider(color = Color(0xFFF1F5F9))
                    SyncStatusRow(
                        title = "آخر حدث مباشر تم استقباله",
                        time = lastIncomingUpdate,
                        badgeText = "Realtime 🔄",
                        badgeColor = SuccessGreen.copy(alpha = 0.15f),
                        textColor = SuccessGreen
                    )

                    if (pendingSyncItems.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .background(Color(0xFFFEF2F2), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFFFCA5A5), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "⚠️ سجلات معلقة بانتظار المزامنة:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B)
                            )
                            pendingSyncItems.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${item.entityType} (ID: ${item.id.take(8)}...)",
                                        fontSize = 11.sp,
                                        color = Color(0xFF7F1D1D),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { viewModel.triggerSingleSync(item) },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                        modifier = Modifier.height(24.dp)
                                    ) {
                                        Text(s().syncButton, fontSize = 10.sp, color = GBRBlueMain, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3.5. Cloud Consistency Audit Tool
        val cloudAuditReport by viewModel.currentCloudAuditReport.collectAsState()
        val isRunningCloudAudit by viewModel.isRunningCloudAudit.collectAsState()

        Text(
            text = "🛡️ أداة مطابقة الاتساق السحابي (Cloud Consistency Audit Area)",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GBRDarkIndigo,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 14.dp, bottom = 6.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تقارن هذه الأداة أعداد ومفاتيح السجلات في قاعدة البيانات المحلية (Room SQL) مع السجلات النشطة على سحابة Google Firestore للتأكد من المزامنة وصحة الروابط وملفات الـ TDS والصور.",
                    fontSize = 11.sp,
                    color = Color.DarkGray,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = { viewModel.triggerCloudConsistencyAudit() },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRPurpleAccent),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isRunningCloudAudit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunningCloudAudit) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s().syncMatchingDb, fontSize = 12.sp, color = Color.White)
                    } else {
                        Text(s().syncStartCheck, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                cloudAuditReport?.let { report ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (report.totalDiscrepancies == 0 && report.connectionSucceeded) Color(0xFFECFDF5) else Color(0xFFFFFBEB),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (report.totalDiscrepancies == 0 && report.connectionSucceeded) Color(0xFFA7F3D0) else Color(0xFFFDE68A),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "حالة اتصال الفحص: ${if (report.connectionSucceeded) "✅ متصل ونشط" else "❌ فشل"}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo
                                )
                                Text(
                                    text = report.auditDate,
                                    fontSize = 9.sp,
                                    color = Color.Gray
                                )
                            }
                            
                            Divider(color = Color.LightGray.copy(alpha = 0.3f))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncRawMaterials, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localRawCount} | ${t("سحابي", "Cloud")} ${report.cloudRawCount} ${if (report.rawMaterialsMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncFormulations, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localFormCount} | ${t("سحابي", "Cloud")} ${report.cloudFormCount} ${if (report.formulationsMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncProductionOrders, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localOrderCount} | ${t("سحابي", "Cloud")} ${report.cloudOrderCount} ${if (report.ordersMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncProductionLogs, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localLogCount} | ${t("سحابي", "Cloud")} ${report.cloudLogCount} ${if (report.logMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncRdProjects, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localDevCount} | ${t("سحابي", "Cloud")} ${report.cloudDevCount} ${if (report.devMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncLabSessions, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localLabCount} | ${t("سحابي", "Cloud")} ${report.cloudLabCount} ${if (report.labMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncCustomPackagings, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localPkgCount} | ${t("سحابي", "Cloud")} ${report.cloudPkgCount} ${if (report.pkgMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(s().syncCustomUsers, fontSize = 10.5.sp, color = Color.DarkGray)
                                Text("${t("محلي", "Local")} ${report.localUserCount} | ${t("سحابي", "Cloud")} ${report.cloudUserCount} ${if (report.userMatch) "✅" else "⚠️"}", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Divider(color = Color.LightGray.copy(alpha = 0.3f))

                            Text(
                                text = report.discrepancyMessage,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (report.totalDiscrepancies == 0 && report.connectionSucceeded) Color(0xFF065F46) else Color(0xFF92400E),
                                lineHeight = 15.sp
                            )

                            if (report.totalDiscrepancies > 0 && report.connectionSucceeded) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        viewModel.triggerManualSync("all")
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "تنزيل ومزامنة كافة السجلات السحابية فوراً 📥",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3.75 Write Diagnostics Card (Central Daily Quota Tracker)
        val writeCounts by WriteDiagnostics.writeCounts.collectAsState()
        val totalWritesGlobal by WriteDiagnostics.totalWrites.collectAsState()
        val currentCycleId by WriteDiagnostics.currentCycleId.collectAsState()

        val actionNames = remember {
            mapOf(
                "raw_materials" to "المواد الخام (raw_materials)",
                "formulations" to "التركيبات والوصفات (formulations)",
                "production_orders" to "أوامر وجدولة الإنتاج (production_orders)",
                "development_projects" to "مشاريع R&D وعينات التطوير (development_projects)",
                "quality_tests" to "فحوصات الجودة والمعايير (quality_tests)",
                "production_adjustments" to "تعديلات كميات التشغيل (production_adjustments)",
                "laboratory_sessions" to "جلسات المختبر (laboratory_sessions)",
                "laboratory_tests" to "تحاليل وفحوصات المختبر (laboratory_tests)",
                "laboratory_attachments" to "مرفقات وملفات التحاليل (laboratory_attachments)",
                "formulation_reference_specs" to "المواصفات القياسية للتركيبات (formulation_reference_specs)",
                "operational_alerts" to "التنبيهات والتحذيرات التشغيلية (operational_alerts)",
                "custom_packagings" to "تكوينات عبوات التعبئة (custom_packagings)",
                "custom_users" to "حسابات وصلاحيات النظام (custom_users)",
                "production_logs" to "سجلات حركات الأصناف الفعلية (production_logs)",
                "sync_tests" to "فحوصات الاتصال وحقن Ping (sync_tests)"
            )
        }

        LaunchedEffect(Unit) {
            WriteDiagnostics.init(context)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 4.dp)
                .clickable { isQuotaExpanded = !isQuotaExpanded },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "📊 الحصة اليومية لعمليات الكتابة (Global Firestore Write Quota)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Icon(
                    imageVector = if (isQuotaExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isQuotaExpanded) t("إخفاء التفاصيل", "Collapse") else t("عرض التفاصيل", "Expand"),
                    tint = GBRDarkIndigo,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        AnimatedVisibility(visible = isQuotaExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                Text(
                    text = "عداد مركزي فوري يراقب عمليات الكتابة (Writes) المنفذة بواسطة كافة الأجهزة والمستخدمين على نفس المشروع لتجنب تجاوز الحد المجاني اليومي لـ Firebase (20,000 عملية كتابة يومياً). يتم التصفير تلقائياً كل يوم في تمام الساعة 10:00 صباحاً بتوقيت فلسطين (Asia/Jerusalem).",
                    fontSize = 11.sp,
                    color = Color.DarkGray,
                    lineHeight = 16.sp
                )

                // Main Quota Stats Card
                val maxLimit = 20000
                val progressFraction = if (totalWritesGlobal > maxLimit) 1.0f else (totalWritesGlobal.toFloat() / maxLimit)
                val percentage = (progressFraction * 100).toInt()
                val remainingWrites = maxOf(0, maxLimit - totalWritesGlobal)

                val progressColor = when {
                    progressFraction < 0.5f -> SuccessGreen
                    progressFraction < 0.85f -> WarningOrange
                    else -> Color(0xFFEF4444)
                }

                val progressBgColor = progressColor.copy(alpha = 0.12f)

                // Progress Bar and Percent Layout
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(progressBgColor, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = progressColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "معدل استهلاك الحصة المجانية:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo
                            )
                        }
                        Text(
                            text = "$percentage%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = progressColor
                        )
                    }

                    // Linear Progress Bar
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(CircleShape),
                        color = progressColor,
                        trackColor = Color(0xFFE2E8F0)
                    )

                    // Row showing values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "المستهلك: $totalWritesGlobal من $maxLimit",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.DarkGray
                        )
                        Text(
                            text = "المتبقي: $remainingWrites عملية",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (remainingWrites < 2000) Color(0xFFEF4444) else SuccessGreen
                        )
                    }
                }

                // Grid stats info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Active Cycle Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = GBRBlueMain,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "دورة المراقبة الحالية",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = if (currentCycleId.isNotEmpty()) currentCycleId else "مستمر",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo
                            )
                        }
                    }

                    // Reset Timer Info Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = GBRPurpleAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "تصفير دوري تلقائي",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                            Text(
                                text = "10:00 صباحاً 🇵🇸",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo
                            )
                        }
                    }
                }

                // Collections Breakdown title
                Text(
                    text = "📋 سجل توزيع الاستهلاك حسب الـ Collection (مفروز تنازلياً):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (writeCounts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GBRLightGray, RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓ لم يتم رصد أي عمليات كتابة على السحابة لهذه الدورة حتى الآن.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val sortedBreakdown = remember(writeCounts) {
                            writeCounts.toList().sortedByDescending { it.second }
                        }

                        sortedBreakdown.forEach { (action, count) ->
                            val displayName = actionNames[action] ?: action
                            val percentageOfTotal = if (totalWritesGlobal > 0) {
                                (count.toFloat() / totalWritesGlobal * 100).toInt()
                            } else 0

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = displayName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.DarkGray
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    // Mini collection progress bar relative to the total writes
                                    val progressCol = count.toFloat() / maxOf(1, totalWritesGlobal)
                                    LinearProgressIndicator(
                                        progress = { progressCol },
                                        modifier = Modifier
                                            .width(80.dp)
                                            .height(3.dp)
                                            .clip(CircleShape),
                                        color = GBRPurpleAccent,
                                        trackColor = Color(0xFFF1F5F9)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "$percentageOfTotal%",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFFEF3C7), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "$count",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD97706)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }
        }

        // 4. Connectivity Tester (Realtime Ping)
        Text(
            text = "🧪 فحص اتصالات الحزمة وقدرة الإرسال",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GBRDarkIndigo,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 14.dp, bottom = 6.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "يسمح هذا الفحص بإجراء اختبار حقن واتصال لحظي ومباشر (Ping Check) للتحقق من صلاحية مفاتيح التشفير ووصول الاتصال لخوادم Firestore بشكل فوري وقاطع.",
                    fontSize = 11.sp,
                    color = Color.DarkGray,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = {
                        isTestingConnection = true
                        connectionTestResult = "جاري الاتصال بخوادم Google Firestore وحقن حزمة Ping..."
                        connectionTestSuccess = null
                        coroutineScope.launch {
                            try {
                                val isInitialized = withContext(Dispatchers.IO) {
                                    com.example.data.SyncManager.initializeFirebase(context)
                                }
                                if (!isInitialized) {
                                    throw Exception("فشلت تهيئة مكتبة Firebase. يرجى مراجعة إعدادات قاعدة البيانات والسحابة.")
                                }
                                val db = FirebaseFirestore.getInstance()
                                val pingPayload = hashMapOf<String, Any>(
                                    "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                                    "timestamp" to System.currentTimeMillis()
                                )
                                var writeSucceeded = false
                                var readSucceeded = false
                                var isPermissionDenied = false
                                withContext(Dispatchers.IO) {
                                    try {
                                        kotlinx.coroutines.withTimeout(5000) {
                                            db.collection("sync_tests").document("ping_test")
                                                .set(pingPayload).awaitTask()
                                        }
                                        writeSucceeded = true
                                    } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
                                        if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                            isPermissionDenied = true
                                        }
                                    } catch (e: Exception) {
                                        // other write failure
                                    }

                                    // Fallback read test
                                    try {
                                        kotlinx.coroutines.withTimeout(5000) {
                                            db.collection("raw_materials").limit(1).get().awaitTask()
                                        }
                                        readSucceeded = true
                                    } catch (e: Exception) {
                                        // read failure
                                    }
                                }

                                if (writeSucceeded) {
                                    connectionTestSuccess = true
                                    val sdf = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale.US)
                                    connectionTestResult = "✅ نجح الاتصال بالكامل (قراءة وكتابة)! خوادم Firestore مستجيبة وجاهزة لتبادل البيانات الفوري (${sdf.format(java.util.Date())})."
                                } else if (readSucceeded) {
                                    connectionTestSuccess = true
                                    val sdf = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale.US)
                                    connectionTestResult = "⚠️ نجح الاتصال (قراءة فقط)! خوادم Firestore مستجيبة ونشطة (${sdf.format(java.util.Date())}). قد تمنع قواعد حماية قواعد البيانات عمليات الكتابة المباشرة دون تسجيل دخول."
                                } else {
                                    connectionTestSuccess = false
                                    if (isPermissionDenied) {
                                        connectionTestResult = "❌ فشل فحص الاتصال سحابياً: قواعد الحماية (Security Rules) تمنع الوصول تماماً (PERMISSION_DENIED)."
                                    } else {
                                        connectionTestResult = "❌ فشل فحص الاتصال سحابياً: انتهت مهلة الطلب أو تعذر الوصول للخوادم. يرجى التحقق من جودة الإنترنت أو صحة الإعدادات."
                                    }
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                connectionTestSuccess = false
                                connectionTestResult = "❌ فشل فحص الاتصال سحابياً: انتهت مهلة الطلب (Timeout 6s). تأكد من جودة الإنترنت أو صحة إعدادات السحابة (Firestore Project ID/API Key)."
                            } catch (e: Exception) {
                                connectionTestSuccess = false
                                connectionTestResult = "❌ فشل فحص الاتصال سحابياً. السبب الكامن: ${e.localizedMessage ?: "مهلة انتظار الطلب انتهت أو غير مخول للوصول (Auth Error)"}"
                            } finally {
                                isTestingConnection = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isTestingConnection,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s().syncConnectingDb, fontSize = 12.sp, color = Color.White)
                    } else {
                        Text(s().syncStartFirestoreCheck, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                connectionTestResult?.let { result ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(
                                color = when (connectionTestSuccess) {
                                    true -> Color(0xFFECFDF5)
                                    false -> Color(0xFFFEF2F2)
                                    else -> Color(0xFFF1F5F9)
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = when (connectionTestSuccess) {
                                    true -> Color(0xFFA7F3D0)
                                    false -> Color(0xFFFCA5A5)
                                    else -> Color(0xFFE2E8F0)
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = result,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (connectionTestSuccess) {
                                true -> Color(0xFF065F46)
                                false -> Color(0xFF991B1B)
                                else -> Color(0xFF475569)
                            }
                        )
                    }
                }
            }
        }

        // 5. In-App Direct Sync Trigger
        Text(
            text = "🔄 حوكمة ومزامنة قواعد البيانات يدوياً",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GBRDarkIndigo,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 14.dp, bottom = 6.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "عند ضغط الزر أدناه يتم سحب كافة السجلات الخارجية وحفظها ودفع السجلات المحلية المعلقة لتمكين تزامن بنسبة 100% بين الهواتف العاملة بالموقع.",
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )

                // Sync progression display
                val isSyncing = globalSyncStatus == "syncing"
                
                Button(
                    onClick = {
                        viewModel.triggerManualSync("all")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s().syncInProgress, fontSize = 12.sp, color = Color.White)
                    } else {
                        Text(s().syncBidirectionalStart, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                if (isSyncing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = WarningOrange,
                            trackColor = Color(0xFFFEF3C7)
                        )
                        Text(
                            text = syncStatusMessage,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarningOrange,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }

        // 6. Sync Log Errors / Console
        Text(
            text = "📋 سجل فعاليات المزامنة ومعالجة الأخطاء",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = GBRDarkIndigo,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(top = 14.dp, bottom = 6.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // terminal-like dark background
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "صندوق رسائل الاتصال ومراقبة التعارضات (Conflicts Console)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Console", fontSize = 8.sp, color = Color.LightGray)
                    }
                }

                val syncActivities = remember(systemLogs) {
                    systemLogs.filter { 
                        it.category == "sync" || it.message.contains("مزامنة") || it.message.contains("سعر") || it.message.contains("سحاب") 
                    }.take(10)
                }

                if (syncActivities.isEmpty()) {
                    Text(
                        text = "لا توجد أية حوادث تعارضات تذكر حالياً. عمليات المزامنة تجري بسلاسة تامة ومكتملة 🤝",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        modifier = Modifier.padding(vertical = 14.dp)
                    )
                } else {
                    syncActivities.forEach { log ->
                        val formattedTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(log.timestamp))
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[$formattedTime]",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = log.message,
                                fontSize = 10.5.sp,
                                color = if (log.category == "error") Color(0xFFF87171) else Color.White,
                                lineHeight = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AuditRowItem(titleAr: String, titleEn: String, count: Int, icon: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, fontSize = 16.sp)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = titleAr,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Text(
                    text = titleEn,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.Gray
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = "$count سجلات",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
        }
    }
}

@Composable
fun SyncStatusRow(title: String, time: String, badgeText: String, badgeColor: Color, textColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontSize = 11.5.sp,
                color = Color.DarkGray,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = time,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (time == "لم يتم المزامنة بعد") Color.Gray else textColor
            )
        }

        Box(
            modifier = Modifier
                .background(badgeColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.DarkGray
            )
        }
    }
}
