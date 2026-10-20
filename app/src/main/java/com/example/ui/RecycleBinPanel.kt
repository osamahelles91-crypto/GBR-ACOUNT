package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.RecycleBinItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinPanel(viewModel: GbrViewModel) {
    val items by viewModel.recycleBinItems.collectAsState()
    val context = LocalContext.current
    
    // Filtering states
    var selectedFilter by remember { mutableStateOf("ALL") }
    
    // Action Confirmation States
    var itemToRestore by remember { mutableStateOf<RecycleBinItem?>(null) }
    var itemToDeletePermanently by remember { mutableStateOf<RecycleBinItem?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    BackHandler {
        if (viewModel.selectedSettingSection.value != null) {
            viewModel.selectedSettingSection.value = null
        } else {
            viewModel.showSegment(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "سلة المحذوفات",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "استعادة المواد والتركيبات وفحوصات الجودة المحذوفة مؤخراً",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.selectedSettingSection.value != null) {
                            viewModel.selectedSettingSection.value = null
                        } else {
                            viewModel.showSegment(null)
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        Button(
                            onClick = { showEmptyConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تفريغ السلة", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GBRBlueMain)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC))
                .padding(paddingValues)
        ) {
            // Notice Bar
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "يتم الاحتفاظ بالعناصر في سلة المحذوفات لمدة 30 يوماً قبل أن يتم حذفها تلقائياً وبشكل نهائي.",
                        fontSize = 12.sp,
                        color = Color(0xFF92400E),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf(
                    "ALL" to "الكل",
                    "RAW_MATERIAL" to "المواد الخام",
                    "FORMULATION" to "التركيبات",
                    "LAB_SESSION" to "جلسات الفحص",
                    "LAB_TEST" to "فحوصات الجودة",
                    "DEVELOPMENT_PROJECT" to "مشاريع R&D",
                    "DEVELOPMENT_SAMPLE" to "عينات R&D"
                )

                filters.forEach { (type, label) ->
                    val isSelected = selectedFilter == type
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) GBRBlueMain else Color.White,
                        border = BorderStroke(1.dp, if (isSelected) Color.Transparent else Color.LightGray),
                        modifier = Modifier
                            .clickable { selectedFilter = type }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else Color.DarkGray,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Filtered Items
            val filteredItems = remember(items, selectedFilter) {
                if (selectedFilter == "ALL") items else items.filter { it.itemType == selectedFilter }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFF1F5F9),
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "سلة المحذوفات فارغة",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "لا توجد أي عناصر محذوفة حالياً لمطابقة الفلتر المحدد.",
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        RecycleBinCard(
                            item = item,
                            onRestore = { itemToRestore = item },
                            onDeletePermanently = { itemToDeletePermanently = item }
                        )
                    }
                }
            }
        }
    }

    // --- DIALOGS ---

    // 1. Confirm Restore
    if (itemToRestore != null) {
        AlertDialog(
            onDismissRequest = { itemToRestore = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = SuccessGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تأكيد استعادة العنصر", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في استعادة \"${itemToRestore!!.displayName}\" وإرجاعه إلى مكانه الأصلي في قاعدة البيانات؟",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreRecycleBinItem(itemToRestore!!)
                        itemToRestore = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("استعادة الآن", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRestore = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    // 2. Confirm Permanent Delete
    if (itemToDeletePermanently != null) {
        AlertDialog(
            onDismissRequest = { itemToDeletePermanently = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("حذف نهائي لا يمكن تراجعه", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "هل أنت متأكد تماماً من رغبتك في حذف \"${itemToDeletePermanently!!.displayName}\" بشكل نهائي من سلة المحذوفات وقاعدة البيانات؟ هذا الإجراء لا يمكن التراجع عنه أبداً.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.permanentlyDeleteRecycleBinItem(itemToDeletePermanently!!)
                        itemToDeletePermanently = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حذف نهائي", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDeletePermanently = null }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    // 3. Confirm Clear Recycle Bin
    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFDC2626))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تفريغ سلة المحذوفات بالكامل", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "هل أنت متأكد من رغبتك في حذف جميع العناصر المحذوفة مؤقتاً بالكامل وبشكل نهائي؟ سيتم تنظيف قاعدة البيانات ولن تتمكن من استعادتها مرة أخرى.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearRecycleBin()
                        showEmptyConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("تفريغ كامل السلة", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun RecycleBinCard(
    item: RecycleBinItem,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    val dateString = remember(item.deletedAt) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(item.deletedAt))
        } catch (e: Exception) {
            "تاريخ غير معروف"
        }
    }

    val (typeLabel, icon, badgeColor) = remember(item.itemType) {
        when (item.itemType) {
            "RAW_MATERIAL" -> Triple("مادة خام", Icons.Default.Menu, Color(0xFF3B82F6))
            "FORMULATION" -> Triple("تركيبة دهان", Icons.Default.Info, Color(0xFFF59E0B))
            "LAB_SESSION" -> Triple("جلسة فحص", Icons.Default.Science, Color(0xFF8B5CF6))
            "LAB_TEST" -> Triple("فحص جودة", Icons.Default.CheckCircle, Color(0xFF10B981))
            "DEVELOPMENT_PROJECT" -> Triple("مشروع R&D", Icons.Default.Edit, Color(0xFFEC4899))
            "DEVELOPMENT_SAMPLE" -> Triple("عينة R&D", Icons.Default.List, Color(0xFF14B8A6))
            else -> Triple("عنصر عام", Icons.Default.Delete, Color(0xFF64748B))
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type Badge Icon
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                
                // Name and Type label
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = typeLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFF1F5F9))
            Spacer(modifier = Modifier.height(10.dp))

            // Footer / Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Deletion Date
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تم الحذف: $dateString",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Restore & Delete Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Permanently Delete Button
                    IconButton(
                        onClick = onDeletePermanently,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFEF2F2), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف نهائي",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Restore Button
                    Button(
                        onClick = onRestore,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFECFDF5)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "استعادة",
                            color = Color(0xFF047857),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
