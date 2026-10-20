package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LabSession
import com.example.data.LabTest
import com.example.data.LabAttachment
import com.example.data.ProductionOrder
import com.example.ui.theme.t
import com.example.ui.theme.s
import com.example.ui.theme.LocalAppLanguage
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.launch
import android.util.Log
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset

private val LabBlueMain = Color(0xFF0056B3)
private val LabDarkIndigo = Color(0xFF0F172A)
private val LabLightBg = Color(0xFFF8FAFC)
private val LabBorder = Color(0xFFE2E8F0)
private val LabSuccessGreen = Color(0xFF10B981)
private val LabWarningYellow = Color(0xFFF59E0B)
private val LabErrorRed = Color(0xFFEF4444)
private val LabPurple = Color(0xFF8B5CF6)
private val LabCyan = Color(0xFF06B6D4)

private fun sortLabTests(testsList: List<LabTest>): List<LabTest> {
    val moshi = com.squareup.moshi.Moshi.Builder()
        .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
    val adapter = moshi.adapter(ViscosityTestData::class.java)

    return testsList.sortedWith(compareBy<LabTest> { test ->
        val nameLower = test.name.lowercase().trim()
        val notes = test.notes
        
        var vData: ViscosityTestData? = null
        if (notes.startsWith("WIZARD_VISCOSITY:")) {
            try {
                vData = adapter.fromJson(notes.removePrefix("WIZARD_VISCOSITY:"))
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Determine Rank (1 to 6)
        val rank = when {
            // 5. فحص لزوجة كوب فورد (تتم المعاينة قبل اللزوجة القياسية لمنع تصنيف كوب فورد كـ لزوجة قياسية)
            nameLower.contains("cup") || nameLower.contains("كوب") || nameLower.contains("فورد") || nameLower.contains("ford") -> 5

            // 4. فحص درجة القلوية pH
            nameLower.contains("ph") || nameLower.contains("درجة القلوية") || nameLower.contains("درجة القلويه") || nameLower.contains("الحموضة") || nameLower.contains("قلوية") || nameLower.contains("قلويه") -> 4

            // 3. فحص اللزوجة بعد التخفيف بالماء
            (vData != null && vData.testType == "DILUTION") || 
            (nameLower.contains("تخفيف") || nameLower.contains("dilution") || nameLower.contains("dilut")) -> 3

            // 2. فحص السلوك الريولوجي
            (vData != null && vData.testType == "RHEOLOGY") || 
            (nameLower.contains("سلوك") || nameLower.contains("ريولوجي") || nameLower.contains("rheology") || nameLower.contains("rheological")) -> 2

            // 1. فحص اللزوجة القياسية (الأبطأ سرعة بالأعلى والأسرع بالأسفل)
            (vData != null && vData.testType == "STANDARD") || 
            (nameLower.contains("اللزوجة") || nameLower.contains("viscosity") || nameLower.contains("ku")) -> 1

            // 6. باقي الفحوصات ان وجدت
            else -> 6
        }
        rank
    }.thenBy { test ->
        // Sub-sorting within rank (specifically for standard viscosity by speed ascending: 6 rpm before 60 rpm)
        val notes = test.notes
        var speedVal = 0.0
        if (notes.startsWith("WIZARD_VISCOSITY:")) {
            try {
                val vData = adapter.fromJson(notes.removePrefix("WIZARD_VISCOSITY:"))
                if (vData != null && vData.testType == "STANDARD") {
                    speedVal = vData.speed.toDoubleOrNull() ?: 0.0
                }
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            // Check if speed is mentioned in the test name or notes, e.g., "سرعة 6" or "6 rpm"
            val regex = """(\d+(?:\.\d+)?)\s*(?:rpm|RPM|سرعة|سرعه)""".toRegex()
            val match = regex.find(test.name) ?: regex.find(test.notes)
            if (match != null) {
                speedVal = match.groupValues[1].toDoubleOrNull() ?: 0.0
            }
        }
        speedVal
    }.thenBy { test ->
        test.createdAt
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaboratoryPanel(
    viewModel: GbrViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lang by viewModel.appLanguage.collectAsStateWithLifecycle()
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val orders by viewModel.productionOrders.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    val allAttachments by viewModel.allLabAttachments.collectAsStateWithLifecycle(initialValue = emptyList())

    // Navigation and detail state
    var selectedSession by remember { mutableStateOf<LabSession?>(null) }
    val currentSessionSelected = remember(sessions, selectedSession) {
        if (selectedSession != null) {
            sessions.find { it.id == selectedSession?.id } ?: selectedSession
        } else {
            null
        }
    }
    var activeLabScreen by remember { mutableStateOf("archive_home") } // archive_home, session_details, direct_comparison
    var showReportViewInPanel by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableStateOf(0) } // 0: Completed (default), 1: Active, 2: Awaiting

    // Direct Session Comparison State
    var isComparisonMode by remember { mutableStateOf(false) }
    var selectedSessionsForComparison by remember { mutableStateOf(setOf<String>()) }

    // Add Session Dialog
    var showAddSessionDialog by remember { mutableStateOf(false) }

    // Search and Filters
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf("ALL") } // ALL, SINGLE, COMPARISON
    var filterCategory by remember { mutableStateOf("ALL") } // ALL or specific Category
    var filterTechnician by remember { mutableStateOf("") }
    var filterDate by remember { mutableStateOf("") }
    var showAdvancedFilters by remember { mutableStateOf(false) }

    // Edit, Delete, Clone & Duplicate states from long press
    var sessionToEdit by remember { mutableStateOf<LabSession?>(null) }
    var sessionToDelete by remember { mutableStateOf<LabSession?>(null) }
    var sessionToClone by remember { mutableStateOf<LabSession?>(null) }
    var sessionToDuplicate by remember { mutableStateOf<LabSession?>(null) }

    // Folder System State
    var selectedFolderFilter by remember { mutableStateOf("ALL") } // "ALL", "UNCATEGORIZED", or folder name
    var folderViewMode by remember { mutableStateOf(false) } // false = Sessions list, true = Folders grid
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<String?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var sessionToAssignFolder by remember { mutableStateOf<LabSession?>(null) }

    // Base standalone Laboratory sessions (strictly excludes reference specs and production order QC sessions)
    // Sessions of an ACTIVE production order (sampleProperties == "ORDER_ID:<id>") stay visible here until the order is completed/archived/cancelled;
    // after that they are reachable only from the (archived) production order itself. Post-production QC sessions (":QC") are never listed here.
    val labPanelSessions = remember(sessions, orders) {
        sessions.filterNot { s ->
            if (s.id.startsWith("ref_specs_") || s.category == "المواصفات المرجعية") {
                true
            } else if (s.sampleProperties.startsWith("ORDER_ID:") || s.sampleProperties.contains("ORDER_ID:")) {
                val linkedOrder = if (s.sampleProperties.startsWith("ORDER_ID:") && !s.sampleProperties.contains(":QC")) {
                    orders.find { it.id == s.sampleProperties.removePrefix("ORDER_ID:").trim() }
                } else null
                linkedOrder == null ||
                    linkedOrder.status.contains("مكتمل") ||
                    linkedOrder.status.contains("مؤرشف") ||
                    linkedOrder.status.contains("ملغي")
            } else {
                false
            }
        }
    }

    val allFolders = remember(labPanelSessions) {
        labPanelSessions
            .map { getLabSessionFolder(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val handleBackPress = {
        if (activeLabScreen == "session_details" || activeLabScreen == "direct_comparison") {
            selectedSession = null
            activeLabScreen = "archive_home"
        } else {
            viewModel.showSegment(null)
        }
    }

    BackHandler {
        handleBackPress()
    }

    // Filter logic
    val filteredSessions = remember(labPanelSessions, searchQuery, filterType, filterCategory, filterTechnician, filterDate, selectedFolderFilter) {
        labPanelSessions.filter { s ->
            val matchesSearch = searchQuery.isBlank() || 
                s.sessionNumber.contains(searchQuery, ignoreCase = true) ||
                s.testName.contains(searchQuery, ignoreCase = true) ||
                s.sampleOrProduct.contains(searchQuery, ignoreCase = true) ||
                (s.partyA ?: "").contains(searchQuery, ignoreCase = true) ||
                (s.partyB ?: "").contains(searchQuery, ignoreCase = true)

            val matchesType = when (filterType) {
                "SINGLE" -> s.testType == "🧪 فحص أحادي"
                "COMPARISON" -> s.testType == "⚖️ فحص مقارنة"
                else -> true
            }

            val matchesCategory = filterCategory == "ALL" || s.category == filterCategory

            val matchesTechnician = filterTechnician.isBlank() || 
                s.technicianName.contains(filterTechnician, ignoreCase = true)

            val matchesDate = filterDate.isBlank() || s.testDate.contains(filterDate)

            val matchesFolder = when (selectedFolderFilter) {
                "ALL" -> true
                "UNCATEGORIZED" -> getLabSessionFolder(s).isBlank()
                else -> getLabSessionFolder(s) == selectedFolderFilter
            }

            matchesSearch && matchesType && matchesCategory && matchesTechnician && matchesDate && matchesFolder
        }
    }

    // Statistics counts (only standalone laboratory sessions)
    val totalCount = labPanelSessions.size
    val singleCount = labPanelSessions.count { it.testType == "🧪 فحص أحادي" }
    val compareCount = labPanelSessions.count { it.testType == "⚖️ فحص مقارنة" }

    val categorizedSessions = remember(filteredSessions, allTests) {
        filteredSessions.map { s ->
            val sTests = allTests.filter { t -> t.sessionId == s.id }
            val total = sTests.size
            val completed = sTests.count { it.status == "مكتمل" || it.status == "خارج المواصفة" }
            val hasAwaiting = sTests.any { it.status == "بانتظار النتيجة" }
            
            val status = when {
                total == 0 -> "active"
                completed == total -> "completed"
                hasAwaiting -> "awaiting"
                else -> "active"
            }
            Triple(s, status, sTests)
        }
    }

    val activeSessions = remember(categorizedSessions) { categorizedSessions.filter { it.second == "active" } }
    val awaitingSessions = remember(categorizedSessions) { categorizedSessions.filter { it.second == "awaiting" } }
    val completedSessions = remember(categorizedSessions) { categorizedSessions.filter { it.second == "completed" } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LabLightBg)
    ) {
        // App Header Bar
        Surface(
            tonalElevation = 4.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Back button & Titles
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { handleBackPress() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع",
                                tint = LabDarkIndigo
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Column {
                            Text(
                                text = "🧪 فحوصات المختبر ومطابقة الجودة",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subTitle = if (activeLabScreen == "session_details" && currentSessionSelected != null) {
                                currentSessionSelected.testName
                            } else {
                                "Laboratory Quality Control"
                            }
                            Text(
                                text = subTitle,
                                fontSize = 11.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Right: Actions for main home screen
                    if (activeLabScreen == "archive_home") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Direct Comparison Toggle
                            val isComparing = isComparisonMode
                            IconButton(
                                onClick = { 
                                    isComparisonMode = !isComparisonMode
                                    if (!isComparisonMode) {
                                        selectedSessionsForComparison = emptySet()
                                    }
                                },
                                modifier = Modifier
                                    .background(
                                        if (isComparing) LabBlueMain.copy(alpha = 0.12f) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isComparing) LabBlueMain.copy(alpha = 0.3f) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = "مقارنة مباشرة",
                                    tint = if (isComparing) LabBlueMain else Color.Gray
                                )
                            }

                            IconButton(onClick = { 
                                isSearchActive = !isSearchActive 
                                if (!isSearchActive) searchQuery = "" 
                            }) {
                                Icon(
                                    imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = "بحث",
                                    tint = LabBlueMain
                               )
                            }
                        }
                    } else if (activeLabScreen == "session_details" && currentSessionSelected != null && !showReportViewInPanel) {
                        IconButton(onClick = { showReportViewInPanel = true }) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = "طباعة تقرير المختبر",
                                tint = LabBlueMain,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Expandable Search Bar
                if (activeLabScreen == "archive_home") {
                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(s().labSearchPlaceholder, fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "بحث", tint = LabBlueMain) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFFF8FAFC),
                                unfocusedContainerColor = Color(0xFFF8FAFC),
                                focusedBorderColor = LabBlueMain,
                                unfocusedBorderColor = LabBorder
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "مسح", tint = Color.Gray)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (activeLabScreen == "session_details" && currentSessionSelected != null) {
            // Details Screen
            SessionDetailsView(
                session = currentSessionSelected,
                viewModel = viewModel,
                onBack = {
                    selectedSession = null
                    activeLabScreen = "archive_home"
                    showReportViewInPanel = false
                },
                onDelete = {
                    viewModel.deleteLabSession(currentSessionSelected)
                    selectedSession = null
                    activeLabScreen = "archive_home"
                    showReportViewInPanel = false
                },
                showReportViewExternal = showReportViewInPanel,
                onShowReportViewExternalChange = { showReportViewInPanel = it }
            )
        } else if (activeLabScreen == "direct_comparison" && selectedSessionsForComparison.size == 2) {
            val sList = selectedSessionsForComparison.toList()
            DirectComparisonView(
                sessionIdA = sList[0],
                sessionIdB = sList[1],
                viewModel = viewModel,
                onBack = {
                    activeLabScreen = "archive_home"
                }
            )
        } else {
            // Main Home Screen: KPIs & Filterable Archive
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 80.dp)
                ) {
                    // Folders Section (نظام المجلدات والشركات)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        border = BorderStroke(1.dp, LabPurple.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Header Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = LabPurple.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = LabPurple,
                                            modifier = Modifier
                                                .padding(6.dp)
                                                .size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "📁 مجلدات الشركات والمنتجات",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                        Text(
                                            text = if (selectedFolderFilter == "ALL") "عرض جميع الجلسات" else "تصفية حسب: ${if (selectedFolderFilter == "UNCATEGORIZED") "بدون مجلد" else selectedFolderFilter}",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Toggle View Mode (List vs Grid)
                                    IconButton(
                                        onClick = { folderViewMode = !folderViewMode },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (folderViewMode) Icons.Default.List else Icons.Default.GridView,
                                            contentDescription = "تبديل العرض",
                                            tint = LabPurple,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // New Folder Button
                                    Button(
                                        onClick = { showCreateFolderDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("مجلد جديد", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)

                            // Horizontal Filter Chips Bar
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // "ALL" Chip
                                item {
                                    val isSelected = selectedFolderFilter == "ALL"
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedFolderFilter = "ALL" },
                                        label = { Text("🌐 الكل (${labPanelSessions.size})", fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = LabBlueMain,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }

                                // Dynamic Folder Chips
                                items(allFolders) { folderName ->
                                    val countInFolder = labPanelSessions.count { getLabSessionFolder(it) == folderName }
                                    val isSelected = selectedFolderFilter == folderName
                                    
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) LabPurple else Color(0xFFF8FAFC),
                                        border = BorderStroke(1.dp, if (isSelected) LabPurple else LabBorder),
                                        modifier = Modifier.clickable { selectedFolderFilter = folderName }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = null,
                                                tint = if (isSelected) Color.White else LabPurple,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                text = folderName,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else LabDarkIndigo
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = if (isSelected) Color.White.copy(alpha = 0.25f) else LabPurple.copy(alpha = 0.1f),
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = countInFolder.toString(),
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color.White else LabPurple
                                                )
                                            }

                                            // Options Menu
                                            var showChipMenu by remember { mutableStateOf(false) }
                                            Box {
                                                IconButton(
                                                    onClick = { showChipMenu = true },
                                                    modifier = Modifier.size(18.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.MoreVert,
                                                        contentDescription = "خيارات المجلد",
                                                        tint = if (isSelected) Color.White else Color.Gray,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = showChipMenu,
                                                    onDismissRequest = { showChipMenu = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("✏️ إعادة تسمية", fontSize = 12.sp) },
                                                        onClick = {
                                                            showChipMenu = false
                                                            folderToRename = folderName
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("🗑️ حذف المجلد", fontSize = 12.sp, color = LabErrorRed) },
                                                        onClick = {
                                                            showChipMenu = false
                                                            folderToDelete = folderName
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // "UNCATEGORIZED" Chip
                                val uncategorizedCount = labPanelSessions.count { getLabSessionFolder(it).isBlank() }
                                if (uncategorizedCount > 0) {
                                    item {
                                        val isSelected = selectedFolderFilter == "UNCATEGORIZED"
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedFolderFilter = "UNCATEGORIZED" },
                                            label = { Text("📦 بدون مجلد ($uncategorizedCount)", fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = Color.Gray,
                                                selectedLabelColor = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Folders Grid View Mode
                    if (folderViewMode) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁 شبكة مجلدات الشركات والمنتجات (${allFolders.size} مجلد)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = { folderViewMode = false },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "إغلاق النافذة ✕",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                if (allFolders.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(30.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("لا توجد مجلدات حالياً", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                            Text("قم بنقل جلسات الفحص إلى مجلدات بالضغط المطوّل على أي جلسة ثم اختر 'نقل إلى مجلد' أو اضغط '+ مجلد جديد'", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                        }
                                    }
                                } else {
                                    allFolders.forEach { folderName ->
                                        val sessionsInFolder = labPanelSessions.filter { getLabSessionFolder(it) == folderName }
                                        LabFolderCard(
                                            folderName = folderName,
                                            sessionsInFolder = sessionsInFolder,
                                            onClick = {
                                                selectedFolderFilter = folderName
                                                folderViewMode = false
                                            },
                                            onRenameClick = { folderToRename = folderName },
                                            onDeleteClick = { folderToDelete = folderName }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 1. Quick Filters Section
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎯 خيارات فرز وتصفية جلسات الجودة",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Compact Dropdowns Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Dropdown 1: نوع الفحص
                                var typeMenuExpanded by remember { mutableStateOf(false) }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(1.dp, LabBorder, RoundedCornerShape(10.dp))
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .clickable { typeMenuExpanded = true }
                                        .padding(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = when (filterType) {
                                                "SINGLE" -> "🧪 فحص أحادي"
                                                "COMPARISON" -> "⚖️ فحص مقارنة"
                                                else -> "🔬 جميع الفئات"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "قائمة منسدلة",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = typeMenuExpanded,
                                        onDismissRequest = { typeMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(s().labAllBranches, fontSize = 11.5.sp) },
                                            onClick = {
                                                filterType = "ALL"
                                                typeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(s().labInstantSingle, fontSize = 11.5.sp) },
                                            onClick = {
                                                filterType = "SINGLE"
                                                typeMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(s().labCalibrateCompare, fontSize = 11.5.sp) },
                                            onClick = {
                                                filterType = "COMPARISON"
                                                typeMenuExpanded = false
                                            }
                                        )
                                    }
                                }

                                // Dropdown 2: التصنيف والأرشيف
                                var categoryMenuExpanded by remember { mutableStateOf(false) }
                                val availableCategories = listOf(
                                    "منتجات نهائية", "مواد خام", "عينات تطوير", "عينات سوق",
                                    "تركيبة مقابل تركيبة", "منتج مقابل منافس", "دفعة مقابل دفعة", "مقارنة مخصصة"
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .border(1.dp, LabBorder, RoundedCornerShape(10.dp))
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFF8FAFC))
                                        .clickable { categoryMenuExpanded = true }
                                        .padding(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (filterCategory == "ALL") "📦 جميع التصنيفات" else "🏷️ $filterCategory",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "قائمة منسدلة",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = categoryMenuExpanded,
                                        onDismissRequest = { categoryMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("📦 جميع التصنيفات", fontSize = 11.5.sp) },
                                            onClick = {
                                                filterCategory = "ALL"
                                                categoryMenuExpanded = false
                                            }
                                        )
                                        availableCategories.forEach { cat ->
                                            DropdownMenuItem(
                                                text = { Text(cat, fontSize = 11.5.sp) },
                                                onClick = {
                                                    filterCategory = cat
                                                    categoryMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Advanced Filters Expandible Panel
                            AnimatedVisibility(visible = showAdvancedFilters) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp)
                                ) {
                                    Divider(color = LabBorder, modifier = Modifier.padding(vertical = 8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            modifier = Modifier.weight(1f),
                                            value = filterTechnician,
                                            onValueChange = { filterTechnician = it },
                                            label = { Text(s().labTechnician, fontSize = 11.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp)
                                        )

                                        OutlinedTextField(
                                            modifier = Modifier.weight(1f),
                                            value = filterDate,
                                            onValueChange = { filterDate = it },
                                            label = { Text(s().labDate, fontSize = 11.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Button(
                                        onClick = {
                                            searchQuery = ""
                                            filterType = "ALL"
                                            filterCategory = "ALL"
                                            filterTechnician = ""
                                            filterDate = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabLightBg),
                                        modifier = Modifier.align(Alignment.End),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(s().labResetFilters, color = LabDarkIndigo, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Custom Tab Selecor Row
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val tabs = listOf(
                                Triple("✅ المكتملة", completedSessions.size, LabSuccessGreen),
                                Triple("⚙️ جلسات نشطة", activeSessions.size, LabBlueMain),
                                Triple("⏳ بانتظار نتائج", awaitingSessions.size, LabWarningYellow)
                            )
                            
                            tabs.forEachIndexed { index, (label, count, activeColor) ->
                                val isSelected = selectedTabIndex == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) Color.White else Color.Transparent)
                                        .clickable { selectedTabIndex = index }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) activeColor else Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isSelected) activeColor else Color.LightGray.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = count.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val currentSelection = when (selectedTabIndex) {
                        0 -> completedSessions
                        1 -> activeSessions
                        2 -> awaitingSessions
                        else -> completedSessions
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (currentSelection.isEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                            border = BorderStroke(1.dp, LabBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "معلومات",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when (selectedTabIndex) {
                                            0 -> "لا توجد جلسات فحص مكتملة حالياً."
                                            1 -> "لا توجد جلسات فحص نشطة حالياً."
                                            else -> "لا توجد جلسات بانتظار النتائج حالياً."
                                        },
                                        fontSize = 11.5.sp,
                                        color = Color.DarkGray,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                if (selectedTabIndex == 1) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { showAddSessionDialog = true },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "بدء جلسة جديدة",
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("بدء جلسة جديدة", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // Render Session Cards
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            currentSelection.forEach { (item, _, sTests) ->
                                val completedCount = sTests.count { it.status == "مكتمل" || it.status == "خارج المواصفة" }
                                val totalCount = sTests.size
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isComparisonMode && item.testType == "🧪 فحص أحادي") {
                                        val isChecked = selectedSessionsForComparison.contains(item.id)
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                if (checked) {
                                                    if (selectedSessionsForComparison.size < 2) {
                                                        selectedSessionsForComparison = selectedSessionsForComparison + item.id
                                                    } else {
                                                        Toast.makeText(context, "الحد الأقصى للمقارنة هو جلستان فقط ⚠️", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    selectedSessionsForComparison = selectedSessionsForComparison - item.id
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = LabBlueMain)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    
                                    val hasAttachments = remember(allAttachments, item.id) {
                                        allAttachments.any { it.sessionId == item.id }
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        SessionListItem(
                                            item = item,
                                            completedCount = completedCount,
                                            totalCount = totalCount,
                                            hasAttachments = hasAttachments, lang = lang,
                                            onClick = {
                                                if (isComparisonMode) {
                                                    if (item.testType == "🧪 فحص أحادي") {
                                                        val isChecked = selectedSessionsForComparison.contains(item.id)
                                                        if (isChecked) {
                                                            selectedSessionsForComparison = selectedSessionsForComparison - item.id
                                                        } else {
                                                            if (selectedSessionsForComparison.size < 2) {
                                                                selectedSessionsForComparison = selectedSessionsForComparison + item.id
                                                            } else {
                                                                Toast.makeText(context, "الحد الأقصى للمقارنة هو جلستان فقط ⚠️", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    } else {
                                                        Toast.makeText(context, "المقارنة المباشرة مدعومة لجلسات الفحص الأحادي فقط 🧪", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    selectedSession = item
                                                    activeLabScreen = "session_details"
                                                }
                                            },
                                            onEditClick = {
                                                sessionToEdit = item
                                            },
                                            onDeleteClick = {
                                                sessionToDelete = item
                                            },
                                            onCloneClick = {
                                                sessionToClone = item
                                            },
                                            onDuplicateClick = {
                                                sessionToDuplicate = item
                                            },
                                            onAssignFolderClick = {
                                                sessionToAssignFolder = item
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Add Floating Action Button to create a session (Only when NOT in comparison mode)
                if (!isComparisonMode) {
                    FloatingActionButton(
                        onClick = { showAddSessionDialog = true },
                        containerColor = LabBlueMain,
                        contentColor = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "جلسة فحص جديدة")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جلسة فحص جديدة", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                // Sticky comparison action bar at the bottom of the home screen
                androidx.compose.animation.AnimatedVisibility(
                    visible = isComparisonMode,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), 
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "مقارنة الجلسات المباشرة ⚖️",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (selectedSessionsForComparison.size) {
                                        0 -> "يرجى تحديد جلستين فحص أحادي 🧪"
                                        1 -> "تم تحديد جلسة واحدة، حدد جلسة ثانية ⏳"
                                        else -> "تم تحديد جلستين جاهزتين للمطابقة والتقييم!"
                                    },
                                    color = Color.LightGray.copy(alpha = 0.9f),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            
                            Button(
                                onClick = {
                                    if (selectedSessionsForComparison.size == 2) {
                                        activeLabScreen = "direct_comparison"
                                    }
                                },
                                enabled = selectedSessionsForComparison.size == 2,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LabBlueMain,
                                    contentColor = Color.White,
                                    disabledContainerColor = Color.Gray.copy(alpha = 0.5f),
                                    disabledContentColor = Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("إجراء المقارنة ⚖️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Session Dialog
    if (showAddSessionDialog) {
        AddSessionDialog(
            currentUser = currentUser?.fullName?.ifBlank { currentUser?.name } ?: "فني المختبر",
            orders = orders,
            onDismiss = { showAddSessionDialog = false },
            onConfirm = { testName, testDate, technicianName, sampleOrProduct, category, testType, notes, comparisonType, partyA, partyB, sampleProperties ->
                viewModel.addLabSession(
                    testName = testName,
                    testDate = testDate,
                    technicianName = technicianName,
                    sampleOrProduct = sampleOrProduct,
                    category = category,
                    testType = testType,
                    notes = notes,
                    comparisonType = comparisonType,
                    partyA = partyA,
                    partyB = partyB,
                    sampleProperties = sampleProperties
                )
                selectedTabIndex = 1 // الجلسة الجديدة بلا فحوص تُصنَّف «نشطة»، فنعرض ذلك التبويب لتظهر مباشرة
                showAddSessionDialog = false
            }
        )
    }

    // Edit & Delete Dialog calls
    sessionToEdit?.let { item ->
        EditLabSessionDialog(
            session = item,
            onDismiss = { sessionToEdit = null },
            onConfirm = { updatedTestName, updatedSample, updatedCategory, updatedTestType, updatedNotes, updatedCompType, updatedPartyA, updatedPartyB ->
                val updatedSession = item.copy(
                    testName = updatedTestName,
                    sampleOrProduct = updatedSample,
                    category = updatedCategory,
                    testType = updatedTestType,
                    notes = updatedNotes,
                    comparisonType = if (updatedTestType == "⚖️ فحص مقارنة") (updatedCompType ?: item.comparisonType) else null,
                    partyA = if (updatedTestType == "⚖️ فحص مقارنة") updatedPartyA else null,
                    partyB = if (updatedTestType == "⚖️ فحص مقارنة") updatedPartyB else null
                )
                viewModel.updateLabSession(updatedSession)
                Toast.makeText(context, "تم تعديل الجلسة المختبرية بنجاح ✏️", Toast.LENGTH_SHORT).show()
                sessionToEdit = null
            }
        )
    }

    if (sessionToDelete != null) {
        val item = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            title = {
                Text("تأكيد الحذف ⚠️", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            text = {
                Text("هل أنت متأكد من رغبتك في حذف هذه الجلسة المختبرية \"${item.sessionNumber}\" بشكل نهائي؟ سيتم حذفها من الأرشيف والسحابة أيضاً ولا يمكن استعادتها.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLabSession(item)
                        Toast.makeText(context, "تم حذف الجلسة المختبرية بنجاح 🗑️", Toast.LENGTH_SHORT).show()
                        sessionToDelete = null
                        if (selectedSession?.id == item.id) {
                            selectedSession = null
                            activeLabScreen = "archive_home"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("نعم، احذف بشكل نهائي", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    sessionToClone?.let { item ->
        CloneAsTemplateDialog(
            session = item,
            onDismiss = { sessionToClone = null },
            onConfirm = { newName ->
                viewModel.cloneLabSessionAsTemplate(item.id, newName)
                Toast.makeText(context, "تم إنشاء قالب جديد بنجاح 📋✨", Toast.LENGTH_SHORT).show()
                sessionToClone = null
            }
        )
    }

    sessionToDuplicate?.let { item ->
        DuplicateSessionDialog(
            session = item,
            onDismiss = { sessionToDuplicate = null },
            onConfirm = { newName ->
                viewModel.duplicateLabSessionFull(item.id, newName)
                sessionToDuplicate = null
            }
        )
    }

    // Assign Session to Folder Dialog
    sessionToAssignFolder?.let { session ->
        AssignToFolderDialog(
            session = session,
            allFolders = allFolders,
            onDismiss = { sessionToAssignFolder = null },
            onConfirm = { folderName ->
                viewModel.updateLabSessionFolder(session, folderName)
                sessionToAssignFolder = null
            }
        )
    }

    // Create New Folder Dialog
    if (showCreateFolderDialog) {
        CreateLabFolderDialog(
            allSessions = labPanelSessions,
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { folderName, selectedIds ->
                if (selectedIds.isNotEmpty()) {
                    viewModel.moveMultipleSessionsToFolder(selectedIds, folderName)
                } else {
                    Toast.makeText(context, "تم إنشاء المجلد '$folderName' 📁", Toast.LENGTH_SHORT).show()
                }
                selectedFolderFilter = folderName
                showCreateFolderDialog = false
            }
        )
    }

    // Rename Folder Dialog
    folderToRename?.let { oldName ->
        RenameLabFolderDialog(
            folderName = oldName,
            onDismiss = { folderToRename = null },
            onConfirm = { newName ->
                viewModel.renameLabFolder(oldName, newName)
                if (selectedFolderFilter == oldName) {
                    selectedFolderFilter = newName
                }
                folderToRename = null
            }
        )
    }

    // Delete Folder Dialog
    folderToDelete?.let { folderName ->
        val countInFolder = labPanelSessions.count { getLabSessionFolder(it) == folderName }
        DeleteLabFolderDialog(
            folderName = folderName,
            sessionCount = countInFolder,
            onDismiss = { folderToDelete = null },
            onConfirm = { deleteSessionsAlso ->
                viewModel.deleteLabFolder(folderName, deleteSessionsAlso)
                if (selectedFolderFilter == folderName) {
                    selectedFolderFilter = "ALL"
                }
                folderToDelete = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditLabSessionDialog(
    session: LabSession,
    onDismiss: () -> Unit,
    onConfirm: (
        testName: String,
        sample: String,
        category: String,
        testType: String,
        notes: String,
        comparisonType: String?,
        partyA: String?,
        partyB: String?
    ) -> Unit
) {
    var sessionTestName by remember { mutableStateOf(session.testName) }
    var sampleOrProduct by remember { mutableStateOf(session.sampleOrProduct) }
    var category by remember { mutableStateOf(session.category) }
    var notes by remember { mutableStateOf(session.notes) }
    var partyA by remember { mutableStateOf(session.partyA ?: "") }
    var partyB by remember { mutableStateOf(session.partyB ?: "") }
    
    // Mode state: "single" vs "comparison"
    val initialIsComparison = session.testType == "⚖️ فحص مقارنة" || session.comparisonType != null || session.partyA != null
    var selectedTypeMode by remember { mutableStateOf(if (initialIsComparison) "comparison" else "single") }

    // Comparison category/type
    var selectedComparisonTypeSeq by remember {
        mutableStateOf(
            when (session.category) {
                "تركيبة مقابل تركيبة" -> "تركيبة"
                "دفعة مقابل دفعة" -> "دفعة"
                "مادة خام مقابل مادة خام" -> "مادة خام"
                "منتج مقابل منافس" -> "منتج منافس"
                else -> if (session.comparisonType?.contains("تركيبة") == true) "تركيبة"
                        else if (session.comparisonType?.contains("دفعة") == true) "دفعة"
                        else if (session.comparisonType?.contains("مادة") == true) "مادة خام"
                        else if (session.comparisonType?.contains("منافس") == true) "منتج منافس"
                        else "مخصصة"
            }
        )
    }

    val singleCategories = listOf("منتجات نهائية", "مواد خام", "عينات تطوير", "عينات سوق")
    var selectedSingleCategory by remember { mutableStateOf(if (!initialIsComparison) session.category else "منتجات نهائية") }

    val partyALabel: String
    val partyBLabel: String
    val categoryValue: String
    val comparisonTypeValue: String

    when (selectedComparisonTypeSeq) {
        "تركيبة" -> {
            partyALabel = "رقم/اسم التركيبة الأولى (أ) 🧪"
            partyBLabel = "رقم/اسم التركيبة الثانية (ب) 🧪"
            categoryValue = "تركيبة مقابل تركيبة"
            comparisonTypeValue = "تركيبة قديمة مقابل تركيبة جديدة"
        }
        "دفعة" -> {
            partyALabel = "رقم/اسم الدفعة الأولى (أ) 📦"
            partyBLabel = "رقم/اسم الدفعة الثانية (ب) 📦"
            categoryValue = "دفعة مقابل دفعة"
            comparisonTypeValue = "دفعة مقابل دفعة أخرى"
        }
        "مادة خام" -> {
            partyALabel = "اسم المورد/المصدر الأول (أ) 🏭"
            partyBLabel = "اسم المورد/المصدر الثاني (ب) 🏭"
            categoryValue = "مادة خام مقابل مادة خام"
            comparisonTypeValue = "مادة خام مقابل مادة خام أخرى"
        }
        "منتج منافس" -> {
            partyALabel = "اسم منتج شركتنا (أ) 🏬"
            partyBLabel = "اسم المنتج المنافس (ب) 🏬"
            categoryValue = "منتج مقابل منافس"
            comparisonTypeValue = "مطابقة منتجنا بمنتج منافس"
        }
        else -> {
            partyALabel = "اسم الطرف الأول (أ) 🧪"
            partyBLabel = "اسم الطرف الثاني (ب) 🔍"
            categoryValue = "مقارنة مخصصة"
            comparisonTypeValue = "مقارنة مخصصة"
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✏️ تعديل الجلسة المختبرية",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, modifier = Modifier.padding(vertical = 8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Field 1: Session Title / Name
                    OutlinedTextField(
                        value = sessionTestName,
                        onValueChange = { sessionTestName = it },
                        label = { Text("🏷️ اسم / عنوان الجلسة (تعديل اسم الجلسة) 🧪", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Field 2: Sample or Product Name
                    OutlinedTextField(
                        value = sampleOrProduct,
                        onValueChange = { sampleOrProduct = it },
                        label = { Text("📦 اسم العينة / المنتج / المادة", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Test Type Switch
                    Text("نوع الفحص المختبري:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            onClick = { selectedTypeMode = "single" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedTypeMode == "single") LabBlueMain.copy(alpha = 0.1f) else LabLightBg
                            ),
                            border = BorderStroke(1.5.dp, if (selectedTypeMode == "single") LabBlueMain else LabBorder)
                        ) {
                            Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "🧪 فحص أحادي",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedTypeMode == "single") LabBlueMain else LabDarkIndigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Card(
                            onClick = { selectedTypeMode = "comparison" },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedTypeMode == "comparison") LabPurple.copy(alpha = 0.1f) else LabLightBg
                            ),
                            border = BorderStroke(1.5.dp, if (selectedTypeMode == "comparison") LabPurple else LabBorder)
                        ) {
                            Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "⚖️ فحص مقارنة",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedTypeMode == "comparison") LabPurple else LabDarkIndigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (selectedTypeMode == "single") {
                        Text("تصنيف الأرشيف الفرعي:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            singleCategories.forEach { cat ->
                                val selected = selectedSingleCategory == cat
                                Surface(
                                    onClick = { selectedSingleCategory = cat },
                                    color = if (selected) LabBlueMain else LabLightBg,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Text(
                                        text = cat,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else LabDarkIndigo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    } else { // comparison
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, LabPurple.copy(alpha = 0.2f)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF5FF)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "⚖️ تفاصيل المقارنة والمطابقة الفنية",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabPurple,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                var showTypeSeqMenu by remember { mutableStateOf(false) }
                                val comparisonOptions = listOf("تركيبة", "دفعة", "مادة خام", "منتج منافس", "مخصصة")
                                
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = selectedComparisonTypeSeq,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("نوع وتصنيف المقارنة ⚖️", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        trailingIcon = {
                                            IconButton(onClick = { showTypeSeqMenu = !showTypeSeqMenu }) {
                                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = LabPurple)
                                            }
                                        },
                                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                    DropdownMenu(
                                        expanded = showTypeSeqMenu,
                                        onDismissRequest = { showTypeSeqMenu = false },
                                        modifier = Modifier.fillMaxWidth(0.95f)
                                    ) {
                                        comparisonOptions.forEach { opt ->
                                            DropdownMenuItem(
                                                text = { Text(opt, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    selectedComparisonTypeSeq = opt
                                                    showTypeSeqMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Party A Name
                                OutlinedTextField(
                                    value = partyA,
                                    onValueChange = { partyA = it },
                                    label = { Text(partyALabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )

                                // Party B Name
                                OutlinedTextField(
                                    value = partyB,
                                    onValueChange = { partyB = it },
                                    label = { Text(partyBLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }

                    // Field: Notes
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("📝 الملاحظات والتنبيهات المختبرية:", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (sessionTestName.isNotBlank() || sampleOrProduct.isNotBlank()) {
                                val finalTestName = sessionTestName.trim().ifBlank { sampleOrProduct.trim() }
                                val finalSample = sampleOrProduct.trim().ifBlank { sessionTestName.trim() }
                                val isComp = selectedTypeMode == "comparison"
                                onConfirm(
                                    finalTestName,
                                    finalSample,
                                    if (isComp) categoryValue else selectedSingleCategory,
                                    if (isComp) "⚖️ فحص مقارنة" else "🧪 فحص أحادي",
                                    notes.trim(),
                                    if (isComp) comparisonTypeValue else null,
                                    if (isComp) partyA.trim().ifBlank { "الطرف أ" } else null,
                                    if (isComp) partyB.trim().ifBlank { "الطرف ب" } else null
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (selectedTypeMode == "comparison") LabPurple else LabBlueMain),
                        enabled = sessionTestName.isNotBlank() || sampleOrProduct.isNotBlank()
                    ) {
                        Text("حفظ التغييرات 💾", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إلغاء", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicateSessionDialog(
    session: LabSession,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var newSessionName by remember { mutableStateOf("${session.testName} (نسخة)") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📄 عمل نسخة كاملة من الجلسة",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, thickness = 1.dp)

                Text(
                    text = "سيقوم النظام بإنشاء نسخة طبق الأصل من هذه الجلسة بما تحمله من فحوصات ونتائج وملاحظات وأسماء العينات (للجلسات المقارنة).",
                    fontSize = 11.5.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "✍️ اسم الجلسة الجديدة:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    OutlinedTextField(
                        value = newSessionName,
                        onValueChange = { newSessionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        placeholder = { Text("أدخل اسم الجلسة الجديدة", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LabPurple,
                            unfocusedBorderColor = LabBorder
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (newSessionName.isNotBlank()) {
                                onConfirm(newSessionName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("تأكيد النسخ 📄", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إلغاء", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CloneAsTemplateDialog(
    session: LabSession,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var newSessionName by remember { mutableStateOf("${session.testName} (قالب)") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 نسخ الجلسة كقالب",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, thickness = 1.dp)

                Text(
                    text = "سيقوم النظام بإنشاء نسخة مستقلة تماماً وجديدة من هذه الجلسة بجميع الفحوصات المضافة وإعداداتها ومعاييرها (مثل Spindle، السرعات، نسبة التخفيف)، وتكون فارغة تماماً من نتائج الفحوصات ومستعدة لإدخال القياسات.",
                    fontSize = 11.5.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "✍️ اسم الجلسة الجديدة:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    OutlinedTextField(
                        value = newSessionName,
                        onValueChange = { newSessionName = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        placeholder = { Text("مثال: فحص لزوجة وصلابة عينة B", fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LabBlueMain,
                            unfocusedBorderColor = LabBorder
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Right)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (newSessionName.isNotBlank()) {
                                onConfirm(newSessionName)
                            }
                        },
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                        enabled = newSessionName.isNotBlank()
                    ) {
                        Text("إنشاء النسخة ⚡", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text("إلغاء", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun KpiCard(
    title: String,
    count: Int,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LabBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = count.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
        }
    }
}

@Composable
fun FilterChipCustom(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) LabBlueMain else LabLightBg,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) LabBlueMain else LabBorder),
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else LabDarkIndigo,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionListItem(
    item: LabSession,
    completedCount: Int,
    totalCount: Int,
    hasAttachments: Boolean = false,
    lang: String = "ar",
    onClick: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onCloneClick: () -> Unit = {},
    onDuplicateClick: () -> Unit = {},
    onAssignFolderClick: () -> Unit = {}
) {
    val isComparison = item.testType == "⚖️ فحص مقارنة"
    val progressFraction = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
    val progressPercent = (progressFraction * 100).toInt()

    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, if (isComparison) LabPurple.copy(alpha = 0.25f) else LabBlueMain.copy(alpha = 0.15f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Serial and Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Serial Number ID Badge and Attachment pin
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isComparison) LabPurple.copy(alpha = 0.1f) else LabBlueMain.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.sessionNumber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isComparison) LabPurple else LabBlueMain
                        )
                    }

                    if (hasAttachments) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isComparison) LabPurple.copy(alpha = 0.18f) else LabBlueMain.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isComparison) LabPurple.copy(alpha = 0.3f) else LabBlueMain.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Attachment,
                                    contentDescription = "يحتوي على مرفقات",
                                    tint = if (isComparison) LabPurple else LabBlueMain,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (lang == "ar") "مرفق" else "Attachment",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isComparison) LabPurple else LabBlueMain
                                )
                            }
                        }
                    }
                }

                // Test Type Label
                Box(
                    modifier = Modifier
                        .background(Color(0xFFF1F5F9), shape = RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.testType,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body: Title / Test Name
            val (arSessionName, enSessionName) = remember(item.testName) { splitTestName(item.testName) }
            Column {
                Text(
                    text = arSessionName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (enSessionName.isNotBlank()) {
                    Text(
                        text = enSessionName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Sample / Product info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📦 العينة الخاضعة: ",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Text(
                    text = item.sampleOrProduct,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabDarkIndigo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Comparison parties preview
            if (isComparison && (!item.partyA.isNullOrBlank() || !item.partyB.isNullOrBlank())) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LabPurple.copy(alpha = 0.04f), shape = RoundedCornerShape(10.dp))
                        .border(1.dp, LabPurple.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🧪 ${getPartyName(item.partyA)}", fontSize = 11.sp, color = LabPurple, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("⚡ مقابل", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                        Text("🔍 ${getPartyName(item.partyB)}", fontSize = 11.sp, color = LabCyan, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Compact linear progress bar tracking
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تقدم الفحوصات الجارية",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray
                    )
                    Text(
                        text = if (totalCount > 0) "$completedCount / $totalCount مكتملة ($progressPercent%)" else "بدون فحوصات بعد ⚠️",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (progressFraction == 1f && totalCount > 0) LabSuccessGreen else LabBlueMain
                    )
                }
                if (totalCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (progressFraction == 1f) LabSuccessGreen else (if (isComparison) LabPurple else LabBlueMain),
                        trackColor = Color(0xFFE2E8F0)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = LabBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Footer Row: Date and Category / Folder Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category & Folder badges
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = when (item.category) {
                                    "منتجات نهائية" -> Color(0xFFE8F5E9)
                                    "مواد خام" -> Color(0xFFE3F2FD)
                                    "عينات تطوير" -> Color(0xFFFFF3E0)
                                    else -> Color(0xFFF3E5F5)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.category,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (item.category) {
                                "منتجات نهائية" -> Color(0xFF2E7D32)
                                "مواد خام" -> Color(0xFF1565C0)
                                "عينات تطوير" -> Color(0xFFEF6C00)
                                else -> LabPurple
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val folderName = getLabSessionFolder(item)
                    val displayFolderName = if (folderName.isNotBlank()) folderName else "العامة"
                    Box(
                        modifier = Modifier
                            .background(
                                color = LabPurple.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = LabPurple,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = displayFolderName,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabPurple,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Date
                val formattedDate = remember(item.testDate) {
                    val raw = item.testDate.trim()
                    if (raw.contains("T")) raw.split("T").firstOrNull() ?: raw
                    else if (raw.contains(" ")) raw.split(" ").firstOrNull() ?: raw
                    else if (raw.length > 10) raw.take(10)
                    else raw
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Icon(imageVector = Icons.Default.DateRange, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(11.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = formattedDate,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }

    DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            val isQcSessionOfActiveOrder = item.sampleProperties.startsWith("ORDER_ID:")
            if (!isQcSessionOfActiveOrder) {
                DropdownMenuItem(
                    text = { Text("✏️ تعديل الجلسة (Edit)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabDarkIndigo) },
                    onClick = {
                        showMenu = false
                        onEditClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("📁 نقل إلى مجلد (Move to Folder)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabPurple) },
                    onClick = {
                        showMenu = false
                        onAssignFolderClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("📄 عمل نسخة (Duplicate)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabPurple) },
                    onClick = {
                        showMenu = false
                        onDuplicateClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("📋 نسخ الجلسة كقالب (Copy as Template)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabBlueMain) },
                    onClick = {
                        showMenu = false
                        onCloneClick()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("🗑️ حذف الجلسة (Delete)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabErrorRed) },
                onClick = {
                    showMenu = false
                    onDeleteClick()
                }
            )
        }
    }
}

private fun formatQuantity(value: Double): String {
    return if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

@Composable
fun SessionDetailsView(
    session: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    showReportViewExternal: Boolean? = null,
    onShowReportViewExternalChange: ((Boolean) -> Unit)? = null,
    isEmbedded: Boolean = false
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var testPendingDelete by remember { mutableStateOf<LabTest?>(null) }
    var showAddTestDialog by remember { mutableStateOf(false) }
    var activeTestView by remember { mutableStateOf<LabTest?>(null) }
    var sessionNotesState by remember(session.notes) { mutableStateOf(session.notes) }
    
    var showReportViewInternal by remember { mutableStateOf(false) }
    val showReportView = showReportViewExternal ?: showReportViewInternal
    val setShowReportView: (Boolean) -> Unit = { newVal ->
        if (onShowReportViewExternalChange != null) {
            onShowReportViewExternalChange(newVal)
        } else {
            showReportViewInternal = newVal
        }
    }
    
    var showUnsavedChangesBackDialog by remember { mutableStateOf(false) }

    var aiAnalysisResult by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var aiErrorMsg by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    var comparisonSessionIdA by remember { mutableStateOf<String?>(null) }
    var comparisonSessionIdB by remember { mutableStateOf<String?>(null) }
    
    var showCompareOptionDialog by remember { mutableStateOf(false) }
    var showCompareOrderListDialog by remember { mutableStateOf(false) }

    val orders by viewModel.productionOrders.collectAsStateWithLifecycle()
    val formulations by viewModel.formulations.collectAsStateWithLifecycle()
    val labSessions by viewModel.labSessions.collectAsStateWithLifecycle()

    val currentOrder = remember(orders, session.sampleProperties) {
        session.sampleProperties?.let { prop ->
            if (prop.startsWith("ORDER_ID:")) {
                val oId = prop.removePrefix("ORDER_ID:").substringBefore(":QC")
                orders.find { it.id == oId }
            } else null
        }
    }
    
    val currentFormulation = remember(formulations, currentOrder) {
        currentOrder?.let { order ->
            formulations.find { it.id == order.formulationId }
        }
    }

    val eligibleOtherOrders = remember(orders, labSessions, currentOrder) {
        if (currentOrder == null) emptyList() else {
            orders.filter { other ->
                other.id != currentOrder.id &&
                other.formulationId == currentOrder.formulationId &&
                labSessions.any { s -> s.sampleProperties == "ORDER_ID:${other.id}" }
            }
        }
    }

    // Observe tests for this session
    val testsFlow = remember(session.id) { viewModel.getLabTestsForSession(session.id) }
    val rawTests by testsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val tests = remember(rawTests) { sortLabTests(rawTests) }

    val hasChanges = remember(sessionNotesState, session.notes) { sessionNotesState != session.notes }

    // BackHandler to exit single test view, report view, or warn about unsaved changes
    BackHandler(enabled = true) {
        if (comparisonSessionIdA != null && comparisonSessionIdB != null) {
            comparisonSessionIdA = null
            comparisonSessionIdB = null
        } else if (activeTestView != null) {
            activeTestView = null
        } else if (showReportView) {
            setShowReportView(false)
        } else if (showUnsavedChangesBackDialog) {
            showUnsavedChangesBackDialog = false
        } else {
            if (hasChanges) {
                showUnsavedChangesBackDialog = true
            } else {
                onBack()
            }
        }
    }

    if (comparisonSessionIdA != null && comparisonSessionIdB != null) {
        DirectComparisonView(
            sessionIdA = comparisonSessionIdA!!,
            sessionIdB = comparisonSessionIdB!!,
            viewModel = viewModel,
            onBack = {
                comparisonSessionIdA = null
                comparisonSessionIdB = null
            }
        )
        return
    }

    if (activeTestView != null) {
        val currentActiveTest = tests.find { it.id == activeTestView?.id } ?: activeTestView!!
        // Render single test workspace
        SingleTestWorkspaceView(
            test = currentActiveTest,
            parentSession = session,
            viewModel = viewModel,
            onBack = { activeTestView = null }
        )
        return
    }

    if (showReportView) {
        SessionReportView(
            session = session,
            viewModel = viewModel,
            onBack = { setShowReportView(false) }
        )
        return
    }

    // Progress calculations
    val totalTests = tests.size
    val completedTests = tests.count { it.status == "مكتمل" }
    val progressFraction = if (totalTests > 0) completedTests.toFloat() / totalTests else 0f
    val progressPercent = (progressFraction * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (isEmbedded) 0.dp else 16.dp,
                vertical = if (isEmbedded) 2.dp else 16.dp
            )
    ) {
        // Operational Alerts for Laboratory Sessions
        com.example.ui.LinkedAlertsDisplay(
            mainSection = "المختبر",
            elementId = session.id,
            viewModel = viewModel
        )

        // ID & Header Banner - Hidden in Embedded mode to avoid duplicate info
        if (!isEmbedded) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (hasChanges) {
                                        showUnsavedChangesBackDialog = true
                                    } else {
                                        onBack()
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "رجوع",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = session.sessionNumber,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = session.testType,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    val (arSessionName, enSessionName) = remember(session.testName) { splitTestName(session.testName) }
                    Column {
                        Text(
                            text = arSessionName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (enSessionName.isNotBlank()) {
                            Text(
                                text = enSessionName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "تاريخ الجلسة: ${session.testDate}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // Warning banner card if there are unsaved modifications
        if (hasChanges) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706))
                        Text(
                            text = "تغييرات غير محفوظة! ⚠️",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFFB45309)
                        )
                    }
                    Text(
                        text = "لقد أجريت تعديلاً على ملاحظات وقرار هذه الجلسة. يرجى حفظ التغييرات ليتم اعتمادها أو إلغاء التعديل للرجوع.",
                        fontSize = 12.sp,
                        color = Color(0xFF92400E)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val updatedSession = session.copy(notes = sessionNotesState.trim())
                                viewModel.updateLabSession(updatedSession)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("حفظ التغييرات 💾", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                sessionNotesState = session.notes
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFD97706)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD97706)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("إلغاء التعديل ↩️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Progress Tracking Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = if (isEmbedded) 8.dp else 12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تقدم الفحوصات داخل الجلسة 📊",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    Text(
                        text = if (totalTests > 0) "$completedTests / $totalTests فحوصات مكتملة ($progressPercent%)" else "لا توجد فحوصات مضافة",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (progressFraction == 1f && totalTests > 0) LabSuccessGreen else LabBlueMain
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (progressFraction == 1f && totalTests > 0) LabSuccessGreen else (if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain),
                    trackColor = LabLightBg
                )
            }
        }

        // Section 2: ⚖️ أطراف المقارنة
        if (session.testType == "⚖️ فحص مقارنة") {
            Text(
                text = "⚖️ أطراف وعناصر الفحص المقارن",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = LabDarkIndigo,
                modifier = Modifier.padding(bottom = if (isEmbedded) 4.dp else 8.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = if (isEmbedded) 8.dp else 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "نوع المقارنة: ${session.comparisonType ?: "مخصصة"}",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(LabPurple.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(text = "الطرف الأول (A) 🧪", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabPurple)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = getPartyName(session.partyA), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            val descA = getPartyDesc(session.partyA)
                            if (descA.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = descA, fontSize = 10.sp, color = Color.Gray)
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(LabCyan.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(text = "الطرف الثاني (B) 🔍", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabCyan)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = getPartyName(session.partyB), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            val descB = getPartyDesc(session.partyB)
                            if (descB.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(text = descB, fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        // Section 3: 🧪 الفحوصات داخل الجلسة
        if (isEmbedded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🧪 الفحوصات والقياسات",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(LabBlueMain.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("انقر للفتح", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (session.sampleProperties?.startsWith("ORDER_ID:") == true && session.sampleProperties?.endsWith(":QC") != true) {
                        Button(
                            onClick = { showCompareOptionDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مقارنة ومطابقة ⚖️", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = { showAddTestDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = if (session.sampleProperties?.startsWith("ORDER_ID:") == true) Modifier.weight(1f) else Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إضافة فحص ➕", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧪 الفحوصات والقياسات",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LabBlueMain.copy(alpha = 0.1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("انقر للفتح", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.sampleProperties?.startsWith("ORDER_ID:") == true && session.sampleProperties?.endsWith(":QC") != true) {
                        Button(
                            onClick = { showCompareOptionDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مقارنة ومطابقة ⚖️", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = { showAddTestDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إضافة فحص ➕", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Smart Rheology suggestion panel
        if (session.testType == "🧪 فحص أحادي" || session.testType == "⚖️ فحص مقارنة" || session.testType.contains("أحادي") || session.testType.contains("مقارنة") || session.testType.contains("Single") || session.testType.contains("Comparison") || session.testType.contains("QC") || session.testType.contains("مراقبة") || session.testType.contains("Quality")) {
            val completedViscosityTests = remember(tests) {
                tests.filter { t -> t.notes.startsWith("WIZARD_VISCOSITY:") }.mapNotNull { t ->
                    try {
                        val pureJson = t.notes.removePrefix("WIZARD_VISCOSITY:")
                        val moshi = com.squareup.moshi.Moshi.Builder()
                            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                            .build()
                        val adapter = moshi.adapter(ViscosityTestData::class.java)
                        val data = adapter.fromJson(pureJson)
                        if (data != null && data.testType == "STANDARD" && data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                            val validReadings = data.readings.filter { it.viscosity.isNotBlank() }
                            if (validReadings.isNotEmpty()) {
                                val avgVisc = validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                                val speedVal = data.speed.toDoubleOrNull()
                                if (speedVal != null && avgVisc > 0.0) {
                                    ViscosityCandidate(
                                        test = t,
                                        data = data,
                                        avgViscosity = avgVisc,
                                        spindle = data.spindle,
                                        speed = speedVal
                                    )
                                } else null
                            } else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val rheologyPairs = remember(completedViscosityTests) {
                val pairs = mutableListOf<Pair<ViscosityCandidate, ViscosityCandidate>>()
                for (i in completedViscosityTests.indices) {
                    for (j in i + 1 until completedViscosityTests.size) {
                        val cand1 = completedViscosityTests[i]
                        val cand2 = completedViscosityTests[j]
                        if (cand1.spindle == cand2.spindle) {
                            val (low, high) = if (cand1.speed < cand2.speed) Pair(cand1, cand2) else Pair(cand2, cand1)
                            if (kotlin.math.abs(high.speed - (low.speed * 10.0)) < 0.01) {
                                pairs.add(Pair(low, high))
                            }
                        }
                    }
                }
                pairs
            }

            val existingRheologies = remember(tests) {
                tests.filter { t -> t.notes.contains("RHEOLOGY") || t.name.contains("ريولوجي") }.mapNotNull { t ->
                    try {
                        if (t.notes.startsWith("WIZARD_VISCOSITY:")) {
                            val pureJson = t.notes.removePrefix("WIZARD_VISCOSITY:")
                            val moshi = com.squareup.moshi.Moshi.Builder()
                                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                .build()
                            val adapter = moshi.adapter(ViscosityTestData::class.java)
                            val data = adapter.fromJson(pureJson)
                            if (data?.testType == "RHEOLOGY") data else null
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            val unusedRheologyPairs = remember(rheologyPairs, existingRheologies) {
                rheologyPairs.filter { pair ->
                    val low = pair.first
                    val high = pair.second
                    existingRheologies.none { r ->
                        r.spindle == low.spindle &&
                        kotlin.math.abs((r.speed.toDoubleOrNull() ?: -1.0) - low.speed) < 0.01 &&
                        kotlin.math.abs((r.secondSpeed.toDoubleOrNull() ?: -1.0) - high.speed) < 0.01
                    }
                }
            }

            if (unusedRheologyPairs.isNotEmpty()) {
                unusedRheologyPairs.forEach { pair ->
                    val low = pair.first
                    val high = pair.second
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("rheology_auto_suggest_card"),
                        colors = CardDefaults.cardColors(containerColor = LabBlueMain.copy(alpha = 0.07f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💡", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "اقتراح ذكي لربط فحوص اللزوجة:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabDarkIndigo
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "تم كشف فحصي لزوجة قياسيين لنفس العينة بنفس المغزل وبنسبة سرعة متوافقة (10x). يمكنك الآن حساب مؤشر السلوك الريولوجي مباشرة دون الحاجة لإعادة الإدخال.",
                                fontSize = 10.5.sp,
                                color = Color(0xFF334155),
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            // Info badge row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .border(0.5.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text("السرعة 1 (منخفضة):", fontSize = 8.sp, color = Color.Gray)
                                        val lowSpeedStr = if (low.speed % 1.0 == 0.0) low.speed.toInt().toString() else low.speed.toString()
                                        Text("$lowSpeedStr RPM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        Text("اللزوجة: ${String.format(Locale.US, "%,.0f cP", low.avgViscosity)}", fontSize = 9.sp, color = LabSuccessGreen)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .border(0.5.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text("السرعة 2 (عالية):", fontSize = 8.sp, color = Color.Gray)
                                        val highSpeedStr = if (high.speed % 1.0 == 0.0) high.speed.toInt().toString() else high.speed.toString()
                                        Text("$highSpeedStr RPM", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        Text("اللزوجة: ${String.format(Locale.US, "%,.0f cP", high.avgViscosity)}", fontSize = 9.sp, color = LabSuccessGreen)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .border(0.5.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("المغزل:", fontSize = 8.sp, color = Color.Gray)
                                        Text(low.spindle, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = LabPurple)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Button(
                                onClick = {
                                    val rIndex = if (high.avgViscosity > 0.0) low.avgViscosity / high.avgViscosity else 1.0
                                    val reduction = if (low.avgViscosity > 0.0) ((low.avgViscosity - high.avgViscosity) / low.avgViscosity) * 100.0 else 0.0
                                    val linkedData = ViscosityTestData(
                                        spindle = low.spindle,
                                        speed = String.format(Locale.US, "%.0f", low.speed),
                                        readings = low.data.readings,
                                        testType = "RHEOLOGY",
                                        secondSpeed = String.format(Locale.US, "%.0f", high.speed),
                                        readingsSecondSpeed = high.data.readings,
                                        rheologyIndex = rIndex,
                                        viscosityReductionPct = reduction,
                                        isAutoCalculated = true,
                                        sourceSpeed1Name = "فحص لزوجة #${low.test.id.take(4)}",
                                        sourceSpeed2Name = "فحص لزوجة #${high.test.id.take(4)}"
                                    )
                                    val serialized = serializeViscosityData(linkedData)
                                    viewModel.addLabTest(
                                        sessionId = session.id,
                                        name = "فحص السلوك الريولوجي",
                                        executionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                                        status = "مكتمل",
                                        notes = serialized,
                                        testValueA = String.format(Locale.US, "%.2f R.I", rIndex)
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("إنشاء فحص ريولوجي وحساب المؤشر تلقائياً 🧪✨", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // List of Tests in Container for prominent visibility and extra breathing space
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(LabBlueMain.copy(alpha = 0.02f))
                .border(1.dp, LabBorder.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(
                    start = if (isEmbedded) 8.dp else 12.dp,
                    end = if (isEmbedded) 8.dp else 12.dp,
                    top = if (isEmbedded) 4.dp else 6.dp,
                    bottom = if (isEmbedded) 8.dp else 12.dp
                )
        ) {
            if (tests.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("📦", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "لا توجد فحوصات مضافة لهذه الجلسة حتى الآن",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اضغط على زر 'إضافة فحص للعملية' بالأعلى للبدء بقياس اللزوجة، الكثافة أو الفحوصات الأخرى.",
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    tests.forEach { test ->
                        TestItemCard(
                            test = test,
                            session = session,
                            operatorName = session.technicianName,
                            onOpen = { activeTestView = test },
                            onDelete = { testPendingDelete = test }
                        )
                    }
                }
            }
        }

        // --- START OF AI R&D ADVISOR PANEL ---
        if (session.testType == "⚖️ فحص مقارنة") {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "🤖 الاستشارة الفنية والتحليل الذكي (AI R&D Advisor)",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = LabPurple,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("ai_rd_advisor_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = LabPurple,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "مستشار التطوير الفني (الذكاء الاصطناعي)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                        }

                        if (aiAnalysisResult != null) {
                            TextButton(
                                onClick = {
                                    aiAnalysisResult = null
                                    aiErrorMsg = null
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("إعادة تعيين 🗑️", fontSize = 11.sp, color = LabErrorRed)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "يمكنك الآن طلب استشارة ذكاء اصطناعي لدراسة الفروق بين العينة (A) والعينة (B). سيقوم النظام بتحليل واشتقاق الأثر المعملي لكل فحص، وتقييم جودة التعديلات كيميائياً، واقتراح مقترحات بحث وتطوير ملموسة لمستقبل خط الإنتاج.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (isAiLoading) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = LabPurple,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Text(
                                text = "جاري قراءة الفحوصات الجارية وتحليل الفروقات الفيزيوكيميائية...",
                                fontSize = 11.sp,
                                color = LabPurple,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (aiAnalysisResult != null) {
                        val resultStr = aiAnalysisResult ?: ""
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LabLightBg)
                                .border(1.dp, LabBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = resultStr,
                                    fontSize = 11.5.sp,
                                    color = LabDarkIndigo,
                                    lineHeight = 18.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Divider(color = LabBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val ctx = LocalContext.current
                                    Button(
                                        onClick = {
                                            val appliedNote = "💡 استشارة الذكاء الاصطناعي الفنية لمقارنة العينات:\n$resultStr"
                                            sessionNotesState = appliedNote
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Done,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "تطبيق الاستشارة كقرار مسجل بالجلسة ✅",
                                            fontSize = 9.5.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            try {
                                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("AI R&D Analysis", resultStr)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(ctx, "تم نسخ التحليل بنجاح! 📋", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Log.e("LabAi", "Copy error", e)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "نسخ",
                                            tint = LabPurple,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val hasCompletedTests = completedTests > 0
                        Button(
                            onClick = {
                                isAiLoading = true
                                aiErrorMsg = null
                                coroutineScope.launch {
                                    try {
                                        val result = GeminiClient.analyzeLabSession(session, tests)
                                        aiAnalysisResult = result
                                    } catch (e: Exception) {
                                        aiErrorMsg = "عذراً، فشل استعلام المساعد الذكي: ${e.message}"
                                    } finally {
                                        isAiLoading = false
                                    }
                                }
                            },
                            enabled = hasCompletedTests,
                            colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (hasCompletedTests) "توليد الاستشارة وتحليل الفروقات الذكي ⚡" else "يرجى إدخال واكتمال فحص واحد على الأقل للتحليل ⚠️",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (aiErrorMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = aiErrorMsg ?: "",
                            fontSize = 11.sp,
                            color = LabErrorRed,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        // --- END OF AI R&D ADVISOR PANEL ---

        Spacer(modifier = Modifier.height(12.dp))

        // Section 5: ✅ القرار النهائي
        Text(
            text = "✅ القرار الفني والاستنتاج النهائي الموثق",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = LabDarkIndigo,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (session.testType == "⚖️ فحص مقارنة") "الاستنتاج النهائي وقرار المختبر الفني 📝" else "ملاحظات الجلسة والنتائج الأولية 📝",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = sessionNotesState,
                    onValueChange = { sessionNotesState = it },
                    placeholder = {
                        Text(
                            text = if (session.testType == "⚖️ فحص مقارنة") {
                                "سجل هنا الاستنتاج الفني والقرار النهائي للمقارنة (مثال: التركيبة الجديدة أفضل من القديمة، أو لا يوجد فرق جوهري...)"
                            } else {
                                "سجل هنا ملاحظات الجلسة أو المواصفات المقاسة..."
                            },
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        val updatedSession = session.copy(notes = sessionNotesState.trim())
                        viewModel.updateLabSession(updatedSession)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain),
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (session.testType == "⚖️ فحص مقارنة") "تحديث الاستنتاج الفني والقرار" else "تحديث الملاحظات والنتائج",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Delete & Save Actions section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    val updatedSession = session.copy(notes = sessionNotesState.trim())
                    viewModel.updateLabSession(updatedSession)
                },
                enabled = hasChanges,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain,
                    disabledContainerColor = Color.LightGray
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ التغييرات", fontWeight = FontWeight.Bold, color = Color.White)
            }

            Button(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حذف الجلسة", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lab Session Attachments Section
        LabSessionAttachmentsSection(
            session = session,
            viewModel = viewModel
        )
    }

    if (showAddTestDialog) {
        AddTestToSessionDialog(
            viewModel = viewModel,
            onDismiss = { showAddTestDialog = false },
            onConfirm = { selectedTestNames ->
                // فحص واحد فقط: الانتقال مباشرة لصفحة الفحص؛ أكثر من فحص: يبقى السلوك الحالي
                val openAfterAdd: ((LabTest) -> Unit)? =
                    if (selectedTestNames.size == 1) ({ t: LabTest -> activeTestView = t }) else null
                selectedTestNames.forEach { tName ->
                    viewModel.addLabTest(
                        sessionId = session.id,
                        name = tName,
                        executionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        onAdded = openAfterAdd
                    )
                }
                showAddTestDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            title = {
                Text("تأكيد الحذف ⚠️", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            text = {
                Text("هل أنت متأكد من رغبتك في حذف هذه الجلسة المختبرية بشكل نهائي؟ سيتم حذفها من الأرشيف والسحابة أيضاً.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("نعم، احذف بشكل نهائي", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    testPendingDelete?.let { pendingTest ->
        AlertDialog(
            onDismissRequest = { testPendingDelete = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            title = {
                Text("تأكيد حذف الفحص ⚠️", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            text = {
                Text("هل أنت متأكد من رغبتك في حذف الفحص \"${pendingTest.name}\" من هذه الجلسة؟ سيُنقل إلى سلة المحذوفات.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            confirmButton = {
                Button(
                    onClick = {
                        testPendingDelete = null
                        viewModel.deleteLabTest(pendingTest)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("نعم، احذف الفحص", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { testPendingDelete = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    if (showUnsavedChangesBackDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedChangesBackDialog = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "تنبيه: تغييرات غير محفوظة ⚠️",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Text(
                    text = "توجد تعديلات وملاحظات لم يتم حفظها في هذه الجلسة المختبرية. هل ترغب في حفظ التغييرات قبل الخروج والرجوع للأرشيف؟",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedSession = session.copy(notes = sessionNotesState.trim())
                        viewModel.updateLabSession(updatedSession)
                        showUnsavedChangesBackDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (session.testType == "⚖️ فحص مقارنة") LabPurple else LabBlueMain),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حفظ والرجوع 💾", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            sessionNotesState = session.notes
                            showUnsavedChangesBackDialog = false
                            onBack()
                        }
                    ) {
                        Text("تراجع (تجاهل التغييرات)", color = LabErrorRed)
                    }
                    TextButton(
                        onClick = { showUnsavedChangesBackDialog = false }
                    ) {
                        Text("إلغاء", color = Color.Gray)
                    }
                }
            }
        )
    }

    if (showCompareOptionDialog && currentOrder != null) {
        Dialog(
            onDismissRequest = { showCompareOptionDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "إجراء مقارنة ومطابقة جودة ⚖️",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        IconButton(onClick = { showCompareOptionDialog = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق")
                        }
                    }

                    Text(
                        text = "اختر نوع المقارنة والمطابقة الفنية لنتائج فحوص الجودة لهذه الدفعة:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        lineHeight = 18.sp
                    )

                    // Option 1: Compare with Reference Specs
                    Card(
                        onClick = {
                            coroutineScope.launch {
                                currentFormulation?.let { formulation ->
                                    val existingDbSession = viewModel.repository.getLabSessionById("ref_specs_${formulation.id}")
                                    if (existingDbSession == null) {
                                        val specs = viewModel.repository.getFormulationReferenceSpecsSync(formulation.id)
                                        val currentDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                        val newSession = LabSession(
                                            id = "ref_specs_${formulation.id}",
                                            sessionNumber = "REF-${formulation.code.ifBlank { "SPEC" }}",
                                            testName = "المواصفات المرجعية (${formulation.name})",
                                            testDate = currentDateStr,
                                            technicianName = "مدير التركيبة",
                                            sampleOrProduct = formulation.name,
                                            category = "المواصفات المرجعية",
                                            testType = "🧪 فحص أحادي",
                                            sampleProperties = "FORMULATION_ID:${formulation.id}"
                                        )
                                        viewModel.repository.insertLabSession(newSession)
                                        if (specs != null) {
                                            if (specs.phValue != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "🧪 فحص درجة القلوية (pH Value)",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    testValueA = specs.phValue
                                                ))
                                            }
                                            if (specs.densityFinalResult != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "🧪 فحص الكثافة النوعية (Specific Gravity - Density)",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    testValueA = String.format(Locale.US, "%.3f g/cm³", specs.densityFinalResult)
                                                ))
                                            }
                                            if (specs.solidResultPct != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "🧪 فحص نسبة الصلابة والجفاف (Solid Content & Drying Time)",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    testValueA = String.format(Locale.US, "%.2f %%", specs.solidResultPct)
                                                ))
                                            }
                                            if (specs.binderResultPct != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "🧪 فحص نسبة المادة الرابطة (Net Binder Content)",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    testValueA = String.format(Locale.US, "%.2f %%", specs.binderResultPct)
                                                ))
                                            }
                                            if (specs.viscosityFinalResult != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "🧪 فحص اللزوجة (Viscosity Test - KU)",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    notes = specs.viscosityJson ?: "",
                                                    testValueA = String.format(Locale.US, "%.0f cP", specs.viscosityFinalResult)
                                                ))
                                            }
                                            if (specs.viscosityDilutedFinalResult != null) {
                                                viewModel.repository.insertLabTest(LabTest(
                                                    sessionId = "ref_specs_${formulation.id}",
                                                    name = "اللزوجة بعد التخفيف بالماء",
                                                    status = "مكتمل",
                                                    executionDate = currentDateStr,
                                                    notes = specs.viscosityDilutedJson ?: "",
                                                    testValueA = String.format(Locale.US, "%.0f cP", specs.viscosityDilutedFinalResult)
                                                ))
                                            }
                                        }
                                    }
                                }
                                comparisonSessionIdA = session.id
                                comparisonSessionIdB = "ref_specs_${currentOrder.formulationId}"
                                showCompareOptionDialog = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("📋", fontSize = 28.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "المقارنة مع المواصفات المرجعية",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabDarkIndigo
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "عرض نتائج الجلسة مع نتائج المواصفات المرجعية المدخلة في هذه التركيبة.",
                                    fontSize = 10.5.sp,
                                    color = Color.Gray,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Option 2: Compare with another Production Order
                    Card(
                        onClick = {
                            showCompareOptionDialog = false
                            showCompareOrderListDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("🏭", fontSize = 28.sp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "مقارنة مع أمر إنتاج آخر",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabDarkIndigo
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "مقارنة نتائج هذه الدفعة مع نتائج دفعة إنتاج سابقة لنفس المنتج والتركيبة.",
                                    fontSize = 10.5.sp,
                                    color = Color.Gray,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCompareOrderListDialog && currentOrder != null) {
        Dialog(
            onDismissRequest = { showCompareOrderListDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.7f),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "اختر أمر إنتاج للمقارنة 🏭",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        IconButton(onClick = { showCompareOrderListDialog = false }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "أوامر الإنتاج المتوفرة لنفس التركيبة (${currentOrder.formulationName}) والتي تحتوي على فحوص جودة:",
                        fontSize = 11.5.sp,
                        color = Color.Gray,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (eligibleOtherOrders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("📭", fontSize = 36.sp)
                                Text(
                                    text = "لا توجد أوامر إنتاج أخرى مدخل لها فحوصات جودة لنفس التركيبة.",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(eligibleOtherOrders) { otherOrder ->
                                val otherSession = labSessions.find { it.sampleProperties == "ORDER_ID:${otherOrder.id}" }
                                Card(
                                    onClick = {
                                        if (otherSession != null) {
                                            comparisonSessionIdA = session.id
                                            comparisonSessionIdB = otherSession.id
                                            showCompareOrderListDialog = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "أمر رقم: ${otherOrder.orderNumber}",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LabDarkIndigo
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFFE0F2FE), RoundedCornerShape(10.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "دفعة: ${otherOrder.batchNumber}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF0369A1)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "الوزن: ${formatQuantity(otherOrder.requiredWeightKg)} كجم",
                                                fontSize = 11.sp,
                                                color = Color.DarkGray
                                            )
                                            Text(
                                                text = otherSession?.testDate?.let { "التاريخ: $it" } ?: "",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LabDarkIndigo)
        Divider(color = LabLightBg, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSessionDialog(
    currentUser: String,
    orders: List<ProductionOrder> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (
        testName: String,
        testDate: String,
        technicianName: String,
        sampleOrProduct: String,
        category: String,
        testType: String,
        notes: String,
        comparisonType: String?,
        partyA: String?,
        partyB: String?,
        sampleProperties: String
    ) -> Unit
) {
    // Input parameters states
    var sampleOrProduct by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    // Test Type State: single, comparison, production_active
    var selectedTypeMode by remember { mutableStateOf("single") }
    var selectedOrderForSession by remember { mutableStateOf<ProductionOrder?>(null) }

    // Dropdown list state for comparison categories/types
    var selectedComparisonTypeSeq by remember { mutableStateOf("تركيبة") } // "تركيبة", "دفعة", "مادة خام", "منتج منافس", "مخصصة"

    // Dynamic Labels and variables
    val partyALabel: String
    val partyBLabel: String
    val defaultA: String
    val defaultB: String
    val categoryValue: String
    val comparisonTypeValue: String

    when (selectedComparisonTypeSeq) {
        "تركيبة" -> {
            partyALabel = "رقم التركيبة الأولى (أ) 🧪"
            partyBLabel = "رقم التركيبة الثانية (ب) 🧪"
            defaultA = "تركيبة A"
            defaultB = "تركيبة B"
            categoryValue = "تركيبة مقابل تركيبة"
            comparisonTypeValue = "تركيبة قديمة مقابل تركيبة جديدة"
        }
        "دفعة" -> {
            partyALabel = "رقم الدفعة الأولى (أ) 📦"
            partyBLabel = "رقم الدفعة الثانية (ب) 📦"
            defaultA = "دفعة A"
            defaultB = "دفعة B"
            categoryValue = "دفعة مقابل دفعة"
            comparisonTypeValue = "دفعة مقابل دفعة أخرى"
        }
        "مادة خام" -> {
            partyALabel = "اسم المورد الأول / المصدر (أ) 🏭"
            partyBLabel = "اسم المورد الثاني / المصدر (ب) 🏭"
            defaultA = "مورد A"
            defaultB = "مورد B"
            categoryValue = "مادة خام مقابل مادة خام"
            comparisonTypeValue = "مادة خام مقابل مادة خام أخرى"
        }
        "منتج منافس" -> {
            partyALabel = "اسم منتج شركتنا (أ) 🏬"
            partyBLabel = "اسم منتج الشركة المنافسة (ب) 🏬"
            defaultA = "منتجنا"
            defaultB = "المنتج المنافس"
            categoryValue = "منتج مقابل منافس"
            comparisonTypeValue = "مطابقة منتجنا بمنتج منافس"
        }
        else -> { // "مخصصة"
            partyALabel = "اسم الطرف الأول (أ) 🧪"
            partyBLabel = "اسم الطرف الثاني (ب) 🔍"
            defaultA = "الطرف أ"
            defaultB = "الطرف ب"
            categoryValue = "مقارنة مخصصة"
            comparisonTypeValue = "مقارنة مخصصة"
        }
    }

    // Party names input states
    var partyAName by remember { mutableStateOf("") }
    var partyBName by remember { mutableStateOf("") }

    // Single / general categories options (unrelated to comparison if single category)
    val singleCategories = listOf("منتجات نهائية", "مواد خام", "عينات تطوير", "عينات سوق")
    var selectedSingleCategory by remember { mutableStateOf("منتجات نهائية") }

    val activeOrders = remember(orders) { orders.filter { it.status == "قيد التنفيذ" } }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "جلسة فحص مختبري جديدة 🧪",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, modifier = Modifier.padding(vertical = 8.dp))

                // Scrollable fields
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Substance / Sample
                    OutlinedTextField(
                        value = sampleOrProduct,
                        onValueChange = { sampleOrProduct = it },
                        label = { Text("اسم العينة / المنتج / المادة 📦", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Selection of single/comparison/production type
                    Text("نوع الفحص المختبري:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                onClick = { selectedTypeMode = "single" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedTypeMode == "single") LabBlueMain.copy(alpha = 0.1f) else LabLightBg
                                ),
                                border = BorderStroke(1.5.dp, if (selectedTypeMode == "single") LabBlueMain else LabBorder)
                            ) {
                                Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        "🧪 فحص أحادي",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTypeMode == "single") LabBlueMain else LabDarkIndigo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Card(
                                onClick = { selectedTypeMode = "comparison" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedTypeMode == "comparison") LabPurple.copy(alpha = 0.1f) else LabLightBg
                                ),
                                border = BorderStroke(1.5.dp, if (selectedTypeMode == "comparison") LabPurple else LabBorder)
                            ) {
                                Box(modifier = Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        "⚖️ فحص مقارنة",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTypeMode == "comparison") LabPurple else LabDarkIndigo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Card(
                            onClick = { selectedTypeMode = "production_active" },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedTypeMode == "production_active") Color(0xFFF59E0B).copy(alpha = 0.1f) else LabLightBg
                            ),
                            border = BorderStroke(1.5.dp, if (selectedTypeMode == "production_active") Color(0xFFD97706) else LabBorder)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "🏭 جلسة فحص لأمر إنتاج جاري",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedTypeMode == "production_active") Color(0xFFD97706) else LabDarkIndigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Depending on type, show appropriate layout
                    if (selectedTypeMode == "single") {
                        // Category Selections for Single Test
                        Text("تصنيف الأرشيف الفرعي:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            singleCategories.forEach { cat ->
                                val selected = selectedSingleCategory == cat
                                Surface(
                                    onClick = { selectedSingleCategory = cat },
                                    color = if (selected) LabBlueMain else LabLightBg,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Text(
                                        text = cat,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else LabDarkIndigo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    } else if (selectedTypeMode == "comparison") {
                        // IF COMPARISON DEV MODE:
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, LabPurple.copy(alpha = 0.2f)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF5FF)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "⚖️ تفاصيل المقارنة والمطابقة الفنية",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabPurple,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                var showTypeSeqMenu by remember { mutableStateOf(false) }
                                val comparisonOptions = listOf("تركيبة", "دفعة", "مادة خام", "منتج منافس", "مخصصة")
                                
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = selectedComparisonTypeSeq,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("نوع وتصنيف المقارنة ⚖️", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        trailingIcon = {
                                            IconButton(onClick = { showTypeSeqMenu = !showTypeSeqMenu }) {
                                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = LabPurple)
                                            }
                                        },
                                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    )
                                    DropdownMenu(
                                        expanded = showTypeSeqMenu,
                                        onDismissRequest = { showTypeSeqMenu = false },
                                        modifier = Modifier.fillMaxWidth(0.95f)
                                    ) {
                                        comparisonOptions.forEach { opt ->
                                            DropdownMenuItem(
                                                text = { Text(opt, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                onClick = {
                                                    selectedComparisonTypeSeq = opt
                                                    showTypeSeqMenu = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Party A Name
                                OutlinedTextField(
                                    value = partyAName,
                                    onValueChange = { partyAName = it },
                                    label = { Text(partyALabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )

                                // Party B Name
                                OutlinedTextField(
                                    value = partyBName,
                                    onValueChange = { partyBName = it },
                                    label = { Text(partyBLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    } else { // production_active
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.2f)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "🏭 اختر أمر الإنتاج الجاري المرتبط بجلسة الفحص:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD97706)
                                )

                                if (activeOrders.isEmpty()) {
                                    Text(
                                        "⚠️ لا توجد أوامر تشغيل/إنتاج قيد التنفيذ حالياً.",
                                        fontSize = 11.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    var showActiveOrdersMenu by remember { mutableStateOf(false) }
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { showActiveOrdersMenu = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFFD97706)),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LabDarkIndigo),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (selectedOrderForSession != null) {
                                                        "${selectedOrderForSession?.formulationName} (دفعة #${selectedOrderForSession?.batchNumber}) - أمر #${selectedOrderForSession?.orderNumber}"
                                                    } else {
                                                        "اختر أمر الإنتاج..."
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = Color(0xFFD97706),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showActiveOrdersMenu,
                                            onDismissRequest = { showActiveOrdersMenu = false },
                                            modifier = Modifier.fillMaxWidth(0.9f)
                                        ) {
                                            activeOrders.forEach { ord ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = "${ord.formulationName} (دفعة #${ord.batchNumber}) - أمر #${ord.orderNumber}",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    },
                                                    onClick = {
                                                        selectedOrderForSession = ord
                                                        sampleOrProduct = "دفعة الإنتاج #${ord.batchNumber}"
                                                        showActiveOrdersMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Notes / Specifications details (labeled as observations or optional notes)
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("ملاحظات أو وصف العينة (اختياري) 📝", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("إلغاء الأمر", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    val isConfirmEnabled = sampleOrProduct.isNotBlank() && (selectedTypeMode != "production_active" || selectedOrderForSession != null)
                    val confirmButtonColor = when (selectedTypeMode) {
                        "comparison" -> LabPurple
                        "production_active" -> Color(0xFFD97706)
                        else -> LabBlueMain
                    }

                    Button(
                        onClick = {
                            if (sampleOrProduct.isNotBlank()) {
                                val testDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                                val technicianName = currentUser
                                
                                when (selectedTypeMode) {
                                    "production_active" -> {
                                        val ord = selectedOrderForSession!!
                                        onConfirm(
                                            "فحص جودة: ${ord.formulationName}",
                                            testDate,
                                            technicianName,
                                            sampleOrProduct.trim(),
                                            "فحص جودة الإنتاج",
                                            "🧪 فحص أحادي",
                                            notes.trim().ifBlank { "جلسة فحص جودة مرتبطة بأمر الإنتاج رقم ${ord.orderNumber} لمنتج ${ord.formulationName}." },
                                            null,
                                            null,
                                            null,
                                            "ORDER_ID:${ord.id}"
                                        )
                                    }
                                    "comparison" -> {
                                        onConfirm(
                                            sampleOrProduct.trim(),
                                            testDate,
                                            technicianName,
                                            sampleOrProduct.trim(),
                                            categoryValue,
                                            "⚖️ فحص مقارنة",
                                            notes.trim(),
                                            comparisonTypeValue,
                                            if (partyAName.trim().isNotBlank()) partyAName.trim() else defaultA,
                                            if (partyBName.trim().isNotBlank()) partyBName.trim() else defaultB,
                                            ""
                                        )
                                    }
                                    else -> { // "single"
                                        onConfirm(
                                            sampleOrProduct.trim(),
                                            testDate,
                                            technicianName,
                                            sampleOrProduct.trim(),
                                            selectedSingleCategory,
                                            "🧪 فحص أحادي",
                                            notes.trim(),
                                            null,
                                            null,
                                            null,
                                            ""
                                        )
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = confirmButtonColor),
                        enabled = isConfirmEnabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("إنشاء الجلسة وحفظها", fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// Helper to split test name into Arabic and English lines
fun splitTestName(fullName: String): Pair<String, String> {
    val cleanName = fullName.replace("🧪", "").replace("🔬", "").trim()
    if (cleanName.contains("(") && cleanName.contains(")")) {
        val startIdx = cleanName.indexOf("(")
        val endIdx = cleanName.lastIndexOf(")")
        if (endIdx > startIdx) {
            val arPart = cleanName.substring(0, startIdx).trim()
            val enPart = cleanName.substring(startIdx + 1, endIdx).trim()
            return Pair(arPart, enPart)
        }
    }
    if (cleanName.contains("/")) {
        val parts = cleanName.split("/")
        return Pair(parts[0].trim(), parts[1].trim())
    }
    return Pair(cleanName, "")
}

// Helper to extract the unit of measurement (e.g. cP, g/cm³)
fun extractUnit(text: String?): String {
    if (text == null) return ""
    val pureUnit = text.replace(Regex("[0-9.%\\s-]"), "").trim()
    return pureUnit
}

// Helper to generate a contextual, kymological comparative assessment
fun generateComparativeAnalysis(testName: String, numA: Double, numB: Double, unit: String): String {
    val diff = numB - numA
    val pct = if (numA != 0.0) (diff / numA) * 100.0 else 0.0
    val absPct = kotlin.math.abs(pct)
    val nameLower = testName.lowercase()
    
    val isViscosity = nameLower.contains("اللزوجة") || nameLower.contains("viscosity") || nameLower.contains("فورد") || nameLower.contains("ford")
    val isDensity = nameLower.contains("الكثافة") || nameLower.contains("density") || nameLower.contains("gravity") || nameLower.contains("كثافة")
    val isSolid = nameLower.contains("الصلابة") || nameLower.contains("solid") || nameLower.contains("الجفاف") || nameLower.contains("drying")
    val isPh = nameLower.contains("قلوية") || nameLower.contains("ph")
    
    return when {
        isViscosity -> {
            if (diff > 0.0) {
                "تسجل العينـة (B) لزوجة أعلى بمقدار ${String.format(Locale.US, "%.1f", diff)} $unit (+${String.format(Locale.US, "%.1f%%", absPct)}) مقارنة بـ (A)، مما يمنحها قواماً أكثر تماسكاً ومقاومة أكبر للسيلان."
            } else if (diff < 0.0) {
                "تسجل العينـة (B) لزوجة أقل بمقدار ${String.format(Locale.US, "%.1f", kotlin.math.abs(diff))} $unit (-${String.format(Locale.US, "%.1f%%", absPct)}) مقارنة بـ (A)، مما يمنحها تدفقاً وسهولة فرد أكبر بالتطبيق."
            } else {
                "تطابق تام في معدل اللزوجة المقاس بين العينة (A) والعينة (B) بواقع $numA $unit."
            }
        }
        isDensity -> {
            if (diff > 0.0) {
                "تسجل العينة (B) كثافة أعلى بمقدار ${String.format(Locale.US, "%.3f", diff)} $unit (+${String.format(Locale.US, "%.1f%%", absPct)}) عن (A)، مما يدل على تركيز أعلى للمواد الصلبة المالئة أو الجليكول."
            } else if (diff < 0.0) {
                "تسجل العينة (B) كثافة أقل بمقدار ${String.format(Locale.US, "%.3f", kotlin.math.abs(diff))} $unit (-${String.format(Locale.US, "%.1f%%", absPct)}) عن (A)، مما يعكس زيادة طفيفة للمذيبات الخفيفة أو الماء."
            } else {
                "تطابق تام وكامل في الكثافة النوعية والوزن الحجمي لكلا الطرفين."
            }
        }
        isSolid -> {
            if (diff > 0.0) {
                "هناك ارتفاع في المواد الصلبة والطلاء الجاف للعينـة (B) بمقدار ${String.format(Locale.US, "%.1f", diff)}$unit (+${String.format(Locale.US, "%.1f%%", absPct)}) مما يزيد سمك تغطية الفيلم النهائي."
            } else if (diff < 0.0) {
                "هناك انخفاض في المواد الجافة للعينـة (B) بمقدار ${String.format(Locale.US, "%.1f", kotlin.math.abs(diff))}$unit (-${String.format(Locale.US, "%.1f%%", absPct)}) مما قد يقلل من سماكة الفيلم الجاف والتغطية."
            } else {
                "تساوٍ تام في التغطية ونسبة التبخر والمحتوى الصلب الجاف للعينتين."
            }
        }
        isPh -> {
            if (diff > 0.0) {
                "ارتفاع في القلوية للعينـة (B) بمقدار ${String.format(Locale.US, "%.2f", diff)} درجات عن (A)، مما يزيد من استقراره الكيميائي وثبات المواد الحافظة."
            } else if (diff < 0.0) {
                "انخفاض في القلوية للعينـة (B) بمقدار ${String.format(Locale.US, "%.2f", kotlin.math.abs(diff))} درجات عن (A)، يرجى التأكد من بقائها في النطاق الآمن لمنع فساد الخلطة."
            } else {
                "مستوى الحموضة (pH) متطابق كيميائياً وبمستوى أمان مثالي."
            }
        }
        else -> {
            if (diff > 0.0) {
                "تسجل العينة (B) قيمة أعلى بفرق $diff، بنسبة تغير قدرها +${String.format(Locale.US, "%.1f%%", absPct)} مقارنة بالعينة (A)."
            } else if (diff < 0.0) {
                "تسجل العينة (B) قيمة أقل بفرق ${kotlin.math.abs(diff)}، بنسبة تغير قدرها -${String.format(Locale.US, "%.1f%%", absPct)} مقارنة بالعينة (A)."
            } else {
                "القيمتان متطابقتان تماماً بدون أي فروقات قياسية."
            }
        }
    }
}

fun getTestValueALabel(test: LabTest): String {
    val nameLower = test.name.lowercase()
    return when {
        nameLower.contains("قلوية") || nameLower.contains("ph") -> "قيمة درجة القلوية الـ pH (مثال: 8.4)"
        nameLower.contains("فورد") || nameLower.contains("ford") -> "زمن السيلان بالثواني (مثال: 25 ثانية)"
        nameLower.contains("نعومة") || nameLower.contains("fineness") -> "درجة النعومة بالميكرون (مثال: 15 ميكرون)"
        nameLower.contains("الرابطة") || nameLower.contains("binder") -> "نسبة المادة الرابطة (%) - مثال: 12.5"
        nameLower.contains("لمعة") || nameLower.contains("gloss") || nameLower.contains("لمعان") -> "قيمة اللمعة بوحدة (GU) - مثال: 45.2"
        else -> "النتيجة المقاسة مباشرة"
    }
}

fun getTestDisplayNames(test: LabTest): Pair<String, String> {
    val nameLower = test.name.lowercase().trim()
    if (nameLower == "ph" || nameLower == "ph value" || nameLower == "درجة الحموضة" || nameLower == "فحص الـ ph" || nameLower == "فحص ph" || nameLower == "فحص درجة القلوية" || nameLower == "فحص درجة القلوية (ph value)") {
        return Pair("فحص درجة القلوية (pH Value)", "pH Value Test")
    }
    if (test.notes.startsWith("WIZARD_VISCOSITY:")) {
        try {
            val pureJson = test.notes.removePrefix("WIZARD_VISCOSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(ViscosityTestData::class.java)
            val data = adapter.fromJson(pureJson)
            if (data != null) {
                return when (data.testType) {
                    "RHEOLOGY" -> Pair("فحص السلوك الريولوجي", "Rheology Test")
                    "DILUTION" -> Pair("اللزوجة بعد التخفيف", "Dilution Viscosity")
                    else -> Pair("فحص اللزوجة القياسي", "Viscosity Test - Standard")
                }
            }
        } catch (e: Exception) {
            // fallback
        }
    } else if (test.name.contains("اللزوجة") && !test.name.contains("فورد")) {
        return Pair("فحص اللزوجة القياسي", "Viscosity Test - Standard")
    }
    
    if (nameLower.contains("فحص اللمعة") || nameLower == "اللمعة" || nameLower.contains("gloss") || nameLower.contains("ثبات درجة اللون واللمعان") || nameLower.contains("ثبات درجة اللون")) {
        return Pair("فحص اللمعة", "Gloss Test")
    }
    val (arName, enName) = splitTestName(test.name)
    return Pair(arName, enName)
}

fun getComparisonKey(test: LabTest): String {
    val baseName = getTestDisplayNames(test).first
    val settings = getTestSettingsSummary(test)
    return if (settings != null) {
        "$baseName - $settings"
    } else {
        baseName
    }
}

fun getTestSettingsSummary(test: LabTest): String? {
    val notes = test.notes
    if (notes.startsWith("WIZARD_VISCOSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(ViscosityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            if (data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                if (data.testType == "RHEOLOGY") {
                    "Spindle ${data.spindle} | ${data.speed} & ${data.secondSpeed} RPM"
                } else if (data.testType == "DILUTION") {
                    "Spindle ${data.spindle} | ${data.speed} RPM | H2O ${data.waterWeight}g"
                } else {
                    "Spindle ${data.spindle} | ${data.speed} RPM"
                }
            } else null
        } catch (e: java.lang.Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_DENSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(DensityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            "Volume ${data.volumeMl} mL"
        } catch (e: java.lang.Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_SOLID_CONTENT:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(SolidContentTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            if (data.useDirectInput) {
                "Direct Input"
            } else {
                "Dish ${data.dishWeight}g | Wet ${data.wetWeight}g"
            }
        } catch (e: java.lang.Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_GLOSS:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_GLOSS:").substringBefore("\n\n")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(GlossTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            if (data.selectedAngle.isNotBlank()) {
                "زاوية القياس: ${data.selectedAngle}°"
            } else null
        } catch (e: java.lang.Exception) {
            null
        }
    }
    return null
}

@Composable
fun TestItemCard(
    test: LabTest,
    session: LabSession? = null,
    operatorName: String = "الفني المسؤول",
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (test.status) {
        "لم يبدأ" -> Color(0xFF64748B)
        "قيد التنفيذ" -> LabBlueMain
        "مكتمل" -> LabSuccessGreen
        "بانتظار النتيجة" -> LabWarningYellow
        "خارج المواصفة" -> LabErrorRed
        else -> Color.Gray
    }

    val statusBg = statusColor.copy(alpha = 0.08f)
    val isComparison = session?.testType == "⚖️ فحص مقارنة" || test.testValueB != null

    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 80.dp)
            .clickable { 
                if (!isComparison) {
                    isExpanded = !isExpanded 
                } else {
                    onOpen()
                }
            },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (!isComparison && isExpanded) LabBlueMain.copy(alpha = 0.4f) else LabBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Comparative style or Unitary style results
            if (isComparison) {
                // Upper row: Test name and status badge, and trash can
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text("🧪", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        val (arName, enName) = remember(test.notes, test.name) { getTestDisplayNames(test) }
                        Column {
                            Text(
                                text = arName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                            if (enName.isNotBlank()) {
                                Text(
                                    text = enName,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(statusBg, shape = RoundedCornerShape(20.dp))
                                .border(1.dp, statusColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = test.status,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(6.dp))
                        
                        IconButton(
                            onClick = { onDelete() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "حذف الفحص",
                                tint = LabErrorRed.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val hasValueA = !test.testValueA.isNullOrBlank()
                val hasValueB = !test.testValueB.isNullOrBlank()
                val valA = if (hasValueA) test.testValueA!! else "-"
                val valB = if (hasValueB) test.testValueB!! else "-"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Column for Side A
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(LabPurple.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, LabPurple.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val partyAName = session?.partyA?.let { getPartyName(it) } ?: "الطرف أ"
                        Text("$partyAName (A) 🧪", fontSize = 9.5.sp, color = LabPurple, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = valA,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = LabDarkIndigo
                        )
                    }

                    // Column for Side B
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(LabCyan.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, LabCyan.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val partyBName = session?.partyB?.let { getPartyName(it) } ?: "الطرف ب"
                        Text("$partyBName (B) 🔍", fontSize = 9.5.sp, color = LabCyan, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = valB,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = LabDarkIndigo
                        )
                    }
                }

                // Show calculation and assessment fields underneath
                val numA = test.testValueA?.replace(Regex("[^0-9.-]"), "")?.toDoubleOrNull()
                val numB = test.testValueB?.replace(Regex("[^0-9.-]"), "")?.toDoubleOrNull()

                if (numA != null && numB != null) {
                    val diff = numB - numA
                    val pct = if (numA != 0.0) (diff / numA) * 100.0 else 0.0
                    val absPct = kotlin.math.abs(pct)
                    val sign = if (diff > 0.0) "+" else ""
                    val isPositive = diff >= 0.0
                    
                    val unit = remember(test.testValueA) { extractUnit(test.testValueA) }
                    val diffStr = String.format(Locale.US, "%s%.2f %s", sign, diff, unit).trim()
                    val pctStr = String.format(Locale.US, "%s%.1f%%", sign, pct)
                    
                    val deltaColor = if (kotlin.math.abs(diff) < 1e-4) Color.Gray else if (isPositive) LabSuccessGreen else LabErrorRed
                    val deltaBg = deltaColor.copy(alpha = 0.06f)
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Difference
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(deltaBg, RoundedCornerShape(8.dp))
                                .border(0.5.dp, deltaColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("الفرق العددي:", fontSize = 10.sp, color = Color.Gray)
                            Text(diffStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = deltaColor)
                        }
                        
                        // Percentage
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .background(deltaBg, RoundedCornerShape(8.dp))
                                .border(0.5.dp, deltaColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("النسبة المئوية:", fontSize = 10.sp, color = Color.Gray)
                            Text(pctStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = deltaColor)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val analysisText = remember(test.name, numA, numB, unit) {
                        generateComparativeAnalysis(test.name, numA, numB, unit)
                    }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LabDarkIndigo.copy(alpha = 0.03f), RoundedCornerShape(10.dp))
                            .border(0.5.dp, LabDarkIndigo.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = null,
                                tint = LabPurple,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "التحليل المقارن والتقييم الفني:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = analysisText,
                            fontSize = 10.sp,
                            color = Color(0xFF334155),
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "بانتظار إدخال قراءات الطرفين للتحليل والمقارنة الفنية 📊",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                // Operator & Date of execution
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Execution Date
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "التنفيذ: ${test.executionDate}",
                            fontSize = 10.5.sp,
                            color = Color.Gray
                        )
                    }

                    // Operator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "المنفذ: $operatorName",
                            fontSize = 10.5.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                // Unitary style: Clear MetricShowCard design focusing purely on showing the main result
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Right side (RTL start): Icon & Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🧪", fontSize = 16.sp)
                        }
                        
                        val (arName, enName) = remember(test.notes, test.name) { getTestDisplayNames(test) }
                        Column {
                            Text(
                                text = arName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                            if (enName.isNotBlank()) {
                                Text(
                                    text = enName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }
                    }

                    // Left side (RTL end): Large Prominent Result with Settings Summary & Arrow indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val resultValue = remember(test.testValueA, test.notes) { getTestShortResultValue(test) }
                        val settingsSummary = remember(test.notes) { getTestSettingsSummary(test) }
                        
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = resultValue ?: "بانتظار النتيجة ⏳",
                                fontSize = if (resultValue != null) 14.sp else 11.sp,
                                fontWeight = FontWeight.Black,
                                color = if (resultValue != null) LabSuccessGreen else Color.Gray,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            if (settingsSummary != null && resultValue != null) {
                                Text(
                                    text = settingsSummary,
                                    fontSize = 9.5.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 2.dp, end = 4.dp)
                                )
                            }
                        }
                        
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "عرض التفاصيل",
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Expandable details section
                AnimatedVisibility(visible = isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Divider(color = LabBorder.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 12.dp))
                        
                        // Detailed properties
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("حالة الفحص الفعلي:", fontSize = 11.sp, color = Color.Gray)
                            Box(
                                modifier = Modifier
                                    .background(statusBg, shape = RoundedCornerShape(20.dp))
                                    .border(1.dp, statusColor.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = test.status,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("تاريخ ووقت التنفيذ:", fontSize = 11.sp, color = Color.Gray)
                            Text(test.executionDate.ifBlank { "غير مسجل" }, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LabDarkIndigo)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("الفني المسؤول والمنفذ:", fontSize = 11.sp, color = Color.Gray)
                            Text(operatorName, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = LabDarkIndigo)
                        }

                        val notes = test.notes
                        if (notes.startsWith("WIZARD_VISCOSITY:")) {
                            // Render Viscosity details beautifully!
                            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
                            val moshi = remember {
                                com.squareup.moshi.Moshi.Builder()
                                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                    .build()
                            }
                            val data = remember(pureJson) {
                                try {
                                    val adapter = moshi.adapter(ViscosityTestData::class.java)
                                    adapter.fromJson(pureJson)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (data != null) {
                                val valid = remember(data) { data.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() } }
                                val avgVisc = remember(valid) {
                                    if (valid.isNotEmpty()) {
                                        valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                                    } else 0.0
                                }
                                val avgTorque = remember(valid) {
                                    if (valid.isNotEmpty()) {
                                        valid.mapNotNull { it.torque.toDoubleOrNull() }.average()
                                    } else 0.0
                                }
                                
                                val formattedVisc = String.format(Locale.US, "%.1f", avgVisc)
                                val formattedTorque = String.format(Locale.US, "%.1f", avgTorque)
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(LabLightBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, LabBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = if (data.testType == "DILUTION") "🔬 لزوجة بعد التخفيف بالماء:" else "📊 تفاصيل فحص اللزوجة الحركية (NDJ-8S):",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        
                                        if (data.testType == "DILUTION") {
                                             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                 DetailHalfRow(label = "المغزل المستخدم:", value = data.spindle.ifBlank { "-" }, modifier = Modifier.weight(1f))
                                                 DetailHalfRow(label = "سرعة الدوران:", value = data.speed.ifBlank { "-" }, trailing = "RPM", modifier = Modifier.weight(1f))
                                             }
                                             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                 DetailHalfRow(label = "متوسط العزم:", value = formattedTorque, trailing = "%", modifier = Modifier.weight(1f))
                                                 DetailHalfRow(label = "اللزوجة الناتجة:", value = formattedVisc, trailing = "cP", modifier = Modifier.weight(1f))
                                             }
                                             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                 DetailHalfRow(label = "وزن الدهان الأصلي:", value = data.paintWeight.ifBlank { "-" }, trailing = "g", modifier = Modifier.weight(1f))
                                                 DetailHalfRow(label = "وزن الماء المضاف:", value = data.waterWeight.ifBlank { "-" }, trailing = "g", modifier = Modifier.weight(1f))
                                             }
                                         } else {
                                             DetailRow(label = "المغزل المستخدم (Spindle):", value = data.spindle.ifBlank { "-" })
                                        DetailRow(label = "سرعة الدوران المختارة (Speed):", value = data.speed.ifBlank { "-" }, trailing = "RPM")
                                        DetailRow(label = "متوسط مستويات العزم (Torque):", value = formattedTorque, trailing = "%")
                                        DetailRow(label = "متوسط لزوجة الدهان الناتجة:", value = formattedVisc, trailing = "cP")
                                         }
                                        
                                        if (data.testType == "RHEOLOGY") {
                                            val validSecond = remember(data) { data.readingsSecondSpeed.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() } }
                                            val avgViscSecond = remember(validSecond) {
                                                if (validSecond.isNotEmpty()) {
                                                    validSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                                                } else 0.0
                                            }
                                            val formattedViscSecond = String.format(Locale.US, "%.1f", avgViscSecond)
                                            val rIndex = if (avgViscSecond > 0.0) String.format(Locale.US, "%.2f", avgVisc / avgViscSecond) else "-"
                                            val reduction = if (avgVisc > 0.0) String.format(Locale.US, "%.1f%%", ((avgVisc - avgViscSecond) / avgVisc) * 100.0) else "-"
                                            
                                            Divider(color = LabBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                                            Text("🧪 سلوك السيلان الريولوجي:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            DetailRow(label = "السرعة الثانية المضاعفة:", value = data.secondSpeed.ifBlank { "-" }, trailing = "RPM")
                                            DetailRow(label = "متوسط اللزوجة عند السرعة الثانية:", value = formattedViscSecond, trailing = "cP")
                                            DetailRow(label = "مؤشر السلوك الريولوجي (R.I):", value = rIndex)
                                            DetailRow(label = "نسبة انخفاض اللزوجة المقاسة:", value = reduction)
                                            
                                            if (data.isAutoCalculated) {
                                                Divider(color = LabBorder.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 6.dp))
                                                Text("🔄 مصدر بيانات التحليل التلقائي:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                                                Spacer(modifier = Modifier.height(4.dp))
                                                DetailRow(label = "السرعة الأولى ومصدرها:", value = "${data.speed} RPM (${data.sourceSpeed1Name ?: "فحص لزوجة قياسي"})")
                                                DetailRow(label = "السرعة الثانية ومصدرها:", value = "${data.secondSpeed} RPM (${data.sourceSpeed2Name ?: "فحص لزوجة قياسي"})")
                                                DetailRow(label = "رقم الـ Spindle المستخدم:", value = data.spindle)
                                            }
                                        } else if (false) {
                                            Divider(color = LabBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                                            Text("🔬 دراسة الاستقرار بعد التخفيف بالماء:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            DetailRow(label = "وزن الدهان الأصلي:", value = data.paintWeight.ifBlank { "-" }, trailing = "g")
                                            DetailRow(label = "وزن الماء المضاف للتخفيف:", value = data.waterWeight.ifBlank { "-" }, trailing = "g")
                                        }
                                        
                                        if (valid.isNotEmpty()) {
                                            Divider(color = LabBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
                                            if (data.testType == "DILUTION") {
                                                val readingsTxt = valid.mapIndexed { i, r -> "#${i+1}: ${r.viscosity} | ${r.torque}%" }.joinToString(" • ")
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("📋 قراءات مكررة (لزوجة - عزم):", fontSize = 10.sp, color = Color.Gray)
                                                    Text(readingsTxt, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                                }
                                            } else {
                                                Text("📋 القراءات الفردية المسجّلة:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                            }
                                            if (data.testType != "DILUTION") valid.forEachIndexed { index, r ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text("القراءة #${index + 1}:", fontSize = 10.sp, color = Color.Gray)
                                                    Text(
                                                        text = "اللزوجة: ${r.viscosity} cP • العزم: ${r.torque}%",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = LabDarkIndigo
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (notes.startsWith("WIZARD_DENSITY:")) {
                            // Render Density details beautifully!
                            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
                            val moshi = remember {
                                com.squareup.moshi.Moshi.Builder()
                                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                    .build()
                            }
                            val data = remember(pureJson) {
                                try {
                                    val adapter = moshi.adapter(DensityTestData::class.java)
                                    adapter.fromJson(pureJson)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (data != null) {
                                val density = if (data.useDirectInput) {
                                    data.directDensity.toDoubleOrNull() ?: 0.0
                                } else {
                                    val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                                    val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                                    val sampleWeight = filled - empty
                                    if (data.volumeMl > 0.0) sampleWeight / data.volumeMl else 0.0
                                }
                                val formattedDensity = String.format(Locale.US, "%.3f", density)
                                val formattedWeight = if (data.useDirectInput) {
                                    String.format(Locale.US, "%.2f", density * data.volumeMl)
                                } else {
                                    val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                                    val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                                    String.format(Locale.US, "%.2f", filled - empty)
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(LabLightBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, LabBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "📊 تفاصيل فحص الكثافة ووزن اللتر:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        if (data.useDirectInput) {
                                            DetailRow(label = "أسلوب الإدخال:", value = "إدخال نتيجة مباشرة ✏️", trailing = "")
                                            DetailRow(label = "الكثافة الحجمية النهائية المقاسة:", value = formattedDensity, trailing = "g/cm³")
                                        } else {
                                            DetailRow(label = "وزن الكوب فارغاً:", value = data.emptyWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن الكوب ممتلئاً بالعينة:", value = data.filledWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن العينة الصافي (Net Weight):", value = formattedWeight, trailing = "g")
                                            DetailRow(label = "حجم كوب القياس المستخدم:", value = data.volumeMl.toString(), trailing = "ml")
                                            DetailRow(label = "الكثافة الحجمية النهائية المقاسة:", value = formattedDensity, trailing = "g/cm³")
                                        }
                                    }
                                }
                            }
                        } else if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
                            // Render Solid content details beautifully!
                            val pureJson = notes.removePrefix("WIZARD_SOLID_CONTENT:")
                            val moshi = remember {
                                com.squareup.moshi.Moshi.Builder()
                                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                    .build()
                            }
                            val data = remember(pureJson) {
                                try {
                                    val adapter = moshi.adapter(SolidContentTestData::class.java)
                                    adapter.fromJson(pureJson)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (data != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(LabLightBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, LabBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "📊 تفاصيل فحص نسبة المواد الصلبة (Solid Content):",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        if (data.useDirectInput) {
                                            DetailRow(label = "طريقة تحديد النسبة:", value = "إدخال مباشر ونوعي")
                                            DetailRow(label = "نسبة المواد الصلبة المعتمدة:", value = data.directPct.ifBlank { "0" }, trailing = "%")
                                        } else {
                                            val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                                            val w = data.wetWeight.toDoubleOrNull() ?: 0.0
                                            val dr = data.dryWeight.toDoubleOrNull() ?: 0.0
                                            val netW = w - d
                                            val netD = dr - d
                                            val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                                            
                                            DetailRow(label = "طريقة تحديد النسبة:", value = "حساب مخبري بالأوزان والجفاف")
                                            DetailRow(label = "وزن الجفنة فارغة:", value = data.dishWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن العينة الرطبة مع الجفنة:", value = data.wetWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "صافي وزن العينة الرطبة:", value = String.format(Locale.US, "%.3f", netW), trailing = "g")
                                            DetailRow(label = "وزن العينة المجففة مع الجفنة:", value = data.dryWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "صافي وزن المادة الصلبة المتبقية:", value = String.format(Locale.US, "%.3f", netD), trailing = "g")
                                            DetailRow(label = "النسبة المئوية للمواد الصلبة:", value = String.format(Locale.US, "%.2f", pct), trailing = "%")
                                        }
                                    }
                                }
                            }
                        } else if (notes.startsWith("WIZARD_NET_BINDER:")) {
                            // Render Net Binder content details beautifully!
                            val pureJson = notes.removePrefix("WIZARD_NET_BINDER:")
                            val moshi = remember {
                                com.squareup.moshi.Moshi.Builder()
                                    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                    .build()
                            }
                            val data = remember(pureJson) {
                                try {
                                    val adapter = moshi.adapter(NetBinderTestData::class.java)
                                    adapter.fromJson(pureJson)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (data != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(LabLightBg, RoundedCornerShape(10.dp))
                                        .border(0.5.dp, LabBorder.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "📊 تفاصيل فحص نسبة المادة الرابطة (Net Binder Content):",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        if (data.useDirectInput) {
                                            DetailRow(label = "طريقة تحديد النسبة:", value = "إدخال مباشر ونوعي")
                                            DetailRow(label = "نسبة المادة الرابطة المعتمدة:", value = data.directPct.ifBlank { "0" }, trailing = "%")
                                        } else {
                                            val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                                            val wBefore = data.sampleWeightBefore.toDoubleOrNull() ?: 0.0
                                            val wAfter = data.sampleWeightAfter.toDoubleOrNull() ?: 0.0
                                            val wBurned = data.sampleWeightBurned.toDoubleOrNull() ?: 0.0
                                            
                                            val sampleOriginal = wBefore - d
                                            val netDry = wAfter - d
                                            val netBurned = wBurned - d
                                            
                                            val solidsPct = if (sampleOriginal > 0.0) (netDry / sampleOriginal) * 100.0 else 0.0
                                            val ashPct = if (sampleOriginal > 0.0) (netBurned / sampleOriginal) * 100.0 else 0.0
                                            val binderPct = solidsPct - ashPct
                                            
                                            DetailRow(label = "طريقة تحديد النسبة:", value = "حساب تلقائي مخبري من الأوزان والحرق")
                                            DetailRow(label = "وزن الطبق فارغاً:", value = data.dishWeight.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن الطبق + العينة قبل التجفيف (رطبة):", value = data.sampleWeightBefore.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن الطبق + العينة بعد التجفيف (جافة):", value = data.sampleWeightAfter.ifBlank { "0" }, trailing = "g")
                                            DetailRow(label = "وزن الطبق + العينة بعد الحرق (رماد):", value = data.sampleWeightBurned.ifBlank { "0" }, trailing = "g")
                                            
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Spacer(modifier = Modifier.height(0.5.dp).fillMaxWidth().background(LabBorder.copy(alpha = 0.5f)))
                                            Spacer(modifier = Modifier.height(4.dp))
                                            
                                            DetailRow(label = "صافي وزن العينة الأصلية:", value = String.format(Locale.US, "%.3f", sampleOriginal), trailing = "g")
                                            DetailRow(label = "نسبة المواد الصلبة (Solids %):", value = String.format(Locale.US, "%.2f", solidsPct), trailing = "%")
                                            DetailRow(label = "نسبة الرماد بعد الحرق (Ash %):", value = String.format(Locale.US, "%.2f", ashPct), trailing = "%")
                                            DetailRow(label = "نسبة المادة الرابطة الصافية (Net Binder):", value = String.format(Locale.US, "%.2f", binderPct), trailing = "%")
                                        }
                                    }
                                }
                            }
                        } else {
                            val summaryText = remember(test.notes) { getTestSummaryText(test.notes) }
                            val notesToShow = if (summaryText.isNotBlank()) summaryText else test.notes
                            
                            if (notesToShow.isNotBlank() && !notesToShow.startsWith("WIZARD_")) {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("ملخص الفحص وملاحظات القياس:", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(LabLightBg, RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text(text = notesToShow, fontSize = 11.sp, color = LabDarkIndigo, lineHeight = 16.sp)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onOpen() },
                                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("المختبر وتعديل القراءة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }

                            Button(
                                onClick = { onDelete() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFEF2F2)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف الفحص", tint = LabErrorRed, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("حذف", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabErrorRed)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, trailing: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = value, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
            if (trailing.isNotBlank()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = trailing, fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun DetailHalfRow(label: String, value: String, trailing: String = "", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 10.5.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
            if (trailing.isNotBlank()) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(text = trailing, fontSize = 9.5.sp, color = Color.Gray)
            }
        }
    }
}

fun getTestShortResultValue(test: LabTest): String? {
    val valA = test.testValueA?.ifBlank { null }
    if (valA != null) return valA
    
    val notes = test.notes
    if (notes.startsWith("WIZARD_VISCOSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(ViscosityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            val valid = data.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
            val avgVisc = if (valid.isNotEmpty()) {
                valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
            } else 0.0
            
            if (data.testType == "RHEOLOGY") {
                val validSecond = data.readingsSecondSpeed.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgViscSecond = if (validSecond.isNotEmpty()) {
                    validSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                } else 0.0
                if (avgVisc > 0.0 && avgViscSecond > 0.0) {
                    String.format(Locale.US, "%.2f R.I", avgVisc / avgViscSecond)
                } else {
                    "-"
                }
            } else {
                String.format(Locale.US, "%.1f cP", avgVisc)
            }
        } catch (e: Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_DENSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(DensityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            val density = if (data.useDirectInput) {
                data.directDensity.toDoubleOrNull() ?: 0.0
            } else {
                val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                val sampleWeight = filled - empty
                if (data.volumeMl > 0.0) sampleWeight / data.volumeMl else 0.0
            }
            String.format(Locale.US, "%.3f g/cm³", density)
        } catch (e: Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_SOLID_CONTENT:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(SolidContentTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            if (data.useDirectInput) {
                "${data.directPct.trim()}%"
            } else {
                val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                val w = data.wetWeight.toDoubleOrNull() ?: 0.0
                val dr = data.dryWeight.toDoubleOrNull() ?: 0.0
                val netW = w - d
                val netD = dr - d
                val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                String.format(Locale.US, "%.2f%%", pct)
            }
        } catch (e: Exception) {
            null
        }
    } else if (notes.startsWith("WIZARD_NET_BINDER:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_NET_BINDER:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(NetBinderTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return null
            if (data.useDirectInput) {
                if (data.directPct.isNotBlank()) "Net Binder: ${data.directPct.trim()}%" else "-"
            } else {
                val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                val wBefore = data.sampleWeightBefore.toDoubleOrNull() ?: 0.0
                val wAfter = data.sampleWeightAfter.toDoubleOrNull() ?: 0.0
                val wBurned = data.sampleWeightBurned.toDoubleOrNull() ?: 0.0
                
                val sampleOriginal = wBefore - d
                val netDry = wAfter - d
                val netBurned = wBurned - d
                if (sampleOriginal > 0.0) {
                    val solidsPct = (netDry / sampleOriginal) * 100.0
                    val ashPct = (netBurned / sampleOriginal) * 100.0
                    val binderPct = solidsPct - ashPct
                    String.format(Locale.US, "Net Binder: %.2f%%", binderPct)
                } else {
                    "-"
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    return null
}

// Data classes for test Wizards
data class ViscosityReading(
    val viscosity: String = "",
    val torque: String = ""
)

data class ViscosityTestData(
    val spindle: String = "",
    val speed: String = "",
    val readings: List<ViscosityReading> = listOf(
        ViscosityReading(),
        ViscosityReading(),
        ViscosityReading()
    ),
    val testType: String = "STANDARD", // "STANDARD", "RHEOLOGY", "DILUTION"
    val secondSpeed: String = "",
    val readingsSecondSpeed: List<ViscosityReading> = emptyList(),
    val paintWeight: String = "160",
    val waterWeight: String = "53.33",
    val rheologyIndex: Double? = null,
    val viscosityReductionPct: Double? = null,
    val isAutoCalculated: Boolean = false,
    val sourceSpeed1Name: String? = null,
    val sourceSpeed2Name: String? = null,
    val entryMethod: String = "USB"
)

data class ViscosityCandidate(
    val test: LabTest,
    val data: ViscosityTestData,
    val avgViscosity: Double,
    val spindle: String,
    val speed: Double
)

data class DensityTestData(
    val emptyWeight: String = "297.61",
    val filledWeight: String = "",
    val volumeMl: Double = 100.0,
    val useDirectInput: Boolean = false,
    val directDensity: String = ""
)

data class ComparisonViscosityTestData(
    val dataA: ViscosityTestData = ViscosityTestData(),
    val dataB: ViscosityTestData = ViscosityTestData()
)

data class ComparisonDensityTestData(
    val dataA: DensityTestData = DensityTestData(),
    val dataB: DensityTestData = DensityTestData()
)

data class SolidContentTestData(
    val useDirectInput: Boolean = true,
    val directPct: String = "",
    val dishWeight: String = "",
    val wetWeight: String = "",
    val dryWeight: String = ""
)

data class ComparisonSolidContentTestData(
    val dataA: SolidContentTestData = SolidContentTestData(),
    val dataB: SolidContentTestData = SolidContentTestData()
)

data class NetBinderTestData(
    val useDirectInput: Boolean = true,
    val directPct: String = "",
    val dishWeight: String = "",
    val sampleWeightBefore: String = "",
    val sampleWeightAfter: String = "",
    val sampleWeightBurned: String = ""
)

data class ComparisonNetBinderTestData(
    val dataA: NetBinderTestData = NetBinderTestData(),
    val dataB: NetBinderTestData = NetBinderTestData()
)

data class GlossTestData(
    val selectedAngle: String = "60", // 20, 60, 85
    val gloss20: String = "",
    val gloss60: String = "",
    val gloss85: String = "",
    val primaryValue: String = ""
)

// Party parser helpers
fun getPartyName(partyStr: String?): String {
    if (partyStr.isNullOrBlank()) return "الطرف أ"
    if (partyStr.contains("::")) {
        val name = partyStr.substringBefore("::")
        return if (name.isBlank()) "الطرف أ" else name
    }
    return partyStr
}

fun getPartyDesc(partyStr: String?): String {
    if (partyStr.isNullOrBlank()) return ""
    if (partyStr.contains("::")) {
        return partyStr.substringAfter("::")
    }
    return ""
}

fun calculateDelta(valA: String?, valB: String?): String {
    if (valA.isNullOrBlank() || valB.isNullOrBlank() || valA == "-" || valB == "-") return "-"
    try {
        // Clean and parse numbers (retain minus/dots/numbers only)
        val numA = valA.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
        val numB = valB.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
        if (numA != null && numB != null) {
            val diff = numB - numA
            val pct = if (numA != 0.0) (diff / numA) * 100.0 else 0.0
            val sign = if (diff > 0.0) "+" else ""
            return String.format(Locale.US, "%s%.2f (%s%.1f%%)", sign, diff, sign, pct)
        }
    } catch (e: Exception) {
        // ignore
    }
    return "-"
}

fun isDeltaPositive(valA: String?, valB: String?): Boolean {
    val numA = valA?.replace(Regex("[^0-9.-]"), "")?.toDoubleOrNull()
    val numB = valB?.replace(Regex("[^0-9.-]"), "")?.toDoubleOrNull()
    return if (numA != null && numB != null) {
        (numB - numA) >= 0.0
    } else {
        true
    }
}

// Serialization helpers
fun serializeViscosityData(data: ViscosityTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ViscosityTestData::class.java)
        "WIZARD_VISCOSITY:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeViscosityData(json: String): ViscosityTestData? {
    return try {
        if (!json.startsWith("WIZARD_VISCOSITY:")) return null
        val pureJson = json.removePrefix("WIZARD_VISCOSITY:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ViscosityTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeDensityData(data: DensityTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(DensityTestData::class.java)
        "WIZARD_DENSITY:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeDensityData(json: String): DensityTestData? {
    return try {
        if (!json.startsWith("WIZARD_DENSITY:")) return null
        val pureJson = json.removePrefix("WIZARD_DENSITY:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(DensityTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeSolidContentData(data: SolidContentTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(SolidContentTestData::class.java)
        "WIZARD_SOLID_CONTENT:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeSolidContentData(json: String): SolidContentTestData? {
    return try {
        if (!json.startsWith("WIZARD_SOLID_CONTENT:")) return null
        val pureJson = json.removePrefix("WIZARD_SOLID_CONTENT:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(SolidContentTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeComparisonSolidContentData(data: ComparisonSolidContentTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonSolidContentTestData::class.java)
        "WIZARD_COMP_SOLID_CONTENT:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeComparisonSolidContentData(json: String): ComparisonSolidContentTestData? {
    return try {
        if (!json.startsWith("WIZARD_COMP_SOLID_CONTENT:")) return null
        val pureJson = json.removePrefix("WIZARD_COMP_SOLID_CONTENT:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonSolidContentTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeNetBinderData(data: NetBinderTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(NetBinderTestData::class.java)
        "WIZARD_NET_BINDER:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeNetBinderData(json: String): NetBinderTestData? {
    return try {
        if (!json.startsWith("WIZARD_NET_BINDER:")) return null
        val pureJson = json.removePrefix("WIZARD_NET_BINDER:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(NetBinderTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeComparisonNetBinderData(data: ComparisonNetBinderTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonNetBinderTestData::class.java)
        "WIZARD_COMP_NET_BINDER:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeComparisonNetBinderData(json: String): ComparisonNetBinderTestData? {
    return try {
        if (!json.startsWith("WIZARD_COMP_NET_BINDER:")) return null
        val pureJson = json.removePrefix("WIZARD_COMP_NET_BINDER:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonNetBinderTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeGlossData(data: GlossTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(GlossTestData::class.java)
        "WIZARD_GLOSS:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeGlossData(json: String): GlossTestData? {
    return try {
        if (!json.startsWith("WIZARD_GLOSS:")) return null
        val pureJson = json.removePrefix("WIZARD_GLOSS:").substringBefore("\n\n")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(GlossTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeComparisonViscosityData(data: ComparisonViscosityTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonViscosityTestData::class.java)
        "WIZARD_COMP_VISCOSITY:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeComparisonViscosityData(json: String): ComparisonViscosityTestData? {
    return try {
        if (!json.startsWith("WIZARD_COMP_VISCOSITY:")) return null
        val pureJson = json.removePrefix("WIZARD_COMP_VISCOSITY:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonViscosityTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun serializeComparisonDensityData(data: ComparisonDensityTestData): String {
    return try {
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonDensityTestData::class.java)
        "WIZARD_COMP_DENSITY:" + adapter.toJson(data)
    } catch (e: Exception) {
        ""
    }
}

fun deserializeComparisonDensityData(json: String): ComparisonDensityTestData? {
    return try {
        if (!json.startsWith("WIZARD_COMP_DENSITY:")) return null
        val pureJson = json.removePrefix("WIZARD_COMP_DENSITY:")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(ComparisonDensityTestData::class.java)
        adapter.fromJson(pureJson)
    } catch (e: Exception) {
        null
    }
}

fun getTestSummaryText(notes: String): String {
    if (notes.startsWith("WIZARD_VISCOSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(ViscosityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return "قالب اللزوجة فارغ"
            val valid = data.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
            val avgVisc = if (valid.isNotEmpty()) {
                valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
            } else 0.0
            val avgTorque = if (valid.isNotEmpty()) {
                valid.mapNotNull { it.torque.toDoubleOrNull() }.average()
            } else 0.0
            
            val formattedVisc = String.format(Locale.US, "%.1f", avgVisc)
            val formattedTorque = String.format(Locale.US, "%.1f", avgTorque)
            
            when (data.testType) {
                "RHEOLOGY" -> {
                    val validSecond = data.readingsSecondSpeed.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                    val avgViscSecond = if (validSecond.isNotEmpty()) {
                        validSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                    } else 0.0
                    val formattedViscSecond = String.format(Locale.US, "%.1f", avgViscSecond)
                    
                    val rIndex = if (avgViscSecond > 0.0) String.format(Locale.US, "%.2f", avgVisc / avgViscSecond) else "-"
                    val reduction = if (avgVisc > 0.0) String.format(Locale.US, "%.1f%%", ((avgVisc - avgViscSecond) / avgVisc) * 100.0) else "-"
                    
                    "فحص ريولوجي | لزوجة منخفضة (${data.speed} RPM): $formattedVisc cP | لزوجة مرتفعة (${data.secondSpeed} RPM): $formattedViscSecond cP | مؤشر السلوك Rheology Index: $rIndex | نسبة الانخفاض: $reduction"
                }
                "DILUTION" -> {
                    "فحص لزوجة مخفف بالماء (${data.paintWeight}جم دهان + ${data.waterWeight}جم ماء) | لزوجة: $formattedVisc cP | العزم: $formattedTorque% (مغزل: ${data.spindle} | سرعة: ${data.speed})"
                }
                else -> {
                    "فحص اللزوجة القياسي | اللزوجة: $formattedVisc cP | العزم: $formattedTorque% (مغزل: ${data.spindle} | سرعة: ${data.speed})"
                }
            }
        } catch (e: Exception) {
            "فشل قراءة بيانات اللزوجة"
        }
    } else if (notes.startsWith("WIZARD_DENSITY:")) {
        return try {
            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(DensityTestData::class.java)
            val data = adapter.fromJson(pureJson) ?: return "قالب الكثافة فارغ"
            
            val density = if (data.useDirectInput) {
                data.directDensity.toDoubleOrNull() ?: 0.0
            } else {
                val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                val sampleWeight = filled - empty
                if (data.volumeMl > 0.0) sampleWeight / data.volumeMl else 0.0
            }
            val formattedDensity = String.format(Locale.US, "%.3f", density)
            
            if (data.useDirectInput) {
                "الكثافة (إدخل مباشر): $formattedDensity g/cm³"
            } else {
                val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                val sampleWeight = filled - empty
                val formattedWeight = String.format(Locale.US, "%.2f", sampleWeight)
                "وزن العينة: $formattedWeight g | الكثافة: $formattedDensity g/cm³"
            }
        } catch (e: Exception) {
            "فشل قراءة بيانات الكثافة"
        }
    } else if (notes.startsWith("WIZARD_COMP_VISCOSITY:")) {
        return try {
            val data = deserializeComparisonViscosityData(notes) ?: return "قالب مقارنة اللزوجة فارغ"
            val validA = data.dataA.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
            val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
            val validB = data.dataB.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
            val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
            "مقارنة لزوجة | A: ${String.format(Locale.US, "%.1f", avgA)} cP | B: ${String.format(Locale.US, "%.1f", avgB)} cP"
        } catch (e: Exception) {
            "فشل قراءة مقارنة اللزوجة"
        }
    } else if (notes.startsWith("WIZARD_COMP_DENSITY:")) {
        return try {
            val data = deserializeComparisonDensityData(notes) ?: return "قالب مقارنة الكثافة فارغ"
            val densityA = if (data.dataA.useDirectInput) {
                data.dataA.directDensity.toDoubleOrNull() ?: 0.0
            } else {
                val emptyA = data.dataA.emptyWeight.toDoubleOrNull() ?: 0.0
                val filledA = data.dataA.filledWeight.toDoubleOrNull() ?: 0.0
                if (data.dataA.volumeMl > 0.0) (filledA - emptyA) / data.dataA.volumeMl else 0.0
            }
            val densityB = if (data.dataB.useDirectInput) {
                data.dataB.directDensity.toDoubleOrNull() ?: 0.0
            } else {
                val emptyB = data.dataB.emptyWeight.toDoubleOrNull() ?: 0.0
                val filledB = data.dataB.filledWeight.toDoubleOrNull() ?: 0.0
                if (data.dataB.volumeMl > 0.0) (filledB - emptyB) / data.dataB.volumeMl else 0.0
            }
            "مقارنة كثافة | A: ${String.format(Locale.US, "%.3f", densityA)} g/cm³ | B: ${String.format(Locale.US, "%.3f", densityB)} g/cm³"
        } catch (e: Exception) {
            "فشل قراءة مقارنة الكثافة"
        }
    } else if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
        return try {
            val data = deserializeSolidContentData(notes) ?: return "قالب المواد الصلبة فارغ"
            if (data.useDirectInput) {
                "محتوى المواد الصلبة: ${data.directPct.trim()}%"
            } else {
                val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                val w = data.wetWeight.toDoubleOrNull() ?: 0.0
                val dr = data.dryWeight.toDoubleOrNull() ?: 0.0
                val netW = w - d
                val netD = dr - d
                val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                "المواد الصلبة (أوزان): ${String.format(Locale.US, "%.2f %%", pct)} [رطب صافي: ${String.format(Locale.US, "%.2f", netW)}g | جاف صافي: ${String.format(Locale.US, "%.2f", netD)}g]"
            }
        } catch (e: Exception) {
            "فشل قراءة بيانات المواد الصلبة"
        }
    } else if (notes.startsWith("WIZARD_COMP_SOLID_CONTENT:")) {
        return try {
            val data = deserializeComparisonSolidContentData(notes) ?: return "قالب مقارنة المواد الصلبة فارغ"
            val pctA = if (data.dataA.useDirectInput) {
                data.dataA.directPct.trim() + "%"
            } else {
                val d = data.dataA.dishWeight.toDoubleOrNull() ?: 0.0
                val w = data.dataA.wetWeight.toDoubleOrNull() ?: 0.0
                val dr = data.dataA.dryWeight.toDoubleOrNull() ?: 0.0
                val netW = w - d
                val netD = dr - d
                val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                String.format(Locale.US, "%.2f %%", pct)
            }
            val pctB = if (data.dataB.useDirectInput) {
                data.dataB.directPct.trim() + "%"
            } else {
                val d = data.dataB.dishWeight.toDoubleOrNull() ?: 0.0
                val w = data.dataB.wetWeight.toDoubleOrNull() ?: 0.0
                val dr = data.dataB.dryWeight.toDoubleOrNull() ?: 0.0
                val netW = w - d
                val netD = dr - d
                val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                String.format(Locale.US, "%.2f %%", pct)
            }
            "مقارنة المواد الصلبة | A: $pctA | B: $pctB"
        } catch (e: Exception) {
            "فشل قراءة مقارنة المواد الصلبة"
        }
    } else if (notes.startsWith("WIZARD_NET_BINDER:")) {
        return try {
            val data = deserializeNetBinderData(notes) ?: return "قالب المادة الرابطة فارغ"
            if (data.useDirectInput) {
                "محتوى المادة الرابطة: ${data.directPct.trim()}%"
            } else {
                val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                val wBefore = data.sampleWeightBefore.toDoubleOrNull() ?: 0.0
                val wAfter = data.sampleWeightAfter.toDoubleOrNull() ?: 0.0
                val wBurned = data.sampleWeightBurned.toDoubleOrNull() ?: 0.0
                
                val sampleOriginal = wBefore - d
                val netDry = wAfter - d
                val netBurned = wBurned - d
                if (sampleOriginal > 0.0) {
                    val solidsPct = (netDry / sampleOriginal) * 100.0
                    val ashPct = (netBurned / sampleOriginal) * 100.0
                    val binderPct = solidsPct - ashPct
                    "المادة الرابطة: ${String.format(Locale.US, "%.2f %%", binderPct)} [صلب: ${String.format(Locale.US, "%.1f", solidsPct)}% | رماد: ${String.format(Locale.US, "%.1f", ashPct)}%]"
                } else {
                    "المادة الرابطة: -"
                }
            }
        } catch (e: Exception) {
            "فشل قراءة بيانات المادة الرابطة"
        }
    } else if (notes.startsWith("WIZARD_COMP_NET_BINDER:")) {
        return try {
            val data = deserializeComparisonNetBinderData(notes) ?: return "قالب مقارنة المادة الرابطة فارغ"
            val pctA = if (data.dataA.useDirectInput) {
                data.dataA.directPct.trim() + "%"
            } else {
                val d = data.dataA.dishWeight.toDoubleOrNull() ?: 0.0
                val wBefore = data.dataA.sampleWeightBefore.toDoubleOrNull() ?: 0.0
                val wAfter = data.dataA.sampleWeightAfter.toDoubleOrNull() ?: 0.0
                val wBurned = data.dataA.sampleWeightBurned.toDoubleOrNull() ?: 0.0
                
                val sampleOriginal = wBefore - d
                val netDry = wAfter - d
                val netBurned = wBurned - d
                if (sampleOriginal > 0.0) {
                    val solidsPct = (netDry / sampleOriginal) * 100.0
                    val ashPct = (netBurned / sampleOriginal) * 100.0
                    String.format(Locale.US, "%.2f %%", solidsPct - ashPct)
                } else "-"
            }
            val pctB = if (data.dataB.useDirectInput) {
                data.dataB.directPct.trim() + "%"
            } else {
                val d = data.dataB.dishWeight.toDoubleOrNull() ?: 0.0
                val wBefore = data.dataB.sampleWeightBefore.toDoubleOrNull() ?: 0.0
                val wAfter = data.dataB.sampleWeightAfter.toDoubleOrNull() ?: 0.0
                val wBurned = data.dataB.sampleWeightBurned.toDoubleOrNull() ?: 0.0
                
                val sampleOriginal = wBefore - d
                val netDry = wAfter - d
                val netBurned = wBurned - d
                if (sampleOriginal > 0.0) {
                    val solidsPct = (netDry / sampleOriginal) * 100.0
                    val ashPct = (netBurned / sampleOriginal) * 100.0
                    String.format(Locale.US, "%.2f %%", solidsPct - ashPct)
                } else "-"
            }
            "مقارنة المادة الرابطة | A: $pctA | B: $pctB"
        } catch (e: Exception) {
            "فشل قراءة مقارنة المادة الرابطة"
        }
    }
    return notes
}

@Composable
fun TestStepProgressBar(
    currentStep: Int,
    totalSteps: Int,
    titles: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LabBlueMain.copy(alpha = 0.03f))
            .padding(12.dp)
            .border(1.dp, LabBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "الخطوة ${currentStep + 1} من $totalSteps",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = LabBlueMain
            )
            Text(
                text = titles.getOrNull(currentStep) ?: "",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = LabDarkIndigo
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (currentStep + 1).toFloat() / totalSteps },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = LabBlueMain,
            trackColor = LabBorder
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingleTestWorkspaceView(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val isComp = parentSession.testType == "⚖️ فحص مقارنة"
    if (test.name.contains("اللزوجة") && !test.name.contains("فورد")) {
        if (isComp) {
            ComparisonViscosityWizard(test, parentSession, viewModel, onBack)
        } else {
            ViscosityWizard(test, parentSession, viewModel, onBack)
        }
    } else if (test.name.contains("الكثافة") || test.name.contains("Density")) {
        if (isComp) {
            ComparisonDensityWizard(test, parentSession, viewModel, onBack)
        } else {
            DensityWizard(test, parentSession, viewModel, onBack)
        }
    } else if (test.name.contains("الصلابة") || test.name.contains("solid") || test.name.contains("الجفاف") || test.name.contains("drying") || test.name.contains("صلب")) {
        SolidContentWorkspace(test, parentSession, viewModel, onBack)
    } else if (test.name.contains("الرابطة") || test.name.contains("binder") || test.name.contains("Binder")) {
        NetBinderWorkspace(test, parentSession, viewModel, onBack)
    } else if (test.name.contains("اللمعة") || test.name.contains("اللمعان") || test.name.contains("Gloss") || test.name.contains("gloss") || test.name.contains("ثبات درجة اللون")) {
        GlossTestWorkspace(test, parentSession, viewModel, onBack)
    } else {
        GenericTestWorkspace(test, parentSession, viewModel, onBack)
    }
}

fun getTestCategory(testName: String): String {
    val nameLower = testName.lowercase().trim()
    return when {
        nameLower.contains("viscosity") || nameLower.contains("لزوجة") || nameLower.contains("ku") || nameLower.contains("spindle") || nameLower.contains("ndj") || nameLower.contains("فورد") -> "viscosity"
        nameLower.contains("density") || nameLower.contains("كثافة") || nameLower.contains("gravity") -> "density"
        nameLower.contains("ph") || nameLower.contains("قلوية") || nameLower.contains("حموضة") -> "ph"
        nameLower.contains("solid") || nameLower.contains("صلابة") || nameLower.contains("جفاف") || nameLower.contains("صلب") -> "solid"
        nameLower.contains("binder") || nameLower.contains("رابطة") || nameLower.contains("مادة رابطة") -> "binder"
        nameLower.contains("color") || nameLower.contains("لمعان") || nameLower.contains("gloss") || nameLower.contains("مطابقة") -> "color_gloss"
        else -> nameLower
    }
}

fun extractNumericValue(valueStr: String?): Double? {
    if (valueStr.isNullOrBlank()) return null
    val pattern = Regex("[0-9]+(?:\\.[0-9]+)?")
    val match = pattern.find(valueStr)
    return match?.value?.toDoubleOrNull()
}

@Composable
fun HistoricalTestsDialog(
    testName: String,
    sampleOrProduct: String,
    sessions: List<LabSession>,
    allTests: List<LabTest>,
    viewModel: GbrViewModel,
    onDismiss: () -> Unit
) {
    val targetCategory = getTestCategory(testName)
    val targetProduct = sampleOrProduct.trim()
    val orders by viewModel.productionOrders.collectAsStateWithLifecycle()

    val matchingTests = remember(sessions, allTests, targetCategory, targetProduct) {
        if (targetProduct.isBlank()) emptyList()
        else {
            val matchingSessions = sessions.filter {
                it.sampleOrProduct.trim().equals(targetProduct, ignoreCase = true)
            }
            val matchingSessionIds = matchingSessions.map { it.id }.toSet()
            
            allTests.filter { it.sessionId in matchingSessionIds && getTestCategory(it.name) == targetCategory }
                .mapNotNull { t ->
                    val s = matchingSessions.find { it.id == t.sessionId }
                    if (s != null) {
                        val valA = t.testValueA ?: getTestShortResultValue(t)
                        val numericVal = extractNumericValue(valA)
                        Triple(s, t, numericVal)
                    } else null
                }
                .sortedByDescending { it.first.id }
        }
    }

    val histValues = remember(matchingTests) {
        matchingTests.mapNotNull { it.third }
    }

    val histAvg = remember(histValues) { if (histValues.isNotEmpty()) histValues.average() else 0.0 }
    val histMin = remember(histValues) { if (histValues.isNotEmpty()) histValues.minOrNull() ?: 0.0 else 0.0 }
    val histMax = remember(histValues) { if (histValues.isNotEmpty()) histValues.maxOrNull() ?: 0.0 else 0.0 }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.Gray)
                    }
                    Text(
                        text = "📜 السجل التاريخي لهذا الفحص",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        textAlign = TextAlign.Right
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "نوع الفحص: $testName\nالمنتج الحالي: $targetProduct",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (matchingTests.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد فحوصات سابقة مسجلة لنفس المنتج 📊",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = LabBlueMain.copy(alpha = 0.04f)),
                        border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.15f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📈 الإحصائيات التاريخية (${matchingTests.size} فحص سابق)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabBlueMain,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("المعدل", fontSize = 9.sp, color = Color.Gray)
                                    Text(
                                        text = if (histAvg > 0) String.format(Locale.US, "%,.2f", histAvg) else "-",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("أعلى قراءة", fontSize = 9.sp, color = Color.Gray)
                                    Text(
                                        text = if (histMax > 0) String.format(Locale.US, "%,.2f", histMax) else "-",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("أدنى قراءة", fontSize = 9.sp, color = Color.Gray)
                                    Text(
                                        text = if (histMin > 0) String.format(Locale.US, "%,.2f", histMin) else "-",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(matchingTests) { (session, testItem, numericVal) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, LabBorder)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    // Top row: Product Name & Result Value
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = testItem.testValueA ?: getTestShortResultValue(testItem) ?: "-",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain
                                        )
                                        Text(
                                            text = session.sampleOrProduct,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo,
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    // Middle row: Date & Production Order Number
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val orderId = session.sampleProperties?.let { prop ->
                                            if (prop.startsWith("ORDER_ID:")) {
                                                prop.substringAfter("ORDER_ID:").substringBefore(":QC")
                                            } else null
                                        }
                                        val matchedOrder = orderId?.let { oId -> orders.find { it.id == oId } }
                                        val orderNum = matchedOrder?.orderNumber
                                        
                                        Text(
                                            text = if (orderNum != null) "أمر إنتاج رقم: $orderNum" else "جلسة رقم: ${session.sessionNumber}",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Left
                                        )
                                        Text(
                                            text = "التاريخ: ${session.testDate}",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Right
                                        )
                                    }

                                    // Bottom row: Measurement Settings (if viscosity)
                                    if (testItem.notes.startsWith("WIZARD_VISCOSITY:")) {
                                        val vData = try {
                                            val pureJson = testItem.notes.removePrefix("WIZARD_VISCOSITY:")
                                            val moshi = com.squareup.moshi.Moshi.Builder()
                                                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                                                .build()
                                            moshi.adapter(ViscosityTestData::class.java).fromJson(pureJson)
                                        } catch (e: Exception) {
                                            null
                                        }
                                        
                                        if (vData != null && vData.spindle.isNotBlank() && vData.speed.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            HorizontalDivider(color = LabBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "⚙️ إعدادات القياس: مغزل ${vData.spindle} | سرعة ${vData.speed} RPM" + 
                                                           (if (vData.testType == "RHEOLOGY") " | ريولوجي" else "") +
                                                           (if (vData.testType == "DILUTION") " | تخفيف بالماء" else ""),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF475569),
                                                    textAlign = TextAlign.Right
                                                )
                                            }
                                        }
                                    }
                                    
                                    // Show notes if any
                                    val finalNotes = testItem.notes
                                    if (finalNotes.isNotBlank() && !finalNotes.startsWith("WIZARD_")) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        HorizontalDivider(color = LabBorder.copy(alpha = 0.3f), thickness = 0.5.dp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "✍️ ملاحظات: $finalNotes",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Right,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnifiedStatisticalComplianceCard(
    currentValue: Double?,
    testName: String,
    sampleOrProduct: String,
    sessions: List<LabSession>,
    allTests: List<LabTest>
) {
    if (currentValue == null || currentValue <= 0.0 || sampleOrProduct.isBlank()) return

    val targetCategory = getTestCategory(testName)
    val targetProduct = sampleOrProduct.trim()

    val matchingTests = remember(sessions, allTests, targetCategory, targetProduct) {
        val matchingSessions = sessions.filter {
            it.sampleOrProduct.trim().equals(targetProduct, ignoreCase = true)
        }
        val matchingSessionIds = matchingSessions.map { it.id }.toSet()
        
        allTests.filter { it.sessionId in matchingSessionIds && getTestCategory(it.name) == targetCategory }
            .mapNotNull { t ->
                val s = matchingSessions.find { it.id == t.sessionId }
                if (s != null) {
                    val valA = t.testValueA ?: getTestShortResultValue(t)
                    val numericVal = extractNumericValue(valA)
                    if (numericVal != null) Pair(s, numericVal) else null
                } else null
            }
    }

    if (matchingTests.isEmpty()) return

    val histValues = matchingTests.map { it.second }
    val histAvg = histValues.average()
    val histMin = histValues.minOrNull() ?: 0.0
    val histMax = histValues.maxOrNull() ?: 0.0

    val isPH = targetCategory == "ph"
    val diffPct = if (histAvg > 0.0) kotlin.math.abs((currentValue - histAvg) / histAvg) * 100.0 else 0.0
    val isCompliant = if (isPH) {
        kotlin.math.abs(currentValue - histAvg) <= 0.5
    } else {
        val threshold = when (targetCategory) {
            "density" -> 5.0
            "solid" -> 10.0
            "viscosity" -> 15.0
            else -> 15.0
        }
        diffPct <= threshold
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCompliant) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
        ),
        border = BorderStroke(1.dp, if (isCompliant) Color(0xFFBBF7D0) else Color(0xFFFECACA))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = if (isCompliant) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isCompliant) Color(0xFF166534) else Color(0xFF991B1B),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isCompliant) "مطابق للمواصفة التاريخية" else "خارج حدود الفحص التاريخي المعتاد",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompliant) Color(0xFF166534) else Color(0xFF991B1B)
                    )
                }
                Text(
                    text = "المطابقة والمعدل الإحصائي التاريخي 📊",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("القراءة الحالية", fontSize = 9.sp, color = Color.Gray)
                    Text(
                        text = if (isPH) String.format(Locale.US, "%.2f", currentValue) else String.format(Locale.US, "%,.2f", currentValue),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("المعدل التاريخي", fontSize = 9.sp, color = Color.Gray)
                    Text(
                        text = if (isPH) String.format(Locale.US, "%.2f", histAvg) else String.format(Locale.US, "%,.2f", histAvg),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الانحراف", fontSize = 9.sp, color = Color.Gray)
                    val deviationText = if (isPH) {
                        String.format(Locale.US, "%.2f pH", currentValue - histAvg)
                    } else {
                        String.format(Locale.US, "%.1f%%", diffPct)
                    }
                    Text(
                        text = deviationText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCompliant) Color(0xFF166534) else Color(0xFF991B1B)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "نطاق الفحوصات التاريخية (عدد ${matchingTests.size}): من ${if (isPH) String.format(Locale.US, "%.2f", histMin) else String.format(Locale.US, "%,.2f", histMin)} إلى ${if (isPH) String.format(Locale.US, "%.2f", histMax) else String.format(Locale.US, "%,.2f", histMax)}",
                fontSize = 9.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
        }
    }
}

@Composable
fun TestHistoryButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LabBlueMain),
        border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = LabBlueMain
            )
            Text(
                text = "عرض النتائج السابقة",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = LabBlueMain
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComparisonViscosityWizard(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeComparisonViscosityData(test.notes) ?: ComparisonViscosityTestData()
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Party A, 1 = Party B, 2 = Summary
    
    // Unified test conditions selected once at the start of comparison
    var commonSpindleVal by remember { mutableStateOf(initialData.dataA.spindle.ifBlank { "1" }) }
    var commonSpeedVal by remember { mutableStateOf(initialData.dataA.speed.ifBlank { "60" }) }

    // Party A Readings
    val readingsA = remember { mutableStateListOf<ViscosityReading>().apply {
        val base = initialData.dataA.readings.ifEmpty { listOf(ViscosityReading(), ViscosityReading(), ViscosityReading()) }
        addAll(base)
        while (size < 3) {
            add(ViscosityReading())
        }
    } }

    // Party B Readings
    val readingsB = remember { mutableStateListOf<ViscosityReading>().apply {
        val base = initialData.dataB.readings.ifEmpty { listOf(ViscosityReading(), ViscosityReading(), ViscosityReading()) }
        addAll(base)
        while (size < 3) {
            add(ViscosityReading())
        }
    } }

    val computedStatus = run {
        val validA = readingsA.filter { it.viscosity.isNotBlank() }
        val validB = readingsB.filter { it.viscosity.isNotBlank() }
        when {
            validA.isEmpty() && validB.isEmpty() -> "فارغ"
            validA.size >= readingsA.size && validB.size >= readingsB.size -> "مكتمل"
            else -> "غير مكتمل"
        }
    }
    val testStatus = computedStatus
    
    val nameA = getPartyName(parentSession.partyA)
    val nameB = getPartyName(parentSession.partyB)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مقارنة لزوجة الدهان NDJ-8S | خطوات تفاعلية",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "قارن لزوجة كلا الطرفين بخطوات بسيطة وراجع الفوارق فوراً",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Unified settings card selected once at the beginning
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = LabPurple.copy(alpha = 0.05f)),
            border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚙️ ظروف الاختبار والقياس القياسية الموحّدة",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabPurple
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(LabPurple.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "شروط علمية موحدة",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabPurple
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "١. رقم المغزل (Spindle) المعتمد للطرفين:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Spacer(modifier = Modifier.height(6.dp))
                val spindles = listOf("1", "2", "3", "4")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    spindles.forEach { sp ->
                        val isSelected = commonSpindleVal == sp
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) LabPurple else Color.White)
                                .border(1.dp, if (isSelected) LabPurple else LabBorder, RoundedCornerShape(8.dp))
                                .clickable { 
                                    commonSpindleVal = sp
                                    val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                                    val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                                    val saved = ComparisonViscosityTestData(finalA, finalB)
                                    val serialized = serializeComparisonViscosityData(saved)
                                    viewModel.updateLabTest(test.copy(notes = serialized))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(sp, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else LabDarkIndigo)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "٢. سرعة الدوران (Speed RPM) المعتمدة للطرفين:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Spacer(modifier = Modifier.height(6.dp))
                val speeds = listOf("0.3", "0.6", "1.5", "3", "6", "12", "30", "60")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    speeds.forEach { spd ->
                        val isSelected = commonSpeedVal == spd
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) LabPurple else Color.White)
                                .border(1.dp, if (isSelected) LabPurple else LabBorder, RoundedCornerShape(8.dp))
                                .clickable { 
                                    commonSpeedVal = spd
                                    val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                                    val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                                    val saved = ComparisonViscosityTestData(finalA, finalB)
                                    val serialized = serializeComparisonViscosityData(saved)
                                    viewModel.updateLabTest(test.copy(notes = serialized))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(spd, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color.White else LabDarkIndigo)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Unified USB OTG Control Panel
        UsbViscometerControlPanel(
            modifier = Modifier.padding(bottom = 12.dp),
            onAutoFillRequested = { reading ->
                val targetList = if (selectedTab == 0) readingsA else if (selectedTab == 1) readingsB else null
                if (targetList != null) {
                    val emptyIdx = targetList.indexOfFirst { it.viscosity.isBlank() || it.torque.isBlank() }
                    if (emptyIdx != -1) {
                        targetList[emptyIdx] = ViscosityReading(
                            viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                            torque = String.format(Locale.US, "%.1f", reading.torquePct)
                        )
                    } else {
                        targetList.add(
                            ViscosityReading(
                                viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                torque = String.format(Locale.US, "%.1f", reading.torquePct)
                            )
                        )
                    }
                    val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                    val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                    val saved = ComparisonViscosityTestData(finalA, finalB)
                    val serialized = serializeComparisonViscosityData(saved)
                    viewModel.updateLabTest(test.copy(notes = serialized))
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tab Row switcher
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = LabPurple,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, LabBorder, RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("🧪 $nameA (A)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("🔍 $nameB (B)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("⚖️ مقارنة النتائج", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> {
                PartyViscosityInputSection(
                    name = nameA,
                    spindle = commonSpindleVal,
                    speed = commonSpeedVal,
                    readings = readingsA,
                    onSave = {
                        val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                        val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                        val saved = ComparisonViscosityTestData(finalA, finalB)
                        val serialized = serializeComparisonViscosityData(saved)
                        val validA = readingsA.filter { it.viscosity.isNotBlank() }
                        val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                notes = serialized,
                                testValueA = if (avgA > 0.0) String.format(Locale.US, "%.1f cP", avgA) else "-"
                            )
                        )
                    },
                    onApprove = { selectedTab = 1 }
                )
            }
            1 -> {
                PartyViscosityInputSection(
                    name = nameB,
                    spindle = commonSpindleVal,
                    speed = commonSpeedVal,
                    readings = readingsB,
                    onSave = {
                        val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                        val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                        val saved = ComparisonViscosityTestData(finalA, finalB)
                        val serialized = serializeComparisonViscosityData(saved)
                        val validB = readingsB.filter { it.viscosity.isNotBlank() }
                        val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                notes = serialized,
                                testValueB = if (avgB > 0.0) String.format(Locale.US, "%.1f cP", avgB) else "-"
                            )
                        )
                    },
                    onApprove = { selectedTab = 2 }
                )
            }
            2 -> {
                ViscosityComparisonSummarySection(
                    nameA = nameA,
                    spindleA = commonSpindleVal,
                    speedA = commonSpeedVal,
                    readingsA = readingsA,
                    nameB = nameB,
                    spindleB = commonSpindleVal,
                    speedB = commonSpeedVal,
                    readingsB = readingsB,
                    testStatus = testStatus,
                    onStatusChange = {},
                    onBack = {
                        val finalA = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsA.toList())
                        val finalB = ViscosityTestData(commonSpindleVal, commonSpeedVal, readingsB.toList())
                        val saved = ComparisonViscosityTestData(finalA, finalB)
                        val serialized = serializeComparisonViscosityData(saved)
                        val validA = readingsA.filter { it.viscosity.isNotBlank() }
                        val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                        val validB = readingsB.filter { it.viscosity.isNotBlank() }
                        val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                status = testStatus,
                                notes = serialized,
                                testValueA = if (avgA > 0.0) String.format(Locale.US, "%.1f cP", avgA) else "-",
                                testValueB = if (avgB > 0.0) String.format(Locale.US, "%.1f cP", avgB) else "-"
                            )
                        )
                        onBack()
                    }
                )
            }
        }
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

data class TorqueEvaluation(
    val status: String,
    val description: String,
    val color: Color,
    val alert: String? = null
)

fun evaluateTorque(torqueVal: Double?): TorqueEvaluation? {
    if (torqueVal == null) return null
    return when {
        torqueVal < 10.0 -> TorqueEvaluation(
            status = "🔴 غير موثوقة",
            description = "قراءة غير موثوقة.",
            color = Color(0xFFEF4444),
            alert = "⚠️ تحذير: قيمة العزم منخفضة جداً (أقل من 10%). القراءة غير دقيقة علمياً، يرجى زيادة السرعة أو استخدام مغزل أصغر."
        )
        torqueVal < 20.0 -> TorqueEvaluation(
            status = "🟡 دقة منخفضة",
            description = "مقبولة ولكن الدقة منخفضة.",
            color = Color(0xFFF59E0B),
            alert = "💡 تنبيه: قيمة العزم منخفضة (بين 10% و 20%). مقبولة ولكن يفضل زيادة العزم لتحسين الدقة."
        )
        torqueVal <= 80.0 -> TorqueEvaluation(
            status = "🟢 نطاق ممتاز",
            description = "نطاق ممتاز وموصى به.",
            color = Color(0xFF10B981)
        )
        torqueVal <= 90.0 -> TorqueEvaluation(
            status = "🟡 قريبة من الحد الأعلى",
            description = "مقبولة وقريبة من الحد الأعلى.",
            color = Color(0xFFF59E0B),
            alert = "💡 تنبيه: قيمة العزم مرتفعة (بين 80% و 90%). قياس مقبول ولكنه يقترب من الحد الأقصى للجهاز."
        )
        else -> TorqueEvaluation(
            status = "🔴 خارج النطاق",
            description = "خارج النطاق الموصى به.",
            color = Color(0xFFEF4444),
            alert = "⚠️ تحذير: مستوى العزم مرتفع جداً (أكبر من 90%). خارج النطاق الموصى به للجهاز لحمايته من التلف ولدقة القياس. يرجى خفض السرعة أو استخدام مغزل أكبر."
        )
    }
}

@Composable
fun TorqueInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً فهمت")
            }
        },
        title = {
            Text(
                text = "ℹ️ دليل تقييم مستويات العزم (Torque %)",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = LabDarkIndigo,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "يعتمد فحص اللزوجة بجهاز NDJ-8S على عزم الموتور الدوار لضمان دقة القراءات. يرجى مطابقة مستوى العزم الفوري وفق النطاقات التالية:",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                // Red Range (<10)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("أقل من 10%: قراءة غير موثوقة (منخفضة جداً) 🔴", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, textAlign = TextAlign.Right)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFEF4444), CircleShape))
                }
                
                // Yellow Range (10-20)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("10% إلى أقل من 20%: مقبولة ولكن الدقة منخفضة 🟡", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, textAlign = TextAlign.Right)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFF59E0B), CircleShape))
                }
                
                // Green Range (20-80)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("20% إلى 80%: نطاق ممتاز وموصى به (مثالي) 🟢", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, textAlign = TextAlign.Right)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFF10B981), CircleShape))
                }
                
                // Yellow Range (80-90)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("80% إلى 90%: مقبولة وقريبة من الحد الأعلى 🟡", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, textAlign = TextAlign.Right)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFF59E0B), CircleShape))
                }
                
                // Red Range (>90)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("أكبر من 90%: خارج النطاق الموصى به للجهاز 🔴", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, textAlign = TextAlign.Right)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFEF4444), CircleShape))
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PartyViscosityInputSection(
    name: String,
    spindle: String,
    speed: String,
    readings: SnapshotStateList<ViscosityReading>,
    onSave: () -> Unit,
    onApprove: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    var showInfoDialog by remember { mutableStateOf(false) }

    if (showInfoDialog) {
        TorqueInfoDialog(onDismiss = { showInfoDialog = false })
    }

    val validReadings = readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
    val avgVisc = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
    val avgTorque = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

    // Dynamic Overall Measurement Rating
    val overallEvalStr = if (validReadings.isEmpty()) {
        "بانتظار إدخال القراءات..."
    } else {
        when {
            avgTorque < 10.0 || avgTorque > 90.0 -> "🔴 غير موثوقة وغير مطابقة للمواصفة"
            avgTorque < 20.0 || avgTorque > 80.0 -> "🟡 قراءة مقبولة بدقة منخفضة"
            else -> "🟢 قراءة ممتازة ومثالية"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LabBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ضبط فحص الطرف: $name 🧪",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = LabPurple,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Notice about inherited unified conditions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LabPurple.copy(alpha = 0.05f))
                    .border(1.dp, LabPurple.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Text(
                        text = "⚙️ ظروف فحص موحدة معتمدة تلقائياً:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabPurple,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "رقم المغزل المستعمل: $spindle | سرعة الدوران المحددة: $speed RPM",
                        fontSize = 11.sp,
                        color = LabDarkIndigo,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Real-time analysis card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = LabLightBg),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showInfoDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "معلومات التقييم",
                                tint = LabPurple,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "📊 التغذية الراجعة والتحليل الفوري الفني",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabPurple
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("متوسط اللزوجة", fontSize = 10.sp, color = Color.Gray)
                            Text(
                                text = if (avgVisc > 0) String.format(Locale.US, "%,.1f cP", avgVisc) else "-",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("متوسط Torque", fontSize = 10.sp, color = Color.Gray)
                            Text(
                                text = if (avgTorque > 0) String.format(Locale.US, "%.1f%%", avgTorque) else "-",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                            Text("تقييم القياس العام", fontSize = 10.sp, color = Color.Gray)
                            Text(
                                text = overallEvalStr,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    overallEvalStr.contains("🟢") -> Color(0xFF10B981)
                                    overallEvalStr.contains("🟡") -> Color(0xFFF59E0B)
                                    overallEvalStr.contains("🔴") -> Color(0xFFEF4444)
                                    else -> Color.Gray
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "سجل القراءات المطلوبة (الحد الأدنى 3 قراءات) 📝",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = LabDarkIndigo,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "الرجاء تعبئة اللزوجة ومستوى العزم. سيقوم النظام بالتقييم الفوري للنتائج لكل قراءة:",
                fontSize = 10.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
            Spacer(modifier = Modifier.height(10.dp))

            readings.forEachIndexed { idx, rd ->
                val torqueDbl = rd.torque.toDoubleOrNull()
                val evaluation = evaluateTorque(torqueDbl)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(LabLightBg, RoundedCornerShape(10.dp))
                        .border(
                            1.dp,
                            if (evaluation?.alert != null) evaluation.color.copy(alpha = 0.5f) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (idx >= 3) {
                            IconButton(
                                onClick = {
                                    readings.removeAt(idx)
                                    onSave()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "حذف القراءة الإضافية",
                                    tint = LabErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(24.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (idx >= 3) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(LabPurple.copy(alpha = 0.1f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .padding(end = 6.dp)
                                ) {
                                    Text("قراءة إضافية", fontSize = 8.sp, color = LabPurple, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text("القراءة رقم ${idx + 1} 🔍", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UsbRowAutoPullButton { visc, torq ->
                            readings[idx] = rd.copy(viscosity = visc, torque = torq)
                            onSave()
                        }

                        OutlinedTextField(
                            value = rd.viscosity,
                            onValueChange = { newVal ->
                                readings[idx] = rd.copy(viscosity = newVal)
                                onSave()
                            },
                            label = { Text("الزوجة (cP)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = rd.torque,
                            onValueChange = { newVal ->
                                readings[idx] = rd.copy(torque = newVal)
                                onSave()
                            },
                            label = { Text("العزم (%)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }

                    // Pre-reading Analysis output
                    if (evaluation != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = { showInfoDialog = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "معلومات التقييم",
                                    tint = LabPurple,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(${evaluation.description})",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = evaluation.status,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = evaluation.color
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "تقييم العزم للرسمة: ",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }

                        if (evaluation.alert != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(evaluation.color.copy(alpha = 0.08f))
                                    .border(0.5.dp, evaluation.color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = evaluation.alert,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = evaluation.color,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            if (readings.size >= 3) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            readings.add(ViscosityReading())
                            onSave()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LabBlueMain.copy(alpha = 0.08f),
                            contentColor = LabBlueMain
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة قراءة اختيارية إضافية ➕", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (onApprove != null) {
                        Button(
                            onClick = {
                                onSave()
                                onApprove()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LabSuccessGreen,
                                contentColor = Color.White
                            ),
                            enabled = validReadings.size >= 3,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("✅ اعتماد نتيجة الطرف $name والتقدم للخطوة التالية", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViscosityComparisonSummarySection(
    nameA: String,
    spindleA: String,
    speedA: String,
    readingsA: List<ViscosityReading>,
    nameB: String,
    spindleB: String,
    speedB: String,
    readingsB: List<ViscosityReading>,
    testStatus: String,
    onStatusChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val validA = readingsA.filter { it.viscosity.isNotBlank() }
    val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
    val avgTorqueA = if (validA.isNotEmpty()) validA.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

    val validB = readingsB.filter { it.viscosity.isNotBlank() }
    val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
    val avgTorqueB = if (validB.isNotEmpty()) validB.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

    val deltaVisc = avgB - avgA
    val deltaPct = if (avgA > 0.0) (deltaVisc / avgA) * 100.0 else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚖️ خلاصة مقارنة لزوجة الدهان",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = LabPurple
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Column A
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(LabPurple.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(nameA, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabPurple, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("المغزل: ${spindleA.ifEmpty { "-" }}", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("السرعة: ${speedA.ifEmpty { "-" }} RPM", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("اللزوجة: ${String.format(Locale.US, "%.1f", avgA)} cP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    Text("متوسط العزم: ${String.format(Locale.US, "%.1f", avgTorqueA)}%", fontSize = 10.sp, color = Color.Gray)
                }

                // Column B
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(LabCyan.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(nameB, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("المغزل: ${spindleB.ifEmpty { "-" }}", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("السرعة: ${speedB.ifEmpty { "-" }} RPM", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("اللزوجة: ${String.format(Locale.US, "%.1f", avgB)} cP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    Text("متوسط العزم: ${String.format(Locale.US, "%.1f", avgTorqueB)}%", fontSize = 10.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LabLightBg, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text("تحليل الفروقات والسرعة 📈", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val sign = if (deltaVisc > 0.0) "+" else ""
                    val formattedDelta = String.format(Locale.US, "%s%.1f cP (%s%.1f%%)", sign, deltaVisc, sign, deltaPct)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الفرق العددي للزوجة:", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formattedDelta,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (deltaVisc >= 0.0) LabSuccessGreen else LabErrorRed
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (deltaVisc > 0.0) "تمتلك عينة $nameB لزوجة أعلى مقارنة بـ $nameA بمقدار فرقي واضح."
                               else if (deltaVisc < 0.0) "تمتلك عينة $nameA لزوجة أعلى مقارنة بـ $nameB."
                               else "العينتان متطابقتان تماماً في اللزوجة المقاسة.",
                        fontSize = 11.sp,
                        color = LabDarkIndigo
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("حالة هذا الفحص المقارن ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
            Spacer(modifier = Modifier.height(8.dp))
            val statColor = when (testStatus) {
                "فارغ" -> Color(0xFF64748B)
                "غير مكتمل" -> LabBlueMain
                "مكتمل" -> LabSuccessGreen
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(statColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, statColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "حالة الفحص التلقائية: $testStatus",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        val desc = when (testStatus) {
                            "فارغ" -> "لم يتم إدخال أي قراءات حتى الآن."
                            "غير مكتمل" -> "تم البدء بإدخال القراءات ولكن لم تنتهِ بعد لجميع الأطراف المحددة."
                            "مكتمل" -> "تم تسجيل قراءات الأطراف بالكامل وجاهز للاعتماد الفني."
                            else -> ""
                        }
                        if (desc.isNotBlank()) {
                            Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("حفظ الفحص والرجوع للجلسة ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
fun ComparisonDensityWizard(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeComparisonDensityData(test.notes) ?: ComparisonDensityTestData()
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Party A, 1 = Party B, 2 = Summary

    // Party A States
    var emptyWeightA by remember { mutableStateOf(initialData.dataA.emptyWeight) }
    var filledWeightA by remember { mutableStateOf(initialData.dataA.filledWeight) }
    var volumeA by remember { mutableStateOf(initialData.dataA.volumeMl.toString()) }

    // Party B States
    var emptyWeightB by remember { mutableStateOf(initialData.dataB.emptyWeight) }
    var filledWeightB by remember { mutableStateOf(initialData.dataB.filledWeight) }
    var volumeB by remember { mutableStateOf(initialData.dataB.volumeMl.toString()) }

    val computedStatus = remember(emptyWeightA, filledWeightA, volumeA, emptyWeightB, filledWeightB, volumeB) {
        val hasA = emptyWeightA.isNotBlank() && filledWeightA.isNotBlank()
        val hasB = emptyWeightB.isNotBlank() && filledWeightB.isNotBlank()
        val emptyAll = emptyWeightA.isBlank() && filledWeightA.isBlank() && emptyWeightB.isBlank() && filledWeightB.isBlank()
        when {
            emptyAll -> "فارغ"
            hasA && hasB -> "مكتمل"
            else -> "غير مكتمل"
        }
    }
    val testStatus = computedStatus

    val nameA = getPartyName(parentSession.partyA)
    val nameB = getPartyName(parentSession.partyB)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مقارنة كثافة السوائل والدهانات | خطوات تفاعلية",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "احسب كثافة كلا العينات بدقة تامة ومقارنتها تلقائياً",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Tab Row switcher
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = LabPurple,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, LabBorder, RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("🧪 $nameA (A)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("🔍 $nameB (B)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("⚖️ مقارنة النتائج", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> {
                PartyDensityInputSection(
                    name = nameA,
                    emptyWeight = emptyWeightA,
                    onEmptyWeightChange = { emptyWeightA = it },
                    filledWeight = filledWeightA,
                    onFilledWeightChange = { filledWeightA = it },
                    volume = volumeA,
                    onVolumeChange = { volumeA = it },
                    onSave = {
                        val vMl = volumeA.toDoubleOrNull() ?: 100.0
                        val finalA = DensityTestData(emptyWeightA, filledWeightA, vMl)
                        val finalB = DensityTestData(emptyWeightB, filledWeightB, volumeB.toDoubleOrNull() ?: 100.0)
                        val saved = ComparisonDensityTestData(finalA, finalB)
                        val serialized = serializeComparisonDensityData(saved)
                        val emp = emptyWeightA.toDoubleOrNull() ?: 0.0
                        val fll = filledWeightA.toDoubleOrNull() ?: 0.0
                        val den = if (vMl > 0.0) (fll - emp) / vMl else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                notes = serialized,
                                testValueA = if (den > 0.0) String.format(Locale.US, "%.3f g/cm³", den) else "-"
                            )
                        )
                    }
                )
            }
            1 -> {
                PartyDensityInputSection(
                    name = nameB,
                    emptyWeight = emptyWeightB,
                    onEmptyWeightChange = { emptyWeightB = it },
                    filledWeight = filledWeightB,
                    onFilledWeightChange = { filledWeightB = it },
                    volume = volumeB,
                    onVolumeChange = { volumeB = it },
                    onSave = {
                        val vMlB = volumeB.toDoubleOrNull() ?: 100.0
                        val finalA = DensityTestData(emptyWeightA, filledWeightA, volumeA.toDoubleOrNull() ?: 100.0)
                        val finalB = DensityTestData(emptyWeightB, filledWeightB, vMlB)
                        val saved = ComparisonDensityTestData(finalA, finalB)
                        val serialized = serializeComparisonDensityData(saved)
                        val emp = emptyWeightB.toDoubleOrNull() ?: 0.0
                        val fll = filledWeightB.toDoubleOrNull() ?: 0.0
                        val den = if (vMlB > 0.0) (fll - emp) / vMlB else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                notes = serialized,
                                testValueB = if (den > 0.0) String.format(Locale.US, "%.3f g/cm³", den) else "-"
                            )
                        )
                    }
                )
            }
            2 -> {
                DensityComparisonSummarySection(
                    nameA = nameA,
                    emptyWeightA = emptyWeightA,
                    filledWeightA = filledWeightA,
                    volumeA = volumeA.toDoubleOrNull() ?: 100.0,
                    nameB = nameB,
                    emptyWeightB = emptyWeightB,
                    filledWeightB = filledWeightB,
                    volumeB = volumeB.toDoubleOrNull() ?: 100.0,
                    testStatus = testStatus,
                    onStatusChange = {},
                    onBack = {
                        val vMlA = volumeA.toDoubleOrNull() ?: 100.0
                        val vMlB = volumeB.toDoubleOrNull() ?: 100.0
                        val finalA = DensityTestData(emptyWeightA, filledWeightA, vMlA)
                        val finalB = DensityTestData(emptyWeightB, filledWeightB, vMlB)
                        val saved = ComparisonDensityTestData(finalA, finalB)
                        val serialized = serializeComparisonDensityData(saved)
                        
                        val empA = emptyWeightA.toDoubleOrNull() ?: 0.0
                        val fllA = filledWeightA.toDoubleOrNull() ?: 0.0
                        val denA = if (vMlA > 0.0) (fllA - empA) / vMlA else 0.0
                        
                        val empB = emptyWeightB.toDoubleOrNull() ?: 0.0
                        val fllB = filledWeightB.toDoubleOrNull() ?: 0.0
                        val denB = if (vMlB > 0.0) (fllB - empB) / vMlB else 0.0
                        
                        viewModel.updateLabTest(
                            test.copy(
                                status = testStatus,
                                notes = serialized,
                                testValueA = if (denA > 0.0) String.format(Locale.US, "%.3f g/cm³", denA) else "-",
                                testValueB = if (denB > 0.0) String.format(Locale.US, "%.3f g/cm³", denB) else "-"
                            )
                        )
                        onBack()
                    }
                )
            }
        }
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@Composable
fun PartyDensityInputSection(
    name: String,
    emptyWeight: String,
    onEmptyWeightChange: (String) -> Unit,
    filledWeight: String,
    onFilledWeightChange: (String) -> Unit,
    volume: String,
    onVolumeChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LabBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ضبط فحص الكثافة للطرف: $name 🧪",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = LabPurple,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = emptyWeight,
                onValueChange = { onEmptyWeightChange(it); onSave() },
                label = { Text("وزن المخبار فارغاً (g) ⚖️", fontSize = 12.sp) },
                placeholder = { Text("المثال: 124.5", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = filledWeight,
                onValueChange = { onFilledWeightChange(it); onSave() },
                label = { Text("وزن المخبار بالمنتج (g) 🧪", fontSize = 12.sp) },
                placeholder = { Text("المثال: 254.8", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = volume,
                onValueChange = { onVolumeChange(it); onSave() },
                label = { Text("حجم العينة الفعلي (ml / S.G. Cup) 📐", fontSize = 12.sp) },
                placeholder = { Text("المثال: 100.0", fontSize = 11.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )
        }
    }
}

@Composable
fun DensityComparisonSummarySection(
    nameA: String,
    emptyWeightA: String,
    filledWeightA: String,
    volumeA: Double,
    nameB: String,
    emptyWeightB: String,
    filledWeightB: String,
    volumeB: Double,
    testStatus: String,
    onStatusChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val empA = emptyWeightA.toDoubleOrNull() ?: 0.0
    val fllA = filledWeightA.toDoubleOrNull() ?: 0.0
    val sampleWeightA = fllA - empA
    val denA = if (volumeA > 0.0) sampleWeightA / volumeA else 0.0

    val empB = emptyWeightB.toDoubleOrNull() ?: 0.0
    val fllB = filledWeightB.toDoubleOrNull() ?: 0.0
    val sampleWeightB = fllB - empB
    val denB = if (volumeB > 0.0) sampleWeightB / volumeB else 0.0

    val deltaDensity = denB - denA
    val deltaPct = if (denA > 0.0) (deltaDensity / denA) * 100.0 else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚖️ خلاصة مقارنة كثافة العينات",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = LabPurple
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Column A
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(LabPurple.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(nameA, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabPurple, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("وزن العينة: ${String.format(Locale.US, "%.1f", sampleWeightA)} g", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("الحجم: ${volumeA} ml", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("الكثافة: ${String.format(Locale.US, "%.3f", denA)} g/cm³", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                }

                // Column B
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(LabCyan.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(nameB, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("وزن العينة: ${String.format(Locale.US, "%.1f", sampleWeightB)} g", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("الحجم: ${volumeB} ml", fontSize = 11.sp, color = LabDarkIndigo)
                    Text("الكثافة: ${String.format(Locale.US, "%.3f", denB)} g/cm³", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LabLightBg, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text("تحليل الفروقات والسرعة 📈", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val sign = if (deltaDensity > 0.0) "+" else ""
                    val formattedDelta = String.format(Locale.US, "%s%.3f g/cm³ (%s%.1f%%)", sign, deltaDensity, sign, deltaPct)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("الفرق العددي للكثافة:", fontSize = 11.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = formattedDelta,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (deltaDensity >= 0.0) LabSuccessGreen else LabErrorRed
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (deltaDensity > 0.0) "تتمتع عينة $nameB بكثافة أثقل من عينة $nameA."
                               else if (deltaDensity < 0.0) "تتمتع عينة $nameA بكثافة أثقل من عينة $nameB."
                               else "العينتان متطابقتان تماماً في الكثافة المقاسة.",
                        fontSize = 11.sp,
                        color = LabDarkIndigo
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("حالة هذا الفحص المقارن ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
            Spacer(modifier = Modifier.height(8.dp))
            val statColor = when (testStatus) {
                "فارغ" -> Color(0xFF64748B)
                "غير مكتمل" -> LabBlueMain
                "مكتمل" -> LabSuccessGreen
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(statColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, statColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "حالة الفحص التلقائية: $testStatus",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        val desc = when (testStatus) {
                            "فارغ" -> "لم يتم إدخال أي قراءات حتى الآن."
                            "غير مكتمل" -> "تم البدء بإدخال القراءات ولكن لم تنتهِ بعد لجميع الأطراف المحددة."
                            "مكتمل" -> "تم تسجيل قراءات الأطراف بالكامل وجاهز للاعتماد الفني."
                            else -> ""
                        }
                        if (desc.isNotBlank()) {
                            Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("حفظ الفحص والرجوع للجلسة ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViscosityWizard(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val initialData = remember(test.notes) {
        deserializeViscosityData(test.notes) ?: ViscosityTestData()
    }
    
    // Core parameters state
    var testTypeState by remember { mutableStateOf(initialData.testType.ifBlank { "STANDARD" }) }
    var spindleState by remember { mutableStateOf(initialData.spindle) }
    var speedState by remember { mutableStateOf(initialData.speed) }
    var entryMethodState by remember { mutableStateOf(initialData.entryMethod.ifBlank { "USB" }) }
    
    // Dilution Weights
    val defaultPaintWeight = viewModel.viscosityDilutionPaintWeight.collectAsState().value
    val defaultWaterWeight = viewModel.viscosityDilutionWaterWeight.collectAsState().value
    var paintWeightState by remember { mutableStateOf(initialData.paintWeight.ifBlank { defaultPaintWeight }) }
    var waterWeightState by remember { mutableStateOf(initialData.waterWeight.ifBlank { defaultWaterWeight }) }

    // Readings for Speed 1 (Low speed)
    val readingsList = remember { mutableStateListOf<ViscosityReading>().apply { 
        val base = initialData.readings.ifEmpty { listOf(ViscosityReading(), ViscosityReading(), ViscosityReading()) }
        addAll(base)
        while (size < 3) {
            add(ViscosityReading())
        }
    } }

    // Readings for Speed 2 (High speed - Used under RHEOLOGY)
    val readingsSecondList = remember { mutableStateListOf<ViscosityReading>().apply {
        val base = initialData.readingsSecondSpeed.ifEmpty { listOf(ViscosityReading(), ViscosityReading(), ViscosityReading()) }
        addAll(base)
        while (size < 3) {
            add(ViscosityReading())
        }
    } }
    
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    val orders by viewModel.productionOrders.collectAsStateWithLifecycle()

    var showHistDialog by remember { mutableStateOf(false) }
    var showMeasurementSettingsDialog by remember { mutableStateOf(false) }

    val isFromOrder = remember(parentSession.sampleProperties) {
        parentSession.sampleProperties.startsWith("ORDER_ID:")
    }
    val orderId = remember(parentSession.sampleProperties, isFromOrder) {
        if (isFromOrder) parentSession.sampleProperties.substringAfter("ORDER_ID:").substringBefore(":QC") else ""
    }
    val matchedOrder = remember(orders, orderId) {
        if (orderId.isNotBlank()) orders.find { it.id == orderId } else null
    }
    val formulationId = remember(matchedOrder) {
        matchedOrder?.formulationId
    }

    val refSpecsState = remember(formulationId) {
        if (formulationId != null) {
            viewModel.getFormulationReferenceSpecs(formulationId)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)

    // Find previous runs for the same product and test type
    val historicalTests = remember(sessions, allTests, parentSession.sampleOrProduct, test.id, testTypeState) {
        if (parentSession.sampleOrProduct.isBlank()) emptyList<Pair<LabSession, ViscosityTestData>>()
        else {
            val targetProduct = parentSession.sampleOrProduct.trim()
            val matchingSessions = sessions.filter { 
                it.sampleOrProduct.trim().equals(targetProduct, ignoreCase = true) 
            }
            val matchingSessionIds = matchingSessions.map { it.id }.toSet()
            
            allTests.filter { it.id != test.id && it.sessionId in matchingSessionIds && it.notes.startsWith("WIZARD_VISCOSITY:") }
                .mapNotNull { t ->
                    val s = matchingSessions.find { it.id == t.sessionId }
                    val data = deserializeViscosityData(t.notes)
                    if (s != null && data != null && data.testType == testTypeState) Pair(s, data) else null
                }
        }
    }

    val standardSettings = remember(refSpecsState.value, historicalTests) {
        val list = mutableListOf<Pair<String, String>>()
        
        // 1. From reference specifications
        refSpecsState.value?.viscosityJson?.let { json ->
            deserializeViscosityData(json)?.let { data ->
                if (data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                    list.add(Pair(data.spindle.trim(), data.speed.trim()))
                }
            }
        }
        
        // 2. From historical tests of type STANDARD
        historicalTests.forEach { (_, data) ->
            if (data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                list.add(Pair(data.spindle.trim(), data.speed.trim()))
            }
        }
        
        list.distinctBy { "${it.first}-${it.second}" }
    }

    val currentSessionViscosityTests = remember(allTests, parentSession.id, test.id) {
        allTests.filter { it.sessionId == parentSession.id && it.id != test.id && it.notes.startsWith("WIZARD_VISCOSITY:") }
            .mapNotNull { deserializeViscosityData(it.notes) }
    }

    val filteredStandardSettings = remember(standardSettings, currentSessionViscosityTests) {
        standardSettings.filter { setting ->
            currentSessionViscosityTests.none { used ->
                used.spindle.trim().equals(setting.first, ignoreCase = true) &&
                used.speed.trim().equals(setting.second, ignoreCase = true)
            }
        }
    }

    val recommendedSpindle = remember(historicalTests) {
        historicalTests.filter { it.second.spindle.isNotBlank() }
            .groupBy { it.second.spindle }
            .maxByOrNull { it.value.size }?.key ?: ""
    }

    val recommendedSpeed = remember(historicalTests) {
        historicalTests.filter { it.second.speed.isNotBlank() }
            .groupBy { it.second.speed }
            .maxByOrNull { it.value.size }?.key ?: ""
    }

    val matchingHistTests = remember(historicalTests, spindleState, speedState) {
        historicalTests.filter { 
            it.second.spindle == spindleState && it.second.speed == speedState 
        }
    }

    val histViscosityList = remember(matchingHistTests) {
        matchingHistTests.mapNotNull { (_, data) ->
            val valid = data.readings.filter { it.viscosity.isNotBlank() }
            if (valid.isNotEmpty()) {
                valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
            } else null
        }
    }

    val histMinVisc = remember(histViscosityList) { if (histViscosityList.isNotEmpty()) histViscosityList.minOrNull() ?: 0.0 else 0.0 }
    val histMaxVisc = remember(histViscosityList) { if (histViscosityList.isNotEmpty()) histViscosityList.maxOrNull() ?: 0.0 else 0.0 }
    val histAvgVisc = remember(histViscosityList) { if (histViscosityList.isNotEmpty()) histViscosityList.average() else 0.0 }

    fun formatDoubleValue(v: Double) = if (v % 1.0 == 0.0) String.format(Locale.US, "%,.0f", v) else String.format(Locale.US, "%,.1f", v)

    var currentStep by remember { mutableStateOf(0) }
    val totalSteps = 5
    
    val stepTitles = remember {
        listOf(
            "نوع الفحص المطلوب ⚙️",
            "اختيار المغزل (Spindle)",
            "سرعة الدوران (Speed RPM)",
            "تسجيل وتحليل القراءات 🧪",
            "التقرير وحفظ الفحص 📈"
        )
    }

    var testNotes by remember { mutableStateOf(test.notes.let { 
        if (it.startsWith("WIZARD_VISCOSITY:")) "" else it 
    }) }
    val computedStatus = run {
        val validPrimary = readingsList.filter { it.viscosity.isNotBlank() }
        val validSecond = readingsSecondList.filter { it.viscosity.isNotBlank() }
        val isRheology = testTypeState == "RHEOLOGY"

        when {
            validPrimary.isEmpty() && (!isRheology || validSecond.isEmpty()) -> "فارغ"
            isRheology -> {
                if (validPrimary.size >= 3 && validSecond.size >= 3 && spindleState.isNotBlank() && speedState.isNotBlank()) {
                    "مكتمل"
                } else {
                    "غير مكتمل"
                }
            }
            else -> {
                if (validPrimary.size >= 3 && spindleState.isNotBlank() && speedState.isNotBlank()) {
                    "مكتمل"
                } else {
                    "غير مكتمل"
                }
            }
        }
    }
    val testStatus = computedStatus

    // For Rheology computed speed
    val computedSecondSpeed = remember(speedState) {
        val parsed = speedState.toDoubleOrNull() ?: 0.0
        if (parsed > 0.0) {
            val mult = parsed * 10.0
            if (mult % 1.0 == 0.0) mult.toInt().toString() else String.format(Locale.US, "%.1f", mult)
        } else ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فحص اللزوجة NDJ-8S | خيار الفحص المتعدد",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TestHistoryButton { showHistDialog = true }
        }

        TestStepProgressBar(
            currentStep = currentStep,
            totalSteps = totalSteps,
            titles = stepTitles
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Persistent USB OTG Control Panel for Steps 0, 1, 2, 3
        if (currentStep < 4 && entryMethodState == "USB") {
            UsbViscometerControlPanel(
                modifier = Modifier.padding(bottom = 16.dp),
                onAutoFillRequested = { reading ->
                    if (testTypeState == "RHEOLOGY") {
                        val emptyIdxPrimary = readingsList.indexOfFirst { it.viscosity.isBlank() || it.torque.isBlank() }
                        if (emptyIdxPrimary != -1) {
                            readingsList[emptyIdxPrimary] = ViscosityReading(
                                viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                torque = String.format(Locale.US, "%.1f", reading.torquePct)
                            )
                        } else {
                            val emptyIdxSecond = readingsSecondList.indexOfFirst { it.viscosity.isBlank() || it.torque.isBlank() }
                            if (emptyIdxSecond != -1) {
                                readingsSecondList[emptyIdxSecond] = ViscosityReading(
                                    viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                    torque = String.format(Locale.US, "%.1f", reading.torquePct)
                                )
                            } else {
                                readingsSecondList.add(
                                    ViscosityReading(
                                        viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                        torque = String.format(Locale.US, "%.1f", reading.torquePct)
                                    )
                                )
                            }
                        }
                    } else {
                        val emptyIdx = readingsList.indexOfFirst { it.viscosity.isBlank() || it.torque.isBlank() }
                        if (emptyIdx != -1) {
                            readingsList[emptyIdx] = ViscosityReading(
                                viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                torque = String.format(Locale.US, "%.1f", reading.torquePct)
                            )
                        } else {
                            readingsList.add(
                                ViscosityReading(
                                    viscosity = String.format(Locale.US, "%.0f", reading.viscosityCp),
                                    torque = String.format(Locale.US, "%.1f", reading.torquePct)
                                )
                            )
                        }
                    }
                    // Auto-navigate to readings step so they can see their filled data
                    if (currentStep < 3) {
                        currentStep = 3
                    }
                }
            )
        }

        when (currentStep) {
            0 -> {
                // Step 0: Test Type Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "اختر نوع فحص اللزوجة المطلوب إجراؤه ⚙️",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "يدعم النظام حالياً 3 أنواع من الفحوصات المتخصصة بمصنوفات اللزوجة والسلوك الحركي للدهانات:",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // Type 1: Standard
                        val isStandard = testTypeState == "STANDARD"
                        Card(
                            onClick = { testTypeState = "STANDARD" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isStandard) LabBlueMain.copy(alpha = 0.05f) else Color.White
                            ),
                            border = BorderStroke(
                                width = if (isStandard) 2.dp else 1.dp,
                                color = if (isStandard) LabBlueMain else LabBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        text = "1- فحص اللزوجة القياسي",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isStandard) LabBlueMain else LabDarkIndigo
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RadioButton(
                                        selected = isStandard,
                                        onClick = { testTypeState = "STANDARD" },
                                        colors = RadioButtonDefaults.colors(selectedColor = LabBlueMain)
                                    )
                                }
                                Text(
                                    text = "فحص اللزوجة التقليدي بالسرعة والمغزل المختار دون أية تعديلات (المثبت قياسياً للدهانات الخام).",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Right
                                )
                            }
                        }

                        // Type 2: Rheology
                        val isRheology = testTypeState == "RHEOLOGY"
                        Card(
                            onClick = { testTypeState = "RHEOLOGY" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRheology) LabBlueMain.copy(alpha = 0.05f) else Color.White
                            ),
                            border = BorderStroke(
                                width = if (isRheology) 2.dp else 1.dp,
                                color = if (isRheology) LabBlueMain else LabBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        text = "2- فحص السلوك الريولوجي (Rheology)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRheology) LabBlueMain else LabDarkIndigo
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RadioButton(
                                        selected = isRheology,
                                        onClick = { testTypeState = "RHEOLOGY" },
                                        colors = RadioButtonDefaults.colors(selectedColor = LabBlueMain)
                                    )
                                }
                                Text(
                                    text = "لقياس مؤشر السيلان للدهان. يحدد الفني السرعة الأولى، ويقوم النظام بمضاعفتها تلقائياً ×10، وحساب نسبة انخفاض اللزوجة ومؤشر السلوك (Rheology Index).",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Right
                                )
                            }
                        }

                        // Type 3: Dilution
                        val isDilution = testTypeState == "DILUTION"
                        Card(
                            onClick = { testTypeState = "DILUTION" },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDilution) LabBlueMain.copy(alpha = 0.05f) else Color.White
                            ),
                            border = BorderStroke(
                                width = if (isDilution) 2.dp else 1.dp,
                                color = if (isDilution) LabBlueMain else LabBorder
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        text = "3- اللزوجة بعد التخفيف",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDilution) LabBlueMain else LabDarkIndigo
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RadioButton(
                                        selected = isDilution,
                                        onClick = { testTypeState = "DILUTION" },
                                        colors = RadioButtonDefaults.colors(selectedColor = LabBlueMain)
                                    )
                                }
                                Text(
                                    text = "فحص اللزوجة المنقحة بعد تحضير العينة بنسبة الوزن المحددة للتخفيف للتأكد من استقرارية لزوجة الدهان عند التطبيق والتخفيف الفعلي.",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Right
                                )

                                if (isDilution) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = LabBorder, thickness = 0.5.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "🧪 نسبة التخفيف المعتمدة الحالية بالمصنع:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Right
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = waterWeightState,
                                            onValueChange = { waterWeightState = it },
                                            label = { Text("المياه المضافة (جم)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = paintWeightState,
                                            onValueChange = { paintWeightState = it },
                                            label = { Text("وزن العينة الكلي (جم)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Premium entry method selection panel
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "اختر طريقة ربط وقراءة البيانات المفضلّة 🔌",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "يدعم النظام طريقتين لإدخال وتحليل البيانات، اختر الأنسب لبيئة عملك الحالية والمعدات المتاحة:",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Card 1: USB Connection
                            val isUsb = entryMethodState == "USB"
                            Card(
                                onClick = { entryMethodState = "USB" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUsb) LabBlueMain.copy(alpha = 0.05f) else Color(0xFFFAFAFA)
                                ),
                                border = BorderStroke(
                                    width = if (isUsb) 2.dp else 1.dp,
                                    color = if (isUsb) LabBlueMain else LabBorder
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isUsb) LabBlueMain.copy(alpha = 0.15f)
                                                else Color.LightGray.copy(alpha = 0.3f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SettingsInputHdmi,
                                            contentDescription = null,
                                            tint = if (isUsb) LabBlueMain else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "اتصال تلقائي ذكي",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isUsb) LabBlueMain else LabDarkIndigo,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "قراءة مباشرة ولحظية من جهاز NDJ-8S عبر منفذ OTG.",
                                        fontSize = 9.5.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 13.sp
                                    )
                                }
                            }

                            // Card 2: Manual entry
                            val isManual = entryMethodState == "MANUAL"
                            Card(
                                onClick = { entryMethodState = "MANUAL" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isManual) LabBlueMain.copy(alpha = 0.05f) else Color(0xFFFAFAFA)
                                ),
                                border = BorderStroke(
                                    width = if (isManual) 2.dp else 1.dp,
                                    color = if (isManual) LabBlueMain else LabBorder
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isManual) LabBlueMain.copy(alpha = 0.15f)
                                                else Color.LightGray.copy(alpha = 0.3f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = if (isManual) LabBlueMain else Color.Gray,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "إدخال النتائج يدوياً",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isManual) LabBlueMain else LabDarkIndigo,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "تسجيل القراءات والمخرجات يدوياً باستخدام لوحة المفاتيح.",
                                        fontSize = 9.5.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "اختر رقم المغزل المستخدم للفحص (Spindle - NDJ-8S) ⚙️",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "اختر المغزل المناسب بناءً على النطاق المتوقع للزوجة الدهان.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (recommendedSpindle.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = LabBlueMain.copy(alpha = 0.06f)),
                                border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = LabBlueMain,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "مواصفة الفحص الموصى بها لمنتج ${parentSession.sampleOrProduct}:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo,
                                            textAlign = TextAlign.Right
                                        )
                                        Text(
                                            text = "المغزل المستخدم تاريخياً: مغزل رقم $recommendedSpindle",
                                            fontSize = 10.5.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                    Button(
                                        onClick = { spindleState = recommendedSpindle },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("تطبيق الموصى به", fontSize = 10.sp, color = Color.White)
                                    }
                                }
                            }
                        }

                        val spindles = listOf("1", "2", "3", "4")
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            spindles.chunked(2).forEach { rowSpindles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowSpindles.forEach { sp ->
                                        val isSelected = spindleState == sp
                                        Card(
                                            onClick = { spindleState = sp },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(72.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) LabBlueMain.copy(alpha = 0.08f) else Color.White
                                            ),
                                            border = BorderStroke(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) LabBlueMain else LabBorder
                                            )
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            tint = LabBlueMain,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = "مغزل رقـم $sp",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) LabBlueMain else LabDarkIndigo
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (testTypeState == "RHEOLOGY") "حدد السرعة الأولى (Low Speed RPM) 🏎️" else "سرعة دوران محرك الجهاز (Speed - RPM) 🏎️",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (testTypeState == "RHEOLOGY") {
                                "اختر السرعة الأولى المنخفضة لقراءات السلوك السيلاني. سيقوم النظام تلقائياً بضربها ×10 لتحديد السرعة الثانية المرتفعة."
                            } else {
                                "حدد سرعة الدوران المناسبة للفحص (لفة/دقيقة) لإعطاء قراءات دقيقة ومثالية."
                            },
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (recommendedSpeed.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = LabBlueMain.copy(alpha = 0.06f)),
                                border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = LabBlueMain,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "مواصفة الفحص الموصى بها لمنتج ${parentSession.sampleOrProduct}:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo,
                                            textAlign = TextAlign.Right
                                        )
                                        Text(
                                            text = "سرعة الدوران المعتمدة تاريخياً: $recommendedSpeed RPM",
                                            fontSize = 10.5.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                    Button(
                                        onClick = { speedState = recommendedSpeed },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("تطبيق الموصى به", fontSize = 10.sp, color = Color.White)
                                    }
                                }
                            }
                        }

                        val speeds = listOf("0.3", "0.6", "1.5", "3", "6", "12", "30", "60")
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            speeds.forEach { speed ->
                                val isSelected = speedState == speed
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(if (isSelected) LabBlueMain else LabLightBg)
                                        .border(
                                            1.dp,
                                            if (isSelected) LabBlueMain else LabBorder,
                                            RoundedCornerShape(32.dp)
                                        )
                                        .clickable { speedState = speed }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "$speed RPM",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else LabDarkIndigo
                                    )
                                }
                            }
                        }

                        if (testTypeState == "RHEOLOGY" && speedState.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(LabSuccessGreen.copy(alpha = 0.05f))
                                    .border(1.dp, LabSuccessGreen.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "مضاعف السرعة ×10 للتحليل الريولوجي المباشر:",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "السرعة الأولى: $speedState RPM ---> السرعة الثانية: $computedSecondSpeed RPM",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabSuccessGreen
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("🔄", fontSize = 24.sp)
                                }
                            }
                        }
                    }
                }
            }
            3 -> {
                var showInfoDialog by remember { mutableStateOf(false) }
                if (showInfoDialog) {
                    TorqueInfoDialog(onDismiss = { showInfoDialog = false })
                }

                // Calculations
                val validReadings = readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgVisc = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                val avgTorque = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

                val validReadingsSecond = readingsSecondList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgViscSecond = if (validReadingsSecond.isNotEmpty()) validReadingsSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                val avgTorqueSecond = if (validReadingsSecond.isNotEmpty()) validReadingsSecond.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

                // Evaluation text
                val overallEvalStr = if (testTypeState != "RHEOLOGY") {
                    if (validReadings.isEmpty()) "بانتظار إدخال القراءات..." else {
                        when {
                            avgTorque < 10.0 || avgTorque > 90.0 -> "🔴 غير موثوقة وغير مطابقة للمواصفة"
                            avgTorque < 20.0 || avgTorque > 80.0 -> "🟡 قراءة مقبولة بدقة منخفضة"
                            else -> "🟢 قراءة ممتازة ومثالية"
                        }
                    }
                } else {
                    if (validReadings.isEmpty() || validReadingsSecond.isEmpty()) "بانتظار المدخلات لكلا السرعتين..." else {
                        val worstTorque = minOf(avgTorque, avgTorqueSecond)
                        val bestTorque = maxOf(avgTorque, avgTorqueSecond)
                        when {
                            worstTorque < 10.0 || bestTorque > 90.0 -> "🔴 عزم غير آمن وغير دقيق للفحص"
                            worstTorque < 20.0 || bestTorque > 80.0 -> "🟡 عزم مقبول بدقة متوسطة"
                            else -> "🟢 دقيق ومطابق للمواصفات الحركية"
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "تسجيل وتحليل قراءات فحص اللزوجة 🧪",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabBlueMain,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Conditions Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LabBlueMain.copy(alpha = 0.05f))
                                .border(1.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "⚙️ ظروف فحص NDJ-8S المعتمدة الجلسة:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabBlueMain
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val descText = when (testTypeState) {
                                    "RHEOLOGY" -> "الفحص: ريولوجي | المغزل: $spindleState | السرعة المنخفضة: $speedState RPM | المرتفعة: $computedSecondSpeed RPM"
                                    "DILUTION" -> "الفحص: مخفف بالماء ($paintWeightState جم دهان + $waterWeightState جم ماء) | المغزل: $spindleState | السرعة: $speedState RPM"
                                    else -> "الفحص: لزوجة قياسي | المغزل: $spindleState | السرعة: $speedState RPM"
                                }
                                Text(
                                    text = descText,
                                    fontSize = 11.sp,
                                    color = LabDarkIndigo,
                                    textAlign = TextAlign.Right
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Summary Analysis Panel
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = LabLightBg),
                            border = BorderStroke(1.dp, LabBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { showInfoDialog = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "معلومات التقييم",
                                            tint = LabBlueMain,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = "📊 خلاصة التحليل والربط الإحصائي الفوري",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabBlueMain
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (testTypeState == "RHEOLOGY") {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("متوسط لزوجة ($speedState)", fontSize = 10.sp, color = Color.Gray)
                                            Text(if (avgVisc > 0) String.format(Locale.US, "%,.0f cP", avgVisc) else "-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("متوسط لزوجة ($computedSecondSpeed)", fontSize = 10.sp, color = Color.Gray)
                                            Text(if (avgViscSecond > 0) String.format(Locale.US, "%,.0f cP", avgViscSecond) else "-", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                    } else {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("متوسط اللزوجة", fontSize = 10.sp, color = Color.Gray)
                                            Text(if (avgVisc > 0) String.format(Locale.US, "%,.1f cP", avgVisc) else "-", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                            Text("متوسط العزم %", fontSize = 10.sp, color = Color.Gray)
                                            Text(if (avgTorque > 0) String.format(Locale.US, "%.1f%%", avgTorque) else "-", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                                        Text("تقييم القياس العام", fontSize = 10.sp, color = Color.Gray)
                                        Text(
                                            text = overallEvalStr,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                overallEvalStr.contains("🟢") -> Color(0xFF10B981)
                                                overallEvalStr.contains("🟡") -> Color(0xFFF59E0B)
                                                overallEvalStr.contains("🔴") -> Color(0xFFEF4444)
                                                else -> Color.Gray
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (avgVisc > 0.0 && histAvgVisc > 0.0) {
                            val diffPct = kotlin.math.abs((avgVisc - histAvgVisc) / histAvgVisc) * 100.0
                            val isHistCompliant = diffPct <= 15.0
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isHistCompliant) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                                ),
                                border = BorderStroke(1.dp, if (isHistCompliant) Color(0xFFBBF7D0) else Color(0xFFFECACA))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isHistCompliant) "✅ مطابق للمواصفة التاريخية" else "⚠️ خارج حدود اللزوجة المعتادة",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isHistCompliant) Color(0xFF166534) else Color(0xFF991B1B)
                                        )
                                        Text(
                                            text = "المطابقة والنتائج السابقة لـ ${parentSession.sampleOrProduct} 📊",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("متوسط اللزوجة الحالي", fontSize = 9.5.sp, color = Color.Gray)
                                            Text("${formatDoubleValue(avgVisc)} cP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("المعدل التاريخي المعتاد", fontSize = 9.5.sp, color = Color.Gray)
                                            Text("${formatDoubleValue(histAvgVisc)} cP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("الانحراف عن المعدل", fontSize = 9.5.sp, color = Color.Gray)
                                            Text("${String.format(Locale.US, "%.1f%%", diffPct)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isHistCompliant) Color(0xFF166534) else Color(0xFF991B1B))
                                        }
                                    }
                                    if (matchingHistTests.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        HorizontalDivider(color = if (isHistCompliant) Color(0xFFDCFCE7) else Color(0xFFFEE2E2), thickness = 1.dp)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "نطاق الفحوصات التاريخية المعتمدة بنفس ظروف القياس (عدد ${matchingHistTests.size}): ${formatDoubleValue(histMinVisc)} cP - ${formatDoubleValue(histMaxVisc)} cP",
                                            fontSize = 9.5.sp,
                                            color = Color.Gray,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Display Inputs Based on Test Type
                        if (testTypeState != "RHEOLOGY") {
                            // Standard & Dilution Single List
                            Text(
                                text = if (testTypeState == "DILUTION") "سجل قراءات العينة المخففة بالماء (الحد الأدنى 3) 📝" else "سجل القراءات المطلوبة (الحد الأدنى 3 قراءات) 📝",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            readingsList.forEachIndexed { idx, rd ->
                                val torqueDbl = rd.torque.toDoubleOrNull()
                                val evaluation = evaluateTorque(torqueDbl)
                                
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .background(LabLightBg, RoundedCornerShape(10.dp))
                                        .border(
                                            1.dp,
                                            if (evaluation?.alert != null) evaluation.color.copy(alpha = 0.5f) else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (idx >= 3) {
                                            IconButton(onClick = { readingsList.removeAt(idx) }, modifier = Modifier.size(24.dp)) {
                                                Icon(imageVector = Icons.Default.Delete, contentDescription = "حذف القراءة", tint = LabErrorRed, modifier = Modifier.size(16.dp))
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(24.dp))
                                        }
                                        Text("القراءة المختبرية رقم ${idx + 1} 🔍", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (entryMethodState == "USB") {
                                            UsbRowAutoPullButton { visc, torq ->
                                                readingsList[idx] = rd.copy(viscosity = visc, torque = torq)
                                            }
                                        }
                                        OutlinedTextField(
                                            value = rd.viscosity,
                                            onValueChange = { readingsList[idx] = rd.copy(viscosity = it) },
                                            label = { Text("الزوجة (cP)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = rd.torque,
                                            onValueChange = { readingsList[idx] = rd.copy(torque = it) },
                                            label = { Text("العزم (%)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            singleLine = true
                                        )
                                    }
                                    if (evaluation != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(text = "العزم: ${evaluation.status} (${evaluation.description})", fontSize = 10.sp, color = evaluation.color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
                                        if (evaluation.alert != null) {
                                            Text(text = evaluation.alert, fontSize = 9.5.sp, color = evaluation.color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            if (readingsList.size >= 3) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { readingsList.add(ViscosityReading()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = LabPurple.copy(alpha = 0.08f), contentColor = LabPurple),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إضافة قراءة اختيارية إضافية ➕", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // Rheology Double List
                            Text(
                                text = "تسجيل قراءات السلوك الريولوجي للسرعتين 🔄",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Speed 1 inputs
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, LabBorder),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "📝 قراءات السرعة الأولى المنخفضة ($speedState RPM):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabBlueMain,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Right
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    readingsList.forEachIndexed { index, rd ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("#${index + 1}", fontSize = 11.sp, color = Color.Gray)
                                            if (entryMethodState == "USB") {
                                                UsbRowAutoPullButton { visc, torq ->
                                                    readingsList[index] = rd.copy(viscosity = visc, torque = torq)
                                                }
                                            }
                                            OutlinedTextField(
                                                value = rd.viscosity,
                                                onValueChange = { readingsList[index] = rd.copy(viscosity = it) },
                                                label = { Text("الزوجة (cP)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                singleLine = true
                                            )
                                            OutlinedTextField(
                                                value = rd.torque,
                                                onValueChange = { readingsList[index] = rd.copy(torque = it) },
                                                label = { Text("العزم (%)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                singleLine = true
                                            )
                                        }
                                        val torqueDbl = rd.torque.toDoubleOrNull()
                                        val evaluation = evaluateTorque(torqueDbl)
                                        if (evaluation != null) {
                                            Text(text = "عزم السرعة الأولى: ${evaluation.status} (${evaluation.description})", fontSize = 9.sp, color = evaluation.color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Speed 2 inputs
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, LabBorder),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "📝 قراءات السرعة الثانية المرتفعة تلقائياً ($computedSecondSpeed RPM):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabSuccessGreen,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Right
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    readingsSecondList.forEachIndexed { index, rd ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("#${index + 1}", fontSize = 11.sp, color = Color.Gray)
                                            if (entryMethodState == "USB") {
                                                UsbRowAutoPullButton { visc, torq ->
                                                    readingsSecondList[index] = rd.copy(viscosity = visc, torque = torq)
                                                }
                                            }
                                            OutlinedTextField(
                                                value = rd.viscosity,
                                                onValueChange = { readingsSecondList[index] = rd.copy(viscosity = it) },
                                                label = { Text("الزوجة (cP)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                singleLine = true
                                            )
                                            OutlinedTextField(
                                                value = rd.torque,
                                                onValueChange = { readingsSecondList[index] = rd.copy(torque = it) },
                                                label = { Text("العزم (%)", fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp),
                                                singleLine = true
                                            )
                                        }
                                        val torqueDbl = rd.torque.toDoubleOrNull()
                                        val evaluation = evaluateTorque(torqueDbl)
                                        if (evaluation != null) {
                                            Text(text = "عزم السرعة الثانية: ${evaluation.status} (${evaluation.description})", fontSize = 9.sp, color = evaluation.color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            4 -> {
                // Step 4: Analysis & Save report setup
                val validReadings = readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val validViscosities = validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }
                val validTorques = validReadings.mapNotNull { it.torque.toDoubleOrNull() }

                val avgVisc = if (validViscosities.isNotEmpty()) validViscosities.average() else 0.0
                val maxVisc = if (validViscosities.isNotEmpty()) validViscosities.maxOrNull() ?: 0.0 else 0.0
                val minVisc = if (validViscosities.isNotEmpty()) validViscosities.minOrNull() ?: 0.0 else 0.0

                val avgTorque = if (validTorques.isNotEmpty()) validTorques.average() else 0.0
                val maxTorque = if (validTorques.isNotEmpty()) validTorques.maxOrNull() ?: 0.0 else 0.0
                val minTorque = if (validTorques.isNotEmpty()) validTorques.minOrNull() ?: 0.0 else 0.0

                // Rheology calculations
                val validReadingsSecond = readingsSecondList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val validViscositiesSecond = validReadingsSecond.mapNotNull { it.viscosity.toDoubleOrNull() }
                val validTorquesSecond = validReadingsSecond.mapNotNull { it.torque.toDoubleOrNull() }

                val avgViscSecond = if (validViscositiesSecond.isNotEmpty()) validViscositiesSecond.average() else 0.0
                val avgTorqueSecond = if (validTorquesSecond.isNotEmpty()) validTorquesSecond.average() else 0.0

                val rheologyIdx = if (avgViscSecond > 0.0) avgVisc / avgViscSecond else 0.0
                val viscosityReduction = if (avgVisc > 0.0) ((avgVisc - avgViscSecond) / avgVisc) * 100.0 else 0.0

                val warningTrigger = if (testTypeState != "RHEOLOGY") {
                    if (validViscosities.size >= 2 && avgVisc > 0) {
                        val spread = maxVisc - minVisc
                        val pct = (spread / avgVisc) * 100.0
                        pct > 10.0
                    } else false
                } else false

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "نتائج وحسابات فحص اللزوجة المعتمدة 📊",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )

                        // Highlight type of test is beautiful badge
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(LabBlueMain.copy(alpha = 0.08f))
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (testTypeState) {
                                    "RHEOLOGY" -> "🔬 فحص السلوك الريولوجي المتقدم (Rheology Index)"
                                    "DILUTION" -> "🔬 فحص اللزوجة المنقحة بعد التخفيف بالماء ($paintWeightState جم دهان + $waterWeightState جم ماء)"
                                    else -> "🔬 فحص اللزوجة القياسي (Standard NDJ-8S)"
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabBlueMain,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (testTypeState != "RHEOLOGY") {
                            // Standard & Dilution results
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(LabBlueMain.copy(alpha = 0.05f))
                                    .border(1.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "متوسط اللزوجة الإجمالي للخلطة",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "%,.1f cP", avgVisc),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black,
                                        color = LabBlueMain,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = LabBorder, thickness = 1.dp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("متوسط Torque", fontSize = 10.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%.1f%%", avgTorque), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("رقم المغزل", fontSize = 10.sp, color = Color.Gray)
                                            Text(spindleState.ifBlank { "غير حدد" }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("سرعة الدوران", fontSize = 10.sp, color = Color.Gray)
                                            Text(if (speedState.isNotBlank()) "$speedState RPM" else "غير حدد", fontSize
 = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = LabLightBg),
                                    border = BorderStroke(1.dp, LabBorder)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("أقصى وأدنى لزوجة", fontSize = 10.sp, color = Color.Gray)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("الأعلى: ${String.format(Locale.US, "%.0f", maxVisc)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabErrorRed)
                                        Text("الأدنى: ${String.format(Locale.US, "%.0f", minVisc)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabSuccessGreen)
                                    }
                                }

                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = LabLightBg),
                                    border = BorderStroke(1.dp, LabBorder)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("أقصى وأدنى عزم (Torque)", fontSize = 10.sp, color = Color.Gray)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("الأعلى: ${String.format(Locale.US, "%.1f%%", maxTorque)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabErrorRed)
                                        Text("الأدنى: ${String.format(Locale.US, "%.1f%%", minTorque)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabSuccessGreen)
                                    }
                                }
                            }

                            if (warningTrigger) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                                    border = BorderStroke(1.dp, LabErrorRed.copy(alpha = 0.3f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("⚠️", fontSize = 20.sp)
                                        Text(
                                            text = "تنبيه: تفاوت كبير (>10%) في قراءات اللزوجة المدخلة! يرجى التحقق من استقرار العينة أو المغزل وإعادة المعايرة.",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabErrorRed,
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                }
                            }

                            if (avgVisc > 0.0 && histAvgVisc > 0.0) {
                                val diffPct = kotlin.math.abs((avgVisc - histAvgVisc) / histAvgVisc) * 100.0
                                val isHistCompliant = diffPct <= 15.0
                                
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isHistCompliant) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                                    ),
                                    border = BorderStroke(1.dp, if (isHistCompliant) Color(0xFFBBF7D0) else Color(0xFFFECACA))
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(
                                                    imageVector = if (isHistCompliant) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = if (isHistCompliant) Color(0xFF166534) else Color(0xFF991B1B),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (isHistCompliant) "مطابق للزوجة التاريخية المعتادة" else "خارج حدود اللزوجة المعتادة تاريخياً",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isHistCompliant) Color(0xFF166534) else Color(0xFF991B1B)
                                                )
                                            }
                                            Text(
                                                text = "المطابقة الإحصائية التاريخية 📊",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LabDarkIndigo
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "بمقارنة القراءة الحالية بمعدل الفحوصات التاريخية السابقة لـ (دهان ${parentSession.sampleOrProduct}) بنفس المغزل والسرعة:",
                                            fontSize = 10.sp,
                                            color = Color.Gray,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Right
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceAround
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("اللزوجة الحالية", fontSize = 9.5.sp, color = Color.Gray)
                                                Text("${formatDoubleValue(avgVisc)} cP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("المعدل التاريخي (±15%)", fontSize = 9.5.sp, color = Color.Gray)
                                                Text("${formatDoubleValue(histAvgVisc)} cP", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                            }
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("نسبة الانحراف", fontSize = 9.5.sp, color = Color.Gray)
                                                Text("${String.format(Locale.US, "%.1f%%", diffPct)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isHistCompliant) Color(0xFF166534) else Color(0xFF991B1B))
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        HorizontalDivider(color = if (isHistCompliant) Color(0xFFDCFCE7) else Color(0xFFFEE2E2), thickness = 1.dp)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // History Details List
                                        Text(
                                            text = "آخر الفحوصات التاريخية المطابقة لظروف الفحص:",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Gray,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Right
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        matchingHistTests.take(3).forEach { (s, data) ->
                                            val hAvg = data.readings.filter { it.viscosity.isNotBlank() }.mapNotNull { it.viscosity.toDoubleOrNull() }.average()
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${formatDoubleValue(hAvg)} cP",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = LabDarkIndigo
                                                )
                                                Text(
                                                    text = "جلسة رقم ${s.sessionNumber} بتاريخ ${s.testDate}",
                                                    fontSize = 9.5.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Rheology Results setup
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, LabBorder),
                                colors = CardDefaults.cardColors(containerColor = LabLightBg)
                            ) {
                                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("متوسط لزوجة السرعة 1", fontSize = 11.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%,.1f cP", avgVisc), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                                            Text("سرعة: $speedState RPM", fontSize = 9.5.sp, color = Color.Gray)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("متوسط لزوجة السرعة 2", fontSize = 11.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%,.1f cP", avgViscSecond), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LabSuccessGreen)
                                            Text("سرعة: $computedSecondSpeed RPM", fontSize = 9.5.sp, color = Color.Gray)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider(color = LabBorder, thickness = 1.dp)
                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Rheology Index Hero Box
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("Rheology Index ⚙️", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (rheologyIdx > 0.0) String.format(Locale.US, "%.2f", rheologyIdx) else "-",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = LabBlueMain
                                            )
                                            Text("متوسط الفحص الضعيف ÷ القوي", fontSize = 9.sp, color = Color.Gray)
                                        }

                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("نسبة انخفاض اللزوجة 📉", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = if (viscosityReduction > 0.0) String.format(Locale.US, "%.1f%%", viscosityReduction) else "0.0%",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Black,
                                                color = LabSuccessGreen
                                            )
                                            Text("تأثير القص والسيولة المقترنة", fontSize = 9.sp, color = Color.Gray)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(LabBlueMain.copy(alpha = 0.05f))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = "💡 التفسير الفيزيائي: يعكس مؤشر السلوك الريولوجي (Rheology Index) مدى انسيابية الدهان عند تعرضه لإجهاد القص (مثل الطلاء بالرول أو الفرشاة) مقارنة بحالته الراكدة. القيمة المثالية بين 4.0 إلى 8.5 للدهانات المتطورة.",
                                            fontSize = 9.5.sp,
                                            color = LabDarkIndigo,
                                            textAlign = TextAlign.Right,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        Text("حالة هذا الفحص 📊", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                        Spacer(modifier = Modifier.height(8.dp))
                        val statColor = when (testStatus) {
                            "فارغ" -> Color(0xFF64748B)
                            "غير مكتمل" -> LabBlueMain
                            "مكتمل" -> LabSuccessGreen
                            else -> Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(statColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                .border(1.5.dp, statColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(statColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "حالة الفحص التلقائية: $testStatus",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                    val desc = when (testStatus) {
                                        "فارغ" -> "لم يتم تسجيل أي قراءات في الفحص حتى الآن."
                                        "غير مكتمل" -> "تم رصد قراءة جزئية لبعض المراحل والمحاور."
                                        "مكتمل" -> "تم تسجيل قراءات الفحص بالكامل وجاهز للاعتماد."
                                        else -> ""
                                    }
                                    if (desc.isNotBlank()) {
                                        Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = testNotes,
                            onValueChange = { testNotes = it },
                            label = { Text("ملاحظات الكيميائي الفنية الإضافية 📝", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                            placeholder = { Text("سجل هنا أي معلومات فنية إضافية تود إلحاقها بالفحص...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (currentStep > 0) {
                OutlinedButton(
                    onClick = { currentStep -= 1 },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("الخطوة السابقة ➡️", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("إلغاء والرجوع ❌", fontWeight = FontWeight.Bold)
                }
            }

            if (currentStep < totalSteps - 1) {
                val nextEnabled = when(currentStep) {
                    1 -> spindleState.isNotBlank()
                    2 -> speedState.isNotBlank()
                    3 -> {
                        if (testTypeState == "RHEOLOGY") {
                            readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }.size >= 3 &&
                            readingsSecondList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }.size >= 3
                        } else {
                            readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }.size >= 3
                        }
                    }
                    else -> true
                }
                Button(
                    onClick = {
                        if (currentStep == 0 && testTypeState == "STANDARD" && isFromOrder && filteredStandardSettings.isNotEmpty()) {
                            showMeasurementSettingsDialog = true
                        } else {
                            currentStep += 1
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                    enabled = nextEnabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("الخطوة التالية ⬅️", fontWeight = FontWeight.Bold, color = if (nextEnabled) Color.White else Color.Gray)
                }
            } else {
                Button(
                    onClick = {
                        val validReadings = readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                        val avgVisc = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0

                        val validReadingsSecond = readingsSecondList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                        val avgViscSecond = if (validReadingsSecond.isNotEmpty()) validReadingsSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0

                        val rIndex = if (testTypeState == "RHEOLOGY" && avgViscSecond > 0.0) avgVisc / avgViscSecond else null
                        val reduction = if (testTypeState == "RHEOLOGY" && avgVisc > 0.0) ((avgVisc - avgViscSecond) / avgVisc) * 100.0 else null

                        val finalWizardData = ViscosityTestData(
                            spindle = spindleState,
                            speed = speedState,
                            readings = readingsList.toList(),
                            testType = testTypeState,
                            secondSpeed = if (testTypeState == "RHEOLOGY") computedSecondSpeed else "",
                            readingsSecondSpeed = if (testTypeState == "RHEOLOGY") readingsSecondList.toList() else emptyList(),
                            paintWeight = if (testTypeState == "DILUTION") paintWeightState else "160",
                            waterWeight = if (testTypeState == "DILUTION") waterWeightState else "53.33",
                            rheologyIndex = rIndex,
                            viscosityReductionPct = reduction,
                            entryMethod = entryMethodState
                         )
                        val serializedNotes = serializeViscosityData(finalWizardData)
                        
                        val updatedTest = test.copy(
                            status = testStatus,
                            notes = serializedNotes,
                            testValueA = if (testTypeState == "RHEOLOGY") {
                                if (rIndex != null) String.format(Locale.US, "%.2f R.I", rIndex) else null
                            } else {
                                if (avgVisc > 0.0) String.format(Locale.US, "%.1f cP", avgVisc) else null
                            }
                        )
                        viewModel.updateLabTest(updatedTest)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabSuccessGreen),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("حفظ والاعتماد للجلسة ✅", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }

    if (showMeasurementSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showMeasurementSettingsDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(
                    text = "⚙️ إعدادات القياس القياسية للمنتج",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "تم العثور على إعدادات قياس قياسية محفوظة مسبقاً لهذا المنتج. يرجى اختيار أحد الإعدادات للمتابعة مباشرة لنتائج الفحص، أو اختيار قياس مخصص لتحديد قيم جديدة:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // List standard settings
                    filteredStandardSettings.forEach { setting ->
                        val spindleVal = setting.first
                        val speedVal = setting.second
                        Card(
                            onClick = {
                                spindleState = spindleVal
                                speedState = speedVal
                                currentStep = 3 // Go directly to enter readings step
                                showMeasurementSettingsDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = LabBlueMain.copy(alpha = 0.05f)),
                            border = BorderStroke(1.dp, LabBlueMain.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "المغزل رقم $spindleVal - السرعة $speedVal RPM",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabDarkIndigo,
                                    textAlign = TextAlign.Right
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📊", fontSize = 16.sp)
                            }
                        }
                    }
                    
                    HorizontalDivider(color = LabBorder, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
                    
                    // Custom measurement option
                    Card(
                        onClick = {
                            currentStep = 1 // Go to Choose Spindle step
                            showMeasurementSettingsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "قياس مخصص (مغزل وسرعة مختلفين)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                textAlign = TextAlign.Right
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("🔧", fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMeasurementSettingsDialog = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViscosityWizard_OLD(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViscosityWizard_OLD_BODY_DUMMY(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val initialData = remember(test.notes) {
        deserializeViscosityData(test.notes) ?: ViscosityTestData()
    }
    
    var spindleState by remember { mutableStateOf(initialData.spindle) }
    var speedState by remember { mutableStateOf(initialData.speed) }
    val readingsList = remember { mutableStateListOf<ViscosityReading>().apply { 
        val base = initialData.readings.ifEmpty { listOf(ViscosityReading(), ViscosityReading(), ViscosityReading()) }
        addAll(base)
        while (size < 3) {
            add(ViscosityReading())
        }
    } }
    
    var currentStep by remember { mutableStateOf(0) }
    val totalSteps = 4
    
    val stepTitles = remember {
        listOf(
            "اختيار المغزل (Spindle)",
            "سرعة الدوران (Speed RPM)",
            "تسجيل وتحليل القراءات 🧪",
            "التقرير وحفظ الفحص 📈"
        )
    }

    var testNotes by remember { mutableStateOf(test.notes.let { 
        if (it.startsWith("WIZARD_VISCOSITY:")) "" else it 
    }) }
    var testStatus by remember { mutableStateOf(test.status) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "فحص اللزوجة NDJ-8S | خطوات تفاعلية",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        TestStepProgressBar(
            currentStep = currentStep,
            totalSteps = totalSteps,
            titles = stepTitles
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (currentStep) {
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "اختر رقم المغزل المستخدم للفحص (Spindle - NDJ-8S) ⚙️",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "اختر المغزل المناسب بناءً على النطاق المتوقع للزوجة الدهان.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val spindles = listOf("1", "2", "3", "4")
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            spindles.chunked(2).forEach { rowSpindles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowSpindles.forEach { sp ->
                                        val isSelected = spindleState == sp
                                        Card(
                                            onClick = { spindleState = sp },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(72.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) LabBlueMain.copy(alpha = 0.08f) else Color.White
                                            ),
                                            border = BorderStroke(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) LabBlueMain else LabBorder
                                            )
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            tint = LabBlueMain,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = "مغزل رقـم $sp",
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) LabBlueMain else LabDarkIndigo
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "سرعة دوران محرك الجهاز (Speed - RPM) 🏎️",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "حدد سرعة الدوران المناسبة للفحص (لفة/دقيقة) لإعطاء قراءات دقيقة.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        val speeds = listOf("0.3", "0.6", "1.5", "3", "6", "12", "30", "60")
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            speeds.forEach { speed ->
                                val isSelected = speedState == speed
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(if (isSelected) LabBlueMain else LabLightBg)
                                        .border(
                                            1.dp,
                                            if (isSelected) LabBlueMain else LabBorder,
                                            RoundedCornerShape(32.dp)
                                        )
                                        .clickable { speedState = speed }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "$speed RPM",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else LabDarkIndigo
                                    )
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                var showInfoDialog by remember { mutableStateOf(false) }

                if (showInfoDialog) {
                    TorqueInfoDialog(onDismiss = { showInfoDialog = false })
                }

                val validReadings = readingsList.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgVisc = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                val avgTorque = if (validReadings.isNotEmpty()) validReadings.mapNotNull { it.torque.toDoubleOrNull() }.average() else 0.0

                // Dynamic Overall Measurement Rating
                val overallEvalStr = if (validReadings.isEmpty()) {
                    "بانتظار إدخال القراءات..."
                } else {
                    when {
                        avgTorque < 10.0 || avgTorque > 90.0 -> "🔴 غير موثوقة وغير مطابقة للمواصفة"
                        avgTorque < 20.0 || avgTorque > 80.0 -> "🟡 قراءة مقبولة بدقة منخفضة"
                        else -> "🟢 قراءة ممتازة ومثالية"
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "تسجيل وتحليل قراءات فحص اللزوجة 🧪",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabBlueMain,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Unified test conditions selected in steps 0 and 1
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LabBlueMain.copy(alpha = 0.05f))
                                .border(1.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "⚙️ ظروف فحص NDJ-8S المعتمدة الجلسة:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabBlueMain,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Right
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "مغزل رقـم: $spindleState | سرعة دوران الحساس: $speedState RPM",
                                    fontSize = 11.sp,
                                    color = LabDarkIndigo,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Right
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Real-time analysis card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = LabLightBg),
                            border = BorderStroke(1.dp, LabBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { showInfoDialog = true },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "معلومات التقييم",
                                            tint = LabBlueMain,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        text = "📊 خلاصة التحليل والربط الإحصائي الفوري",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabBlueMain
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text("متوسط اللزوجة", fontSize = 10.sp, color = Color.Gray)
                                        Text(
                                            text = if (avgVisc > 0) String.format(Locale.US, "%,.1f cP", avgVisc) else "-",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                        Text("متوسط Torque", fontSize = 10.sp, color = Color.Gray)
                                        Text(
                                            text = if (avgTorque > 0) String.format(Locale.US, "%.1f%%", avgTorque) else "-",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.2f)) {
                                        Text("تقييم القياس العام", fontSize = 10.sp, color = Color.Gray)
                                        Text(
                                            text = overallEvalStr,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                overallEvalStr.contains("🟢") -> Color(0xFF10B981)
                                                overallEvalStr.contains("🟡") -> Color(0xFFF59E0B)
                                                overallEvalStr.contains("🔴") -> Color(0xFFEF4444)
                                                else -> Color.Gray
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "سجل القراءات المطلوبة (الحد الأدنى 3 قراءات) 📝",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "الرجاء تعبئة اللزوجة وعزم الدوران. سيقوم النظام بتحليل القراءة فورياً وإبداء التوجيهات الفورية لضبط الدقة:",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        readingsList.forEachIndexed { idx, rd ->
                            val torqueDbl = rd.torque.toDoubleOrNull()
                            val evaluation = evaluateTorque(torqueDbl)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(LabLightBg, RoundedCornerShape(10.dp))
                                    .border(
                                        1.dp,
                                        if (evaluation?.alert != null) evaluation.color.copy(alpha = 0.5f) else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (idx >= 3) {
                                        IconButton(
                                            onClick = {
                                                readingsList.removeAt(idx)
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "حذف القراءة الإضافية",
                                                tint = LabErrorRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.width(24.dp))
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (idx >= 3) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(LabPurple.copy(alpha = 0.1f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    .padding(end = 6.dp)
                                            ) {
                                                Text("قراءة إضافية", fontSize = 8.sp, color = LabPurple, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Text("القراءة المختبرية رقم ${idx + 1} 🔍", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = rd.viscosity,
                                        onValueChange = { newVal ->
                                            readingsList[idx] = rd.copy(viscosity = newVal)
                                        },
                                        label = { Text("الزوجة (cP)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )

                                    OutlinedTextField(
                                        value = rd.torque,
                                        onValueChange = { newVal ->
                                            readingsList[idx] = rd.copy(torque = newVal)
                                        },
                                        label = { Text("العزم (%)", fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true
                                    )
                                }

                                // Interactive evaluation outputs
                                if (evaluation != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(
                                            onClick = { showInfoDialog = true },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "معلومات التقييم",
                                                tint = LabBlueMain,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "(${evaluation.description})",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = evaluation.status,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = evaluation.color
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "تقييم العزم للرسمة: ",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }

                                    if (evaluation.alert != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(evaluation.color.copy(alpha = 0.08f))
                                                .border(0.5.dp, evaluation.color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                text = evaluation.alert,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = evaluation.color,
                                                textAlign = TextAlign.Right,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (readingsList.size >= 3) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        readingsList.add(ViscosityReading())
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LabPurple.copy(alpha = 0.08f),
                                        contentColor = LabPurple
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إضافة قراءة اختيارية إضافية ➕", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        currentStep = 3
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("التالي")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
     @OptIn(ExperimentalLayoutApi::class)
@Composable
fun DensityWizard(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeDensityData(test.notes) ?: DensityTestData()
    }

    var useDirectInput by remember { mutableStateOf(initialData.useDirectInput) }
    var directDensityState by remember { mutableStateOf(initialData.directDensity) }
    var emptyWeightState by remember { mutableStateOf(initialData.emptyWeight.ifBlank { "297.61" }) }
    var filledWeightState by remember { mutableStateOf(initialData.filledWeight) }
    var currentStep by remember { mutableStateOf(0) }

    var testNotes by remember { mutableStateOf(test.notes.let { 
        if (it.startsWith("WIZARD_DENSITY:")) "" else it 
    }) }

    // Smart Sync: calculate density and update directDensityState if user is entering weights
    val calculatedDensity = remember(emptyWeightState, filledWeightState) {
        val emp = emptyWeightState.toDoubleOrNull() ?: 0.0
        val fll = filledWeightState.toDoubleOrNull() ?: 0.0
        val sample = fll - emp
        if (sample > 0.0) String.format(Locale.US, "%.3f", sample / 100.0) else ""
    }

    LaunchedEffect(calculatedDensity) {
        if (!useDirectInput && calculatedDensity.isNotBlank()) {
            directDensityState = calculatedDensity
        }
    }

    LaunchedEffect(directDensityState) {
        if (useDirectInput && directDensityState.isNotBlank()) {
            filledWeightState = ""
        }
    }

    val computedDensityFloat = remember(useDirectInput, directDensityState, emptyWeightState, filledWeightState) {
        if (useDirectInput) {
            directDensityState.trim().toDoubleOrNull()
        } else {
            val emp = emptyWeightState.toDoubleOrNull() ?: 0.0
            val fll = filledWeightState.toDoubleOrNull() ?: 0.0
            val sampleWeight = fll - emp
            if (sampleWeight > 0.0) {
                sampleWeight / 100.0
            } else {
                null
            }
        }
    }

    val finalResultStr = remember(useDirectInput, directDensityState, computedDensityFloat) {
        if (useDirectInput) {
            if (directDensityState.isNotBlank()) {
                val clean = directDensityState.trim()
                "$clean g/cm³"
            } else {
                "-"
            }
        } else {
            if (computedDensityFloat != null) {
                String.format(Locale.US, "%.3f g/cm³", computedDensityFloat)
            } else {
                "-"
            }
        }
    }

    val computedStatus = remember(useDirectInput, directDensityState, emptyWeightState, filledWeightState) {
        if (useDirectInput) {
            if (directDensityState.isNotBlank()) "مكتمل" else "فارغ"
        } else {
            val emp = emptyWeightState.toDoubleOrNull() ?: 0.0
            val fll = filledWeightState.toDoubleOrNull() ?: 0.0
            if (emptyWeightState.isBlank() && filledWeightState.isBlank()) {
                "فارغ"
            } else if (emp > 0.0 && fll > 0.0) {
                "مكتمل"
            } else {
                "غير مكتمل"
            }
        }
    }
    val testStatus = computedStatus
    val finalStatusColor = when (testStatus) {
        "مكتمل" -> LabSuccessGreen
        "غير مكتمل" -> LabWarningYellow
        else -> Color.Gray
    }

    val totalSteps = 3
    val stepTitles = listOf(
        "وزن الكوب فارغاً (g) ⚖️",
        "وزن الكوب ممتلئاً بالعينة (g) 🧪",
        "حساب الكثافة والنتائج النهائية 📈"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فحص الكثافة النوعية الموحد | هوية واحدة 📊",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Test Hero Header Card
        TestHeroHeader(
            title = "الفحص للمنتج الحالي:",
            subtitle = test.name,
            mainResult = finalResultStr,
            status = testStatus,
            accentColor = LabBlueMain,
            statusColor = finalStatusColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Method Toggle Pills
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "أسلوب إدخال وتوثيق نتيجة فحص الكثافة:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val directBg = if (useDirectInput) LabBlueMain else Color.White
                    val directTint = if (useDirectInput) Color.White else LabDarkIndigo
                    val directBorder = if (useDirectInput) LabBlueMain else LabBorder

                    val weightBg = if (!useDirectInput) LabBlueMain else Color.White
                    val weightTint = if (!useDirectInput) Color.White else LabDarkIndigo
                    val weightBorder = if (!useDirectInput) LabBlueMain else LabBorder

                    Button(
                        onClick = { useDirectInput = true },
                        modifier = Modifier.weight(1f).border(1.dp, directBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = directBg, contentColor = directTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("إدخال نتيجة مباشرة ✏️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { useDirectInput = false },
                        modifier = Modifier.weight(1f).border(1.dp, weightBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = weightBg, contentColor = weightTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("طريقة حساب الأوزان ⚖️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (useDirectInput) {
            // Direct Input UI
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "إدخال قيمة الكثافة الحجمية مباشرة ✏️",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    Text(
                        text = "أدخل الكثافة المقاسة بوحدة جرام/سم³ (g/cm³) مباشرة في الحقل أدناه.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )

                    OutlinedTextField(
                        value = directDensityState,
                        onValueChange = { directDensityState = it },
                        label = { Text("الكثافة النهائية المقاسة (g/cm³) - Direct Density", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                        placeholder = { Text("مثال: 1.25") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    val directVal = directDensityState.toDoubleOrNull()
                    if (directVal != null && directVal > 0.0) {
                        UnifiedStatisticalComplianceCard(
                            currentValue = directVal,
                            testName = test.name,
                            sampleOrProduct = parentSession.sampleOrProduct,
                            sessions = sessions,
                            allTests = allTests
                        )
                    }

                    OutlinedTextField(
                        value = testNotes,
                        onValueChange = { testNotes = it },
                        label = { Text("ملاحظات الكيميائي الفنية الإضافية 📝", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                        placeholder = { Text("سجل هنا أي معلومات فنية إضافية تود إلحاقها بالفحص...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        } else {
            // Weights step-by-step Wizard
            TestStepProgressBar(
                currentStep = currentStep,
                totalSteps = totalSteps,
                titles = stepTitles
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (currentStep) {
                0 -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "وزن الكوب المعدني وهو فارغ ونظيف ⚖️",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                            Text(
                                text = "تأكد من نظافة وجفاف عبوة الكثافة (Pycnometer) سعة 100 مل تماماً قبل وضعها على ميزان الصيدلية الحساس وضبط المصفر. القيمة افتراضياً 297.61 ويمكنك تعديلها.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )

                            OutlinedTextField(
                                value = emptyWeightState,
                                onValueChange = { emptyWeightState = it },
                                label = { Text("وزن الوعاء الفارغ (جرام) - Empty Pycnometer Weight (g)", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                                placeholder = { Text("مثال: 297.61") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
                1 -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "وزن غطاء وكوب الكثافة وهو ممتلئ بالعينة 🧪",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                            Text(
                                text = "املاً كوب الـ 100 مل بعينة الدهان بالكامل ثم اضغط الغطاء حتى تقفل الفتحة ويمسح الدهان الزائد بالمنديل، ثم يوزن.",
                                fontSize = 11.sp,
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )

                            OutlinedTextField(
                                value = filledWeightState,
                                onValueChange = { filledWeightState = it },
                                label = { Text("وزن الوعاء ممتلئاً (جرام) - Filled Pycnometer Weight (g)", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                                placeholder = { Text("مثال: 423.50") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
                else -> {
                    val emptyVal = emptyWeightState.toDoubleOrNull() ?: 0.0
                    val filledVal = filledWeightState.toDoubleOrNull() ?: 0.0
                    val sampleWeight = filledVal - emptyVal
                    val densityVal = if (sampleWeight > 0) sampleWeight / 100.0 else 0.0

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, LabBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = "تقرير نتائج فحص الكثافة النوعية 📈",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(LabBlueMain.copy(alpha = 0.05f))
                                    .border(1.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "الكثافة النوعية المحسوبة للمنتج مباشرة",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(Locale.US, "%,.3f g/cm³", densityVal),
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Black,
                                        color = LabBlueMain,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = LabBorder, thickness = 1.dp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("وزن العينة", fontSize = 10.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%.2f جرام", sampleWeight), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("وزن العبوة ممتلئة", fontSize = 10.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%.2f جرام", filledVal), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("وزن العبوة فارغة", fontSize = 10.sp, color = Color.Gray)
                                            Text(String.format(Locale.US, "%.2f جرام", emptyVal), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                        }
                                    }
                                }
                            }

                            if (densityVal > 0.0) {
                                UnifiedStatisticalComplianceCard(
                                    currentValue = densityVal,
                                    testName = test.name,
                                    sampleOrProduct = parentSession.sampleOrProduct,
                                    sessions = sessions,
                                    allTests = allTests
                                )
                            }

                            Text("حالة هذا الفحص 📊", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            Spacer(modifier = Modifier.height(8.dp))
                            val statColor = when (testStatus) {
                                "فارغ" -> Color(0xFF64748B)
                                "غير مكتمل" -> LabBlueMain
                                "مكتمل" -> LabSuccessGreen
                                else -> Color.Gray
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(statColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                    .border(1.5.dp, statColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(statColor, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "حالة الفحص التلقائية: $testStatus",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                        val desc = when (testStatus) {
                                            "فارغ" -> "لم يتم تسجيل أي قراءات في الفحص حتى الآن."
                                            "غير مكتمل" -> "تم رصد قراءة جزئية لبعض المراحل والمحاور."
                                            "مكتمل" -> "تم تسجيل قراءات الفحص بالكامل وجاهز للاعتماد."
                                            else -> ""
                                        }
                                        if (desc.isNotBlank()) {
                                            Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = testNotes,
                                onValueChange = { testNotes = it },
                                label = { Text("ملاحظات الكيميائي الفنية الإضافية 📝", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()) },
                                placeholder = { Text("سجل هنا أي معلومات فنية إضافية تود إلحاقها بالفحص...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (useDirectInput) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("إلغاء والرجوع ❌", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val finalDensityData = DensityTestData(
                            emptyWeight = "297.61",
                            filledWeight = "",
                            useDirectInput = true,
                            directDensity = directDensityState
                        )
                        val serializedNotes = serializeDensityData(finalDensityData)
                        val updatedTest = test.copy(
                            status = testStatus,
                            notes = serializedNotes,
                            testValueA = finalResultStr
                        )
                        viewModel.updateLabTest(updatedTest)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabSuccessGreen),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("حفظ والاعتماد للجلسة ✅", fontWeight = FontWeight.Bold, color = Color.White)
                }
            } else {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep -= 1 },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("الخطوة السابقة ➡️", fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("إلغاء والرجوع ❌", fontWeight = FontWeight.Bold)
                    }
                }

                if (currentStep < totalSteps - 1) {
                    Button(
                        onClick = {
                            currentStep += 1
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("الخطوة التالية ⬅️", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    Button(
                        onClick = {
                            val finalDensityData = DensityTestData(
                                emptyWeight = emptyWeightState,
                                filledWeight = filledWeightState,
                                useDirectInput = false,
                                directDensity = directDensityState
                            )
                            val serializedNotes = serializeDensityData(finalDensityData)
                            val updatedTest = test.copy(
                                status = testStatus,
                                notes = serializedNotes,
                                testValueA = finalResultStr
                            )
                            viewModel.updateLabTest(updatedTest)
                            onBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabSuccessGreen),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("حفظ والاعتماد للجلسة ✅", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenericTestWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    var notesState by remember { mutableStateOf(test.notes) }
    var executionDateState by remember { mutableStateOf(test.executionDate) }
    
    // For comparison values
    var valAState by remember { mutableStateOf(test.testValueA ?: "") }
    var valBState by remember { mutableStateOf(test.testValueB ?: "") }

    val cleanNumeric = remember {
        { input: String ->
            val filtered = input.filter { it.isDigit() || it == '.' }
            val dotCount = filtered.count { it == '.' }
            if (dotCount <= 1) {
                filtered
            } else {
                val firstDotIndex = filtered.indexOf('.')
                filtered.filterIndexed { index, char -> char != '.' || index == firstDotIndex }
            }
        }
    }

    val isPHTest = remember(test.name) {
        val nameLower = test.name.lowercase().trim()
        nameLower == "ph" || nameLower == "ph value" || nameLower == "درجة الحموضة" || nameLower == "فحص الـ ph" || nameLower == "فحص ph" || nameLower == "فحص درجة القلوية" || nameLower == "فحص درجة القلوية (ph value)" || nameLower.contains("قلوية")
    }

    val cleanPH = remember {
        { input: String ->
            // Replace Arabic/English commas with dots
            val mapped = input.replace('،', '.').replace(',', '.')
            // Only allow digits and dot (strictly no letters!)
            val filtered = mapped.filter { it.isDigit() || it == '.' }
            
            val dotCount = filtered.count { it == '.' }
            val sanitized = if (dotCount <= 1) {
                filtered
            } else {
                val firstDotIndex = filtered.indexOf('.')
                filtered.filterIndexed { index, char -> char != '.' || index == firstDotIndex }
            }
            
            // Limit digits (excluding the decimal point) to a maximum of 3
            val digitCount = sanitized.count { it.isDigit() }
            if (digitCount <= 3) {
                sanitized
            } else {
                var keptDigits = 0
                val sb = java.lang.StringBuilder()
                for (char in sanitized) {
                    if (char.isDigit()) {
                        if (keptDigits < 3) {
                            sb.append(char)
                            keptDigits++
                        }
                    } else if (char == '.') {
                        sb.append(char)
                    }
                }
                sb.toString()
            }
        }
    }

    val statusOptions = listOf("لم يبدأ", "قيد التنفيذ", "مكتمل", "بانتظار النتيجة", "خارج المواصفة")
    val isComparison = parentSession.testType == "⚖️ فحص مقارنة"

    val computedStatus = remember(valAState, valBState, isComparison) {
        if (isComparison) {
            when {
                valAState.isBlank() && valBState.isBlank() -> "فارغ"
                valAState.isNotBlank() && valBState.isNotBlank() -> "مكتمل"
                else -> "غير مكتمل"
            }
        } else {
            if (valAState.isNotBlank()) "مكتمل" else "فارغ"
        }
    }
    val statusState = computedStatus

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = test.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "حالة الفحص التلقائية 📊",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                val statColor = when (statusState) {
                    "فارغ" -> Color(0xFF64748B)
                    "غير مكتمل" -> LabBlueMain
                    "مكتمل" -> LabSuccessGreen
                    else -> Color.Gray
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(statColor.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .border(1.dp, statColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = statusState,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = statColor
                            )
                            val desc = when (statusState) {
                                "فارغ" -> "لم يتم تسجيل أي نتائج في الفحص حتى الآن."
                                "غير مكتمل" -> "تم رصد قراءة جزئية لبعض الأطراف."
                                "مكتمل" -> "تم تسجيل قراءات الفحص بالكامل وجاهز للاعتماد."
                                else -> ""
                            }
                            if (desc.isNotBlank()) {
                                Text(desc, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "بيانات وجدولة الفحص المختبري 🗓️",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )

                OutlinedTextField(
                    value = executionDateState,
                    onValueChange = { executionDateState = it },
                    label = { Text("تاريخ التنفيذ") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isComparison) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabBlueMain.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "🎯 نتيجة القياس المباشرة",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabBlueMain,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    OutlinedTextField(
                        value = valAState,
                        onValueChange = { valAState = if (isPHTest) cleanPH(it) else cleanNumeric(it) },
                        label = { Text(getTestValueALabel(test), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                        placeholder = if (isPHTest) { { Text("مثال: 8.35") } } else null,
                        supportingText = if (isPHTest) { { Text("الحد الأقصى 3 أرقام مع الفاصلة، الحروف غير مقبولة.", fontSize = 9.5.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) } } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LabBlueMain,
                            focusedLabelColor = LabBlueMain
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )

                    val numericVal = extractNumericValue(valAState)
                    if (numericVal != null && numericVal > 0.0) {
                        UnifiedStatisticalComplianceCard(
                            currentValue = numericVal,
                            testName = test.name,
                            sampleOrProduct = parentSession.sampleOrProduct,
                            sessions = sessions,
                            allTests = allTests
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (isComparison) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "⚖️ قراءات وقياسات المقارنة المختبرية",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabPurple
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = valAState,
                            onValueChange = { valAState = if (isPHTest) cleanPH(it) else cleanNumeric(it) },
                            label = { Text("قراءة: ${parentSession.partyA ?: "أ"}") },
                            placeholder = if (isPHTest) { { Text("مثال: 8.35") } } else null,
                            supportingText = if (isPHTest) { { Text("الحد الأقصى 3 أرقام مع الفاصلة", fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) } } else null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LabPurple,
                                focusedLabelColor = LabPurple
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )

                        OutlinedTextField(
                            value = valBState,
                            onValueChange = { valBState = if (isPHTest) cleanPH(it) else cleanNumeric(it) },
                            label = { Text("قراءة: ${parentSession.partyB ?: "ب"}") },
                            placeholder = if (isPHTest) { { Text("مثال: 8.35") } } else null,
                            supportingText = if (isPHTest) { { Text("الحد الأقصى 3 أرقام مع الفاصلة", fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) } } else null,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LabCyan,
                                focusedLabelColor = LabCyan
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }

                    val numericValA = extractNumericValue(valAState)
                    if (numericValA != null && numericValA > 0.0) {
                        Text(
                            text = "المطابقة لـ ${parentSession.partyA ?: "الطرف أ"}:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabPurple,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Right
                        )
                        UnifiedStatisticalComplianceCard(
                            currentValue = numericValA,
                            testName = test.name,
                            sampleOrProduct = parentSession.sampleOrProduct,
                            sessions = sessions,
                            allTests = allTests
                        )
                    }

                    val numericValB = extractNumericValue(valBState)
                    if (numericValB != null && numericValB > 0.0) {
                        Text(
                            text = "المطابقة لـ ${parentSession.partyB ?: "الطرف ب"}:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabCyan,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Right
                        )
                        UnifiedStatisticalComplianceCard(
                            currentValue = numericValB,
                            testName = test.name,
                            sampleOrProduct = parentSession.sampleOrProduct,
                            sessions = sessions,
                            allTests = allTests
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "تسجيل النتائج، القراءات والتقرير الفني 📝",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )

                OutlinedTextField(
                    value = notesState,
                    onValueChange = { notesState = it },
                    label = { Text("ادخل نتائج الفحص أو الانحرافات أو القيم المقاسة...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء والرجوع")
            }

            Button(
                onClick = {
                    val updatedTest = test.copy(
                        status = statusState,
                        executionDate = executionDateState,
                        notes = notesState,
                        testValueA = valAState.ifBlank { null },
                        testValueB = valBState.ifBlank { null }
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ التغييرات ✅", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@Composable
fun GlossTestWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val isComparison = parentSession.testType == "⚖️ فحص مقارنة"
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeGlossData(test.notes) ?: run {
            val existingVal = test.testValueA ?: ""
            val angle = when {
                existingVal.contains("20") || test.notes.contains("20") -> "20"
                existingVal.contains("85") || test.notes.contains("85") -> "85"
                else -> "60"
            }
            val numericOnly = existingVal.replace(Regex("[^0-9.]"), "").trim()
            GlossTestData(
                selectedAngle = angle,
                gloss20 = if (angle == "20") numericOnly else "",
                gloss60 = if (angle == "60") numericOnly else "",
                gloss85 = if (angle == "85") numericOnly else "",
                primaryValue = numericOnly
            )
        }
    }

    var selectedAngle by remember { mutableStateOf(initialData.selectedAngle.ifBlank { "60" }) }
    var gloss20State by remember { mutableStateOf(initialData.gloss20) }
    var gloss60State by remember { mutableStateOf(initialData.gloss60) }
    var gloss85State by remember { mutableStateOf(initialData.gloss85) }
    var primaryValueState by remember {
        mutableStateOf(
            initialData.primaryValue.ifBlank {
                when (initialData.selectedAngle) {
                    "20" -> initialData.gloss20
                    "85" -> initialData.gloss85
                    else -> initialData.gloss60
                }.ifBlank { test.testValueA?.replace(Regex("[^0-9.]"), "")?.trim() ?: "" }
            }
        )
    }

    var valBState by remember { mutableStateOf(test.testValueB ?: "") }
    var executionDateState by remember { mutableStateOf(test.executionDate) }
    var notesState by remember {
        mutableStateOf(
            if (test.notes.startsWith("WIZARD_GLOSS:")) {
                test.notes.substringAfter("\n\n", "")
            } else test.notes
        )
    }
    var showAllAngles by remember { mutableStateOf(false) }

    val cleanNumeric = remember {
        { input: String ->
            val filtered = input.filter { it.isDigit() || it == '.' }
            val dotCount = filtered.count { it == '.' }
            if (dotCount <= 1) filtered
            else {
                val firstDot = filtered.indexOf('.')
                filtered.filterIndexed { index, c -> c != '.' || index == firstDot }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع",
                    tint = LabDarkIndigo
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "✨ فحص اللمعة (Gloss Test)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة #${parentSession.sessionNumber} - ${parentSession.sampleOrProduct}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            TestHistoryButton(
                onClick = { showHistDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status Card
        val isComplete = primaryValueState.isNotBlank() || (isComparison && valBState.isNotBlank())
        val currentStatus = if (isComplete) "مكتمل" else "فارغ"
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isComplete) Color(0xFFDEF7EC) else Color(0xFFF1F5F9),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = currentStatus,
                        color = if (isComplete) Color(0xFF03543F) else Color(0xFF64748B),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("حالة الفحص", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        if (isComplete) "تم تسجيل قراءة اللمعة بنجاح" else "بانتظار إدخال قراءات الفحص",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LabDarkIndigo
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Date Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "بيانات وجدولة فحص اللمعة 🗓️",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
                OutlinedTextField(
                    value = executionDateState,
                    onValueChange = { executionDateState = it },
                    label = { Text("تاريخ التنفيذ", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Measurement Angle Selection Card (Core User Request)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, LabBlueMain.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "اختر زاوية القياس المعتمدة",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray
                    )
                    Text(
                        text = "📐 خيارات زاوية القياس (ASTM D523)",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                }

                // Segmented Angle Options: 20, 60, 85
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val anglesList = listOf(
                        Triple("20", "20°", "لمعان عالي\nHigh Gloss\n(>70 GU)"),
                        Triple("60", "60°", "قياسية\nSemi-Gloss\n(10-70 GU)"),
                        Triple("85", "85°", "مطفأ / خافت\nMatt / Sheen\n(<10 GU)")
                    )

                    anglesList.forEach { (angleVal, angleTitle, angleDesc) ->
                        val isSelected = selectedAngle == angleVal
                        Card(
                            onClick = {
                                selectedAngle = angleVal
                                val mappedVal = when (angleVal) {
                                    "20" -> gloss20State
                                    "60" -> gloss60State
                                    "85" -> gloss85State
                                    else -> ""
                                }
                                if (mappedVal.isNotBlank()) {
                                    primaryValueState = mappedVal
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) LabBlueMain.copy(alpha = 0.12f) else Color(0xFFF8FAFC)
                            ),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) LabBlueMain else LabBorder
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = angleTitle,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) LabBlueMain else LabDarkIndigo
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = angleDesc,
                                    fontSize = 9.sp,
                                    lineHeight = 12.sp,
                                    textAlign = TextAlign.Center,
                                    color = if (isSelected) LabBlueMain else Color.Gray
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "• الزاوية 60° هي الزاوية المرجعية القياسية لجميع الدهانات.\n• الزاوية 20° مخصصة للمعان العالي، والزاوية 85° للمظهر المطفأ (Sheen).",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Direct Measurement Input Card
        if (!isComparison) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🎯 نتيجة قياس اللمعة عند زاوية $selectedAngle° (Gloss Units)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabBlueMain,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )

                    OutlinedTextField(
                        value = primaryValueState,
                        onValueChange = { newVal ->
                            val clean = cleanNumeric(newVal)
                            primaryValueState = clean
                            when (selectedAngle) {
                                "20" -> gloss20State = clean
                                "60" -> gloss60State = clean
                                "85" -> gloss85State = clean
                            }
                        },
                        label = {
                            Text(
                                "قراءة اللمعة بزاوية $selectedAngle° (GU)",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        },
                        placeholder = { Text("مثال: 45.2", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                        supportingText = {
                            Text(
                                "وحدة القياس: GU (Gloss Units) عند زاوية $selectedAngle درجة",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LabBlueMain,
                            focusedLabelColor = LabBlueMain
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )

                    // ASTM D523 Classification Badge
                    val currentNum = primaryValueState.toDoubleOrNull()
                    if (currentNum != null) {
                        val (classAr, classDesc, bColor) = when {
                            selectedAngle == "60" && currentNum > 70.0 ->
                                Triple("شديد اللمعان (High Gloss)", "يوصى بالقياس التوكيدي بزاوية 20°", Color(0xFF2563EB))
                            selectedAngle == "60" && currentNum >= 30.0 ->
                                Triple("نصف لامع (Semi-Gloss)", "ضمن نطاق زاوية 60° المعتمد", Color(0xFF16A34A))
                            selectedAngle == "60" && currentNum >= 10.0 ->
                                Triple("حريري / خفيف اللمعة (Eggshell / Satin)", "ضمن نطاق زاوية 60° المعتمد", Color(0xFF0D9488))
                            selectedAngle == "60" && currentNum < 10.0 ->
                                Triple("مطفأ (Matt / Flat)", "يوصى بالقياس التوكيدي بزاوية 85°", Color(0xFF64748B))
                            selectedAngle == "20" && currentNum >= 70.0 ->
                                Triple("لمعان عالي معتمد (High Gloss)", "تطابق ممتاز بزاوية 20°", Color(0xFF2563EB))
                            selectedAngle == "20" ->
                                Triple("قراءة اللمعة بزاوية 20°", "مؤشر لمعان عالي", Color(0xFF4F46E5))
                            selectedAngle == "85" && currentNum <= 10.0 ->
                                Triple("مطفأ معتمد (Matt / Sheen)", "تطابق ممتاز بزاوية 85°", Color(0xFF64748B))
                            else ->
                                Triple("قراءة اللمعة بزاوية 85°", "مؤشر الانعكاس الخافت (Sheen)", Color(0xFF0284C7))
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = classDesc,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = classAr,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = bColor
                                )
                            }
                        }

                        // Statistical Compliance Card
                        UnifiedStatisticalComplianceCard(
                            currentValue = currentNum,
                            testName = test.name,
                            sampleOrProduct = parentSession.sampleOrProduct,
                            sessions = sessions,
                            allTests = allTests
                        )
                    }

                    // Optional Multi-Angle Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAllAngles = !showAllAngles }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showAllAngles) "إخفاء الزوايا الإضافية ▲" else "توثيق قراءات الزوايا الأخرى (20° / 60° / 85°) ▼",
                            fontSize = 11.5.sp,
                            color = LabBlueMain,
                            fontWeight = FontWeight.Bold
                        )
                        Text("جهاز ثلاثي الزوايا Tri-Gloss", fontSize = 10.5.sp, color = Color.Gray)
                    }

                    if (showAllAngles) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = gloss20State,
                                    onValueChange = {
                                        val clean = cleanNumeric(it)
                                        gloss20State = clean
                                        if (selectedAngle == "20") primaryValueState = clean
                                    },
                                    label = { Text("20° (GU)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                OutlinedTextField(
                                    value = gloss60State,
                                    onValueChange = {
                                        val clean = cleanNumeric(it)
                                        gloss60State = clean
                                        if (selectedAngle == "60") primaryValueState = clean
                                    },
                                    label = { Text("60° (GU)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                OutlinedTextField(
                                    value = gloss85State,
                                    onValueChange = {
                                        val clean = cleanNumeric(it)
                                        gloss85State = clean
                                        if (selectedAngle == "85") primaryValueState = clean
                                    },
                                    label = { Text("85° (GU)", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Comparison Input Card
        if (isComparison) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabPurple.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "⚖️ مقارنة اللمعة عند زاوية $selectedAngle° (GU)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabPurple
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = primaryValueState,
                            onValueChange = { primaryValueState = cleanNumeric(it) },
                            label = { Text("قراءة: ${parentSession.partyA ?: "أ"} (${selectedAngle}°)") },
                            placeholder = { Text("GU") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LabPurple,
                                focusedLabelColor = LabPurple
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )

                        OutlinedTextField(
                            value = valBState,
                            onValueChange = { valBState = cleanNumeric(it) },
                            label = { Text("قراءة: ${parentSession.partyB ?: "ب"} (${selectedAngle}°)") },
                            placeholder = { Text("GU") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LabCyan,
                                focusedLabelColor = LabCyan
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }

                    // Delta Calculation
                    val numA = primaryValueState.toDoubleOrNull()
                    val numB = valBState.toDoubleOrNull()
                    if (numA != null && numB != null) {
                        val delta = numA - numB
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LabPurple.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2f GU (Δ)", Math.abs(delta)),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = LabPurple
                                )
                                Text(
                                    text = "فارق اللمعة بين الطرفين عند $selectedAngle°:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LabDarkIndigo
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Notes and Technical Observations
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "الملاحظات والتقرير الفني لفحص اللمعة 📝",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
                OutlinedTextField(
                    value = notesState,
                    onValueChange = { notesState = it },
                    label = { Text("أي ملاحظات بصرية أو انحرافات في اللمعان...", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء والرجوع")
            }

            Button(
                onClick = {
                    val formattedResultA = if (primaryValueState.isNotBlank()) {
                        "${primaryValueState} GU @ ${selectedAngle}°"
                    } else ""

                    val formattedResultB = if (valBState.isNotBlank()) {
                        "${valBState} GU @ ${selectedAngle}°"
                    } else ""

                    val glossData = GlossTestData(
                        selectedAngle = selectedAngle,
                        gloss20 = if (selectedAngle == "20") primaryValueState else gloss20State,
                        gloss60 = if (selectedAngle == "60") primaryValueState else gloss60State,
                        gloss85 = if (selectedAngle == "85") primaryValueState else gloss85State,
                        primaryValue = primaryValueState
                    )
                    val jsonNotes = serializeGlossData(glossData)
                    val fullNotes = if (notesState.isNotBlank()) {
                        jsonNotes + "\n\n" + notesState
                    } else {
                        jsonNotes
                    }

                    val updatedTest = test.copy(
                        status = if (primaryValueState.isNotBlank() || (isComparison && valBState.isNotBlank())) "مكتمل" else "فارغ",
                        executionDate = executionDateState,
                        notes = fullNotes,
                        testValueA = formattedResultA.ifBlank { null },
                        testValueB = if (isComparison) formattedResultB.ifBlank { null } else null
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ التغييرات ✅", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}


@Composable
fun AddTestToSessionDialog(
    viewModel: GbrViewModel,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val predefinedTests = listOf(
        "🧪 فحص اللزوجة (Viscosity Test - KU)",
        "🧪 فحص الكثافة النوعية (Specific Gravity - Density)",
        "🧪 فحص نسبة الصلابة والجفاف (Solid Content & Drying Time)",
        "🧪 فحص درجة القلوية (pH Value)",
        "🧪 فحص نسبة المادة الرابطة (Net Binder Content)",
        "🧪 فحص اللمعة (Gloss Test)",
        "🧪 فحص قوة الالتصاق والصفات الميكانيكية (Adhesion & Hardness)",
        "🧪 فحص مقاومة الغسيل والاحتكاك (Scrub Resistance)",
        "🧪 فحص لزوجة كوب فورد 4 (Ford Cup 4 Viscosity)",
        "🧪 فحص نعومة الطحن (Fineness of Grind)"
    )

    val customTestsState = viewModel.qualityTests.collectAsStateWithLifecycle(initialValue = emptyList())
    val customTests = customTestsState.value

    val allTests = remember(customTests) {
        val predefinedNormalized = predefinedTests.map { it.replace("🧪", "").replace("🔬", "").trim().lowercase() }
        val uniqueCustom = customTests.filter { ct ->
            val ctNorm = ct.name.replace("🧪", "").replace("🔬", "").trim().lowercase()
            ctNorm !in predefinedNormalized
        }.map { "🧪 " + it.name }
        predefinedTests + uniqueCustom
    }

    var selectedTests by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = "إضافة فحص جديد للجلسة 🔬",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = LabDarkIndigo,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "اختر فحصاً واحداً أو أكثر من الفحوصات المعرفة في إعدادات المختبر:",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
                
                allTests.forEach { testName ->
                    val isChecked = selectedTests.contains(testName)
                    val (arabicName, englishName) = remember(testName) {
                        val openParenIdx = testName.indexOf('(')
                        val closeParenIdx = testName.lastIndexOf(')')
                        if (openParenIdx != -1 && closeParenIdx != -1 && closeParenIdx > openParenIdx) {
                            val arabic = testName.substring(0, openParenIdx).trim()
                            val english = testName.substring(openParenIdx + 1, closeParenIdx).trim()
                            Pair(arabic, english)
                        } else {
                            Pair(testName, "")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedTests = if (isChecked) {
                                    selectedTests - testName
                                } else {
                                    selectedTests + testName
                                }
                            }
                            .background(if (isChecked) LabBlueMain.copy(alpha = 0.05f) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = arabicName,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isChecked) LabBlueMain else LabDarkIndigo,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (englishName.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = englishName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Light,
                                    color = Color(0xFF64748B), // Calm, soothing Slate gray
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                selectedTests = if (isChecked) {
                                    selectedTests - testName
                                } else {
                                    selectedTests + testName
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = LabBlueMain)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedTests.toList()) },
                enabled = selectedTests.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("إضافة الفحوصات المحددة", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.Gray)
            }
        }
    )
}

@Composable
fun TestHeroHeader(
    title: String,
    subtitle: String,
    mainResult: String,
    status: String,
    accentColor: Color = LabBlueMain,
    statusColor: Color = LabSuccessGreen
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.05f)),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "الحالة: $status",
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Core result badge
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = accentColor),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "النتيجة الأساسية",
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mainResult,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SolidContentWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val isComparison = parentSession.testType == "⚖️ فحص مقارنة"
    if (isComparison) {
        ComparisonSolidContentWorkspace(test, parentSession, viewModel, onBack)
    } else {
        SingleSolidContentWorkspace(test, parentSession, viewModel, onBack)
    }
}

@Composable
fun SingleSolidContentWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeSolidContentData(test.notes) ?: SolidContentTestData()
    }

    var useDirectInput by remember { mutableStateOf(initialData.useDirectInput) }
    var directPct by remember { mutableStateOf(initialData.directPct) }
    var dishWeight by remember { mutableStateOf(initialData.dishWeight) }
    var wetWeight by remember { mutableStateOf(initialData.wetWeight) }
    var dryWeight by remember { mutableStateOf(initialData.dryWeight) }

    var testNotes by remember { mutableStateOf(test.notes.let {
        if (it.startsWith("WIZARD_SOLID_CONTENT:")) "" else it
    }) }
    var executionDateState by remember { mutableStateOf(test.executionDate) }

    // Live calculations
    val dWeight = dishWeight.toDoubleOrNull() ?: 0.0
    val wWeight = wetWeight.toDoubleOrNull() ?: 0.0
    val drWeight = dryWeight.toDoubleOrNull() ?: 0.0

    val netWet = wWeight - dWeight
    val netDry = drWeight - dWeight
    
    val computedPctFloat = remember(useDirectInput, directPct, dWeight, wWeight, drWeight) {
        if (useDirectInput) {
            directPct.replace("%", "").trim().toDoubleOrNull()
        } else {
            if (netWet > 0.0 && netDry >= 0.0) {
                (netDry / netWet) * 100.0
            } else {
                null
            }
        }
    }

    val finalResultStr = remember(useDirectInput, directPct, computedPctFloat) {
        if (useDirectInput) {
            if (directPct.isNotBlank()) {
                val clean = directPct.replace("%", "").trim()
                "$clean %"
            } else {
                "-"
            }
        } else {
            if (computedPctFloat != null) {
                String.format(Locale.US, "%.2f %%", computedPctFloat)
            } else {
                "-"
            }
        }
    }

    val computedStatus = remember(useDirectInput, directPct, dishWeight, wetWeight, dryWeight) {
        if (useDirectInput) {
            if (directPct.isNotBlank()) "مكتمل" else "فارغ"
        } else {
            if (dishWeight.isBlank() && wetWeight.isBlank() && dryWeight.isBlank()) {
                "فارغ"
            } else if (dishWeight.isNotBlank() && wetWeight.isNotBlank() && dryWeight.isNotBlank()) {
                "مكتمل"
            } else {
                "غير مكتمل"
            }
        }
    }

    val finalStatusColor = when (computedStatus) {
        "مكتمل" -> LabSuccessGreen
        "غير مكتمل" -> LabWarningYellow
        else -> Color.Gray
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Back toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فحص المواد الصلبة جاف 🌾",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Prominent Result Header Card (Requirement 1)
        TestHeroHeader(
            title = "الفحص للمنتج الحالي:",
            subtitle = test.name,
            mainResult = finalResultStr,
            status = computedStatus,
            accentColor = LabBlueMain,
            statusColor = finalStatusColor
        )

        // Method Toggle Pills (Requirement 2)
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "أسلوب إدخال وتوثيق نتيجة الفحص:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val directBg = if (useDirectInput) LabBlueMain else Color.White
                    val directTint = if (useDirectInput) Color.White else LabDarkIndigo
                    val directBorder = if (useDirectInput) LabBlueMain else LabBorder

                    val weightBg = if (!useDirectInput) LabBlueMain else Color.White
                    val weightTint = if (!useDirectInput) Color.White else LabDarkIndigo
                    val weightBorder = if (!useDirectInput) LabBlueMain else LabBorder

                    Button(
                        onClick = { useDirectInput = true },
                        modifier = Modifier.weight(1f).border(1.dp, directBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = directBg, contentColor = directTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("إدراج نسبة مباشرة ✏️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { useDirectInput = false },
                        modifier = Modifier.weight(1f).border(1.dp, weightBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = weightBg, contentColor = weightTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("طريقة الأوزان والتجفيف ⚖️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action Inputs Pane
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (useDirectInput) {
                    Text(
                        text = "أدخل النسبة المئوية للمواد الصلبة مباشرة ✏️",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    OutlinedTextField(
                        value = directPct,
                        onValueChange = { directPct = it },
                        label = { Text("نسبة المحتوى الصلب (%) - مثال: 65") },
                        placeholder = { Text("65") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Text(
                        text = "مثال: عند كتابة 65 سيتم حفظ وتوثيق النسبة كـ 65 % في جدول نتائج الجلسة.",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                } else {
                    Text(
                        text = "قياس الأوزان لنسبة التجفيف والجاف ⚖️",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    
                    // 1. Dish weight
                    OutlinedTextField(
                        value = dishWeight,
                        onValueChange = { dishWeight = it },
                        label = { Text("وزن طبق الفحص فارغاً (g) - Dish Tare Weight") },
                        placeholder = { Text("مثال: 5.20") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 2. Wet Weight
                    OutlinedTextField(
                        value = wetWeight,
                        onValueChange = { wetWeight = it },
                        label = { Text("الوزن الكلي رطب مع طبق الفحص قبل التجفيف (g) - Total Wet Weight") },
                        placeholder = { Text("مثال: 15.30") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 3. Dry Weight
                    OutlinedTextField(
                        value = dryWeight,
                        onValueChange = { dryWeight = it },
                        label = { Text("الوزن الكلي جاف مع طبق الفحص بعد التجفيف (g) - Total Dry Weight") },
                        placeholder = { Text("مثال: 11.55") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Display Calculations live
                    if (netWet > 0.0 && netDry >= 0.0) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = LabSuccessGreen.copy(alpha = 0.04f)),
                            border = BorderStroke(0.5.dp, LabSuccessGreen.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "🔢 الحسابات والنتائج التفصيلية الحالية:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabSuccessGreen
                                )
                                Text(
                                    text = "• وزن العينة الرطبة الصافي = ${String.format(Locale.US, "%.2f", netWet)} جرام",
                                    fontSize = 10.5.sp,
                                    color = LabDarkIndigo
                                )
                                Text(
                                    text = "• وزن المواد الصلبة الصافية بعد الجفاف = ${String.format(Locale.US, "%.2f", netDry)} جرام",
                                    fontSize = 10.5.sp,
                                    color = LabDarkIndigo
                                )
                                Text(
                                    text = "• النسبة المحتسبة = (${String.format(Locale.US, "%.2f", netDry)} / ${String.format(Locale.US, "%.2f", netWet)}) \u00D7 100 = ${finalResultStr}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabSuccessGreen
                                )
                            }
                        }
                    }
                }

                val currentVal = computedPctFloat
                if (currentVal != null && currentVal > 0.0) {
                    UnifiedStatisticalComplianceCard(
                        currentValue = currentVal,
                        testName = test.name,
                        sampleOrProduct = parentSession.sampleOrProduct,
                        sessions = sessions,
                        allTests = allTests
                    )
                }
            }
        }

        // Execution Date & Custom analysis notes
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "توثيق وتفاصيل إضافية للتقرير 📝",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                OutlinedTextField(
                    value = executionDateState,
                    onValueChange = { executionDateState = it },
                    label = { Text("تاريخ التنفيذ الفعلي") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = testNotes,
                    onValueChange = { testNotes = it },
                    label = { Text("أي ملاحظات إضافية أو ظروف الفحص") },
                    placeholder = { Text("مثال: عينة مجففة بالفرن لمدة ساعتين على درجة حرارة 105°م.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        // Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء والرجوع")
            }

            Button(
                onClick = {
                    val sData = SolidContentTestData(
                        useDirectInput = useDirectInput,
                        directPct = directPct,
                        dishWeight = dishWeight,
                        wetWeight = wetWeight,
                        dryWeight = dryWeight
                    )
                    val serialized = serializeSolidContentData(sData)
                    val finalVal = if (finalResultStr == "-") null else finalResultStr
                    val updatedTest = test.copy(
                        status = computedStatus,
                        executionDate = executionDateState,
                        notes = serialized + (if (testNotes.isNotBlank()) "\n$testNotes" else ""),
                        testValueA = finalVal
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ التغييرات ✅", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@Composable
fun ComparisonSolidContentWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeComparisonSolidContentData(test.notes) ?: ComparisonSolidContentTestData()
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = A, 1 = B, 2 = Summary
    
    // Tab states
    var useDirectInputA by remember { mutableStateOf(initialData.dataA.useDirectInput) }
    var directPctA by remember { mutableStateOf(initialData.dataA.directPct) }
    var dishWeightA by remember { mutableStateOf(initialData.dataA.dishWeight) }
    var wetWeightA by remember { mutableStateOf(initialData.dataA.wetWeight) }
    var dryWeightA by remember { mutableStateOf(initialData.dataA.dryWeight) }

    var useDirectInputB by remember { mutableStateOf(initialData.dataB.useDirectInput) }
    var directPctB by remember { mutableStateOf(initialData.dataB.directPct) }
    var dishWeightB by remember { mutableStateOf(initialData.dataB.dishWeight) }
    var wetWeightB by remember { mutableStateOf(initialData.dataB.wetWeight) }
    var dryWeightB by remember { mutableStateOf(initialData.dataB.dryWeight) }

    var testNotes by remember { mutableStateOf(test.notes.let {
        if (it.startsWith("WIZARD_COMP_SOLID_CONTENT:")) "" else it
    }) }
    var executionDateState by remember { mutableStateOf(test.executionDate) }

    val partyAName = getPartyName(parentSession.partyA)
    val partyBName = getPartyName(parentSession.partyB)

    // Live compute Side A
    val dWeightA = dishWeightA.toDoubleOrNull() ?: 0.0
    val wWeightA = wetWeightA.toDoubleOrNull() ?: 0.0
    val drWeightA = dryWeightA.toDoubleOrNull() ?: 0.0
    val netWetA = wWeightA - dWeightA
    val netDryA = drWeightA - dWeightA
    val computedPctFloatA = if (useDirectInputA) {
        directPctA.replace("%", "").trim().toDoubleOrNull()
    } else {
        if (netWetA > 0.0 && netDryA >= 0.0) (netDryA / netWetA) * 100.0 else null
    }
    val finalResultStrA = if (useDirectInputA) {
        if (directPctA.isNotBlank()) directPctA.replace("%", "").trim() + " %" else "-"
    } else {
        if (computedPctFloatA != null) String.format(Locale.US, "%.2f %%", computedPctFloatA) else "-"
    }

    // Live compute Side B
    val dWeightB = dishWeightB.toDoubleOrNull() ?: 0.0
    val wWeightB = wetWeightB.toDoubleOrNull() ?: 0.0
    val drWeightB = dryWeightB.toDoubleOrNull() ?: 0.0
    val netWetB = wWeightB - dWeightB
    val netDryB = drWeightB - dWeightB
    val computedPctFloatB = if (useDirectInputB) {
        directPctB.replace("%", "").trim().toDoubleOrNull()
    } else {
        if (netWetB > 0.0 && netDryB >= 0.0) (netDryB / netWetB) * 100.0 else null
    }
    val finalResultStrB = if (useDirectInputB) {
        if (directPctB.isNotBlank()) directPctB.replace("%", "").trim() + " %" else "-"
    } else {
        if (computedPctFloatB != null) String.format(Locale.US, "%.2f %%", computedPctFloatB) else "-"
    }

    // Comparison status
    val statusA = if (useDirectInputA) {
        if (directPctA.isNotBlank()) "مكتمل" else "فارغ"
    } else {
        if (dishWeightA.isBlank() && wetWeightA.isBlank() && dryWeightA.isBlank()) "فارغ"
        else if (dishWeightA.isNotBlank() && wetWeightA.isNotBlank() && dryWeightA.isNotBlank()) "مكتمل"
        else "غير مكتمل"
    }
    val statusB = if (useDirectInputB) {
        if (directPctB.isNotBlank()) "مكتمل" else "فارغ"
    } else {
        if (dishWeightB.isBlank() && wetWeightB.isBlank() && dryWeightB.isBlank()) "فارغ"
        else if (dishWeightB.isNotBlank() && wetWeightB.isNotBlank() && dryWeightB.isNotBlank()) "مكتمل"
        else "غير مكتمل"
    }

    val computedStatus = when {
        statusA == "مكتمل" && statusB == "مكتمل" -> "مكتمل"
        statusA == "فارغ" && statusB == "فارغ" -> "فارغ"
        else -> "غير مكتمل"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Back toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مقارنة محتوى المواد الصلبة جاف ⚖️",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Tabrow or Switch
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = LabPurple,
            modifier = Modifier.padding(bottom = 16.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, LabBorder)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("$partyAName (A)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("$partyBName (B)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("التحليل والمقارنة 📊", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            )
        }

        when (selectedTab) {
            0 -> {
                // Side A Form
                TestHeroHeader(
                    title = "قياس الطرف الأول (A) - $partyAName:",
                    subtitle = test.name,
                    mainResult = finalResultStrA,
                    status = statusA,
                    accentColor = LabPurple,
                    statusColor = if (statusA == "مكتمل") LabSuccessGreen else (if (statusA == "فارغ") Color.Gray else LabWarningYellow)
                )

                // Sub-Toggle Method for A
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "أسلوب إدخال الطرف (A):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { useDirectInputA = true },
                                modifier = Modifier.weight(1f).border(1.dp, if (useDirectInputA) LabPurple else LabBorder, RoundedCornerShape(10.dp)),
                                colors = ButtonDefaults.buttonColors(containerColor = if (useDirectInputA) LabPurple else Color.White, contentColor = if (useDirectInputA) Color.White else LabDarkIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("نسبة مباشرة ✏️", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = { useDirectInputA = false },
                                modifier = Modifier.weight(1f).border(1.dp, if (!useDirectInputA) LabPurple else LabBorder, RoundedCornerShape(10.dp)),
                                colors = ButtonDefaults.buttonColors(containerColor = if (!useDirectInputA) LabPurple else Color.White, contentColor = if (!useDirectInputA) Color.White else LabDarkIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("طريقة الأوزان ⚖️", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (useDirectInputA) {
                            Text("أدخل نسبة المواد الصلبة للطرف (A) مباشرة ✏️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            OutlinedTextField(
                                value = directPctA,
                                onValueChange = { directPctA = it },
                                label = { Text("نسبة المحتوى الصلب (%) للطرف أ") },
                                placeholder = { Text("مثال: 65") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        } else {
                            Text("وزن وطبق فخص الطرف (A) ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            OutlinedTextField(
                                value = dishWeightA,
                                onValueChange = { dishWeightA = it },
                                label = { Text("وزن طبق الفحص (g)") },
                                placeholder = { Text("مثال: 5.20") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = wetWeightA,
                                onValueChange = { wetWeightA = it },
                                label = { Text("الوزن الكلي رطب مع طبق الفحص قبل التجفيف (g)") },
                                placeholder = { Text("مثال: 15.30") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = dryWeightA,
                                onValueChange = { dryWeightA = it },
                                label = { Text("الوزن الكلي جاف مع طبق الفحص بعد التجفيف (g)") },
                                placeholder = { Text("مثال: 11.55") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (netWetA > 0.0 && netDryA >= 0.0) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = LabPurple.copy(alpha = 0.04f)),
                                    border = BorderStroke(0.5.dp, LabPurple.copy(alpha = 0.15f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("• العينة الرطبة الصافية: ${String.format(Locale.US, "%.2f", netWetA)} g", fontSize = 10.5.sp, color = LabDarkIndigo)
                                        Text("• العينة الجافة الصافية: ${String.format(Locale.US, "%.2f", netDryA)} g", fontSize = 10.5.sp, color = LabDarkIndigo)
                                        Text("• النسبة المحسوبة لـ (A): $finalResultStrA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabPurple)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                // Side B Form
                TestHeroHeader(
                    title = "قياس الطرف الثاني (B) - $partyBName:",
                    subtitle = test.name,
                    mainResult = finalResultStrB,
                    status = statusB,
                    accentColor = LabCyan,
                    statusColor = if (statusB == "مكتمل") LabSuccessGreen else (if (statusB == "فارغ") Color.Gray else LabWarningYellow)
                )

                // Sub-Toggle Method for B
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "أسلوب إدخال الطرف (B):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { useDirectInputB = true },
                                modifier = Modifier.weight(1f).border(1.dp, if (useDirectInputB) LabCyan else LabBorder, RoundedCornerShape(10.dp)),
                                colors = ButtonDefaults.buttonColors(containerColor = if (useDirectInputB) LabCyan else Color.White, contentColor = if (useDirectInputB) Color.White else LabDarkIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("نسبة مباشرة ✏️", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = { useDirectInputB = false },
                                modifier = Modifier.weight(1f).border(1.dp, if (!useDirectInputB) LabCyan else LabBorder, RoundedCornerShape(10.dp)),
                                colors = ButtonDefaults.buttonColors(containerColor = if (!useDirectInputB) LabCyan else Color.White, contentColor = if (!useDirectInputB) Color.White else LabDarkIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("طريقة الأوزان ⚖️", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (useDirectInputB) {
                            Text("أدخل نسبة المواد الصلبة للطرف (B) مباشرة ✏️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            OutlinedTextField(
                                value = directPctB,
                                onValueChange = { directPctB = it },
                                label = { Text("نسبة المحتوى الصلب (%) للطرف ب") },
                                placeholder = { Text("مثال: 62.5") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                        } else {
                            Text("وزن وطبق فخص الطرف (B) ⚖️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            OutlinedTextField(
                                value = dishWeightB,
                                onValueChange = { dishWeightB = it },
                                label = { Text("وزن طبق الفحص (g)") },
                                placeholder = { Text("مثال: 5.18") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = wetWeightB,
                                onValueChange = { wetWeightB = it },
                                label = { Text("الوزن الكلي رطب مع طبق الفحص قبل التجفيف (g)") },
                                placeholder = { Text("مثال: 15.22") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            OutlinedTextField(
                                value = dryWeightB,
                                onValueChange = { dryWeightB = it },
                                label = { Text("الوزن الكلي جاف مع طبق الفحص بعد التجفيف (g)") },
                                placeholder = { Text("مثال: 11.40") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            if (netWetB > 0.0 && netDryB >= 0.0) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = LabCyan.copy(alpha = 0.04f)),
                                    border = BorderStroke(0.5.dp, LabCyan.copy(alpha = 0.15f))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("• العينة الرطبة الصافية: ${String.format(Locale.US, "%.2f", netWetB)} g", fontSize = 10.5.sp, color = LabDarkIndigo)
                                        Text("• العينة الجافة الصافية: ${String.format(Locale.US, "%.2f", netDryB)} g", fontSize = 10.5.sp, color = LabDarkIndigo)
                                        Text("• النسبة المحسوبة لـ (B): $finalResultStrB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = LabCyan)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // Summary & Compare tab (Requirement 1 & 2)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "مقارنة نسب ومحتوى المواد الصلبة 📊",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(LabPurple.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .border(1.dp, LabPurple.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("$partyAName (A)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LabPurple)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(finalResultStrA, fontSize = 20.sp, fontWeight = FontWeight.Black, color = LabDarkIndigo)
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(LabCyan.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                                    .border(1.dp, LabCyan.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("$partyBName (B)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LabCyan)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(finalResultStrB, fontSize = 20.sp, fontWeight = FontWeight.Black, color = LabDarkIndigo)
                            }
                        }

                        // Delta output
                        val valAVal = finalResultStrA.replace("%", "").trim().toDoubleOrNull()
                        val valBVal = finalResultStrB.replace("%", "").trim().toDoubleOrNull()
                        if (valAVal != null && valBVal != null) {
                            val diff = valBVal - valAVal
                            val diffPct = if (valAVal != 0.0) (diff / valAVal) * 100.0 else 0.0
                            val deltaColor = if (kotlin.math.abs(diff) < 1e-4) Color.Gray else (if (diff >= 0.0) LabSuccessGreen else LabErrorRed)
                            val deltaBg = deltaColor.copy(alpha = 0.05f)

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().background(deltaBg, RoundedCornerShape(10.dp)).border(0.5.dp, deltaColor.copy(alpha = 0.2f)).padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("فرق نسبة المواد الصلبة جافة:", fontSize = 10.sp, color = Color.Gray)
                                    Text(
                                        text = (if (diff > 0.0) "+" else "") + String.format(Locale.US, "%.2f %%", diff) + " (" + (if (diff > 0.0) "+" else "") + String.format(Locale.US, "%.1f%%", diffPct) + ")",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = deltaColor
                                    )
                                }
                                Box(
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(deltaColor).padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (diff > 0.0) "ارتفاع جاف ↑" else (if (diff < 0.0) "انخفاض جاف ↓" else "متطابق"),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // General documentation
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, LabBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("توثيق التقرير الفني المعتمد والملاحظات 📝", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                        OutlinedTextField(
                            value = executionDateState,
                            onValueChange = { executionDateState = it },
                            label = { Text("تاريخ الفحص الفعلي") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = testNotes,
                            onValueChange = { testNotes = it },
                            label = { Text("الملاحظات الفنية للمقارنة") },
                            placeholder = { Text("مثال: تسجل التركيبة (B) نسبة صلابة أعلى، مما يزيد سماكة جفاف الفيلم النهائي للطلاء.") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء ورجوع")
            }

            Button(
                onClick = {
                    val sDataA = SolidContentTestData(
                        useDirectInput = useDirectInputA,
                        directPct = directPctA,
                        dishWeight = dishWeightA,
                        wetWeight = wetWeightA,
                        dryWeight = dryWeightA
                    )
                    val sDataB = SolidContentTestData(
                        useDirectInput = useDirectInputB,
                        directPct = directPctB,
                        dishWeight = dishWeightB,
                        wetWeight = wetWeightB,
                        dryWeight = dryWeightB
                    )
                    val compData = ComparisonSolidContentTestData(dataA = sDataA, dataB = sDataB)
                    val serialized = serializeComparisonSolidContentData(compData)

                    val valA = if (finalResultStrA == "-") null else finalResultStrA
                    val valB = if (finalResultStrB == "-") null else finalResultStrB

                    val updatedTest = test.copy(
                        status = computedStatus,
                        executionDate = executionDateState,
                        notes = serialized + (if (testNotes.isNotBlank()) "\n$testNotes" else ""),
                        testValueA = valA,
                        testValueB = valB
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ والاعتماد ✅", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@Composable
fun DirectComparisonView(
    sessionIdA: String,
    sessionIdB: String,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val orders by viewModel.productionOrders.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())

    val sessionA = remember(sessions) { sessions.find { it.id == sessionIdA } }
    val sessionB = remember(sessions) { sessions.find { it.id == sessionIdB } }

    if (sessionA == null || sessionB == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("حدث خطأ في تحميل قراءات الجلسات للمقارنة ⚠️", color = Color.Gray, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("العودة للأرشيف", color = Color.White)
                }
            }
        }
        return
    }

    val getSampleDisplayTitle = { s: LabSession ->
        if (s.id.startsWith("ref_specs_") || s.category == "المواصفات المرجعية") {
            "المواصفات المرجعية"
        } else if (s.sampleProperties?.startsWith("ORDER_ID:") == true) {
            val oId = s.sampleProperties.substringAfter("ORDER_ID:").substringBefore(":QC")
            val matchedOrder = orders.find { it.id == oId }
            matchedOrder?.orderNumber ?: s.sampleOrProduct
        } else {
            s.sampleOrProduct
        }
    }

    val sampleTitleA = remember(sessionA, orders) { getSampleDisplayTitle(sessionA) }
    val sampleTitleB = remember(sessionB, orders) { getSampleDisplayTitle(sessionB) }

    val testsA = remember(allTests) { sortLabTests(allTests.filter { it.sessionId == sessionIdA }) }
    val testsB = remember(allTests) { sortLabTests(allTests.filter { it.sessionId == sessionIdB }) }

    val allUniqueTestKeys = remember(testsA, testsB) {
        (testsA.map { getComparisonKey(it) } + testsB.map { getComparisonKey(it) }).distinct()
    }

    var isAiLoading by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val aiPrompt = remember(sessionA, sessionB, sampleTitleA, sampleTitleB, testsA, testsB, allUniqueTestKeys) {
        val sb = StringBuilder()
        sb.append("يرجى مقارنة وتحليل العينتين التاليتين وتقديم مقارنة علمية ونصائح للتحسين ومميزات كل عينة وعيوبها:\n\n")
        sb.append("📦 تفاصيل عينة المقارنة الأولى (A):\n")
        sb.append("- الاسم: $sampleTitleA\n")
        sb.append("- رقم الجلسة: ${sessionA.sessionNumber}\n")
        sb.append("- تصنيف التطبيق: ${sessionA.category}\n")
        if (sessionA.notes.isNotBlank()) {
            sb.append("- ملاحظات وتوجيهات: ${sessionA.notes}\n")
        }
        sb.append("\n📦 تفاصيل عينة المقارنة الثانية (B):\n")
        sb.append("- الاسم: $sampleTitleB\n")
        sb.append("- رقم الجلسة: ${sessionB.sessionNumber}\n")
        sb.append("- تصنيف التطبيق: ${sessionB.category}\n")
        if (sessionB.notes.isNotBlank()) {
            sb.append("- ملاحظات وتوجيهات: ${sessionB.notes}\n")
        }
        
        sb.append("\n📊 الفحوصات والقياسات المقارنة والنتائج:\n")
        allUniqueTestKeys.forEachIndexed { i, key ->
            val tA = testsA.find { getComparisonKey(it) == key }
            val tB = testsB.find { getComparisonKey(it) == key }
            val tName = tA?.name ?: tB?.name ?: key
            
            val valA = if (tA != null) getTestShortResultValue(tA) ?: "بانتظار النتيجة ⏳" else "غير متوفر في هذه الجلسة"
            val valB = if (tB != null) getTestShortResultValue(tB) ?: "بانتظار النتيجة ⏳" else "غير متوفر في هذه الجلسة"
            
            sb.append("${i+1}. فحص ($tName):\n")
            sb.append("   - قياس الطرف (A): $valA\n")
            if (tA != null && tA.notes.isNotBlank() && !tA.notes.startsWith("WIZARD_VISCOSITY:")) {
                sb.append("     ملاحظات: ${tA.notes}\n")
            }
            sb.append("   - قياس الطرف (B): $valB\n")
            if (tB != null && tB.notes.isNotBlank() && !tB.notes.startsWith("WIZARD_VISCOSITY:")) {
                sb.append("     ملاحظات: ${tB.notes}\n")
            }
        }
        sb.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LabLightBg)
    ) {
        // High quality top action bar
        Surface(
            tonalElevation = 4.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = LabDarkIndigo
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "المقارنة المباشرة الفورية ⚖️",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    Text(
                        text = "مطابقة عينات الجودة للفحوصات الأحادية بالأرشيف",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sample Identification Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Sample A
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, LabPurple.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = LabPurple.copy(alpha = 0.02f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "العينة الأولى (A) 🧪",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabPurple
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = sampleTitleA,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = LabDarkIndigo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!sessionA.id.startsWith("ref_specs_") && sessionA.sessionNumber.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "رقم الجلسة: ${sessionA.sessionNumber}",
                                    fontSize = 9.5.sp,
                                    color = Color.Gray
                                )
                            }
                            if (!sessionA.id.startsWith("ref_specs_") && sessionA.technicianName.isNotBlank()) {
                                Text(
                                    text = "الفني: ${sessionA.technicianName}",
                                    fontSize = 9.5.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Sample B
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, LabCyan.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = LabCyan.copy(alpha = 0.02f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (sessionB.id.startsWith("ref_specs_") || sessionB.category == "المواصفات المرجعية") "المواصفات المرجعية 📋" else "العينة الثانية (B) 🔍",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabCyan
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = sampleTitleB,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = LabDarkIndigo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!sessionB.id.startsWith("ref_specs_") && sessionB.category != "المواصفات المرجعية" && sessionB.sessionNumber.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "رقم الجلسة: ${sessionB.sessionNumber}",
                                    fontSize = 9.5.sp,
                                    color = Color.Gray
                                )
                            }
                            if (!sessionB.id.startsWith("ref_specs_") && sessionB.category != "المواصفات المرجعية" && sessionB.technicianName.isNotBlank()) {
                                Text(
                                    text = "الفني: ${sessionB.technicianName}",
                                    fontSize = 9.5.sp,
                                    color = Color.Gray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Results matching header
            item {
                Text(
                    text = "📋 نتائج مطابقة الفحوصات الفردية المشتركة والمستقلة:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            if (allUniqueTestKeys.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .border(1.dp, LabBorder, RoundedCornerShape(12.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد أي فحوصات مدخلة في هاتين الجلستين لمطابقتها حالياً.",
                            fontSize = 11.5.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(allUniqueTestKeys) { testKey ->
                    val testA = testsA.find { getComparisonKey(it) == testKey }
                    val testB = testsB.find { getComparisonKey(it) == testKey }

                    val displayNames = when {
                        testA != null -> getTestDisplayNames(testA)
                        testB != null -> getTestDisplayNames(testB)
                        else -> {
                            val parts = testKey.split(" - ")
                            Pair(parts.firstOrNull() ?: testKey, "")
                        }
                    }

                    val settingsSummary = remember(testKey, testA, testB) {
                        val t = testA ?: testB
                        if (t != null) getTestSettingsSummary(t) else null
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LabBorder, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Test Title Row
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🧪", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = displayNames.first,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                    if (displayNames.second.isNotBlank()) {
                                        Text(
                                            text = displayNames.second,
                                            fontSize = 10.sp,
                                            color = Color.Gray,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    if (settingsSummary != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(LabBlueMain.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                                .border(0.5.dp, LabBlueMain.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = null,
                                                tint = LabBlueMain,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "خصائص الفحص: $settingsSummary",
                                                fontSize = 9.5.sp,
                                                color = LabBlueMain,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Results Side-by-Side Area
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Session A result box
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(LabPurple.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                        .border(1.dp, LabPurple.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                        Text("العينة الأولى (A)", fontSize = 9.sp, color = LabPurple, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        if (testA != null) {
                                            val valText = getTestShortResultValue(testA) ?: "بانتظار النتيجة ⏳"
                                            Text(
                                                text = valText,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (valText.contains("⏳")) Color.Gray else LabDarkIndigo,
                                                textAlign = TextAlign.Center
                                            )
                                        } else {
                                            Text(
                                                text = "الفحص غير موجود في الطرف الآخر",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                // Session B result box
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(LabCyan.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                                        .border(1.dp, LabCyan.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                        Text("العينة الثانية (B)", fontSize = 9.sp, color = LabCyan, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        if (testB != null) {
                                            val valText = getTestShortResultValue(testB) ?: "بانتظار النتيجة ⏳"
                                            Text(
                                                text = valText,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (valText.contains("⏳")) Color.Gray else LabDarkIndigo,
                                                textAlign = TextAlign.Center
                                            )
                                        } else {
                                            Text(
                                                text = "الفحص غير موجود في الطرف الآخر",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            // Quantitative comparison analysis (only if both exist of course)
                            if (testA != null && testB != null) {
                                val valTextA = getTestShortResultValue(testA)
                                val valTextB = getTestShortResultValue(testB)

                                if (valTextA != null && valTextB != null && !valTextA.contains("⏳") && !valTextB.contains("⏳")) {
                                    val numA = valTextA.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
                                    val numB = valTextB.replace(Regex("[^0-9.-]"), "").toDoubleOrNull()
                                    val unit = extractUnit(valTextA)

                                    if (numA != null && numB != null) {
                                        val diff = numB - numA
                                        val pct = if (numA != 0.0) (diff / numA) * 100.0 else 0.0
                                        val absPct = kotlin.math.abs(pct)
                                        val sign = if (diff > 0.0) "+" else ""
                                        val isPositive = diff >= 0.0

                                        val diffStr = String.format(Locale.US, "%s%.2f %s", sign, diff, unit).trim()
                                        val pctStr = String.format(Locale.US, "%s%.1f%%", sign, pct)

                                        val deltaColor = if (kotlin.math.abs(diff) < 1e-4) Color.Gray else if (isPositive) LabSuccessGreen else LabErrorRed
                                        val deltaBg = deltaColor.copy(alpha = 0.05f)

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(deltaBg, RoundedCornerShape(10.dp))
                                                .border(0.5.dp, deltaColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text("الفرق ومقدار التغير القياسي:", fontSize = 9.sp, color = Color.Gray)
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "الفرق: $diffStr ($pctStr)",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = deltaColor
                                                )
                                            }
                                            
                                            Box(
                                                modifier = Modifier
                                                    .background(deltaColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = if (kotlin.math.abs(diff) < 1e-4) "متطابق" else if (isPositive) "زيادة 📈" else "انخفاض 📉",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = deltaColor
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        // Custom detailed summary assessment text
                                        val evalText = remember(displayNames.first, numA, numB, unit) {
                                            generateComparativeAnalysis(displayNames.first, numA, numB, unit)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(LabDarkIndigo.copy(alpha = 0.02f), RoundedCornerShape(8.dp))
                                                .border(0.5.dp, LabDarkIndigo.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.BarChart,
                                                        contentDescription = null,
                                                        tint = LabPurple,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "التقييم ومطابقة الجودة الفنية:",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = LabDarkIndigo
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = evalText,
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF334155),
                                                    lineHeight = 15.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                // If some tests are completely missing in one of them
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .border(0.5.dp, Color.Gray.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "المقارنة المباشرة غير مكتملة لعدم تطابق خصائص الفحص (مثل Spindle أو السرعة) أو لعدم إجرائه في كلا الجلستين.",
                                        fontSize = 10.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // AI Expert Comparison Assistant Item
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .border(1.dp, LabPurple.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Advice",
                                tint = LabPurple,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "مساعد التطوير الذكي (R&D AI Expert) 🔮",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabDarkIndigo
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "قارن واعرف مميزات العينات ونقاط قوتها واحصل على نصائح لتحسين الصيغ الكيميائية والتركيبية مباشرة عبر الذكاء الاصطناعي.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            lineHeight = 16.sp
                        )
                        
                        Spacer(modifier = Modifier.height(14.dp))
                        
                        if (isAiLoading) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = LabPurple, strokeWidth = 3.dp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "جاري الاتصال بقاعدة المعرفة وتحليل الفروقات المخبرية بدقة... ⏳",
                                    fontSize = 11.sp,
                                    color = LabPurple,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            if (aiResult != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(LabPurple.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                                        .border(0.5.dp, LabPurple.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "⚖️ التقرير الاستشاري والمقارنة الفنية للعينتين:",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = LabPurple
                                            )
                                            IconButton(
                                                onClick = {
                                                    try {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                        val clip = android.content.ClipData.newPlainText("AI Comparison Report", aiResult)
                                                        clipboard.setPrimaryClip(clip)
                                                        Toast.makeText(context, "تم نسخ تقرير المقارنة الفنية بنجاح! 📋", Toast.LENGTH_SHORT).show()
                                                    } catch (e: Exception) {
                                                        Log.e("AI_COPY", "Copy failed", e)
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copy Report",
                                                    tint = LabPurple,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = aiResult ?: "",
                                            fontSize = 11.sp,
                                            color = Color(0xFF1E293B),
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            
                            if (aiError != null) {
                                Text(
                                    text = aiError ?: "",
                                    color = LabErrorRed,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }
                            
                            Button(
                                onClick = {
                                    isAiLoading = true
                                    aiError = null
                                    coroutineScope.launch {
                                        try {
                                            val result = GeminiClient.generateComparisonAdvice(aiPrompt)
                                            aiResult = result
                                        } catch (e: Exception) {
                                            aiError = "تعذر الحصول على الاستشارة الذكية: ${e.localizedMessage}"
                                        } finally {
                                            isAiLoading = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (aiResult != null) "إعادة توليد المقارنة الذكية ⚡" else "توليد استشارة ومقارنة الذكاء الاصطناعي ⚡",
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
}

@Composable
fun NetBinderInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً، فهمت ذلك 👍", fontWeight = FontWeight.Bold, color = LabBlueMain)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = LabBlueMain, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("دليل فحص نسبة المادة الرابطة ℹ️", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "فحص نسبة المادة الرابطة (Net Binder Content) يعتبر من الفحوصات الجوهرية للتحقق من كمية الراتنج (الراتنج العضوي/الأكريليك) الفعلي المسؤول عن قوة الالتصاق والربط، وتمييزه عن الحشو والمواد المالئة غير العضوية.",
                    fontSize = 11.5.sp,
                    color = LabDarkIndigo,
                    lineHeight = 18.sp
                )
                
                Spacer(modifier = Modifier.height(0.5.dp).fillMaxWidth().background(LabBorder))
                
                Text("🌿 طريقة أخذ العينة وتحضيرها:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                Text(
                    text = "يتم وزن طبق الفحص فارغاً، ثم توضع كمية دهان أو معجون رطب تتراوح بين 1-3 جرام، وتفرد بالتساوي لضمان كفاءة التجفيف والحرق.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    lineHeight = 16.sp
                )

                Text("⚖️ شرح الأوزان والقياسات المطلوبة:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• وزن الطبق فارغاً: وزن الوعاء المعدني أو السيراميكي فارغاً ونظيفاً تماماً.", fontSize = 10.5.sp, color = LabDarkIndigo)
                    Text("• وزن العينة قبل التجفيف: قيم الوزن الكلي للطبق مع العينة الرطبة المأخوذة فوراً.", fontSize = 10.5.sp, color = LabDarkIndigo)
                    Text("• وزن العينة بعد التجفيف: وزن الطبق والعينة بعد الجفاف في فرن التجفيف (105°م) لساعتين لتبخير الماء والمذيبات المتطايرة.", fontSize = 10.5.sp, color = LabDarkIndigo)
                    Text("• وزن العينة بعد الحرق: وزن الطبق بعد الحرق في فرن الحرق (تفوق 450°م)، حيث تتبخر المادة العضوية كاملة ويتبقى فقط الرماد المعدني غير القابل للاحتراق.", fontSize = 10.5.sp, color = LabDarkIndigo)
                }

                Text("🔢 طريقة الحساب الرياضي التلقائي:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LabLightBg, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "1. وزن العينة الرطبة الصافي = (الوزن قبل التجفيف) - (وزن الطبق)\n" +
                               "2. نسبة المواد الصلبة (Solids%) = (الوزن بعد التجفيف - وزن الطبق) / وزن العينة الأصلية × 100\n" +
                               "3. نسبة الرماد (Ash%) = (الوزن بعد الحرق - وزن الطبق) / وزن العينة الأصلية × 100\n" +
                               "4. نسبة المادة الرابطة الصافية (Net Binder%) = نسبة الصلابة - نسبة الرماد\n\n" +
                               "الصيغة المباشرة = (الوزن بعد التجفيف - الوزن بعد الحرق) / (الوزن قبل التجفيف - وزن الطبق) × 100",
                        fontSize = 10.5.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = LabDarkIndigo,
                        lineHeight = 16.sp
                    )
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetBinderWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val isComparison = parentSession.testType == "⚖️ فحص مقارنة"
    if (isComparison) {
        ComparisonNetBinderWorkspace(test, parentSession, viewModel, onBack)
    } else {
        SingleNetBinderWorkspace(test, parentSession, viewModel, onBack)
    }
}

@Composable
fun SingleNetBinderWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeNetBinderData(test.notes) ?: NetBinderTestData()
    }

    var useDirectInput by remember { mutableStateOf(initialData.useDirectInput) }
    var directPct by remember { mutableStateOf(initialData.directPct) }
    var dishWeight by remember { mutableStateOf(initialData.dishWeight) }
    var sampleWeightBefore by remember { mutableStateOf(initialData.sampleWeightBefore) }
    var sampleWeightAfter by remember { mutableStateOf(initialData.sampleWeightAfter) }
    var sampleWeightBurned by remember { mutableStateOf(initialData.sampleWeightBurned) }

    var testNotes by remember { mutableStateOf(test.notes.let {
        if (it.startsWith("WIZARD_NET_BINDER:")) "" else it
    }) }
    var executionDateState by remember { mutableStateOf(test.executionDate) }
    
    var showInfoDialog by remember { mutableStateOf(false) }

    // Live calculations
    val dWeight = dishWeight.toDoubleOrNull() ?: 0.0
    val wBefore = sampleWeightBefore.toDoubleOrNull() ?: 0.0
    val wAfter = sampleWeightAfter.toDoubleOrNull() ?: 0.0
    val wBurned = sampleWeightBurned.toDoubleOrNull() ?: 0.0

    val sampleOriginal = wBefore - dWeight
    val netDry = wAfter - dWeight
    val netBurned = wBurned - dWeight

    val solidsPct = if (sampleOriginal > 0.0 && netDry >= 0.0) (netDry / sampleOriginal) * 100.0 else null
    val ashPct = if (sampleOriginal > 0.0 && netBurned >= 0.0) (netBurned / sampleOriginal) * 100.0 else null
    val binderPct = if (solidsPct != null && ashPct != null) solidsPct - ashPct else null

    val finalResultStr = remember(useDirectInput, directPct, binderPct) {
        if (useDirectInput) {
            if (directPct.isNotBlank()) {
                val clean = directPct.replace("%", "").trim()
                "$clean%"
            } else {
                "-"
            }
        } else {
            if (binderPct != null) {
                String.format(Locale.US, "%.2f%%", binderPct)
            } else {
                "-"
            }
        }
    }

    val computedStatus = remember(useDirectInput, directPct, dishWeight, sampleWeightBefore, sampleWeightAfter, sampleWeightBurned) {
        if (useDirectInput) {
            if (directPct.isNotBlank()) "مكتمل" else "فارغ"
        } else {
            if (dishWeight.isBlank() && sampleWeightBefore.isBlank() && sampleWeightAfter.isBlank() && sampleWeightBurned.isBlank()) {
                "فارغ"
            } else if (dishWeight.isNotBlank() && sampleWeightBefore.isNotBlank() && sampleWeightAfter.isNotBlank() && sampleWeightBurned.isNotBlank()) {
                "مكتمل"
            } else {
                "غير مكتمل"
            }
        }
    }

    val finalStatusColor = when (computedStatus) {
        "مكتمل" -> LabSuccessGreen
        "غير مكتمل" -> LabWarningYellow
        else -> Color.Gray
    }

    if (showInfoDialog) {
        NetBinderInfoDialog(onDismiss = { showInfoDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Back toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "رجوع للجلسة",
                    tint = LabDarkIndigo
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فحص نسبة المادة الرابطة 🧪",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "جلسة: ${parentSession.sessionNumber} - ${parentSession.testName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "دليل الفحص والشرح",
                    tint = LabBlueMain,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            TestHistoryButton { showHistDialog = true }
        }

        // Prominent Result Header Card
        TestHeroHeader(
            title = "الفحص للمنتج الحالي:",
            subtitle = test.name,
            mainResult = finalResultStr,
            status = computedStatus,
            accentColor = LabBlueMain,
            statusColor = finalStatusColor
        )

        // Method Toggle Pills
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "أسلوب إدخال وتوثيق نتيجة الفحص:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val directBg = if (useDirectInput) LabBlueMain else Color.White
                    val directTint = if (useDirectInput) Color.White else LabDarkIndigo
                    val directBorder = if (useDirectInput) LabBlueMain else LabBorder

                    val weightBg = if (!useDirectInput) LabBlueMain else Color.White
                    val weightTint = if (!useDirectInput) Color.White else LabDarkIndigo
                    val weightBorder = if (!useDirectInput) LabBlueMain else LabBorder

                    Button(
                        onClick = { useDirectInput = true },
                        modifier = Modifier.weight(1f).border(1.dp, directBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = directBg, contentColor = directTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("إدخال مباشر ✏️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { useDirectInput = false },
                        modifier = Modifier.weight(1f).border(1.dp, weightBorder, RoundedCornerShape(10.dp)),
                        colors = ButtonDefaults.buttonColors(containerColor = weightBg, contentColor = weightTint),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("الأوزان والحرق ⚖️", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Action Inputs Pane
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (useDirectInput) {
                    Text(
                        text = "أدخل نسبة المادة الرابطة مباشرة ✏️",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    OutlinedTextField(
                        value = directPct,
                        onValueChange = { directPct = it },
                        label = { Text("نسبة المادة الرابطة (%)") },
                        placeholder = { Text("مثال: 12.5") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Text(
                        text = "سيتم حفظ وتوثيق النسبة المدخلة مباشرة كـ Net Binder المعتمد للعينة.",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                } else {
                    Text(
                        text = "إدخال قياسات الأوزان والتجفيف والحرق ⚖️",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    
                    // 1. Dish weight
                    OutlinedTextField(
                        value = dishWeight,
                        onValueChange = { dishWeight = it },
                        label = { Text("وزن طبق الفحص فارغاً (g)") },
                        placeholder = { Text("مثال: 5.20") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 2. Weight Before Drying
                    OutlinedTextField(
                        value = sampleWeightBefore,
                        onValueChange = { sampleWeightBefore = it },
                        label = { Text("وزن العينة الرطب مع الطبق (g)") },
                        placeholder = { Text("مثال: 15.30") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 3. Weight After Drying
                    OutlinedTextField(
                        value = sampleWeightAfter,
                        onValueChange = { sampleWeightAfter = it },
                        label = { Text("وزن العينة الجاف مع الطبق (g)") },
                        placeholder = { Text("مثال: 11.55") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // 4. Weight After Burning
                    OutlinedTextField(
                        value = sampleWeightBurned,
                        onValueChange = { sampleWeightBurned = it },
                        label = { Text("وزن العينة المحترق مع الطبق (g)") },
                        placeholder = { Text("مثال: 9.80") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )

                    // Display Calculations live
                    if (sampleOriginal > 0.0 && solidsPct != null && ashPct != null && binderPct != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = LabSuccessGreen.copy(alpha = 0.04f)),
                            border = BorderStroke(0.5.dp, LabSuccessGreen.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "🔢 النتائج والحسابات التفصيلية التلقائية:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabSuccessGreen
                                )
                                Text(
                                    text = "• صافي وزن العينة الأصلية: ${String.format(Locale.US, "%.3f", sampleOriginal)} g",
                                    fontSize = 10.5.sp,
                                    color = LabDarkIndigo
                                )
                                Text(
                                    text = "• نسبة المواد الصلبة (Solids %): ${String.format(Locale.US, "%.2f%%", solidsPct)}",
                                    fontSize = 10.5.sp,
                                    color = LabDarkIndigo
                                )
                                Text(
                                    text = "• نسبة الرماد بعد الحرق (Ash %): ${String.format(Locale.US, "%.2f%%", ashPct)}",
                                    fontSize = 10.5.sp,
                                    color = LabDarkIndigo
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Spacer(modifier = Modifier.height(0.5.dp).fillMaxWidth().background(LabSuccessGreen.copy(alpha = 0.15f)))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "• نسبة المادة الرابطة الصافية (Net Binder) = ${String.format(Locale.US, "%.2f%%", binderPct)}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabSuccessGreen
                                )
                            }
                        }
                    }
                }

                val currentVal = if (useDirectInput) directPct.toDoubleOrNull() else binderPct
                if (currentVal != null && currentVal > 0.0) {
                    UnifiedStatisticalComplianceCard(
                        currentValue = currentVal,
                        testName = test.name,
                        sampleOrProduct = parentSession.sampleOrProduct,
                        sessions = sessions,
                        allTests = allTests
                    )
                }
            }
        }

        // Execution Date & Custom analysis notes
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "توثيق وتفاصيل إضافية للتقرير 📝",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                OutlinedTextField(
                    value = executionDateState,
                    onValueChange = { executionDateState = it },
                    label = { Text("تاريخ التنفيذ الفعلي") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = testNotes,
                    onValueChange = { testNotes = it },
                    label = { Text("أي ملاحظات إضافية أو ظروف الفحص") },
                    placeholder = { Text("مثال: حرق في فرن Muffle Furnace على حرارة 480 درجة مئوية.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        // Action Toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء والرجوع")
            }

            Button(
                onClick = {
                    val sData = NetBinderTestData(
                        useDirectInput = useDirectInput,
                        directPct = directPct,
                        dishWeight = dishWeight,
                        sampleWeightBefore = sampleWeightBefore,
                        sampleWeightAfter = sampleWeightAfter,
                        sampleWeightBurned = sampleWeightBurned
                    )
                    val serialized = serializeNetBinderData(sData)
                    val finalVal = if (finalResultStr == "-") null else finalResultStr
                    val updatedTest = test.copy(
                        status = computedStatus,
                        executionDate = executionDateState,
                        notes = serialized + (if (testNotes.isNotBlank()) "\n$testNotes" else ""),
                        testValueA = finalVal
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حفظ التغييرات ✅", fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

@Composable
fun ComparisonNetBinderWorkspace(
    test: LabTest,
    parentSession: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val sessions by viewModel.labSessions.collectAsStateWithLifecycle()
    val allTests by viewModel.allLabTests.collectAsStateWithLifecycle(initialValue = emptyList())
    var showHistDialog by remember { mutableStateOf(false) }

    val initialData = remember(test.notes) {
        deserializeComparisonNetBinderData(test.notes) ?: ComparisonNetBinderTestData()
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = A, 1 = B, 2 = Summary
    
    // Tab states
    var useDirectInputA by remember { mutableStateOf(initialData.dataA.useDirectInput) }
    var directPctA by remember { mutableStateOf(initialData.dataA.directPct) }
    var dishWeightA by remember { mutableStateOf(initialData.dataA.dishWeight) }
    var sampleWeightBeforeA by remember { mutableStateOf(initialData.dataA.sampleWeightBefore) }
    var sampleWeightAfterA by remember { mutableStateOf(initialData.dataA.sampleWeightAfter) }
    var sampleWeightBurnedA by remember { mutableStateOf(initialData.dataA.sampleWeightBurned) }

    var useDirectInputB by remember { mutableStateOf(initialData.dataB.useDirectInput) }
    var directPctB by remember { mutableStateOf(initialData.dataB.directPct) }
    var dishWeightB by remember { mutableStateOf(initialData.dataB.dishWeight) }
    var sampleWeightBeforeB by remember { mutableStateOf(initialData.dataB.sampleWeightBefore) }
    var sampleWeightAfterB by remember { mutableStateOf(initialData.dataB.sampleWeightAfter) }
    var sampleWeightBurnedB by remember { mutableStateOf(initialData.dataB.sampleWeightBurned) }

    var testNotes by remember { mutableStateOf(test.notes.let {
        if (it.startsWith("WIZARD_COMP_NET_BINDER:")) "" else it
    }) }
    var executionDateState by remember { mutableStateOf(test.executionDate) }

    // Math for A
    val dWeightA = dishWeightA.toDoubleOrNull() ?: 0.0
    val wBeforeA = sampleWeightBeforeA.toDoubleOrNull() ?: 0.0
    val wAfterA = sampleWeightAfterA.toDoubleOrNull() ?: 0.0
    val wBurnedA = sampleWeightBurnedA.toDoubleOrNull() ?: 0.0
    val sampleOriginalA = wBeforeA - dWeightA
    val netDryA = wAfterA - dWeightA
    val netBurnedA = wBurnedA - dWeightA
    val solidsPctA = if (sampleOriginalA > 0.0 && netDryA >= 0.0) (netDryA / sampleOriginalA) * 100.0 else null
    val ashPctA = if (sampleOriginalA > 0.0 && netBurnedA >= 0.0) (netBurnedA / sampleOriginalA) * 100.0 else null
    val binderPctA = if (solidsPctA != null && ashPctA != null) solidsPctA - ashPctA else null

    // Math for B
    val dWeightB = dishWeightB.toDoubleOrNull() ?: 0.0
    val wBeforeB = sampleWeightBeforeB.toDoubleOrNull() ?: 0.0
    val wAfterB = sampleWeightAfterB.toDoubleOrNull() ?: 0.0
    val wBurnedB = sampleWeightBurnedB.toDoubleOrNull() ?: 0.0
    val sampleOriginalB = wBeforeB - dWeightB
    val netDryB = wAfterB - dWeightB
    val netBurnedB = wBurnedB - dWeightB
    val solidsPctB = if (sampleOriginalB > 0.0 && netDryB >= 0.0) (netDryB / sampleOriginalB) * 100.0 else null
    val ashPctB = if (sampleOriginalB > 0.0 && netBurnedB >= 0.0) (netBurnedB / sampleOriginalB) * 100.0 else null
    val binderPctB = if (solidsPctB != null && ashPctB != null) solidsPctB - ashPctB else null

    val valAStr = if (useDirectInputA) {
        if (directPctA.isNotBlank()) "${directPctA.trim()}%" else "-"
    } else {
        if (binderPctA != null) String.format(Locale.US, "%.2f%%", binderPctA) else "-"
    }

    val valBStr = if (useDirectInputB) {
        if (directPctB.isNotBlank()) "${directPctB.trim()}%" else "-"
    } else {
        if (binderPctB != null) String.format(Locale.US, "%.2f%%", binderPctB) else "-"
    }

    val partyAName = remember(parentSession.partyA) { getPartyName(parentSession.partyA) }
    val partyBName = remember(parentSession.partyB) { getPartyName(parentSession.partyB) }

    val computedStatus = remember(useDirectInputA, directPctA, dishWeightA, sampleWeightBeforeA, sampleWeightAfterA, sampleWeightBurnedA,
                                  useDirectInputB, directPctB, dishWeightB, sampleWeightBeforeB, sampleWeightAfterB, sampleWeightBurnedB) {
        val stA = if (useDirectInputA) (if (directPctA.isNotBlank()) "مكتمل" else "فارغ") else {
            if (dishWeightA.isNotBlank() && sampleWeightBeforeA.isNotBlank() && sampleWeightAfterA.isNotBlank() && sampleWeightBurnedA.isNotBlank()) "مكتمل" else "غير مكتمل"
        }
        val stB = if (useDirectInputB) (if (directPctB.isNotBlank()) "مكتمل" else "فارغ") else {
            if (dishWeightB.isNotBlank() && sampleWeightBeforeB.isNotBlank() && sampleWeightAfterB.isNotBlank() && sampleWeightBurnedB.isNotBlank()) "مكتمل" else "غير مكتمل"
        }
        if (stA == "مكتمل" && stB == "مكتمل") "مكتمل" else "غير مكتمل"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Back toolbar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع للجلسة", tint = LabDarkIndigo)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مقارنة فحص نسبة المادة الرابطة ⚖️",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LabDarkIndigo
                )
                Text(
                    text = "العينة أ: $partyAName | العينة ب: $partyBName",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            TestHistoryButton { showHistDialog = true }
        }

        // Subtabs
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = LabBlueMain,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("العينة أ ($partyAName)", modifier = Modifier.padding(vertical = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("العينة ب ($partyBName)", modifier = Modifier.padding(vertical = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                Text("جدول المقارنة 📊", modifier = Modifier.padding(vertical = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (selectedTab == 0) {
            // Render Sample A Form
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("مدخلات العينة أ ($partyAName) - المادة الرابطة", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { useDirectInputA = true },
                            colors = ButtonDefaults.buttonColors(containerColor = if (useDirectInputA) LabBlueMain else Color.LightGray.copy(alpha=0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إدخال مباشر", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { useDirectInputA = false },
                            colors = ButtonDefaults.buttonColors(containerColor = if (!useDirectInputA) LabBlueMain else Color.LightGray.copy(alpha=0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("أوزان وحرق", fontSize = 11.sp)
                        }
                    }

                    if (useDirectInputA) {
                        OutlinedTextField(
                            value = directPctA,
                            onValueChange = { directPctA = it },
                            label = { Text("نسبة المادة الرابطة مباشرة (%)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = dishWeightA,
                            onValueChange = { dishWeightA = it },
                            label = { Text("وزن طبق الفحص فارغاً (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightBeforeA,
                            onValueChange = { sampleWeightBeforeA = it },
                            label = { Text("وزن العينة الرطب مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightAfterA,
                            onValueChange = { sampleWeightAfterA = it },
                            label = { Text("وزن العينة الجاف مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightBurnedA,
                            onValueChange = { sampleWeightBurnedA = it },
                            label = { Text("وزن العينة المحترق مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        if (sampleOriginalA > 0.0 && binderPctA != null) {
                            Text("• النتيجة المحتسبة للعينة أ: ${String.format(Locale.US, "%.2f%%", binderPctA)}", fontWeight = FontWeight.Bold, color = LabSuccessGreen)
                        }
                    }
                }
            }
        } else if (selectedTab == 1) {
            // Render Sample B Form
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("مدخلات العينة ب ($partyBName) - المادة الرابطة", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabBlueMain)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { useDirectInputB = true },
                            colors = ButtonDefaults.buttonColors(containerColor = if (useDirectInputB) LabBlueMain else Color.LightGray.copy(alpha=0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("إدخال مباشر", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { useDirectInputB = false },
                            colors = ButtonDefaults.buttonColors(containerColor = if (!useDirectInputB) LabBlueMain else Color.LightGray.copy(alpha=0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("أوزان وحرق", fontSize = 11.sp)
                        }
                    }

                    if (useDirectInputB) {
                        OutlinedTextField(
                            value = directPctB,
                            onValueChange = { directPctB = it },
                            label = { Text("نسبة المادة الرابطة مباشرة (%)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = dishWeightB,
                            onValueChange = { dishWeightB = it },
                            label = { Text("وزن طبق الفحص فارغاً (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightBeforeB,
                            onValueChange = { sampleWeightBeforeB = it },
                            label = { Text("وزن العينة الرطب مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightAfterB,
                            onValueChange = { sampleWeightAfterB = it },
                            label = { Text("وزن العينة الجاف مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sampleWeightBurnedB,
                            onValueChange = { sampleWeightBurnedB = it },
                            label = { Text("وزن العينة المحترق مع الطبق (g)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        if (sampleOriginalB > 0.0 && binderPctB != null) {
                            Text("• النتيجة المحتسبة للعينة ب: ${String.format(Locale.US, "%.2f%%", binderPctB)}", fontWeight = FontWeight.Bold, color = LabSuccessGreen)
                        }
                    }
                }
            }
        } else {
            // Render Comparison Table
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("مخلص مقارنة النتائج النهائية للمادة الرابطة 📊", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    
                    DetailRow(label = "محتوى المادة الرابطة لمنشأ (أ) - $partyAName:", value = valAStr)
                    DetailRow(label = "محتوى المادة الرابطة لمنشأ (ب) - $partyBName:", value = valBStr)
                    
                    val parsedA = valAStr.replace("%", "").trim().toDoubleOrNull()
                    val parsedB = valBStr.replace("%", "").trim().toDoubleOrNull()
                    if (parsedA != null && parsedB != null) {
                        val diff = parsedB - parsedA
                        val sign = if (diff > 0.0) "+" else ""
                        DetailRow(label = "فارق التباين الفعلي بين العينتين:", value = String.format(Locale.US, "%s%.2f%%", sign, diff))
                    }
                }
            }
        }

        // Execution Date & Notes
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, LabBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = executionDateState,
                    onValueChange = { executionDateState = it },
                    label = { Text("تاريخ التنفيذ الفعلي") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = testNotes,
                    onValueChange = { testNotes = it },
                    label = { Text("الملاحظات الاستثنائية للتقرير") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("إلغاء والرجوع")
            }
            Button(
                onClick = {
                    val dataA = NetBinderTestData(useDirectInputA, directPctA, dishWeightA, sampleWeightBeforeA, sampleWeightAfterA, sampleWeightBurnedA)
                    val dataB = NetBinderTestData(useDirectInputB, directPctB, dishWeightB, sampleWeightBeforeB, sampleWeightAfterB, sampleWeightBurnedB)
                    val saved = ComparisonNetBinderTestData(dataA, dataB)
                    val serialized = serializeComparisonNetBinderData(saved)
                    val updatedTest = test.copy(
                        status = computedStatus,
                        executionDate = executionDateState,
                        notes = serialized + if (testNotes.isNotBlank()) "\n$testNotes" else "",
                        testValueA = if (valAStr == "-") null else valAStr,
                        testValueB = if (valBStr == "-") null else valBStr
                    )
                    viewModel.updateLabTest(updatedTest)
                    onBack()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                modifier = Modifier.weight(1f)
            ) {
                Text("حفظ ومقارنة القراءات ✅")
            }
         }
         Spacer(modifier = Modifier.height(100.dp))
    }

    if (showHistDialog) {
        HistoricalTestsDialog(
            testName = test.name,
            sampleOrProduct = parentSession.sampleOrProduct,
            sessions = sessions,
            allTests = allTests,
            viewModel = viewModel,
            onDismiss = { showHistDialog = false }
        )
    }
}

// Image Compressors
fun compressAndPrepareImage(context: Context, sourceUri: Uri): Uri? {
    return try {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(sourceUri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        
        if (originalBitmap == null) return null
        
        val cacheDir = context.cacheDir
        val tempFile = java.io.File(cacheDir, "compressed_lab_${System.currentTimeMillis()}.jpg")
        val outStream = java.io.FileOutputStream(tempFile)
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        outStream.flush()
        outStream.close()
        
        Uri.fromFile(tempFile)
    } catch (e: Exception) {
        Log.e("LabAttachments", "Error compressing image: ${e.message}", e)
        null
    }
}

fun saveCameraBitmapToCache(context: Context, bitmap: Bitmap): Uri? {
    return try {
        val cacheDir = context.cacheDir
        val tempFile = java.io.File(cacheDir, "camera_lab_${System.currentTimeMillis()}.jpg")
        val outStream = java.io.FileOutputStream(tempFile)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        outStream.flush()
        outStream.close()
        Uri.fromFile(tempFile)
    } catch (e: Exception) {
        Log.e("LabAttachments", "Error saving camera bitmap: ${e.message}", e)
        null
    }
}

@Composable
fun LabSessionAttachmentsSection(
    session: LabSession,
    viewModel: GbrViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val attachments by viewModel.allLabAttachments.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessionAttachments = remember(attachments, session.id) {
        attachments.filter { it.sessionId == session.id }.sortedBy { it.createdAt }
    }
    
    var pendingUriToUpload by remember { mutableStateOf<Uri?>(null) }
    var attachmentDescriptionInput by remember { mutableStateOf("") }
    var showDescriptionPromptDialog by remember { mutableStateOf(false) }
    var showSourcePickerBottomSheet by remember { mutableStateOf(false) }
    var imageToPreviewInDialog by remember { mutableStateOf<String?>(null) }
    var imageToPreviewTitle by remember { mutableStateOf("") }
    var downloadingAttachmentId by remember { mutableStateOf<String?>(null) }
    var isDownloadingInPreview by remember { mutableStateOf(false) }
    var attachmentToDeleteConf by remember { mutableStateOf<com.example.data.LabAttachment?>(null) }
    
    var cameraTempImageUri by remember { mutableStateOf<Uri?>(null) }
    
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val copiedUri = com.example.data.GbrFileManager.copyUriToCache(context, uri, "lab_attachment")
            if (copiedUri != null) {
                pendingUriToUpload = copiedUri
                attachmentDescriptionInput = ""
                showDescriptionPromptDialog = true
            } else {
                Toast.makeText(context, "فشل معالجة ونقل الصورة المحددة", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && cameraTempImageUri != null) {
            pendingUriToUpload = cameraTempImageUri
            attachmentDescriptionInput = ""
            showDescriptionPromptDialog = true
        } else {
            Toast.makeText(context, "فشل أو تم إلغاء التقاط الكاميرا الحية", Toast.LENGTH_SHORT).show()
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, LabBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row with + button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Attachment,
                        contentDescription = null,
                        tint = LabBlueMain,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "📎 الصور والملفات المرفقة (${sessionAttachments.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = LabDarkIndigo
                    )
                }
                
                Button(
                    onClick = { showSourcePickerBottomSheet = true },
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("مرفق جديد", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "يسمح هذا القسم بالاحتفاظ بالتوثيق البصري لجلسة الفحص الفني بشكل آمن ومحمي؛ لتكون الصور والمستندات مزامنة تلقائياً على خوادم الاستضافة والسحابة.",
                fontSize = 11.sp,
                color = Color.Gray,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            if (sessionAttachments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LabLightBg, shape = RoundedCornerShape(12.dp))
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.Gray.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "لا توجد أي صور أو مرفقات للجلسة حالياً.",
                            fontSize = 11.5.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sessionAttachments.forEach { attachment ->
                        val isLocal = attachment.filePathOrUrl.startsWith("content://") || attachment.filePathOrUrl.startsWith("file://")
                        val formattedDate = remember(attachment.createdAt) {
                            try {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(attachment.createdAt))
                            } catch (e: Exception) {
                                ""
                            }
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Image view/thumbnail
                                Card(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .clickable { imageToPreviewInDialog = attachment.filePathOrUrl },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        coil.compose.AsyncImage(
                                            model = attachment.filePathOrUrl,
                                            contentDescription = attachment.testName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                // Text metadata Column
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = attachment.testName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = LabDarkIndigo,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "تاريخ الإضافة: $formattedDate",
                                        fontSize = 10.5.sp,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isLocal) {
                                            Icon(
                                                imageVector = Icons.Default.HourglassEmpty,
                                                contentDescription = null,
                                                tint = LabWarningYellow,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("جاري المزامنة السحابية... ⏳", fontSize = 9.5.sp, color = LabWarningYellow, fontWeight = FontWeight.SemiBold)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.CloudQueue,
                                                contentDescription = null,
                                                tint = LabSuccessGreen,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("مرفق سحابي آمن ☁️", fontSize = 9.5.sp, color = LabSuccessGreen, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                // Actions
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    // View button
                                    IconButton(
                                        onClick = {
                                            imageToPreviewInDialog = attachment.filePathOrUrl
                                            imageToPreviewTitle = attachment.testName
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Visibility,
                                            contentDescription = "عرض",
                                            tint = LabBlueMain,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    
                                    // Download button
                                    val isDownloadingThis = downloadingAttachmentId == attachment.id
                                    IconButton(
                                        onClick = {
                                            if (!isDownloadingThis) {
                                                coroutineScope.launch {
                                                    downloadingAttachmentId = attachment.id
                                                    Toast.makeText(context, "جاري تنزيل وحفظ الصورة على الهاتف... ⏳", Toast.LENGTH_SHORT).show()
                                                    val result = com.example.data.GbrFileManager.saveFileToDeviceStorage(
                                                        context = context,
                                                        sourcePathOrUrl = attachment.filePathOrUrl,
                                                        suggestedTitle = "${session.testName}_${attachment.testName}"
                                                    )
                                                    downloadingAttachmentId = null
                                                    if (result.success) {
                                                        Toast.makeText(
                                                            context,
                                                            "✅ تم حفظ الصورة بنجاح على الهاتف في:\n${result.savedLocationDesc}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "❌ تعذر تنزيل الصورة: ${result.errorMessage}",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                        enabled = !isDownloadingThis
                                    ) {
                                        if (isDownloadingThis) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF16A34A)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "تحميل على الهاتف",
                                                tint = Color(0xFF16A34A),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // Delete button
                                    IconButton(
                                        onClick = { attachmentToDeleteConf = attachment },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "حذف",
                                            tint = LabErrorRed,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Choose Source dialog (Camera vs Gallery)
    if (showSourcePickerBottomSheet) {
        AlertDialog(
            onDismissRequest = { showSourcePickerBottomSheet = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "مصدر الملف المرفق 📎",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "يرجى اختيار طريقة إرفاق الصورة أو الملف التوثيقي الخاص بجلسة الفحص:",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSourcePickerBottomSheet = false
                                try {
                                    val cacheDir = com.example.data.GbrFileManager.getCacheDir(context)
                                    val tempFile = java.io.File(cacheDir, "gbr_camera_lab_${System.currentTimeMillis()}.jpg")
                                    val authority = "${context.packageName}.provider"
                                    val tempUri = androidx.core.content.FileProvider.getUriForFile(context, authority, tempFile)
                                    cameraTempImageUri = tempUri
                                    cameraLauncher.launch(tempUri)
                                } catch (e: Exception) {
                                    android.util.Log.e("LabAttachments", "Error launching high resolution camera: ${e.message}", e)
                                    Toast.makeText(context, "فشل تهيئة الكاميرا لحفظ الصورة بدقة عالية", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .background(LabLightBg, shape = RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text("التقاط صورة حية بالكاميرا 📸", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LabDarkIndigo)
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = null, tint = LabBlueMain)
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSourcePickerBottomSheet = false
                                selectImageLauncher.launch("image/*")
                            }
                            .background(LabLightBg, shape = RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text("اختيار صورة من معرض الصور 🖼️", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LabDarkIndigo)
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(imageVector = Icons.Default.Collections, contentDescription = null, tint = LabBlueMain)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSourcePickerBottomSheet = false
                                selectImageLauncher.launch("*/*")
                            }
                            .background(LabLightBg, shape = RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text("إرفاق ملف/مستند آخر من الجهاز 📄", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LabDarkIndigo)
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(imageVector = Icons.Default.InsertDriveFile, contentDescription = null, tint = LabBlueMain)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSourcePickerBottomSheet = false }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    
    // Description prompt Dialog
    if (showDescriptionPromptDialog) {
        val testsPreset = listOf(
            "فحص التغطية (Coverage)",
            "فحص العسيل والترهل (Sagging)",
            "فحص النعومة (Fineness)",
            "فحص الالتصاق (Adhesion)",
            "فحص اللمعة (Gloss)",
            "فحص تغير اللون (Color Change)",
            "توثيق بصري مخصص"
        )
        var activePreset by remember { mutableStateOf(testsPreset[0]) }
        var customDescriptionInput by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showDescriptionPromptDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(
                    text = "تصنيف المرفق والوصف المرتبط 🗒️",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = LabDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "اختر الفحص المرتبط بالصورة أو اكتب وصفاً مخصصاً:",
                        fontSize = 11.5.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Right
                    )
                    
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        testsPreset.forEach { preset ->
                            val isSelected = activePreset == preset
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activePreset = preset
                                        attachmentDescriptionInput = preset
                                    },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isSelected) LabBlueMain else LabBorder),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) LabBlueMain.copy(alpha = 0.08f) else Color.White
                                )
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Text(
                                        text = preset,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) LabBlueMain else LabDarkIndigo
                                    )
                                }
                            }
                        }
                    }
                    
                    OutlinedTextField(
                        value = customDescriptionInput,
                        onValueChange = {
                            customDescriptionInput = it
                            activePreset = ""
                            attachmentDescriptionInput = it
                        },
                        label = { Text("أو اكتب وصفًا مخصصًا هنا...") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Right),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalDesc = attachmentDescriptionInput.ifBlank {
                            customDescriptionInput.ifBlank { activePreset.ifBlank { "توثيق فحص بصري" } }
                        }
                        if (pendingUriToUpload != null) {
                            viewModel.addLabAttachment(
                                sessionId = session.id,
                                testName = finalDesc,
                                filePathOrUrl = pendingUriToUpload.toString()
                            )
                        }
                        showDescriptionPromptDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("إرفاق وحفظ 📎", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDescriptionPromptDialog = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }
    
    // Large preview Image dialog
    if (imageToPreviewInDialog != null) {
        Dialog(
            onDismissRequest = { imageToPreviewInDialog = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Image container that captures gestures
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                // Only pan if scaled in
                                if (scale > 1f) {
                                    offset = Offset(
                                        x = offset.x + pan.x,
                                        y = offset.y + pan.y
                                    )
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model = imageToPreviewInDialog,
                        contentDescription = "معاينة المرفق كاملة",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }

                // Top Bar with Download and Close Buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Download Button
                    Button(
                        onClick = {
                            if (!isDownloadingInPreview && imageToPreviewInDialog != null) {
                                coroutineScope.launch {
                                    isDownloadingInPreview = true
                                    Toast.makeText(context, "جاري تنزيل وحفظ الصورة على الهاتف... ⏳", Toast.LENGTH_SHORT).show()
                                    val title = imageToPreviewTitle.ifBlank { session.testName }
                                    val result = com.example.data.GbrFileManager.saveFileToDeviceStorage(
                                        context = context,
                                        sourcePathOrUrl = imageToPreviewInDialog!!,
                                        suggestedTitle = "${session.testName}_$title"
                                    )
                                    isDownloadingInPreview = false
                                    if (result.success) {
                                        Toast.makeText(
                                            context,
                                            "✅ تم حفظ الصورة بنجاح على الهاتف في:\n${result.savedLocationDesc}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "❌ تعذر تنزيل الصورة: ${result.errorMessage}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        enabled = !isDownloadingInPreview
                    ) {
                        if (isDownloadingInPreview) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جاري الحفظ...", fontSize = 12.sp, color = Color.White)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تحميل على الموبايل 📥", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = { imageToPreviewInDialog = null },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                // Helper tooltip at bottom
                if (scale > 1f) {
                    Text(
                        text = "اسحب بإصبعين للتصغير أو التحريك 🔍",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 40.dp)
                            .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
    
    // Delete Confirmation
    if (attachmentToDeleteConf != null) {
        val toDelete = attachmentToDeleteConf!!
        AlertDialog(
            onDismissRequest = { attachmentToDeleteConf = null },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White,
            title = {
                Text(s().labDeleteConfirmTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            text = {
                Text(s().labDeleteConfirmBody.replace("هذا المرفق", "هذا المرفق (${toDelete.testName})"), fontSize = 13.sp, color = Color.Gray, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLabAttachment(toDelete)
                        attachmentToDeleteConf = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(s().delete, color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { attachmentToDeleteConf = null }) {
                    Text(s().cancel, color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun LabFolderCard(
    folderName: String,
    sessionsInFolder: List<LabSession>,
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = BorderStroke(1.dp, LabPurple.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = LabPurple.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = LabPurple,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp)
                    )
                }

                Column {
                    Text(
                        text = folderName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${sessionsInFolder.size} جلسة فحص مختبري",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "خيارات", tint = Color.Gray)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("✏️ إعادة تسمية المجلد", fontSize = 12.sp) },
                            onClick = {
                                showMenu = false
                                onRenameClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🗑️ حذف المجلد", fontSize = 12.sp, color = LabErrorRed) },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LabPurple,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AssignToFolderDialog(
    session: LabSession,
    allFolders: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (folderName: String) -> Unit
) {
    var selectedFolder by remember { mutableStateOf(getLabSessionFolder(session)) }
    var isNewFolderInput by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁 نقل الجلسة إلى مجلد / شركة",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, thickness = 1.dp)

                Text(
                    text = "الجلسة: ${session.sessionNumber} - ${session.sampleOrProduct}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LabBlueMain
                )

                if (!isNewFolderInput) {
                    Text("اختر المجلد المطلوب أو أنشئ مجلداً جديداً:", fontSize = 12.sp, color = Color.Gray)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // "No Folder" Option
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedFolder.isBlank()) LabPurple.copy(alpha = 0.1f) else Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, if (selectedFolder.isBlank()) LabPurple else LabBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolder = "" }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(selected = selectedFolder.isBlank(), onClick = { selectedFolder = "" })
                                Text("📦 بدون مجلد (عام)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                            }
                        }

                        // Existing Folders
                        allFolders.forEach { folderName ->
                            val isSelected = selectedFolder == folderName
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) LabPurple.copy(alpha = 0.1f) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (isSelected) LabPurple else LabBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedFolder = folderName }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(selected = isSelected, onClick = { selectedFolder = folderName })
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = LabPurple, modifier = Modifier.size(16.dp))
                                    Text(folderName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                                }
                            }
                        }
                    }

                    TextButton(
                        onClick = { isNewFolderInput = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("+ إنشاء مجلد جديد باسم شركة جديدة", fontSize = 11.5.sp, color = LabPurple, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("اسم المجلد / الشركة الجديدة:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                        OutlinedTextField(
                            value = newFolderNameInput,
                            onValueChange = { newFolderNameInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true,
                            placeholder = { Text("مثال: شركة الجزيرة", fontSize = 12.sp) }
                        )
                        TextButton(onClick = { isNewFolderInput = false }) {
                            Text("← العودة للقائمة", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("إلغاء", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalFolder = if (isNewFolderInput) newFolderNameInput.trim() else selectedFolder
                            onConfirm(finalFolder)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("حفظ التغييرات", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CreateLabFolderDialog(
    allSessions: List<LabSession>,
    onDismiss: () -> Unit,
    onConfirm: (folderName: String, selectedSessionIds: List<String>) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    var selectedSessionIds by remember { mutableStateOf(setOf<String>()) }
    var searchQuery by remember { mutableStateOf("") }

    val unassignedSessions = remember(allSessions, searchQuery) {
        allSessions.filter { s ->
            getLabSessionFolder(s).isBlank() &&
            (searchQuery.isBlank() || s.sessionNumber.contains(searchQuery, ignoreCase = true) || s.sampleOrProduct.contains(searchQuery, ignoreCase = true))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📁 إنشاء مجلد شركة جديد",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = LabErrorRed)
                    }
                }

                Divider(color = LabBorder, thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("اسم المجلد أو الشركة:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo)
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        placeholder = { Text("مثال: شركة جوتن أو مواد خام المورد X", fontSize = 12.sp) }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("إضافة جلسات غير مصنفة إلى المجلد فوراً (اختياري):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Gray)

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        placeholder = { Text("بحث عن جلسة فحص...", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (unassignedSessions.isEmpty()) {
                            Text("لا توجد جلسات غير مصنفة مطابقة", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
                        } else {
                            unassignedSessions.forEach { s ->
                                val isChecked = selectedSessionIds.contains(s.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSessionIds = if (isChecked) selectedSessionIds - s.id else selectedSessionIds + s.id
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedSessionIds = if (checked == true) selectedSessionIds + s.id else selectedSessionIds - s.id
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("${s.sessionNumber} - ${s.sampleOrProduct}", fontSize = 11.5.sp, color = LabDarkIndigo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("إلغاء", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (folderName.isNotBlank()) {
                                onConfirm(folderName.trim(), selectedSessionIds.toList())
                            }
                        },
                        enabled = folderName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إنشاء المجلد 📁", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun RenameLabFolderDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: (newName: String) -> Unit
) {
    var newName by remember { mutableStateOf(folderName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text("✏️ إعادة تسمية المجلد", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الاسم الحالي: $folderName", fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    placeholder = { Text("الاسم الجديد للمجلد", fontSize = 12.sp) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank() && newName.trim() != folderName) {
                        onConfirm(newName.trim())
                    }
                },
                enabled = newName.isNotBlank() && newName.trim() != folderName,
                colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("حفظ التغيير", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.Gray)
            }
        }
    )
}

@Composable
fun DeleteLabFolderDialog(
    folderName: String,
    sessionCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteSessionsAlso: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text("🗑️ حذف المجلد '$folderName'", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = LabDarkIndigo, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Right)
        },
        text = {
            Text(
                text = "يحتوي هذا المجلد على $sessionCount جلسة فحص مختبري.\nاختر كيف ترغب في التعامل مع هذه الجلسات:",
                fontSize = 12.5.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Right
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onConfirm(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = LabPurple),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حفظ الجلسات وفك الربط فقط (إزالة من المجلد)", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { onConfirm(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("حذف المجلد وجميع الجلسات بداخله ($sessionCount جلسة)", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        }
    )
}
