package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.example.data.OperationalAlert
import com.example.ui.theme.*

@Composable
fun OperationalAlertsPanel(viewModel: GbrViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val controllerAlerts by viewModel.activeControllerAlerts.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        viewModel.refreshAllControllerAlerts()
    }

    val alerts by viewModel.allOperationalAlerts.collectAsState(initial = emptyList())
    val filteredAlerts = remember(alerts, searchQuery) {
        alerts.filter { alert ->
            alert.status == "ACTIVE" && (
                searchQuery.isBlank() ||
                alert.title.contains(searchQuery, ignoreCase = true) ||
                alert.description.contains(searchQuery, ignoreCase = true) ||
                alert.bindingElementName.contains(searchQuery, ignoreCase = true)
            )
        }
    }

    val activeOrders by viewModel.productionOrders.collectAsState(initial = emptyList())
    val filteredInProgressOrders = remember(activeOrders, searchQuery) {
        activeOrders.filter { order ->
            order.status == "قيد التنفيذ" && (
                searchQuery.isBlank() ||
                order.orderNumber.contains(searchQuery, ignoreCase = true) ||
                order.formulationName.contains(searchQuery, ignoreCase = true) ||
                order.batchNumber.contains(searchQuery, ignoreCase = true)
            )
        }
    }

    val activeTimers by viewModel.activeGrindingTimers.collectAsState(initial = emptyList())
    val filteredActiveTimers = remember(activeTimers, searchQuery) {
        activeTimers.filter { timer ->
            searchQuery.isBlank() ||
            timer.orderNumber.contains(searchQuery, ignoreCase = true) ||
            timer.phaseName.contains(searchQuery, ignoreCase = true) ||
            timer.materialName.contains(searchQuery, ignoreCase = true)
        }
    }

    val line1Status by viewModel.line1Status.collectAsState()
    val line2Status by viewModel.line2Status.collectAsState()
    val context = LocalContext.current
    val equipmentPrefs = remember { context.getSharedPreferences("gbr_equipment_prefs", android.content.Context.MODE_PRIVATE) }
    val line1Name = equipmentPrefs.getString("line_1_name", "خط الإنتاج رقم 1") ?: "خط الإنتاج رقم 1"
    val line2Name = equipmentPrefs.getString("line_2_name", "خط الإنتاج رقم 2") ?: "خط الإنتاج رقم 2"

    val activeFills = remember(line1Status, line2Status, line1Name, line2Name) {
        val list = mutableListOf<Triple<Int, String, LineStatus>>()
        if (line1Status?.fill_active == true) {
            list.add(Triple(0, line1Name, line1Status!!))
        }
        if (line2Status?.fill_active == true) {
            list.add(Triple(1, line2Name, line2Status!!))
        }
        list
    }

    val filteredActiveFills = remember(activeFills, searchQuery) {
        activeFills.filter { (_, name, _) ->
            searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true) || "ماء".contains(searchQuery) || "تعبئة".contains(searchQuery)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IndustrialGrayBg)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.showSegment(null) },
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward, // RTL back arrow
                            contentDescription = "رجوع",
                            tint = GBRBlueMain
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "🔔 مركز التنبيهات التشغيلية",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(t("ابحث في التنبيهات...", "Search alerts..."), fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GBRBlueMain,
                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f)
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Alerts List (Active Only)
            if (controllerAlerts.isEmpty() && filteredAlerts.isEmpty() && filteredInProgressOrders.isEmpty() && filteredActiveTimers.isEmpty() && filteredActiveFills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "لا توجد تنبيهات تشغيلية نشطة حالياً",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (controllerAlerts.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🚨 تنبيهات الأجهزة النشطة (${controllerAlerts.size})",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed
                                )
                                TextButton(
                                    onClick = { viewModel.acknowledgeAllControllerAlerts() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = GBRBlueMain)
                                ) {
                                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تأكيد استلام الكل", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        items(controllerAlerts) { alert ->
                            ControllerAlertCard(alert = alert, viewModel = viewModel)
                        }
                    }

                    if (filteredActiveFills.isNotEmpty()) {
                        items(filteredActiveFills) { (lineIndex, lineName, status) ->
                            ActiveEquipmentFillCard(
                                lineIndex = lineIndex,
                                lineName = lineName,
                                status = status,
                                viewModel = viewModel
                            )
                        }
                    }

                    if (filteredActiveTimers.isNotEmpty()) {
                        items(filteredActiveTimers) { timer ->
                            ActiveGrindingTimerCard(timer = timer, viewModel = viewModel)
                        }
                    }

                    if (filteredInProgressOrders.isNotEmpty()) {
                        items(filteredInProgressOrders) { order ->
                            ActiveProductionOrderCard(order = order, viewModel = viewModel)
                        }
                    }

                    if (filteredAlerts.isNotEmpty()) {
                        items(filteredAlerts) { alert ->
                            AlertCard(alert = alert, viewModel = viewModel)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Professional full-width bottom button with plus sign
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "إنشاء تنبيه تشغيلي جديد +",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
            }
        }
    }

    if (showAddDialog) {
        AddAlertDialog(
            alertToEdit = null,
            viewModel = viewModel,
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
fun AlertCard(alert: OperationalAlert, viewModel: GbrViewModel) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val (color, levelText) = when (alert.alertLevel) {
        "MANDATORY" -> ErrorRed to "🔴 تنبيه إلزامي"
        "WARNING" -> WarningOrange to "🟡 تنبيه"
        else -> SuccessGreen to "🟢 ملاحظة تشغيلية"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, CircleShape)
                    )
                    Text(
                        text = alert.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GBRDarkIndigo
                    )
                }

                Text(
                    text = levelText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            if (alert.description.isNotBlank()) {
                Text(
                    text = com.example.formatDecimalsInText(alert.description),
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Bindings Information & Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "القسم: ${alert.mainSection}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GBRBlueMain
                    )
                    if (alert.bindingScope != "ALL") {
                        Text(
                            text = " » ${alert.bindingElementName}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRPinkAccent
                        )
                    } else {
                        Text(
                            text = " » جميع العناصر",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Action buttons: Edit and Delete with Confirmation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Edit Button
                    TextButton(
                        onClick = { showEditDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = GBRBlueMain),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تعديل التنبيه",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تعديل", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Delete Button
                    TextButton(
                        onClick = { showDeleteConfirmDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف التنبيه",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(s().delete, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(ErrorRed.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = ErrorRed,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "تأكيد حذف التنبيه التشغيلي",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف هذا التنبيه التشغيلي نهائياً؟",
                        fontSize = 13.5.sp,
                        color = Color(0xFF475569),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = "« ${alert.title} »",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteOperationalAlert(alert)
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("نعم، تأكيد الحذف", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(s().cancel, color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Edit Alert Dialog
    if (showEditDialog) {
        AddAlertDialog(
            alertToEdit = alert,
            viewModel = viewModel,
            onDismiss = { showEditDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAlertDialog(
    alertToEdit: OperationalAlert? = null,
    viewModel: GbrViewModel,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(alertToEdit?.title ?: "") }
    var description by remember { mutableStateOf(alertToEdit?.description ?: "") }
    
    var mainSection by remember { mutableStateOf(alertToEdit?.mainSection ?: "عام") } // "التركيبات" | "أوامر الإنتاج" | "المختبر" | "عام"
    var bindingScope by remember { mutableStateOf(alertToEdit?.bindingScope ?: "ALL") }
    var bindingElementName by remember { mutableStateOf(alertToEdit?.bindingElementName ?: "") }
    var alertLevel by remember { mutableStateOf(alertToEdit?.alertLevel ?: "INFO") } // "INFO" | "WARNING" | "MANDATORY"

    // Retrieve references from ViewModel
    val formulations by viewModel.formulations.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()

    var showBindingDropdown by remember { mutableStateOf(false) }
    var isFirstRun by remember { mutableStateOf(true) }

    // Auto reset bindingScope when mainSection changes (except on initial load when editing)
    LaunchedEffect(mainSection) {
        if (isFirstRun) {
            isFirstRun = false
        } else {
            bindingScope = "ALL"
            bindingElementName = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (alertToEdit != null) Icons.Default.Edit else Icons.Default.Notifications,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (alertToEdit != null) "تعديل التنبيه التشغيلي" else "إنشاء تنبيه تشغيلي جديد",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFF1F5F9))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(t("عنوان التنبيه", "Alert Title"), fontWeight = FontWeight.Bold) },
                    placeholder = { Text(t("مثال: رفع اللزوجة في الدفعة القادمة", "e.g., Increase viscosity in next batch")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GBRBlueMain,
                        focusedLabelColor = GBRBlueMain,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(t("الشرح الكامل / الملاحظة", "Full Description / Remark"), fontWeight = FontWeight.Bold) },
                    placeholder = { Text(t("اكتب كامل التفاصيل والتعليمات هنا لضمان عمل الفنيين بها...", "Write full details and instructions here...")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GBRBlueMain,
                        focusedLabelColor = GBRBlueMain,
                        unfocusedBorderColor = Color(0xFFE2E8F0)
                    )
                )

                // Main Section Selection
                Text(s().altSectionRelated, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sections = listOf("التركيبات", "أوامر الإنتاج", "المختبر", "عام")
                    sections.forEach { sec ->
                        val isSelected = mainSection == sec
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) GBRBlueMain else Color(0xFFF8FAFC))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color.Transparent else Color(0xFFE2E8F0),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { mainSection = sec }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when(sec) {
                                    "التركيبات" -> t("التركيبات", "Recipes")
                                    "أوامر الإنتاج" -> t("أوامر الإنتاج", "Production")
                                    "المختبر" -> t("المختبر", "Lab")
                                    else -> t("عام", "General")
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF475569)
                            )
                        }
                    }
                }

                // Optional Specific Binding Selector
                if (mainSection != "عام") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(s().altCustomItem, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = { showBindingDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (bindingScope == "ALL") "جميع عناصر هذا القسم" else bindingElementName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (bindingScope == "ALL") Color.Gray else GBRPinkAccent
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = GBRBlueMain)
                            }
                        }

                        DropdownMenu(
                            expanded = showBindingDropdown,
                            onDismissRequest = { showBindingDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.95f)
                        ) {
                            DropdownMenuItem(
                                text = { Text(s().altAllElements, fontWeight = FontWeight.Bold, color = GBRBlueMain) },
                                onClick = {
                                    bindingScope = "ALL"
                                    bindingElementName = "جميع العناصر"
                                    showBindingDropdown = false
                                }
                            )
                            
                            when (mainSection) {
                                "التركيبات" -> {
                                    DropdownMenuItem(
                                        text = { Text(s().altSelectRecipe, fontWeight = FontWeight.Bold, color = Color.Gray) },
                                        onClick = {},
                                        enabled = false
                                    )
                                    formulations.forEach { form ->
                                        DropdownMenuItem(
                                            text = { Text("${form.name} (${form.code})") },
                                            onClick = {
                                                bindingScope = form.id
                                                bindingElementName = form.name
                                                showBindingDropdown = false
                                            }
                                        )
                                    }
                                }
                                "أوامر الإنتاج" -> {
                                    val approvedFormulations = formulations.filter { it.status == "🟢 معتمدة للإنتاج" }
                                    DropdownMenuItem(
                                        text = { Text(s().altRecipeApproved, fontWeight = FontWeight.Bold, color = Color.Gray) },
                                        onClick = {},
                                        enabled = false
                                    )
                                    if (approvedFormulations.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(s().altNoRecipe, color = ErrorRed) },
                                            onClick = {},
                                            enabled = false
                                        )
                                    } else {
                                        approvedFormulations.forEach { form ->
                                            DropdownMenuItem(
                                                text = { Text("${t("منتج:", "Product:")} ${form.name} (${form.code})") },
                                                onClick = {
                                                    bindingScope = "PRODUCT_${form.id}"
                                                    bindingElementName = "منتج: ${form.name}"
                                                    showBindingDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                                "المختبر" -> {
                                    DropdownMenuItem(
                                        text = { Text(s().altSelectSession, fontWeight = FontWeight.Bold, color = Color.Gray) },
                                        onClick = {},
                                        enabled = false
                                    )
                                    labSessions.forEach { session ->
                                        DropdownMenuItem(
                                            text = { Text("${t("جلسة:", "Session:")} ${session.sessionNumber} (${session.testName})") },
                                            onClick = {
                                                bindingScope = session.id
                                                bindingElementName = "جلسة ${session.sessionNumber}"
                                                showBindingDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Alert Levels
                Spacer(modifier = Modifier.height(4.dp))
                Text(s().altSeverityLevel, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val levels = listOf(
                        Triple("INFO", "🟢 ملاحظة", SuccessGreen),
                        Triple("WARNING", "🟡 تنبيه هام", WarningOrange),
                        Triple("MANDATORY", "🔴 إلزامي فوري", ErrorRed)
                    )
                    levels.forEach { (lvl, label, col) ->
                        val isSelected = alertLevel == lvl
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) col.copy(alpha = 0.12f) else Color.Transparent)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) col else Color(0xFFE2E8F0),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { alertLevel = lvl }
                            .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) col else Color(0xFF475569)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        if (alertToEdit != null) {
                            val updated = alertToEdit.copy(
                                title = title.trim(),
                                description = description.trim(),
                                mainSection = mainSection,
                                bindingScope = bindingScope,
                                bindingElementName = bindingElementName,
                                alertLevel = alertLevel
                            )
                            viewModel.updateOperationalAlert(updated)
                        } else {
                            val alert = OperationalAlert(
                                title = title.trim(),
                                description = description.trim(),
                                mainSection = mainSection,
                                bindingScope = bindingScope,
                                bindingElementName = bindingElementName,
                                alertLevel = alertLevel,
                                status = "ACTIVE"
                            )
                            viewModel.insertOperationalAlert(alert)
                        }
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                shape = RoundedCornerShape(10.dp),
                enabled = title.isNotBlank()
            ) {
                Text(
                    text = if (alertToEdit != null) "حفظ التعديلات" else s().altSaveAlert,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(s().cancel, color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun LinkedAlertsDisplay(
    mainSection: String,
    elementId: String?, // e.g., formulationId, orderId, sessionId, rawMaterialId
    viewModel: GbrViewModel,
    modifier: Modifier = Modifier
) {
    val alerts by viewModel.allOperationalAlerts.collectAsState(initial = emptyList())
    val productionOrders by viewModel.productionOrders.collectAsState(initial = emptyList())
    
    val filteredAlerts = remember(alerts, mainSection, elementId, productionOrders) {
        alerts.filter { alert ->
            if (alert.status != "ACTIVE" || alert.mainSection != mainSection) return@filter false
            
            // If the alert is set for all elements of this section
            if (alert.bindingScope == "ALL") return@filter true
            
            if (elementId == null) return@filter false
            
            // Direct ID matching
            if (alert.bindingScope == elementId) return@filter true
            
            // Custom matching for Production Orders: match if the alert is targeted to the product (formulation)
            if (mainSection == "أوامر الإنتاج") {
                val order = productionOrders.find { it.id == elementId }
                if (order != null && alert.bindingScope == "PRODUCT_${order.formulationId}") {
                    return@filter true
                }
            }
            
            false
        }
    }

    if (filteredAlerts.isNotEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filteredAlerts.forEach { alert ->
                val (color, label, icon) = when (alert.alertLevel) {
                    "MANDATORY" -> Triple(ErrorRed, "تنبيه إلزامي", Icons.Default.Warning)
                    "WARNING" -> Triple(WarningOrange, "تنبيه هام", Icons.Default.Info)
                    else -> Triple(SuccessGreen, "ملاحظة تشغيلية", Icons.Default.Info)
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = color.copy(alpha = 0.08f)
                    ),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = color,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "${alert.title} [$label]",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = color
                                )
                            }
                            
                            // "تم الاطلاع" button to delete/dismiss the alert instantly
                            Box(
                                modifier = Modifier
                                    .background(color, shape = RoundedCornerShape(12.dp))
                                    .clickable {
                                        viewModel.deleteOperationalAlert(alert)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "تم الاطلاع ✅",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        if (alert.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = com.example.formatDecimalsInText(alert.description),
                                fontSize = 12.sp,
                                color = GBRDarkIndigo.copy(alpha = 0.8f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveGrindingTimerCard(timer: GbrViewModel.ActiveGrindingTimer, viewModel: GbrViewModel) {
    // Live ticking inside the card too just in case
    var currentTickTime by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer.startTimeMs) {
        while (true) {
            currentTickTime = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
    }
    
    val remainingSec = remember(currentTickTime, timer.startTimeMs, timer.durationMinutes) {
        val totalSec = timer.durationMinutes * 60
        val elapsedSec = ((currentTickTime - timer.startTimeMs) / 1000).toInt().coerceAtLeast(0)
        (totalSec - elapsedSec).coerceAtLeast(0)
    }
    
    val mins = remainingSec / 60
    val secs = remainingSec % 60
    val formattedTime = String.format(java.util.Locale.US, "%02d:%02d", mins, secs)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.selectAndNavigateToProductionOrder(timer.orderId) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)), // Amber-100
        border = BorderStroke(1.5.dp, Color(0xFFFDE68A)), // Amber-200
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(WarningOrange, CircleShape)
                    )
                    Text(
                        text = "⏱️ مؤقت طحن نشط - ${timer.orderNumber}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GBRDarkIndigo
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "المرحلة: ${timer.phaseName}",
                    fontSize = 13.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = "المادة: ${timer.materialName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = GBRBlueMain
                )
            }
            
            Box(
                modifier = Modifier
                    .background(WarningOrange.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formattedTime,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = WarningOrange
                )
            }
        }
    }
}

@Composable
fun ActiveProductionOrderCard(order: com.example.data.ProductionOrder, viewModel: GbrViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.selectAndNavigateToProductionOrder(order.id) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), // Light blue
        border = BorderStroke(1.dp, GBRBlueMain.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "🏭 أمر إنتاج نشط: ${order.orderNumber}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GBRDarkIndigo
                    )
                }
                
                Text(
                    text = "قيد التنفيذ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRBlueMain,
                    modifier = Modifier
                        .background(GBRBlueMain.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "المنتج: ${order.formulationName}",
                fontSize = 13.sp,
                color = Color.DarkGray
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الدفعة: ${order.batchNumber} • الوزن: ${com.example.formatQuantity(order.requiredWeightKg)} كجم",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                
                Text(
                    text = "${order.progressPercent}% مكتمل",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SuccessGreen
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Progress Bar
            LinearProgressIndicator(
                progress = { order.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = SuccessGreen,
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun ActiveEquipmentFillCard(lineIndex: Int, lineName: String, status: LineStatus, viewModel: GbrViewModel) {
    val currentWeight = status.weight
    val targetWeight = status.fill_target
    val pct = if (targetWeight > 0) ((currentWeight / targetWeight) * 100.0).coerceIn(0.0, 100.0) else 0.0
    val remaining = if (targetWeight > 0) maxOf(0.0, targetWeight - currentWeight) else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.showSegment("equipment_control") },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)), // Light Sky Blue
        border = BorderStroke(1.5.dp, Color(0xFFBAE6FD)), // Sky Blue 200
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF0284C7).copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WaterDrop,
                            contentDescription = "صمام المياه",
                            tint = Color(0xFF0284C7),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "🚰 تعبئة مياه جارية ($lineName)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = GBRDarkIndigo
                        )
                        Text(
                            text = "صمام الماء مفتوح ويتم الضخ التلقائي الآن",
                            fontSize = 11.5.sp,
                            color = Color(0xFF0369A1)
                        )
                    }
                }

                Text(
                    text = "${String.format(java.util.Locale.US, "%.0f", pct)}%",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = Color(0xFF0284C7),
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFBAE6FD), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Linear Progress Indicator
            LinearProgressIndicator(
                progress = { (pct / 100.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = Color(0xFF0284C7),
                trackColor = Color(0xFFE0F2FE)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("الوزن الحالي", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${String.format(java.util.Locale.US, "%.1f", currentWeight)} كجم",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الهدف المستهدف", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${String.format(java.util.Locale.US, "%.1f", targetWeight)} كجم",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0284C7)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("المتبقي للوصول", fontSize = 11.sp, color = Color.Gray)
                    Text(
                        "${String.format(java.util.Locale.US, "%.1f", remaining)} كجم",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (remaining <= 5.0) SuccessGreen else Color(0xFFE11D48)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { viewModel.showSegment("equipment_control") },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "فتح شاشة التحكم بالمعدات ⚙️",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0284C7)
                    )
                }
            }
        }
    }
}

@Composable
fun ControllerAlertCard(alert: com.example.ui.ControllerHardwareAlert, viewModel: GbrViewModel) {
    val (color, emoji) = when (alert.type) {
        "NO_SENSOR_RESPONSE" -> WarningOrange to "⚠️"
        "WATER_SUPPLY_FAILURE" -> ErrorRed to "🚰❌"
        "SCALE_DISCONNECTED_WHILE_FILLING" -> ErrorRed to "⚖️❌"
        else -> ErrorRed to "🚨"
    }

    val typeText = when (alert.type) {
        "NO_SENSOR_RESPONSE" -> "عدم استجابة المستشعر"
        "WATER_SUPPLY_FAILURE" -> "انقطاع مصدر المياه أثناء التعبئة"
        "SCALE_DISCONNECTED_WHILE_FILLING" -> "انقطاع الميزان أثناء التعبئة"
        else -> alert.type
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.05f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = emoji, fontSize = 16.sp)
                    Text(
                        text = typeText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = GBRDarkIndigo
                    )
                }
                Text(
                    text = alert.lineName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRBlueMain,
                    modifier = Modifier
                        .background(GBRBlueMain.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = alert.message,
                fontSize = 13.sp,
                color = Color.DarkGray,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = alert.time,
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Button(
                    onClick = { viewModel.acknowledgeControllerAlert(alert) },
                    colors = ButtonDefaults.buttonColors(containerColor = color),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تأكيد الاطلاع", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
