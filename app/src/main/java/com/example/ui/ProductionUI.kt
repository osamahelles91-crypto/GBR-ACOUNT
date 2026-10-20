package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.nativeCanvas
import com.example.data.*
import com.example.ui.GbrViewModel
import com.example.ui.theme.*
import com.example.formatQuantity
import com.example.formatPercent
import com.example.formatCost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Helper functions for formatting
private fun formatNum(value: Double): String {
    return com.example.formatQuantity(value)
}

private fun formatNoDec(value: Double): String {
    return try {
        if (value.isNaN() || value.isInfinite()) "0"
        else java.math.BigDecimal(value.toString())
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .toPlainString()
    } catch (e: Exception) {
        String.format(Locale.US, "%.0f", value)
    }
}

private fun formatSupervisorQtyClean(value: Double): String {
    return com.example.formatQuantity(value)
}

fun Modifier.swipeableTabs(
    selectedTab: Int,
    totalTabs: Int,
    onTabSelected: (Int) -> Unit
): Modifier = this.pointerInput(selectedTab, totalTabs) {
    var totalDrag = 0f
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onDragEnd = {
            val threshold = 40.dp.toPx()
            if (totalDrag < -threshold) { // Dragged Left -> Next Tab
                if (selectedTab < totalTabs - 1) {
                    onTabSelected(selectedTab + 1)
                }
            } else if (totalDrag > threshold) { // Dragged Right -> Previous Tab
                if (selectedTab > 0) {
                    onTabSelected(selectedTab - 1)
                }
            }
        },
        onDragCancel = { totalDrag = 0f },
        onHorizontalDrag = { change, dragAmount ->
            totalDrag += dragAmount
            if (kotlin.math.abs(totalDrag) > 12.dp.toPx()) {
                change.consume()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionPanel(viewModel: GbrViewModel) {
    // Reset completed orders filter and search whenever navigating away from the Production section entirely
    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetCompletedOrdersFilters()
        }
    }

    val selectedOrder by viewModel.selectedProductionOrder.collectAsState()
    val ordersList by viewModel.productionOrders.collectAsState()
    val formulationsList by viewModel.formulations.collectAsState()
    val customPackagings by viewModel.customPackagings.collectAsState()

    val activeTab by viewModel.productionActiveTab.collectAsState()
    val isAddingNewOrder by viewModel.isAddingProductionOrder.collectAsState()

    BackHandler(enabled = true) {
        if (selectedOrder != null) {
            viewModel.closeSelectedProductionOrder()
        } else if (isAddingNewOrder) {
            viewModel.isAddingProductionOrder.value = false
            viewModel.preselectedFormulationForOrder.value = null
        } else {
            viewModel.showSegment(null)
        }
    }

    if (selectedOrder != null) {
        // DETAILED / RUN VIEW OF A PRODUCTION ORDER
        androidx.compose.runtime.key(selectedOrder!!.id) {
            OrderExecutionOrRecordView(
                order = selectedOrder!!,
                viewModel = viewModel,
                onClose = { viewModel.closeSelectedProductionOrder() }
            )
        }
    } else if (isAddingNewOrder) {
        // PANEL FOR CREATING A NEW ORDER
        CreateProductionOrderView(
            viewModel = viewModel,
            formulationsList = formulationsList,
            customPackagings = customPackagings,
            onClose = { 
                viewModel.isAddingProductionOrder.value = false
                viewModel.preselectedFormulationForOrder.value = null
            }
        )
    } else {
        // CENTRAL DASHBOARD WITH 4 PANELS
        val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(IndustrialGrayBg)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = s().productionTitle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo
                    )
                    Text(
                        text = s().productionSubtitle,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Button(
                    onClick = { viewModel.isAddingProductionOrder.value = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    modifier = Modifier.testTag("add_order_btn")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(s().createOrderButton, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (ordersList.isEmpty()) {
                com.example.GbrEmptyState(
                    title = "لا توجد أوامر إنتاج مسجلة حالياً 🏭",
                    description = "للبدء بالدورة الإنتاجية للمصنع الكيميائي، يمكنك جدولة أول دفعة تلوين، أو استرداد نسخة احتياطية محلية، أو مزامنة التطبيق.",
                    onAddNew = { viewModel.isAddingProductionOrder.value = true },
                    onRestoreBackup = { viewModel.activeSegment.value = "settings" },
                    onCloudSync = { viewModel.triggerManualSync("all") },
                    addNewText = "جدولة أمر إنتاج جديد ➕"
                )
            } else {
                val coroutineScope = rememberCoroutineScope()
                val pagerState = rememberPagerState(
                    initialPage = activeTab.coerceIn(0, 2),
                    pageCount = { 3 }
                )

                // Sync pager state with activeTab when changed externally
                LaunchedEffect(activeTab) {
                    if (pagerState.currentPage != activeTab) {
                        pagerState.animateScrollToPage(activeTab)
                    }
                }

                // Sync activeTab with pager state when swiped
                LaunchedEffect(pagerState.settledPage) {
                    if (viewModel.productionActiveTab.value != pagerState.settledPage) {
                        viewModel.productionActiveTab.value = pagerState.settledPage
                    }
                }

                // Tab Folder Selector
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = GBRBlueMain,
                    divider = { Divider(color = IndustrialBorder) }
                ) {
                    val readyCount = ordersList.count { it.status == "جاهز للتنفيذ" }
                    val inProgressCount = ordersList.count { it.status == "قيد التنفيذ" }
                    
                    val tabs = listOf(
                        s().productionTabReady to Icons.Default.CheckCircle,
                        s().productionTabInProgress to Icons.Default.PlayArrow,
                        s().productionTabCompleted to Icons.Default.Done
                    )
                    tabs.forEachIndexed { index, pair ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                viewModel.productionActiveTab.value = index
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            ) {
                                val count = when (index) {
                                    0 -> readyCount
                                    1 -> inProgressCount
                                    else -> 0
                                }
                                Box {
                                    Icon(
                                        imageVector = pair.second,
                                        contentDescription = null,
                                        tint = if (pagerState.currentPage == index) GBRBlueMain else Color.Gray,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    if (count > 0 && (index == 0 || index == 1)) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 10.dp, y = (-8).dp)
                                                .background(Color(0xFFEF4444), CircleShape)
                                                .sizeIn(minWidth = 18.dp, minHeight = 18.dp)
                                                .padding(horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = count.toString(),
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = pair.first,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (pagerState.currentPage == index) GBRBlueMain else Color.Gray,
                                    maxLines = 2,
                                    lineHeight = 17.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Render active folder content with native HorizontalPager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> ReadyOrdersTab(ordersList, viewModel)
                        1 -> InProgressOrdersTab(ordersList, viewModel)
                        2 -> CompletedOrdersTab(ordersList, viewModel, scrollState)
                        else -> ReadyOrdersTab(ordersList, viewModel)
                    }
                }
            }
        }
    }
}

// ==========================================
// OPTIMIZED HELPER FOR TEST COUNTS
// ==========================================
fun calculateTestsCountByOrderId(
    allTestRecords: List<com.example.data.ProductionOrderTestRecord>,
    labSessions: List<com.example.data.LabSession>,
    allLabTests: List<com.example.data.LabTest>
): Map<String, Int> {
    val map = mutableMapOf<String, Int>()
    allTestRecords.forEach { record ->
        if (!record.resultsJson.isNullOrBlank()) {
            val hasResults = try {
                org.json.JSONObject(record.resultsJson).length() > 0
            } catch (e: Exception) { false }
            if (hasResults) {
                map[record.productionOrderId] = (map[record.productionOrderId] ?: 0) + 1
            }
        }
    }
    val sessionIdToOrderId = mutableMapOf<String, String>()
    labSessions.forEach { session ->
        if (session.sampleProperties.startsWith("ORDER_ID:")) {
            val orderId = session.sampleProperties.substringAfter("ORDER_ID:")
            sessionIdToOrderId[session.id] = orderId
        }
    }
    if (sessionIdToOrderId.isNotEmpty()) {
        allLabTests.forEach { test ->
            val orderId = sessionIdToOrderId[test.sessionId]
            if (orderId != null) {
                map[orderId] = (map[orderId] ?: 0) + 1
            }
        }
    }
    return map
}

// ==========================================
// SUBVIEW: READY ORDERS LISTING
// ==========================================
@Composable
fun ReadyOrdersTab(orders: List<ProductionOrder>, viewModel: GbrViewModel) {
    val readyOrders = remember(orders) { orders.filter { it.status == "جاهز للتنفيذ" } }
    var orderToDelete by remember { mutableStateOf<ProductionOrder?>(null) }

    val allTestRecords by viewModel.allProductionOrderTestRecords.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()

    val testsCountByOrderId = remember(allTestRecords, labSessions, allLabTests) {
        calculateTestsCountByOrderId(allTestRecords, labSessions, allLabTests)
    }

    if (orderToDelete != null) {
        AlertDialog(
            onDismissRequest = { orderToDelete = null },
            title = { Text("تأكيد حذف أمر الإنتاج") },
            text = { Text("هل أنت متأكد من حذف أمر الإنتاج رقم ${orderToDelete?.orderNumber}؟ هذا الإجراء لا يمكن التراجع عنه.") },
            confirmButton = {
                Button(
                    onClick = {
                        orderToDelete?.let { viewModel.deleteProductionOrder(it.id) }
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { orderToDelete = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (readyOrders.isEmpty()) {
        EmptyBox(
            message = "لا توجد أوامر إنتاج مجدولة جاهزة حالياً.",
            subMessage = "قم بإنشاء أمر إنتاج جديد من زر الإضافة بالأعلى."
        )
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            readyOrders.forEach { order ->
                val testsCount = testsCountByOrderId[order.id] ?: 0

                OrderSummaryCard(order = order, viewModel = viewModel, onSelect = {
                    viewModel.selectedProductionOrder.value = order
                }, actionContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { orderToDelete = order },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "حذف أمر الإنتاج", tint = ErrorRed)
                        }

                        IconButton(
                            onClick = {
                                viewModel.updateProductionOrderStatus(
                                    orderId = order.id,
                                    newStatus = "ملغي",
                                    eventName = "إلغاء أمر الإنتاج",
                                    description = "تم إلغاء أمر الإنتاج المجدول بنجاح"
                                )
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "إلغاء أمر الإنتاج", tint = Color.Gray)
                        }

                        Button(
                            onClick = { viewModel.startProductionExecution(order.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "بدء التشغيل",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                })
            }
        }
    }
}

// ==========================================
// SUBVIEW: IN PROGRESS ORDERS LISTING
// ==========================================
@Composable
fun InProgressOrdersTab(orders: List<ProductionOrder>, viewModel: GbrViewModel) {
    val inProgress = remember(orders) { orders.filter { it.status == "قيد التنفيذ" } }
    var orderToDelete by remember { mutableStateOf<ProductionOrder?>(null) }

    val allTestRecords by viewModel.allProductionOrderTestRecords.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()

    val testsCountByOrderId = remember(allTestRecords, labSessions, allLabTests) {
        calculateTestsCountByOrderId(allTestRecords, labSessions, allLabTests)
    }

    if (orderToDelete != null) {
        AlertDialog(
            onDismissRequest = { orderToDelete = null },
            title = { Text("تأكيد حذف أمر الإنتاج") },
            text = { Text("هل أنت متأكد من حذف أمر الإنتاج رقم ${orderToDelete?.orderNumber}؟ هذا الإجراء لا يمكن التراجع عنه.") },
            confirmButton = {
                Button(
                    onClick = {
                        orderToDelete?.let { viewModel.deleteProductionOrder(it.id) }
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { orderToDelete = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (inProgress.isEmpty()) {
        EmptyBox(
            message = "لا توجد أي أوامر قيد التصنيع حالياً.",
            subMessage = "عند الضغط على 'بدء تشغيل الدفعة' ستظهر الأوامر هنا لمتابعة تقدمها."
        )
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            inProgress.forEach { order ->
                val testsCount = testsCountByOrderId[order.id] ?: 0

                val recipeItems by androidx.compose.runtime.produceState<List<com.example.data.ProductionOrderRecipeItem>>(initialValue = emptyList(), order.id) {
                    viewModel.repository.getProductionOrderRecipeItemsForOrder(order.id).collect { value = it }
                }

                val isPackagingStarted = remember(order.timerStartTimesJson) {
                    try {
                        val jsonObj = org.json.JSONObject(order.timerStartTimesJson)
                        jsonObj.has("packaging_start")
                    } catch (e: Exception) {
                        false
                    }
                }

                val computedProgressPercent = remember(recipeItems, order.completedItemsJson, order.progressPercent, isPackagingStarted) {
                    if (isPackagingStarted) {
                        100
                    } else if (recipeItems.isEmpty()) {
                        order.progressPercent
                    } else {
                        val completedSet = try {
                            val arr = org.json.JSONArray(order.completedItemsJson)
                            val s = mutableSetOf<String>()
                            for (i in 0 until arr.length()) {
                                s.add(arr.getString(i))
                            }
                            s
                        } catch (e: Exception) {
                            emptySet<String>()
                        }
                        
                        val totalSteps = recipeItems.size
                        val completedCount = recipeItems.count { ri ->
                            completedSet.any { cKey ->
                                cKey.contains("_ri${ri.id}") || cKey.contains("_it${ri.rawMaterialId}")
                            }
                        }
                        val calcPercent = if (totalSteps > 0) (completedCount * 100) / totalSteps else 0
                        if (calcPercent == 0 && order.progressPercent > 0) order.progressPercent else calcPercent
                    }
                }

                OrderSummaryCard(order = order, viewModel = viewModel, onSelect = {
                    viewModel.selectedProductionOrder.value = order
                }, actionContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val isFillingOrPackaging = isPackagingStarted || computedProgressPercent == 100
                        val statusLabelText = if (isFillingOrPackaging) "📦 قيد التعبئة والتغليف (المواد مكتملة)" else "تقدم التنفيذ: $computedProgressPercent%"
                        val statusLabelColor = if (isFillingOrPackaging) Color(0xFF0D9488) else WarningOrange
                        val progressTrackColor = if (isFillingOrPackaging) Color(0xFFCCFBF1) else Color(0xFFFEF3C7)

                        Text(
                            text = statusLabelText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusLabelColor
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { orderToDelete = order },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "حذف أمر الإنتاج", tint = ErrorRed, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    viewModel.updateProductionOrderStatus(
                                        orderId = order.id,
                                        newStatus = "ملغي",
                                        eventName = "إلغاء أمر الإنتاج",
                                        description = "تم إلغاء أمر الإنتاج أثناء تقدم التنفيذ بقرار من المشرف"
                                    )
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "إلغاء أمر الإنتاج", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                            LinearProgressIndicator(
                                progress = computedProgressPercent / 100f,
                                color = statusLabelColor,
                                trackColor = progressTrackColor,
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                })
            }
        }
    }
}

// ==========================================
// SUBVIEW: COMPLETED ORDERS LISTING
// ==========================================
@Composable
fun CompletedOrdersTab(
    orders: List<ProductionOrder>,
    viewModel: GbrViewModel,
    scrollState: ScrollState? = null
) {
    val completed = remember(orders) {
        orders.filter {
            (it.status == "مكتمل" || it.status.contains("مكتمل") || it.status.contains("مؤرشف") || it.status == "📦 مؤرشف") && it.status != "قيد التنفيذ" && it.status != "جاهز للتنفيذ"
        }
    }

    val searchQuery by viewModel.completedOrdersSearchQuery.collectAsState()
    val selectedProductFilter by viewModel.completedOrdersSelectedProductFilter.collectAsState()
    val fromDateMillis by viewModel.completedOrdersFromDateMillis.collectAsState()
    val toDateMillis by viewModel.completedOrdersToDateMillis.collectAsState()
    val qcFilter by viewModel.completedOrdersQcFilter.collectAsState()
    val filtersExpanded by viewModel.completedOrdersFiltersExpanded.collectAsState()

    val allTestRecords by viewModel.allProductionOrderTestRecords.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()
    val formulations by viewModel.formulations.collectAsState()

    val testsCountByOrderId = remember(allTestRecords, labSessions, allLabTests) {
        calculateTestsCountByOrderId(allTestRecords, labSessions, allLabTests)
    }

    val getEffectiveFormulationName: (ProductionOrder) -> String = remember(formulations) {
        { order ->
            val matched = formulations.find { it.id == order.formulationId }
            (matched?.name?.trim() ?: order.formulationName.trim()).ifBlank { "بدون اسم" }
        }
    }

    val uniqueProducts = remember(completed, formulations) {
        val approvedFormulations = formulations.filter {
            it.status == "🟢 معتمدة للإنتاج" || it.status.contains("معتمد")
        }
        val approvedIds = approvedFormulations.map { it.id }.toSet()
        val approvedNames = approvedFormulations.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()

        val fromOrders = completed.filter { order ->
            order.formulationId in approvedIds || getEffectiveFormulationName(order) in approvedNames
        }.map { getEffectiveFormulationName(it) }.filter { it.isNotBlank() }

        (approvedNames + fromOrders).distinct().sorted()
    }

    val context = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    val filteredOrders = remember(completed, searchQuery, selectedProductFilter, fromDateMillis, toDateMillis, qcFilter, formulations, allTestRecords, labSessions) {
        completed.filter { order ->
            val effName = getEffectiveFormulationName(order)
            val matchQuery = searchQuery.isBlank() || 
                order.orderNumber.contains(searchQuery, ignoreCase = true) ||
                order.batchNumber.contains(searchQuery, ignoreCase = true) ||
                effName.contains(searchQuery, ignoreCase = true) ||
                order.formulationName.contains(searchQuery, ignoreCase = true)

            val matchProduct = selectedProductFilter == null || effName == selectedProductFilter || order.formulationName == selectedProductFilter

            val matchFromDate = fromDateMillis == null || order.createdAt >= fromDateMillis!!
            val matchToDate = toDateMillis == null || order.createdAt <= (toDateMillis!! + 86400000L - 1)

            val matchQc = when (qcFilter) {
                "PRODUCTION_QC" -> {
                    val hasSession = labSessions.any { session ->
                        (session.sampleProperties == "ORDER_ID:${order.id}" || 
                         (session.sampleProperties.startsWith("ORDER_ID:${order.id}") && !session.sampleProperties.contains(":QC"))) ||
                        (session.sampleProperties.contains(order.orderNumber) && !session.sampleProperties.contains(":QC") && !session.category.contains("مراقبة"))
                    }
                    val hasRecord = allTestRecords.any { it.productionOrderId == order.id && it.isDirectTest }
                    hasSession || hasRecord
                }
                "MONITORING_QC" -> {
                    val hasSession = labSessions.any { session ->
                        session.sampleProperties.startsWith("ORDER_ID:${order.id}:QC") ||
                        session.sampleProperties.contains("ORDER_ID:${order.id}:QC") ||
                        (session.sampleProperties.contains(order.id) && (session.category.contains("مراقبة") || session.category == "مراقبة جودة" || session.notes.contains("مراقبة") || session.testName.contains("مراقبة")))
                    }
                    val hasRecord = allTestRecords.any { it.productionOrderId == order.id && !it.isDirectTest }
                    hasSession || hasRecord
                }
                else -> true
            }

            matchQuery && matchProduct && matchFromDate && matchToDate && matchQc
        }
    }

    val activeFiltersCount = remember(searchQuery, selectedProductFilter, fromDateMillis, toDateMillis, qcFilter) {
        var count = 0
        if (searchQuery.isNotBlank()) count++
        if (selectedProductFilter != null) count++
        if (fromDateMillis != null) count++
        if (toDateMillis != null) count++
        if (qcFilter != null) count++
        count
    }

    var visibleCount by remember(searchQuery, selectedProductFilter, fromDateMillis, toDateMillis) {
        mutableIntStateOf(10)
    }

    val displayedOrders = remember(filteredOrders, visibleCount) {
        filteredOrders.take(visibleCount)
    }

    var showComparisonModal by remember { mutableStateOf(false) }
    var showRecipeAnalyticsModal by remember { mutableStateOf(false) }
    var comparisonProductFilter by remember { mutableStateOf<String?>(null) }
    var selectedOrderIdsForComp by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.completedOrdersFiltersExpanded.value = !filtersExpanded },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (filtersExpanded || activeFiltersCount > 0) GBRBlueMain else IndustrialBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (filtersExpanded) GBRBlueMain.copy(alpha = 0.05f) else Color.White,
                        contentColor = GBRBlueMain
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("toggle_filters_button")
                ) {
                    Icon(
                        imageVector = if (filtersExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.Search,
                        contentDescription = "البحث والتصفية",
                        modifier = Modifier.size(16.dp),
                        tint = GBRBlueMain
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "بحث وتصفية",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (activeFiltersCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(GBRPinkAccent)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "نشط: $activeFiltersCount",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (activeFiltersCount > 0) {
                    IconButton(
                        onClick = {
                            viewModel.completedOrdersSearchQuery.value = ""
                            viewModel.completedOrdersSelectedProductFilter.value = null
                            viewModel.completedOrdersFromDateMillis.value = null
                            viewModel.completedOrdersToDateMillis.value = null
                            viewModel.completedOrdersQcFilter.value = null
                        },
                        modifier = Modifier.size(32.dp).testTag("clear_filters_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "إعادة ضبط",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                OutlinedButton(
                    onClick = { showComparisonModal = true },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, GBRBlueMain),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = GBRBlueMain.copy(alpha = 0.06f),
                        contentColor = GBRBlueMain
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("compare_batches_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "مقارنة الدفعات",
                        modifier = Modifier.size(16.dp),
                        tint = GBRBlueMain
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "مقارنة الدفعات",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = filtersExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.completedOrdersSearchQuery.value = it },
                        placeholder = { Text("بحث برقم الأمر، الوجبة، المنتج...", fontSize = 11.5.sp, maxLines = 1) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.completedOrdersSearchQuery.value = "" }) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        maxLines = 1,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GBRBlueMain,
                            unfocusedBorderColor = IndustrialBorder
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var prodExpanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1.5f)) {
                            OutlinedButton(
                                onClick = { prodExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                border = BorderStroke(1.dp, if (selectedProductFilter != null) GBRBlueMain else IndustrialBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (selectedProductFilter != null) GBRBlueMain else Color.DarkGray
                                )
                            ) {
                                Text(
                                    text = selectedProductFilter ?: "تصفية بالمنتج: الكل 🧪",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(
                                expanded = prodExpanded,
                                onDismissRequest = { prodExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.6f)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("عرض جميع المنتجات", fontSize = 11.sp) },
                                    onClick = {
                                        viewModel.completedOrdersSelectedProductFilter.value = null
                                        prodExpanded = false
                                    }
                                )
                                uniqueProducts.forEach { prod ->
                                    DropdownMenuItem(
                                        text = { Text(prod, fontSize = 11.sp) },
                                        onClick = {
                                            viewModel.completedOrdersSelectedProductFilter.value = prod
                                            prodExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (selectedProductFilter != null || fromDateMillis != null || toDateMillis != null || qcFilter != null || searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    viewModel.completedOrdersSelectedProductFilter.value = null
                                    viewModel.completedOrdersFromDateMillis.value = null
                                    viewModel.completedOrdersToDateMillis.value = null
                                    viewModel.completedOrdersQcFilter.value = null
                                    viewModel.completedOrdersSearchQuery.value = ""
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp))
                            ) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "تصفير الفلاتر", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val calendar = java.util.Calendar.getInstance()
                                if (fromDateMillis != null) calendar.timeInMillis = fromDateMillis!!
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val cal = java.util.Calendar.getInstance()
                                        cal.set(java.util.Calendar.YEAR, year)
                                        cal.set(java.util.Calendar.MONTH, month)
                                        cal.set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                                        cal.set(java.util.Calendar.MINUTE, 0)
                                        cal.set(java.util.Calendar.SECOND, 0)
                                        cal.set(java.util.Calendar.MILLISECOND, 0)
                                        viewModel.completedOrdersFromDateMillis.value = cal.timeInMillis
                                    },
                                    calendar.get(java.util.Calendar.YEAR),
                                    calendar.get(java.util.Calendar.MONTH),
                                    calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                ).apply {
                                    if (toDateMillis != null) datePicker.maxDate = toDateMillis!!
                                }.show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            border = BorderStroke(1.dp, if (fromDateMillis != null) GBRBlueMain else IndustrialBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (fromDateMillis != null) GBRBlueMain else Color.Gray
                            )
                        ) {
                            val text = if (fromDateMillis != null) "من: " + dateFormatter.format(Date(fromDateMillis!!)) else "من تاريخ 📅"
                            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        OutlinedButton(
                            onClick = {
                                val calendar = java.util.Calendar.getInstance()
                                if (toDateMillis != null) calendar.timeInMillis = toDateMillis!!
                                android.app.DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val cal = java.util.Calendar.getInstance()
                                        cal.set(java.util.Calendar.YEAR, year)
                                        cal.set(java.util.Calendar.MONTH, month)
                                        cal.set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                                        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                                        cal.set(java.util.Calendar.MINUTE, 59)
                                        cal.set(java.util.Calendar.SECOND, 59)
                                        cal.set(java.util.Calendar.MILLISECOND, 999)
                                        viewModel.completedOrdersToDateMillis.value = cal.timeInMillis
                                    },
                                    calendar.get(java.util.Calendar.YEAR),
                                    calendar.get(java.util.Calendar.MONTH),
                                    calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                ).apply {
                                    if (fromDateMillis != null) datePicker.minDate = fromDateMillis!!
                                }.show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                            border = BorderStroke(1.dp, if (toDateMillis != null) GBRBlueMain else IndustrialBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (toDateMillis != null) GBRBlueMain else Color.Gray
                            )
                        ) {
                            val text = if (toDateMillis != null) "إلى: " + dateFormatter.format(Date(toDateMillis!!)) else "إلى تاريخ 📅"
                            Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Row for Quality Control Filters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.completedOrdersQcFilter.value = if (qcFilter == "PRODUCTION_QC") null else "PRODUCTION_QC"
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, if (qcFilter == "PRODUCTION_QC") GBRBlueMain else IndustrialBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (qcFilter == "PRODUCTION_QC") GBRBlueMain.copy(alpha = 0.08f) else Color.White,
                                contentColor = if (qcFilter == "PRODUCTION_QC") GBRBlueMain else Color.DarkGray
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🛡️ جودة الإنتاج",
                                    fontSize = 11.sp,
                                    fontWeight = if (qcFilter == "PRODUCTION_QC") FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (qcFilter == "PRODUCTION_QC") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = GBRBlueMain
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                viewModel.completedOrdersQcFilter.value = if (qcFilter == "MONITORING_QC") null else "MONITORING_QC"
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, if (qcFilter == "MONITORING_QC") Color(0xFF8B5CF6) else IndustrialBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (qcFilter == "MONITORING_QC") Color(0xFF8B5CF6).copy(alpha = 0.08f) else Color.White,
                                contentColor = if (qcFilter == "MONITORING_QC") Color(0xFF8B5CF6) else Color.DarkGray
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "🧪 مراقبة الجودة (متابعة)",
                                    fontSize = 10.5.sp,
                                    fontWeight = if (qcFilter == "MONITORING_QC") FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (qcFilter == "MONITORING_QC") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = Color(0xFF8B5CF6)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (filteredOrders.isEmpty()) {
            EmptyBox(
                message = "لا توجد نتائج مطابقة لخيارات البحث والفلاتر الحالية.",
                subMessage = "تأكد من تعديل كلمات البحث لتجد أوامر الإنتاج الكيميائي المؤرشفة."
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "عدد السجلات المطابقة: ${filteredOrders.size} من أصل ${completed.size}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                if (filteredOrders.size > 10) {
                    Text(
                        text = if (displayedOrders.size < filteredOrders.size)
                            "معروض: ${displayedOrders.size} من ${filteredOrders.size}"
                        else
                            "إجمالي المعروض: ${filteredOrders.size} أمر",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRBlueMain
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                displayedOrders.forEach { order ->
                    val testsCount = testsCountByOrderId[order.id] ?: 0
                    val containersCount = remember(order.actualPackagingJson) {
                        var total = 0
                        if (!order.actualPackagingJson.isNullOrBlank()) {
                            try {
                                val arr = org.json.JSONArray(order.actualPackagingJson)
                                for (i in 0 until arr.length()) {
                                    total += arr.getJSONObject(i).optInt("actualCount", 0)
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        total
                    }

                    // علامة 📝 صغيرة داخل شارة الحالة إذا كان للأمر ملاحظات ختامية (بلا ازدحام في البطاقة)
                    val archivedHasFinalNote = order.notes.split("\nملاحظات الإكمال:").getOrNull(1)?.isNotBlank() == true
                    val archivedNoteMark = if (archivedHasFinalNote) " 📝" else ""

                    OrderSummaryCard(
                        order = order,
                        viewModel = viewModel,
                        onSelect = {
                            viewModel.selectedProductionOrder.value = order
                        },
                        actionContent = {
                            if (containersCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFD1FAE5))
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text("مكتمل ومغلق ✔️$archivedNoteMark", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFE2E8F0))
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text("مؤرشف / مكتمل 📁$archivedNoteMark", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                                }
                            }
                        },
                        testsCount = testsCount,
                        containersCount = containersCount
                    )
                }

                if (filteredOrders.size > displayedOrders.size) {
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

                    // Auto-load trigger when scrolling down
                    LaunchedEffect(scrollState?.value) {
                        if (scrollState != null && scrollState.value > 50 && visibleCount < filteredOrders.size) {
                            visibleCount = filteredOrders.size
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 16.dp)
                            .onGloballyPositioned { coordinates ->
                                val y = coordinates.positionInWindow().y
                                if (y > 0 && y <= screenHeightPx + 400) {
                                    if (visibleCount < filteredOrders.size) {
                                        visibleCount = filteredOrders.size
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = GBRBlueMain,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "جاري تحميل باقي أوامر الإنتاج المؤرشفة تلقائياً... 📦",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRBlueMain
                            )
                        }
                    }
                }
            }
        }

        if (showComparisonModal) {
            MultiOrderComparisonView(
                initialProductFilter = comparisonProductFilter,
                initialSelectedOrderIds = selectedOrderIdsForComp,
                viewModel = viewModel,
                onClose = { showComparisonModal = false }
            )
        }

        if (showRecipeAnalyticsModal) {
            RecipePerformanceAnalyticsModal(
                initialProductFilter = comparisonProductFilter,
                viewModel = viewModel,
                onClose = { showRecipeAnalyticsModal = false }
            )
        }
    }
}

// ==========================================
// CARD: ORDER SUMMARY PREVIEW
// ==========================================
@Composable
fun OrderSummaryCard(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    onSelect: () -> Unit,
    actionContent: @Composable () -> Unit,
    testsCount: Int? = null,
    containersCount: Int? = null
) {
    val dateStr = try {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.format(Date(order.createdAt))
    } catch (e: Exception) {
        ""
    }

    val adjustmentsState = produceState<List<com.example.data.ProductionAdjustment>>(initialValue = emptyList(), key1 = order.id) {
        viewModel.getProductionAdjustmentsFlow(order.id).collect {
            value = it
        }
    }
    val adjustments = adjustmentsState.value

    val cleanVersion = order.formulationVersion
        .replace("الإصدار: ", "")
        .replace("الإصدار ", "")
        .replace("الاصدار ", "")

    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, IndustrialBorder),
        colors = CardDefaults.cardColors(containerColor = IndustrialSurface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = order.orderNumber,
                        fontWeight = FontWeight.ExtraBold,
                        color = GBRDarkIndigo,
                        fontSize = 15.sp,
                        modifier = Modifier.testTag("production_order_number_${order.orderNumber}")
                    )
                }
                actionContent()
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.formulationName,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "إصدار $cleanVersion | معامل: ×${order.scaleFactor}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${formatNum(order.requiredWeightKg)} كجم",
                        fontWeight = FontWeight.Black,
                        color = GBRPinkAccent,
                        fontSize = 13.sp
                    )
                    Text(
                        text = dateStr,
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }

            val showTestsBadge = testsCount != null && testsCount > 0 && order.status == "مكتمل"
            if (showTestsBadge || (containersCount != null && containersCount > 0)) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showTestsBadge) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(Color(0xFFEFF6FF), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = GBRBlueMain,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "الفحوصات: $testsCount",
                                color = GBRBlueMain,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (containersCount != null && containersCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(Color(0xFFECFDF5), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "العبوات: $containersCount",
                                color = SuccessGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (adjustments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarningOrange.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = WarningOrange,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "تعديلات الكمية: ${adjustments.size}",
                        color = WarningOrange,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ==========================================
// SUBVIEW: PRODUCTION STATISTICS
// ==========================================
@Composable
fun ProductionStatsTab(orders: List<ProductionOrder>) {
    val readyVal = orders.count { it.status == "جاهز للتنفيذ" }
    val inProgressVal = orders.count { it.status == "قيد التنفيذ" }
    val completedVal = orders.count { it.status == "مكتمل" }
    val totalWeight = orders.filter { it.status == "مكتمل" }.sumOf { it.requiredWeightKg }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(title = "جاهز للتنفيذ", value = readyVal.toString(), color = GBRBlueMain, icon = Icons.Default.Info, modifier = Modifier.weight(1f))
            StatCard(title = "قيد التشغيل", value = inProgressVal.toString(), color = WarningOrange, icon = Icons.Default.Refresh, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(title = "الأوامر المكتملة", value = completedVal.toString(), color = SuccessGreen, icon = Icons.Default.CheckCircle, modifier = Modifier.weight(1f))
            StatCard(title = "إجمالي الإنتاج الكلي", value = "${formatNoDec(totalWeight)} كجم", color = GBRPinkAccent, icon = Icons.Default.Star, modifier = Modifier.weight(1f))
        }

        // Future expansions planning card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, IndustrialBorder),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("تكامل المصنع الذكي ومؤشرات الأداء", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "تصميم قاعدة البيانات جاهز للتوسع لربط شاشات العمال وصالة الإنتاج الذكية، وإدارة المخازن التلقائية، ونتائج مراقبة الجودة وربط خلاطات PLC ومقاييس الأوزان لدفاتر الدفعات الإلكترونية التفاعلية.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = IndustrialSurface),
        border = BorderStroke(1.dp, IndustrialBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Icon(imageVector = icon, contentDescription = null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = color)
        }
    }
}


// ==========================================
// COMPOSABLE: NEW ORDER FORM / VIEW
// ==========================================
@Composable
fun CreateProductionOrderView(
    viewModel: GbrViewModel,
    formulationsList: List<Formulation>,
    customPackagings: List<GbrViewModel.CustomPackaging>,
    onClose: () -> Unit
) {
    val approvedFormulations = remember(formulationsList) {
        formulationsList
            .filter { it.status == "🟢 معتمدة للإنتاج" }
            .groupBy { it.code.trim().uppercase() }
            .map { entry -> 
                entry.value.maxByOrNull { f ->
                    val ver = f.version.trim()
                    val vNum = if (ver.startsWith("الإصدار ")) {
                        ver.substringAfter("الإصدار ").trim().toIntOrNull() ?: 1
                    } else {
                        ver.filter { it.isDigit() }.toIntOrNull() ?: 1
                    }
                    vNum
                }!!
            }
            .sortedBy { it.name }
    }
    val allFormulationItems by viewModel.allFormulationItems.collectAsState()
    val preselectedFormula by viewModel.preselectedFormulationForOrder.collectAsState()

    var selectedFormula by remember { mutableStateOf<Formulation?>(null) }
    var chosenVersionName by remember { mutableStateOf("النشط") }
    var scaleMultiplierStr by remember { mutableStateOf("1") }
    var notesInput by remember { mutableStateOf("") }
    var showCommonNames by remember { mutableStateOf(false) }
    val rawMaterialsList by viewModel.rawMaterials.collectAsState()
    
    var showImportantAlertPopup by remember { mutableStateOf(false) }
    var importantAlertText by remember { mutableStateOf("") }

    var recipePhasesOfSelectedFormula by remember { mutableStateOf<List<com.example.data.RecipePhase>>(emptyList()) }
    var recipeStatusOfSelectedFormula by remember { mutableStateOf<com.example.data.RecipeStatus?>(null) }
    var isLoadingRecipeInfo by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var showModAlertBeforeCreate by remember { mutableStateOf(false) }
    var showComparisonTableAfterAlert by remember { mutableStateOf(false) }
    var lastCompletedAdjustmentsForCreate by remember { mutableStateOf<List<com.example.data.ProductionAdjustment>>(emptyList()) }
    var lastCompletedOrderForCreate by remember { mutableStateOf<com.example.data.ProductionOrder?>(null) }

    LaunchedEffect(preselectedFormula) {
        if (preselectedFormula != null) {
            selectedFormula = preselectedFormula
        }
    }

    LaunchedEffect(selectedFormula) {
        val formula = selectedFormula
        if (formula != null) {
            isLoadingRecipeInfo = true
            try {
                recipePhasesOfSelectedFormula = viewModel.getRecipePhasesForFormulationSync(formula.id)
                recipeStatusOfSelectedFormula = viewModel.getRecipeStatusSync(formula.id)
            } catch (e: Exception) {
                recipePhasesOfSelectedFormula = emptyList()
                recipeStatusOfSelectedFormula = null
            } finally {
                isLoadingRecipeInfo = false
            }

            if (formula.notes.startsWith("[IMPORTANT_ALERT]")) {
                val cleanText = formula.notes.removePrefix("[IMPORTANT_ALERT]").trim()
                notesInput = cleanText
                importantAlertText = cleanText
                showImportantAlertPopup = true
            } else {
                notesInput = formula.notes
                importantAlertText = ""
                showImportantAlertPopup = false
            }
        } else {
            recipePhasesOfSelectedFormula = emptyList()
            recipeStatusOfSelectedFormula = null
        }
    }

    val isRecipeInactive = remember(selectedFormula, recipePhasesOfSelectedFormula, recipeStatusOfSelectedFormula) {
        selectedFormula != null && (recipePhasesOfSelectedFormula.isEmpty() || recipeStatusOfSelectedFormula?.status != "READY")
    }
    
    val matchingMaterials = remember(selectedFormula, allFormulationItems) {
        if (selectedFormula == null) emptyList()
        else allFormulationItems.filter { it.formulationId == selectedFormula!!.id }
    }
    
    val originalWeight = remember(matchingMaterials) {
        matchingMaterials.sumOf { it.quantityMultiplier }
    }

    val activeScale = scaleMultiplierStr.toDoubleOrNull() ?: 1.0
    val finalWeight = originalWeight * activeScale

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IndustrialGrayBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (showImportantAlertPopup && importantAlertText.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { showImportantAlertPopup = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = ErrorRed,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "تنبيه هام ⚠️",
                            color = ErrorRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                },
                text = {
                    Text(
                        text = importantAlertText,
                        fontSize = 13.sp,
                        color = Color.DarkGray,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showImportantAlertPopup = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("موافق وقرأت التنبيه", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Form Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = Icons.Default.AddCircle, contentDescription = null, tint = GBRBlueMain)
                Text("إصدار وجدولة أمر إنتاج جديد", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
            }
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = ErrorRed)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step 1: Select Approved Formula
        Text("1. اختر التركيبة المعتمدة للإنتاج:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (approvedFormulations.isEmpty()) {
            Text(
                "❌ لا توجد أي تركيبات معتمدة للإنتاج بالنظام حالياً! يرجى الذهاب لصفحة التركيبات واعتماد تركيبة أولاً.",
                color = ErrorRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                Card(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedFormula != null) GBRBlueMain.copy(alpha = 0.02f) else Color(0xFFF8FAFC)
                    ),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (expanded) GBRBlueMain else if (selectedFormula != null) GBRBlueMain.copy(alpha = 0.5f) else Color(0xFFCBD5E1)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Arrow Indicator
                        Icon(
                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = if (selectedFormula != null) GBRBlueMain else Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (selectedFormula != null) {
                                    Text(
                                        text = "التركيبة الكيميائية المحددة",
                                        fontSize = 10.sp,
                                        color = GBRBlueMain,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Right
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = selectedFormula!!.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo,
                                        textAlign = TextAlign.Right
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "الكود: ${selectedFormula!!.code} • الإصدار: ${selectedFormula!!.version}",
                                        fontSize = 10.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Right
                                    )
                                } else {
                                    Text(
                                        text = "اضغط لاختيار تركيبة كيميائية معتمدة 🧪",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Right
                                    )
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selectedFormula != null) GBRBlueMain.copy(alpha = 0.1f)
                                        else Color.LightGray.copy(alpha = 0.3f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = null,
                                    tint = if (selectedFormula != null) GBRBlueMain else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .background(Color.White)
                        .border(1.dp, GBRBlueMain.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) {
                    approvedFormulations.forEachIndexed { index, form ->
                        val isSelected = selectedFormula?.id == form.id
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = GBRBlueMain.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = form.version,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRBlueMain,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = GBRBlueMain,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                                    ) {
                                        Text(
                                            text = form.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                                            color = if (isSelected) GBRBlueMain else GBRDarkIndigo,
                                            textAlign = TextAlign.Right
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "الكود: ${form.code}",
                                            fontSize = 10.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Right
                                        )
                                    }
                                }
                            },
                            onClick = {
                                selectedFormula = form
                                expanded = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) GBRBlueMain.copy(alpha = 0.05f)
                                    else Color.Transparent
                                )
                        )
                        if (index < approvedFormulations.size - 1) {
                            Divider(
                                color = Color.LightGray.copy(alpha = 0.3f),
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }

            if (selectedFormula != null) {
                if (isLoadingRecipeInfo) {
                    Text(
                        "⏳ جاري التحقق من وصفة التشغيل للتركيبة المحددة...",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else if (isRecipeInactive) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "تحذير: وصفة التشغيل غير معتمدة أو غير مفعلة!",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF991B1B)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "طالما أن وصفة التشغيل للتركيبة غير معتمدة أو غير مفعلة، فلا يمكن إنشاء أمر الإنتاج.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7F1D1D)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedFormula != null) {
            val formula = selectedFormula!!

            // Step 2: Operating scale multiplier (Renumbered from 3, Step 2 is removed)
            Text("2. معامل التشغيل (الدفعة):", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = scaleMultiplierStr,
                onValueChange = { scaleMultiplierStr = it },
                label = { Text("معامل الحساب") },
                placeholder = { Text("مثال: 0.5, 1, 1.5, 2") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3.1: Formulation Ingredients Table (Immediately below Operating Factor)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📋 مكونات التركيبة المحسوبة للدفعة:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
                OutlinedButton(
                    onClick = { showCommonNames = !showCommonNames },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showCommonNames) "عرض الاسم الفعلي" else "عرض الاسم المتداول",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // The Formulation Table Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder)
            ) {
                Column(modifier = Modifier.padding(4.dp)) {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9))
                            .padding(8.dp)
                    ) {
                        Text("م", modifier = Modifier.width(30.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)
                        Text("مستلزم الخلط والمُحسن الكيميائي", modifier = Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("الكمية الأصلية", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)
                        Text("كمية الدفعة", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    
                    if (matchingMaterials.isEmpty()) {
                        Text(
                            "لا توجد مواد مضافة في بطاقة هذه التركيبة حالياً.",
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    } else {
                        matchingMaterials.forEachIndexed { idx, item ->
                            val rm = rawMaterialsList.find { it.id == item.rawMaterialId }
                            val shownName = if (showCommonNames) {
                                if (!rm?.productionName.isNullOrBlank()) rm.productionName else rm?.name ?: ""
                            } else {
                                rm?.name ?: "مادة مجهولة"
                            }
                            val baseWeight = item.quantityMultiplier
                            val batchWeight = baseWeight * activeScale
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${idx + 1}", modifier = Modifier.width(30.dp), fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                Text(shownName, modifier = Modifier.weight(1.5f), fontSize = 11.sp, color = GBRDarkIndigo, fontWeight = FontWeight.SemiBold)
                                Text("${formatNum(baseWeight)} كجم", modifier = Modifier.weight(1f), fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                                Text("${formatNum(batchWeight)} كجم", modifier = Modifier.weight(1f), fontSize = 12.sp, color = GBRBlueMain, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                            if (idx < matchingMaterials.size - 1) {
                                Divider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Calculation Box
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📊 ملخص الوزن ومخطط الحساب والكميات الحية:", fontWeight = FontWeight.Bold, color = Color(0xFF166534), fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الوزن الأصلي للوجبة الكاملة:", fontSize = 11.sp, color = Color.Gray)
                        Text("${formatNum(originalWeight)} كجم", fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("معامل التشغيل المعتمد:", fontSize = 11.sp, color = Color.Gray)
                        Text("× $activeScale", fontWeight = FontWeight.Bold, color = WarningOrange)
                    }
                    Divider(color = Color(0xFFDCFCE7))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الوزن النهائي المتوقع للدفعة:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF14532D))
                        Text("${formatNum(finalWeight)} كجم", fontWeight = FontWeight.Black, color = Color(0xFF166534), fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Packaging properties Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("📦 العبوات المدعومة للإنتاج من بطاقة التركيبة:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 12.sp)
                    
                    val dispPacks = remember(formula, customPackagings) {
                        // نفس مصدر لقطة العبوات التي تُحفظ مع الأمر: الخياران القديمان + العبوات المعرّفة في التركيبة
                        val rows = mutableListOf<Triple<String, Double, String>>()
                        if (formula.supports18L) {
                            val p = customPackagings.find { it.netWeight == 18.0 || it.name.contains("18") }
                            rows.add(Triple(p?.name ?: "سطل معياري 18 لتر", formula.netWeight18L.trim().toDoubleOrNull() ?: 18.0, p?.id ?: "1"))
                        }
                        if (formula.supports5L) {
                            val p = customPackagings.find { it.netWeight == 5.0 || it.name.contains("5") }
                            rows.add(Triple(p?.name ?: "جالون معياري 5 لتر", formula.netWeight5L.trim().toDoubleOrNull() ?: 5.0, p?.id ?: "2"))
                        }
                        if (formula.packagingWeightsJson.isNotBlank()) {
                            try {
                                val jo = org.json.JSONObject(formula.packagingWeightsJson)
                                jo.keys().forEach { key ->
                                    val net = jo.optString(key, "").split(":").getOrNull(0)?.toDoubleOrNull()
                                    val p = customPackagings.find { it.id == key }
                                    if (net != null && p != null && rows.none { it.third == p.id }) rows.add(Triple(p.name, net, p.id))
                                }
                            } catch (e: Exception) { }
                        }
                        val idxOrder = customPackagings.mapIndexed { i, p -> p.id to i }.toMap()
                        rows.sortedBy { idxOrder[it.third] ?: Int.MAX_VALUE }
                    }
                    dispPacks.forEach { (pkName, pkNet, _) ->
                        val expectedCount = if (finalWeight > 0.0 && pkNet > 0.0) finalWeight / pkNet else 0.0
                        Text("• $pkName: وزن صافي ${String.format(java.util.Locale.US, "%.2f", pkNet)} كجم | عدد العبوات المتوقع: ${formatNoDec(expectedCount)} عبوة", fontSize = 11.sp, color = GBRDarkIndigo)
                    }
                    if (dispPacks.isEmpty()) {
                        Text("• عبوة معيارية 18 لتر: وزن صافي 18.0 كجم | غير محدد بالبطاقة (سيتم تمكين سطل 18L كافتراضي للتعبئة)", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3: Notes
            Text("3. أي ملاحظات أو تعليمات توجيه للإنتاج:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = notesInput,
                onValueChange = { notesInput = it },
                placeholder = { Text("مثال: تعبئة في عبوات البلاستيك المقوى أزرق 18L") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Button Action Save Order
            val isFormValid = activeScale > 0.0 && originalWeight > 0.0 && !isRecipeInactive && !isLoadingRecipeInfo
            Button(
                onClick = {
                    if (isFormValid && formula != null) {
                        val proceedExecution = {
                            // Gather extra operating costs map
                            val extraCostsMap = try {
                                if (formula.packagingWeightsJson.isBlank()) {
                                    emptyMap()
                                } else {
                                    val json = org.json.JSONObject(formula.packagingWeightsJson)
                                    val m = mutableMapOf<String, Double>()
                                    json.keys().forEach { key ->
                                        val rawVal = json.optString(key, "")
                                        val parts = rawVal.split(":")
                                        val extraC = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                        m[key] = extraC
                                    }
                                    m
                                }
                            } catch (e: Exception) {
                                emptyMap<String, Double>()
                            }

                            // Serialize packaging snapshot definition
                            val packSnapshots = mutableListOf<JSONObject>()
                            if (formula.supports18L) {
                                val customWt = formula.netWeight18L.trim().toDoubleOrNull() ?: 18.0
                                val pkg18 = customPackagings.find { it.netWeight == 18.0 || it.name.contains("18") } 
                                    ?: GbrViewModel.CustomPackaging("1", "سطل معياري 18 لتر", 18.0, 19.2, 15.00)
                                packSnapshots.add(JSONObject().apply {
                                    put("id", pkg18.id)
                                    put("name", pkg18.name)
                                    put("netWeight", customWt)
                                    put("weightWithLid", pkg18.weightWithLid)
                                    put("price", pkg18.price)
                                    put("extraCost", extraCostsMap[pkg18.id] ?: extraCostsMap["1"] ?: 0.0)
                                })
                            }
                            if (formula.supports5L) {
                                val customWt = formula.netWeight5L.trim().toDoubleOrNull() ?: 5.0
                                val pkg5 = customPackagings.find { it.netWeight == 5.0 || it.name.contains("5") } 
                                    ?: GbrViewModel.CustomPackaging("2", "جالون معياري 5 لتر", 5.0, 5.4, 5.50)
                                packSnapshots.add(JSONObject().apply {
                                    put("id", pkg5.id)
                                    put("name", pkg5.name)
                                    put("netWeight", customWt)
                                    put("weightWithLid", pkg5.weightWithLid)
                                    put("price", pkg5.price)
                                    put("extraCost", extraCostsMap[pkg5.id] ?: extraCostsMap["2"] ?: 0.0)
                                })
                            }
                            if (formula.packagingWeightsJson.isNotBlank()) {
                                try {
                                    val jsonObj = org.json.JSONObject(formula.packagingWeightsJson)
                                    jsonObj.keys().forEach { key ->
                                        val pkgId = key
                                        val rawVal = jsonObj.optString(pkgId, "")
                                        val parts = rawVal.split(":")
                                        val customNetWeight = parts.getOrNull(0)?.toDoubleOrNull()
                                        val customExtraCost = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                        if (customNetWeight != null) {
                                            val pkg = customPackagings.find { it.id == pkgId }
                                            if (pkg != null) {
                                                // Ensure we don't duplicate if already added
                                                if (packSnapshots.none { it.optString("id") == pkg.id }) {
                                                    packSnapshots.add(JSONObject().apply {
                                                        put("id", pkg.id)
                                                        put("name", pkg.name)
                                                        put("netWeight", customNetWeight)
                                                        put("weightWithLid", pkg.weightWithLid)
                                                        put("price", pkg.price)
                                                        put("extraCost", customExtraCost)
                                                    })
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                            if (packSnapshots.isEmpty()) {
                                packSnapshots.add(JSONObject().apply {
                                    put("id", "1")
                                    put("name", "سطل معياري 18 لتر")
                                    put("netWeight", 18.0)
                                    put("weightWithLid", 19.2)
                                    put("price", 15.00)
                                    put("extraCost", 0.0)
                                })
                            }
                            val packagingSnapshotJson = JSONArray(com.example.ui.gbrSortPackSnapshots(packSnapshots, customPackagings)).toString()

                            viewModel.addProductionOrder(
                                formulationId = formula.id,
                                formulationName = formula.name,
                                versionName = formula.version,
                                scaleFactor = activeScale,
                                originalWeightKg = originalWeight,
                                notes = notesInput,
                                packagingSnapshotJson = packagingSnapshotJson
                            )
                            onClose()
                        }

                        scope.launch {
                            val lastOrder = viewModel.getLastCompletedProductionOrderForFormulation(formula.id)
                            if (lastOrder != null) {
                                val adjustments = viewModel.getProductionAdjustmentsSync(lastOrder.id)
                                if (adjustments.isNotEmpty()) {
                                    lastCompletedOrderForCreate = lastOrder
                                    lastCompletedAdjustmentsForCreate = adjustments
                                    showModAlertBeforeCreate = true
                                    return@launch
                                }
                            }
                            proceedExecution()
                        }
                    }
                },
                enabled = isFormValid,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(imageVector = Icons.Default.Done, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("حفظ وإصدار أمر الإنتاج المجدول لخط العمل", fontWeight = FontWeight.Bold, color = Color.White)
            }

            if (showModAlertBeforeCreate && lastCompletedOrderForCreate != null) {
                AlertDialog(
                    onDismissRequest = { showModAlertBeforeCreate = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = Color.White,
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "تنبيه: تعديلات في آخر إنتاج",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFB45309)
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "لقد تم إجراء تعديلات على كميات المواد الخام أثناء تنفيذ آخر أمر إنتاج مكتمل لهذه التركيبة (رقم أمر الإنتاج: ${lastCompletedOrderForCreate?.orderNumber}).",
                                fontSize = 13.sp,
                                color = Color.DarkGray
                            )
                            
                            Text(
                                text = "تفاصيل المواد المعدلة:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo
                            )
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFFBEB), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFFEF3C7), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                lastCompletedAdjustmentsForCreate.forEach { adj ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "🧪 ${adj.rawMaterialName}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E293B),
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.Right
                                        )
                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = "الكمية الأصلية: ${formatNum(adj.originalQuantity)} كجم",
                                                fontSize = 11.sp,
                                                color = Color(0xFF475569)
                                            )
                                            Text(
                                                text = "الكمية المعدلة: ${formatNum(adj.newQuantity)} كجم",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB45309)
                                            )
                                        }
                                    }
                                    if (adj != lastCompletedAdjustmentsForCreate.last()) {
                                        HorizontalDivider(color = Color(0xFFFEF3C7))
                                    }
                                }
                            }

                            Text(
                                text = "هل ترغب في مراجعة ومقارنة هذه التعديلات بالتفصيل؟",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showModAlertBeforeCreate = false
                                showComparisonTableAfterAlert = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("مراجعة التعديلات بالتفصيل", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showModAlertBeforeCreate = false
                                // User chose to proceed with the original formulation! Let's trigger proceedExecution directly!
                                val proceedExecution = {
                                    val packSnapshots = mutableListOf<JSONObject>()
                                    if (formula.supports18L) {
                                        val customWt = formula.netWeight18L.trim().toDoubleOrNull() ?: 18.0
                                        val pkg18 = customPackagings.find { it.netWeight == 18.0 || it.name.contains("18") } 
                                            ?: GbrViewModel.CustomPackaging("1", "سطل معياري 18 لتر", 18.0, 19.2, 15.00)
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", pkg18.id)
                                            put("name", pkg18.name)
                                            put("netWeight", customWt)
                                            put("weightWithLid", pkg18.weightWithLid)
                                            put("price", pkg18.price)
                                            put("extraCost", (formula.packagingWeightsJson.isBlank()).let {
                                                // Simple fallback operating check or lookup from extraCostsMap if needed
                                                0.0
                                            })
                                        })
                                    }
                                    if (formula.supports5L) {
                                        val customWt = formula.netWeight5L.trim().toDoubleOrNull() ?: 5.0
                                        val pkg5 = customPackagings.find { it.netWeight == 5.0 || it.name.contains("5") } 
                                            ?: GbrViewModel.CustomPackaging("2", "جالون معياري 5 لتر", 5.0, 5.4, 5.50)
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", pkg5.id)
                                            put("name", pkg5.name)
                                            put("netWeight", customWt)
                                            put("weightWithLid", pkg5.weightWithLid)
                                            put("price", pkg5.price)
                                            put("extraCost", 0.0)
                                        })
                                    }
                                    if (formula.packagingWeightsJson.isNotBlank()) {
                                        try {
                                            val jsonObj = org.json.JSONObject(formula.packagingWeightsJson)
                                            jsonObj.keys().forEach { key ->
                                                val pkgId = key
                                                val rawVal = jsonObj.optString(pkgId, "")
                                                val parts = rawVal.split(":")
                                                val customNetWeight = parts.getOrNull(0)?.toDoubleOrNull()
                                                val customExtraCost = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                                if (customNetWeight != null) {
                                                    val pkg = customPackagings.find { it.id == pkgId }
                                                    if (pkg != null) {
                                                        if (packSnapshots.none { it.optString("id") == pkg.id }) {
                                                            packSnapshots.add(JSONObject().apply {
                                                                put("id", pkg.id)
                                                                put("name", pkg.name)
                                                                put("netWeight", customNetWeight)
                                                                put("weightWithLid", pkg.weightWithLid)
                                                                put("price", pkg.price)
                                                                put("extraCost", customExtraCost)
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // ignore
                                        }
                                    }
                                    if (packSnapshots.isEmpty()) {
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", "1")
                                            put("name", "سطل معياري 18 لتر")
                                            put("netWeight", 18.0)
                                            put("weightWithLid", 19.2)
                                            put("price", 15.00)
                                            put("extraCost", 0.0)
                                        })
                                    }
                                    val packagingSnapshotJson = JSONArray(com.example.ui.gbrSortPackSnapshots(packSnapshots, customPackagings)).toString()

                                    viewModel.addProductionOrder(
                                        formulationId = formula.id,
                                        formulationName = formula.name,
                                        versionName = formula.version,
                                        scaleFactor = activeScale,
                                        originalWeightKg = originalWeight,
                                        notes = notesInput,
                                        packagingSnapshotJson = packagingSnapshotJson
                                    )
                                    onClose()
                                }
                                proceedExecution()
                            }
                        ) {
                            Text("متابعة بالتركيبة الأصلية", fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }
                )
            }

            if (showComparisonTableAfterAlert && lastCompletedOrderForCreate != null) {
                AlertDialog(
                    onDismissRequest = { showComparisonTableAfterAlert = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = Color.White,
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = "مقارنة تعديلات آخر إنتاج",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "أمر الإنتاج: ${lastCompletedOrderForCreate?.orderNumber} | الدفعة: ${lastCompletedOrderForCreate?.batchNumber}",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "التعديلات التي تم تطبيقها على الكميات أثناء تنفيذ آخر تشغيلة مقارنة بالتركيبة الأساسية الأصلية:",
                                fontSize = 13.sp,
                                color = Color.DarkGray
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .heightIn(max = 280.dp)
                                    .fillMaxWidth()
                                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                    .background(Color(0xFFF8FAFC))
                                    .padding(8.dp)
                            ) {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(lastCompletedAdjustmentsForCreate) { adj ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White, RoundedCornerShape(8.dp))
                                                .border(0.5.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = adj.rawMaterialName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRDarkIndigo
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "التركيبة الأصلية",
                                                        fontSize = 10.sp,
                                                        color = Color.Gray
                                                    )
                                                    Text(
                                                        text = "${formatQuantity(adj.originalQuantity)} كغم",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color(0xFF64748B)
                                                    )
                                                }
                                                
                                                Icon(
                                                    imageVector = Icons.Default.ArrowForward,
                                                    contentDescription = null,
                                                    tint = Color(0xFF94A3B8),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = "آخر إنتاج فعلي",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF0F172A),
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${formatQuantity(adj.newQuantity)} كغم",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF10B981)
                                                    )
                                                }
                                            }
                                            if (adj.reason.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFFEFF6FF), RoundedCornerShape(4.dp))
                                                        .padding(6.dp)
                                                ) {
                                                    Text(
                                                        text = "السبب: ${adj.reason}",
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF1D4ED8),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showComparisonTableAfterAlert = false
                                // Proceed to create the order with original formulation
                                val proceedExecution = {
                                    val packSnapshots = mutableListOf<JSONObject>()
                                    if (formula.supports18L) {
                                        val customWt = formula.netWeight18L.trim().toDoubleOrNull() ?: 18.0
                                        val pkg18 = customPackagings.find { it.netWeight == 18.0 || it.name.contains("18") } 
                                            ?: GbrViewModel.CustomPackaging("1", "سطل معياري 18 لتر", 18.0, 19.2, 15.00)
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", pkg18.id)
                                            put("name", pkg18.name)
                                            put("netWeight", customWt)
                                            put("weightWithLid", pkg18.weightWithLid)
                                            put("price", pkg18.price)
                                            put("extraCost", 0.0)
                                        })
                                    }
                                    if (formula.supports5L) {
                                        val customWt = formula.netWeight5L.trim().toDoubleOrNull() ?: 5.0
                                        val pkg5 = customPackagings.find { it.netWeight == 5.0 || it.name.contains("5") } 
                                            ?: GbrViewModel.CustomPackaging("2", "جالون معياري 5 لتر", 5.0, 5.4, 5.50)
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", pkg5.id)
                                            put("name", pkg5.name)
                                            put("netWeight", customWt)
                                            put("weightWithLid", pkg5.weightWithLid)
                                            put("price", pkg5.price)
                                            put("extraCost", 0.0)
                                        })
                                    }
                                    if (formula.packagingWeightsJson.isNotBlank()) {
                                        try {
                                            val jsonObj = org.json.JSONObject(formula.packagingWeightsJson)
                                            jsonObj.keys().forEach { key ->
                                                val pkgId = key
                                                val rawVal = jsonObj.optString(pkgId, "")
                                                val parts = rawVal.split(":")
                                                val customNetWeight = parts.getOrNull(0)?.toDoubleOrNull()
                                                val customExtraCost = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                                                if (customNetWeight != null) {
                                                    val pkg = customPackagings.find { it.id == pkgId }
                                                    if (pkg != null) {
                                                        if (packSnapshots.none { it.optString("id") == pkg.id }) {
                                                            packSnapshots.add(JSONObject().apply {
                                                                put("id", pkg.id)
                                                                put("name", pkg.name)
                                                                put("netWeight", customNetWeight)
                                                                put("weightWithLid", pkg.weightWithLid)
                                                                put("price", pkg.price)
                                                                put("extraCost", customExtraCost)
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // ignore
                                        }
                                    }
                                    if (packSnapshots.isEmpty()) {
                                        packSnapshots.add(JSONObject().apply {
                                            put("id", "1")
                                            put("name", "سطل معياري 18 لتر")
                                            put("netWeight", 18.0)
                                            put("weightWithLid", 19.2)
                                            put("price", 15.00)
                                            put("extraCost", 0.0)
                                        })
                                    }
                                    val packagingSnapshotJson = JSONArray(com.example.ui.gbrSortPackSnapshots(packSnapshots, customPackagings)).toString()

                                    viewModel.addProductionOrder(
                                        formulationId = formula.id,
                                        formulationName = formula.name,
                                        versionName = formula.version,
                                        scaleFactor = activeScale,
                                        originalWeightKg = originalWeight,
                                        notes = notesInput,
                                        packagingSnapshotJson = packagingSnapshotJson
                                    )
                                    onClose()
                                }
                                proceedExecution()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("متابعة باستخدام التركيبة الأصلية", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showComparisonTableAfterAlert = false
                            }
                        ) {
                            Text("إلغاء", fontWeight = FontWeight.Bold, color = Color.Gray)
                        }
                    }
                )
            }
        }
    }
}

// ==========================================
// COMPOSABLE: ACTIVE RUN VIEW / BACK TO POINT
// ==========================================
@Composable
fun OrderExecutionOrRecordView(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    if (order.status == "مكتمل") {
        // RENDER LEDGER / HISTORICAL LEDGER SCREEN WITH 6 TABS
        OrderHistoryLedgerView(order, viewModel, onClose)
    } else {
        // RENDER RUN SCREEN (Manager vs Shop Floor)
        ActiveExecutionView(order, viewModel, onClose)
    }
}

// ==========================================
// SUBVIEW: HISTORICAL LEDGER COMPOSABLE (6 TABS)
// ==========================================
@Composable
fun OrderHistoryLedgerView(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    val orderEvents by viewModel.selectedProductionOrderEvents.collectAsState()
    val orderPhases by viewModel.selectedProductionOrderPhases.collectAsState()
    val orderRecipeItems by viewModel.selectedProductionOrderRecipeItems.collectAsState()
    val orderItems by viewModel.selectedProductionOrderItems.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val formulationsList by viewModel.formulations.collectAsState()
    val formulation = remember(order.formulationId, formulationsList) {
        formulationsList.find { it.id == order.formulationId }
    }
    var showQcReportInTab by remember { mutableStateOf(false) }
    var showMultiComparisonModal by remember { mutableStateOf(false) }

    // Trigger state reads
    LaunchedEffect(order.id) {
        viewModel.selectedProductionOrder.value = order
    }

    val coroutineScope = rememberCoroutineScope()
    val histPagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 7 }
    )

    LaunchedEffect(histPagerState.currentPage) {
        if (histPagerState.currentPage != 4) {
            showQcReportInTab = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IndustrialGrayBg)
            .padding(16.dp)
    ) {
        // Historical Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("سجل تصنيع الدفعة الكيميائية", fontSize = 18.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("رمز الدفعة الموحد: ${order.orderNumber}", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
                    if (order.orderNumber != order.batchNumber) {
                        Text("| الدفعة السابقة: ${order.batchNumber}", fontSize = 11.sp, color = Color.Gray)
                    }
                    val orderDateStr = remember(order.createdAt) {
                        try {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(order.createdAt))
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    if (orderDateStr.isNotBlank()) {
                        Text("| $orderDateStr", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (histPagerState.currentPage == 4) {
                    val currentQcSession = remember(labSessions, order.id) {
                        labSessions.find { it.sampleProperties == "ORDER_ID:${order.id}" }
                    }
                    if (currentQcSession != null && !showQcReportInTab) {
                        IconButton(onClick = { showQcReportInTab = true }) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = "طباعة تقرير المختبر",
                                tint = GBRBlueMain,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = {
                    onClose()
                    showQcReportInTab = false
                }) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = ErrorRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ScrollableTabRow(
            selectedTabIndex = histPagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = GBRBlueMain,
            edgePadding = 0.dp
        ) {
            val tabs = listOf("التركيبة", "تحليل التكاليف", "التعبئة", "تقرير الإنتاج", "الفحوصات", "سجل التنفيذ", "تعديلات الإنتاج")
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = histPagerState.currentPage == index,
                    onClick = { 
                        if (index != 4) showQcReportInTab = false
                        coroutineScope.launch { histPagerState.animateScrollToPage(index) }
                    },
                    text = { Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tab Content Boxes
        HorizontalPager(
            state = histPagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (page) {
                0 -> HistoricalRecipeSnapshotView(order, orderItems, orderPhases, orderRecipeItems, viewModel)
                1 -> HistoricalCostsAnalysisView(order, orderItems, formulation, viewModel)
                2 -> HistoricalPackingResultsView(order, orderItems)
                3 -> HistoricalReportView(order)
                4 -> HistoricalQualityControlPanel(
                    order = order, 
                    viewModel = viewModel, 
                    onBack = { 
                        if (showQcReportInTab) {
                            showQcReportInTab = false
                        } else {
                            onClose()
                        }
                    },
                    showReportViewExternal = showQcReportInTab,
                    onShowReportViewExternalChange = { showQcReportInTab = it }
                )
                5 -> HistoricalAuditLogsView(orderEvents)
                6 -> HistoricalAdjustmentsView(order, viewModel)
            }
        }

        if (showMultiComparisonModal) {
            MultiOrderComparisonView(
                initialProductFilter = order.formulationName,
                initialSelectedOrderIds = setOf(order.id),
                viewModel = viewModel,
                onClose = { showMultiComparisonModal = false }
            )
        }
    }
}

// ------------------------------------------
// HISTORICAL TAB 1: FROZEN RECIPE SCREEN
// ------------------------------------------
@Composable
fun HistoricalRecipeSnapshotView(
    order: ProductionOrder,
    items: List<ProductionOrderItem>,
    phases: List<com.example.data.ProductionOrderPhase>,
    recipeItems: List<com.example.data.ProductionOrderRecipeItem>,
    viewModel: GbrViewModel
) {
    var showPhasesDetail by remember { mutableStateOf(false) }
    val totalWeight = remember(items) { items.sumOf { it.calculatedQuantity } }

    // لا نكمّل مراحل الأمر المؤرشف من وصفة التركيبة الحالية: الوصفات تتغير، فنعرض فقط ما حُفظ مع الأمر وقت الإنتاج
    val effectivePhases = phases
    val effectiveRecipeItems = recipeItems

    val adjustments by produceState<List<com.example.data.ProductionAdjustment>>(
        initialValue = viewModel.selectedProductionOrderAdjustments.value.filter { it.productionOrderId == order.id },
        key1 = order.id
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.getProductionAdjustmentsSync(order.id)
        }
        viewModel.getProductionAdjustmentsFlow(order.id).collect {
            value = it
        }
    }
    var selectedAdjustmentForDetails by remember { mutableStateOf<com.example.data.ProductionAdjustment?>(null) }
    val finalNotes = remember(order.notes) {
        val notesParts = order.notes.split("\nملاحظات الإكمال:")
        if (notesParts.size > 1) notesParts[1].trim() else ""
    }

    if (selectedAdjustmentForDetails != null) {
        val adj = selectedAdjustmentForDetails!!
        val diffSign = if (adj.difference >= 0) "+" else ""
        val timeStr = try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            sdf.format(Date(adj.timestamp))
        } catch (e: Exception) {
            ""
        }

        AlertDialog(
            onDismissRequest = { selectedAdjustmentForDetails = null },
            title = {
                Text(
                    text = "⚙️ تفاصيل التعديل الفني للمادة",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GBRDarkIndigo,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "المادة المعدلة: ${adj.rawMaterialName}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = GBRDarkIndigo
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("المسؤول عن التعديل:", fontSize = 12.sp, color = Color.Gray)
                                Text(adj.userName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GBRDarkIndigo)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("وقت التعديل الفعلي:", fontSize = 12.sp, color = Color.Gray)
                                Text(timeStr, fontSize = 11.sp, color = Color.DarkGray)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الكمية الأصلية:", fontSize = 12.sp, color = Color.Gray)
                                Text("${formatSupervisorQtyClean(adj.originalQuantity)} كغم", fontWeight = FontWeight.Medium, fontSize = 12.sp, color = GBRDarkIndigo)
                            }
                            Divider(color = Color(0xFFF1F5F9))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الكمية الفعلية بعد التعديل:", fontSize = 12.sp, color = Color.Gray)
                                Text("${formatSupervisorQtyClean(adj.newQuantity)} كغم", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = WarningOrange)
                            }
                            Divider(color = Color(0xFFF1F5F9))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الفرق الإجمالي للخلطة:", fontSize = 12.sp, color = Color.Gray)
                                Text(
                                    text = "$diffSign${formatNum(adj.difference)} كغم",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    color = if (adj.difference >= 0) SuccessGreen else ErrorRed
                                )
                            }
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("سبب هذا التعديل الإنتاجي:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GBRDarkIndigo)
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = adj.reason,
                                fontSize = 12.sp,
                                color = Color(0xFF92400E),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (adj.notes.isNotBlank()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ملاحظات المسؤول:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GBRDarkIndigo)
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = adj.notes,
                                    fontSize = 11.sp,
                                    color = Color.DarkGray,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedAdjustmentForDetails = null },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("جاهز / إغلاق", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, IndustrialBorder),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header block
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 مكونات التركيبة الفعلية:",
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo,
                        fontSize = 14.sp
                    )
                    Surface(
                        color = GBRCyanAccent.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "إصدار: ${order.formulationVersion}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRCyanAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                
                Text(
                    text = "معامل حساب حجم الدفعة المستخدم: ×${order.scaleFactor} | الوزن النهائي النظري: ${formatNum(order.requiredWeightKg)} كجم",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                
                Divider(color = IndustrialBorder)

                // Professional Table Container
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column {
                        // Table Header row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F5F9))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "اسم المادة الخام كيميائياً",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo,
                                modifier = Modifier.weight(2f),
                                textAlign = TextAlign.Right
                            )
                            Text(
                                text = "النسبة القياسية",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo,
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "الوزن الفعلي",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo,
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.Left
                            )
                        }

                        // Table rows with alternating backgrounds and clean spacing
                        if (items.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("لا توجد مادة مدرجة في هذه التركيبة الكيميائية.", fontSize = 12.sp, color = Color.Gray)
                            }
                        } else {
                            items.forEachIndexed { index, ri ->
                                val matchedAdjustment = adjustments.find { it.rawMaterialId == ri.rawMaterialId }
                                val isAdjusted = matchedAdjustment != null
                                val rowBgColor = if (isAdjusted) Color(0xFFFEF3C7) else (if (index % 2 == 0) Color.White else Color(0xFFF8FAFC))
                                val rowModifier = Modifier
                                    .fillMaxWidth()
                                    .background(rowBgColor)
                                    .then(
                                        if (isAdjusted) Modifier.clickable { selectedAdjustmentForDetails = matchedAdjustment }
                                        else Modifier
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp)

                                Row(
                                    modifier = rowModifier,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(2f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = ri.rawMaterialName,
                                            fontSize = 12.sp,
                                            fontWeight = if (isAdjusted) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isAdjusted) GBRDarkIndigo else Color(0xFF1E293B),
                                            textAlign = TextAlign.Right
                                        )
                                        if (isAdjusted) {
                                            Surface(
                                                color = WarningOrange.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "معدّلة ✏️",
                                                    fontSize = 9.sp,
                                                    color = WarningOrange,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "${formatNum(ri.quantityMultiplier)} كجم/طن",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B),
                                        modifier = Modifier.weight(1.2f),
                                        textAlign = TextAlign.Center
                                    )
                                    Column(
                                        modifier = Modifier.weight(1.2f),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = "${formatNum(ri.calculatedQuantity)} كجم",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isAdjusted) WarningOrange else GBRPinkAccent,
                                            textAlign = TextAlign.Left
                                        )
                                        if (isAdjusted && matchedAdjustment != null) {
                                            val diffValue = matchedAdjustment.difference
                                            val diffSign = if (diffValue >= 0) "+" else ""
                                            Text(
                                                text = "الأصل: ${formatNum(matchedAdjustment.originalQuantity)} كجم",
                                                fontSize = 9.sp,
                                                color = Color.Gray,
                                                textAlign = TextAlign.Left
                                            )
                                            Text(
                                                text = "الفرق: $diffSign${formatNum(diffValue)} كجم",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (diffValue >= 0) SuccessGreen else ErrorRed,
                                                textAlign = TextAlign.Left
                                            )
                                        }
                                    }
                                }
                                if (index < items.size - 1) {
                                    Divider(color = Color(0xFFE2E8F0).copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }

                // Total weight section box (below the table, as requested)
                Surface(
                    color = Color(0xFFECFDF5), // Soft green highlight box
                    border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "إجمالي الوزن النظري:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF047857)
                        )
                        Text(
                            text = "${formatNum(totalWeight)} كجم",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF047857)
                        )
                    }
                }
            }
        }

        // ملاحظات ختامية قابلة للتعديل/الإضافة حتى بعد أرشفة الأمر
        var showEditFinalNotesDialog by remember { mutableStateOf(false) }
        var finalNotesDraft by remember { mutableStateOf("") }

        if (showEditFinalNotesDialog) {
            AlertDialog(
                onDismissRequest = { showEditFinalNotesDialog = false },
                title = {
                    Text(
                        text = if (finalNotes.isNotBlank()) "✏️ تعديل الملاحظات الختامية" else "➕ إضافة ملاحظة لأمر الإنتاج",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GBRDarkIndigo
                    )
                },
                text = {
                    OutlinedTextField(
                        value = finalNotesDraft,
                        onValueChange = { finalNotesDraft = it },
                        label = { Text("الملاحظات الختامية وإنحرافات التشغيل") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        minLines = 4
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.updateArchivedOrderFinalNotes(order.id, finalNotesDraft)
                            showEditFinalNotesDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("حفظ", fontWeight = FontWeight.Bold, color = Color.White) }
                },
                dismissButton = {
                    TextButton(onClick = { showEditFinalNotesDialog = false }) { Text("إلغاء") }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("📝", fontSize = 14.sp)
                        Text(
                            text = "الملاحظات الختامية وإنحرافات التشغيل:",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF15803D),
                            fontSize = 13.sp
                        )
                    }
                    TextButton(onClick = {
                        finalNotesDraft = finalNotes
                        showEditFinalNotesDialog = true
                    }) {
                        Text(
                            text = if (finalNotes.isNotBlank()) "✏️ تعديل" else "➕ إضافة ملاحظة",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRBlueMain
                        )
                    }
                }
                Text(
                    text = if (finalNotes.isNotBlank()) finalNotes else "لا توجد ملاحظات ختامية لهذا الأمر.",
                    color = if (finalNotes.isNotBlank()) Color(0xFF1F2937) else Color.Gray,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Expand Toggle Phases / Operating Recipe
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, if (showPhasesDetail) Color(0xFF99F6E4) else Color(0xFFE2E8F0)),
            colors = CardDefaults.cardColors(containerColor = if (showPhasesDetail) Color(0xFFF0FDFA) else Color(0xFFF8FAFC)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPhasesDetail = !showPhasesDetail }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
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
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0D9488)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PrecisionManufacturing,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "مراحل وصفة التشغيل والإنتاج المنفذة:",
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo,
                            fontSize = 12.5.sp
                        )
                        Text(
                            text = if (effectivePhases.isEmpty()) "لا توجد مراحل مسجلة" else "إجمالي المراحل: ${effectivePhases.size} مرحلة تشغيل وخلط",
                            fontSize = 10.5.sp,
                            color = Color(0xFF0F766E)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (showPhasesDetail) Color(0xFFCCFBF1) else Color.White,
                    border = BorderStroke(1.dp, if (showPhasesDetail) Color(0xFF5EEAD4) else Color(0xFFCBD5E1))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (showPhasesDetail) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF0F766E),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (showPhasesDetail) "إخفاء مراحل الإنتاج" else "إظهار مراحل الإنتاج",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color(0xFF0F766E)
                        )
                    }
                }
            }
        }

        if (showPhasesDetail) {
            Spacer(modifier = Modifier.height(10.dp))
            if (effectivePhases.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد مراحل مسجلة لوصفة التشغيل لهذه الدفعة وقت الإنتاج.",
                            fontSize = 11.5.sp,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    effectivePhases.sortedBy { it.sequence }.forEachIndexed { pIdx, phase ->
                        val itemsInPhase = effectiveRecipeItems
                            .filter { it.productionOrderPhaseId == phase.id }
                            .sortedBy { it.sequence }
                        val phaseTotalWeight = itemsInPhase.sumOf { it.calculatedQuantity }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Phase Top Header
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(GBRBlueMain),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = (pIdx + 1).toString(),
                                                color = Color.White,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Text(
                                            text = phase.name,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRDarkIndigo,
                                            fontSize = 13.5.sp
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (phase.mixerRpm > 0) {
                                            Surface(
                                                color = Color(0xFFF1F5F9),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(0.5.dp, Color(0xFFCBD5E1))
                                            ) {
                                                Text(
                                                    text = "⚡ ${phase.mixerRpm} RPM",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF334155),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                        if (phase.durationMinutes > 0) {
                                            Surface(
                                                color = Color(0xFFFFF7ED),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(0.5.dp, Color(0xFFFED7AA))
                                            ) {
                                                Text(
                                                    text = "⏱️ ${phase.durationMinutes} دقيقة",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFFC2410C),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Phase Instructions if any
                                if (phase.instructions.isNotBlank()) {
                                    Surface(
                                        color = Color(0xFFF8FAFC),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text("📝", fontSize = 11.sp)
                                            Text(
                                                text = "التعليمات: ${phase.instructions}",
                                                fontSize = 11.sp,
                                                color = Color.DarkGray,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }

                                Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)

                                // Phase Materials Content (محتوى المرحلة من المواد)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "المواد المضافة في هذه المرحلة (${itemsInPhase.size} مواد):",
                                            fontWeight = FontWeight.Bold,
                                            color = GBRDarkIndigo,
                                            fontSize = 11.5.sp
                                        )
                                        if (itemsInPhase.isNotEmpty()) {
                                            Text(
                                                text = "وزن المرحلة: ${formatNum(phaseTotalWeight)} كجم",
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF047857),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    if (itemsInPhase.isEmpty()) {
                                        Surface(
                                            color = Color(0xFFF8FAFC),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "لا توجد مواد مضافة مخصصة لهذه المرحلة (مرحلة تشغيل/خلط/تفريغ).",
                                                fontSize = 10.5.sp,
                                                color = Color.Gray,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    } else {
                                        // Materials Table within Phase
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                            color = Color.White,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column {
                                                // Table Sub-header
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFFF8FAFC))
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "المادة الخام",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF475569),
                                                        modifier = Modifier.weight(2f),
                                                        textAlign = TextAlign.Right
                                                    )
                                                    Text(
                                                        text = "نسبة الإضافة",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF475569),
                                                        modifier = Modifier.weight(1f),
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Text(
                                                        text = "الوزن المحسوب",
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF475569),
                                                        modifier = Modifier.weight(1.2f),
                                                        textAlign = TextAlign.Left
                                                    )
                                                }

                                                itemsInPhase.forEachIndexed { itemIdx, ri ->
                                                    val matchedAdjustment = adjustments.find { it.rawMaterialId == ri.rawMaterialId }
                                                    val isAdjusted = matchedAdjustment != null
                                                    val rowBg = if (isAdjusted) Color(0xFFFEF3C7) else (if (itemIdx % 2 == 0) Color.White else Color(0xFFFAFAFA))

                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(rowBg)
                                                            .then(
                                                                if (isAdjusted) Modifier.clickable { selectedAdjustmentForDetails = matchedAdjustment }
                                                                else Modifier
                                                            )
                                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.weight(2f),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(
                                                                text = ri.rawMaterialName,
                                                                fontSize = 11.5.sp,
                                                                fontWeight = if (isAdjusted) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (isAdjusted) GBRDarkIndigo else Color(0xFF1E293B),
                                                                textAlign = TextAlign.Right
                                                            )
                                                            if (isAdjusted) {
                                                                Surface(
                                                                    color = WarningOrange.copy(alpha = 0.15f),
                                                                    shape = RoundedCornerShape(4.dp)
                                                                ) {
                                                                    Text(
                                                                        text = "معدّلة ✏️",
                                                                        fontSize = 8.5.sp,
                                                                        color = WarningOrange,
                                                                        fontWeight = FontWeight.Bold,
                                                                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        val ratioText = if (ri.ratio < 0.999 || ri.ratio > 1.001) {
                                                            "${String.format(Locale.US, "%.0f", ri.ratio * 100)}%"
                                                        } else {
                                                            "100%"
                                                        }
                                                        Text(
                                                            text = ratioText,
                                                            fontSize = 10.5.sp,
                                                            color = Color(0xFF64748B),
                                                            modifier = Modifier.weight(1f),
                                                            textAlign = TextAlign.Center
                                                        )

                                                        Column(
                                                            modifier = Modifier.weight(1.2f),
                                                            horizontalAlignment = Alignment.Start
                                                        ) {
                                                            Text(
                                                                text = "${formatNum(ri.calculatedQuantity)} كجم",
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isAdjusted) WarningOrange else GBRPinkAccent,
                                                                textAlign = TextAlign.Left
                                                            )
                                                            if (isAdjusted && matchedAdjustment != null) {
                                                                val diffValue = matchedAdjustment.difference
                                                                val diffSign = if (diffValue >= 0) "+" else ""
                                                                Text(
                                                                    text = "الفرق: $diffSign${formatNum(diffValue)} كجم",
                                                                    fontSize = 8.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = if (diffValue >= 0) SuccessGreen else ErrorRed,
                                                                    textAlign = TextAlign.Left
                                                                )
                                                            }
                                                        }
                                                    }
                                                    if (itemIdx < itemsInPhase.size - 1) {
                                                        Divider(color = Color(0xFFE2E8F0).copy(alpha = 0.5f), thickness = 0.5.dp)
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
        }
    }
}

// ------------------------------------------
// HISTORICAL TAB 2: PRODUCTION REPORT
// ------------------------------------------
@Composable
fun HistoricalReportView(order: ProductionOrder) {
    val durationText = remember(order.startTime, order.endTime) {
        if (order.startTime == 0L || order.endTime == 0L) "غير مسجلة"
        else {
            val totalSec = ((order.endTime - order.startTime) / 1000).toInt()
            val hrs = totalSec / 3600
            val mins = (totalSec % 3600) / 60
            if (hrs > 0) "$hrs ساعة و $mins دقيقة" else "$mins دقيقة"
        }
    }
    val startStr = if (order.startTime == 0L) "غير مسجلة" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(order.startTime))
    val endStr = if (order.endTime == 0L) "غير مسجلة" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(order.endTime))

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, IndustrialBorder),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("تقرير تصنيع الدفعة الفعلي:", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 13.sp)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("المشغل المسؤول:", fontSize = 12.sp, color = Color.Gray)
                Text(order.operatorName, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
            }
            Divider(color = IndustrialBorder)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("وقت البدء الفعلي للدفعة:", fontSize = 12.sp, color = Color.Gray)
                Text(startStr, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
            }
            Divider(color = IndustrialBorder)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("وقت الانتهاء الفعلي:", fontSize = 12.sp, color = Color.Gray)
                Text(endStr, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
            }
            Divider(color = IndustrialBorder)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("مدة التنفيذ الإجمالية:", fontSize = 12.sp, color = Color.Gray)
                Text(durationText, fontWeight = FontWeight.Black, color = GBRPinkAccent, fontSize = 12.sp)
            }

            if (order.notes.isNotBlank()) {
                val notesParts = order.notes.split("\nملاحظات الإكمال:")
                val originalNotes = notesParts.getOrNull(0)?.trim() ?: ""

                if (originalNotes.isNotBlank()) {
                    Divider(color = IndustrialBorder)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("الملاحظات والتعليمات التشغيلية الأصلية:", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(originalNotes, color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------
// HISTORICAL TAB 3: PACKING RESULTS VIEW
// ------------------------------------------
@Composable
fun HistoricalPackingResultsView(
    order: ProductionOrder,
    items: List<ProductionOrderItem>
) {
    val packingDataList = remember(order.actualPackagingJson) {
        parsePackContents(order.actualPackagingJson)
    }

    val theoreticalWeight = remember(items) {
        items.sumOf { it.calculatedQuantity }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("نتائج وملخص التعبئة المعبأة للدفعة:", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 13.sp)

        if (packingDataList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .background(Color(0xFFFEF3C7))
                    .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text("⚠️ لا توجد أي بيانات تعبئة للمنتج مسجلة! يرجى التحقق.", color = WarningOrange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            var sumCount = 0
            var sumWeight = 0.0

            packingDataList.forEach { map ->
                val name = map["name"] ?: "عبوة"
                val net = map["netWeight"]?.toDoubleOrNull() ?: 18.0
                val countVal = map["actualCount"]?.toIntOrNull() ?: 0
                val totalNetWeight = net * countVal
                sumCount += countVal
                sumWeight += totalNetWeight

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, IndustrialBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(name, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 13.sp)
                            Text("الوزن الصافي للعبوة: $net كجم", fontSize = 11.sp, color = Color.Gray)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("$countVal عبوة كلي", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 13.sp)
                            Text("إجمالي الوزن: ${formatNum(totalNetWeight)} كجم", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD1FAE5)),
                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("إجماليات التعبئة الكلية المصنّعة:", fontWeight = FontWeight.Bold, color = SuccessGreen, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("إجمالي عدد العبوات الفعلي:", fontSize = 12.sp, color = GBRDarkIndigo)
                        Text("$sumCount عبوة كلي", fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("إجمالي وزن التعبئة الصافي الفعلي:", fontSize = 12.sp, color = GBRDarkIndigo)
                        Text("${formatNum(sumWeight)} كجم كلي", fontWeight = FontWeight.Black, color = Color(0xFF047857), fontSize = 14.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("إجمالي الوزن النظري:", fontSize = 12.sp, color = GBRDarkIndigo)
                        Text("${formatNum(theoreticalWeight)} كجم", fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }
                    
                    // Deviation section
                    val deviation = sumWeight - theoreticalWeight
                    val (deviationText, deviationColor) = when {
                        deviation > 0 -> Pair("+${formatNum(deviation)} كجم", Color(0xFF16A34A)) // Green for positive deviation
                        deviation < 0 -> Pair("-${formatNum(Math.abs(deviation))} كجم", ErrorRed) // Red for negative deviation
                        else -> Pair("0 كجم", Color.Gray)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الانحراف:", fontSize = 12.sp, color = GBRDarkIndigo, fontWeight = FontWeight.Bold)
                        Text(deviationText, fontWeight = FontWeight.Black, color = deviationColor, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------
// HISTORICAL TAB 4: COSTS ANALYSIS VIEW
// ------------------------------------------
@Composable
fun HistoricalCostsAnalysisView(
    order: ProductionOrder,
    items: List<ProductionOrderItem>,
    formulation: com.example.data.Formulation?,
    viewModel: GbrViewModel
) {
    val formatCostTwoDec = { value: Double ->
        try {
            if (value.isNaN() || value.isInfinite()) "0"
            else {
                val bd = java.math.BigDecimal(value.toString())
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                bd.toPlainString()
            }
        } catch (e: Exception) {
            try {
                val symbols = java.text.DecimalFormatSymbols(java.util.Locale.US)
                val df = java.text.DecimalFormat("#.##", symbols)
                df.format(value)
            } catch (ex: Exception) {
                value.toString()
            }
        }
    }

    // 2. Filter actual packaging list to only look at types actually used in the batch (actualCount > 0)
    val rawPackingDataList = remember(order.actualPackagingJson) {
        parsePackContents(order.actualPackagingJson)
    }
    val packingDataList = remember(rawPackingDataList) {
        rawPackingDataList.filter { (it["actualCount"]?.toDoubleOrNull() ?: 0.0) > 0.0 }
    }
    val snapshotPackList = remember(order.packagingSnapshotJson) {
        parsePackContents(order.packagingSnapshotJson)
    }

    val materialsCost = remember(items) {
        items.sumOf { it.calculatedQuantity * it.rawMaterialPrice }
    }
    val totalBatchWeight = remember(items) {
        items.sumOf { it.calculatedQuantity }
    }

    // Pre-calculate operating costs and packaging empty cost from actual packing data
    val packingCostsGroup = remember(packingDataList, snapshotPackList, formulation, totalBatchWeight) {
        var opSum = 0.0
        var emptySum = 0.0
        
        if (packingDataList.isNotEmpty()) {
            packingDataList.forEach { act ->
                val idStr = act["id"] ?: "1"
                val actualCount = act["actualCount"]?.toDoubleOrNull()?.toInt() ?: 0
                val netWeight = act["netWeight"]?.toDoubleOrNull() ?: 18.0
                
                val snap = snapshotPackList.find { it["id"] == idStr }
                var snapPrice = snap?.get("price")?.toDoubleOrNull() ?: run {
                    if (netWeight == 18.0) 15.00 else if (netWeight == 5.0) 5.50 else 2.2
                }
                if (snapPrice == 0.0) {
                    snapPrice = if (netWeight == 18.0) 15.00 else if (netWeight == 5.0) 5.50 else 2.2
                }
                
                var snapExtraCost = snap?.get("extraCost")?.toDoubleOrNull() ?: 0.0
                if (snapExtraCost == 0.0 && formulation != null) {
                    val formulaExtraCost = try {
                        val json = org.json.JSONObject(formulation.packagingWeightsJson)
                        val rawVal = json.optString(idStr, "")
                        val parts = rawVal.split(":")
                        parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    } catch(e: Exception) {
                        0.0
                    }
                    snapExtraCost = formulaExtraCost
                }
                
                opSum += snapExtraCost * actualCount
                emptySum += snapPrice * actualCount
            }
        } else {
            // Or if actual is empty, estimate operating cost from formulation/snapshot definitions
            val firstSnap = snapshotPackList.firstOrNull()
            if (firstSnap != null) {
                val idStr = firstSnap["id"] ?: "1"
                val netWeight = firstSnap["netWeight"]?.toDoubleOrNull() ?: 18.0
                var extraC = firstSnap["extraCost"]?.toDoubleOrNull() ?: 0.0
                if (extraC == 0.0 && formulation != null) {
                    extraC = try {
                        val json = org.json.JSONObject(formulation.packagingWeightsJson)
                        val rawVal = json.optString(idStr, "")
                        val parts = rawVal.split(":")
                        parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    } catch(e: Exception) {
                        0.0
                    }
                }
                val count = if (netWeight > 0.0) totalBatchWeight / netWeight else 0.0
                opSum = extraC * count
            }
        }
        Pair(opSum, emptySum)
    }

    val totalOperatingCosts = packingCostsGroup.first
    val totalPackagingEmptyCost = packingCostsGroup.second

    val totalPackedWeight = remember(packingDataList) {
        packingDataList.sumOf { (it["actualCount"]?.toDoubleOrNull() ?: 0.0) * (it["netWeight"]?.toDoubleOrNull() ?: 18.0) }
    }

    // Raw paint cost per kg (direct materials only)
    val costPerKg = remember(materialsCost, totalPackedWeight, totalBatchWeight) {
        val divisor = if (totalPackedWeight > 0.0) totalPackedWeight else totalBatchWeight
        if (divisor > 0.0) materialsCost / divisor else 0.0
    }

    // Paint cost per kg including operating/operational costs from the formulation
    val costPerKgWithOperating = remember(materialsCost, totalOperatingCosts, totalPackedWeight, totalBatchWeight) {
        val totalBasePaintCost = materialsCost + totalOperatingCosts
        val divisor = if (totalPackedWeight > 0.0) totalPackedWeight else totalBatchWeight
        if (divisor > 0.0) totalBasePaintCost / divisor else 0.0
    }

    val detailedPackingList = packingDataList.map { act ->
        val idStr = act["id"] ?: "1"
        val name = act["name"] ?: "سطل معياري 18 لتر"
        val actualCount = act["actualCount"]?.toDoubleOrNull()?.toInt() ?: 0
        val netWeight = act["netWeight"]?.toDoubleOrNull() ?: 18.0
        
        val snap = snapshotPackList.find { it["id"] == idStr }
        var snapPrice = snap?.get("price")?.toDoubleOrNull() ?: run {
            if (netWeight == 18.0) 15.00 else if (netWeight == 5.0) 5.50 else 2.2
        }
        if (snapPrice == 0.0) {
            snapPrice = if (netWeight == 18.0) 15.00 else if (netWeight == 5.0) 5.50 else 2.2
        }
        
        var snapExtraCost = snap?.get("extraCost")?.toDoubleOrNull() ?: 0.0
        if (snapExtraCost == 0.0 && formulation != null) {
            val formulaExtraCost = try {
                val json = org.json.JSONObject(formulation.packagingWeightsJson)
                val rawVal = json.optString(idStr, "")
                val parts = rawVal.split(":")
                parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            } catch(e: Exception) {
                0.0
            }
            snapExtraCost = formulaExtraCost
        }
        
        val itemPaintCostTotal = costPerKg * netWeight * actualCount
        val itemContainerCostTotal = snapPrice * actualCount
        val itemOperatingCostTotal = snapExtraCost * actualCount
        val totalTypeCost = itemPaintCostTotal + itemContainerCostTotal + itemOperatingCostTotal
        val unitFinalCost = (costPerKg * netWeight) + snapPrice + snapExtraCost
        
        mapOf(
            "name" to name,
            "actualCount" to actualCount.toString(),
            "netWeight" to netWeight.toString(),
            "snapPrice" to snapPrice.toString(),
            "snapExtraCost" to snapExtraCost.toString(),
            "itemPaintCostTotal" to itemPaintCostTotal.toString(),
            "itemContainerCostTotal" to itemContainerCostTotal.toString(),
            "itemOperatingCostTotal" to itemOperatingCostTotal.toString(),
            "totalTypeCost" to totalTypeCost.toString(),
            "unitFinalCost" to unitFinalCost.toString()
        )
    }

    val totalCost = materialsCost + totalPackagingEmptyCost + totalOperatingCosts

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Raw Materials Cost Table Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, IndustrialBorder),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "📋 تفصيل تكلفة المواد الخام للدفعة الكيميائية",
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("اسم المادة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(2f), textAlign = TextAlign.Right)
                    Text("الكمية المضافة (كجم)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                    Text("سعر الكيلو (شيكل)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                    Text("التكلفة (شيكل)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Left)
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (items.isEmpty()) {
                    Text(
                        text = "لا توجد تفاصيل مواد لهذه الدفعة.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    items.forEachIndexed { idx, item ->
                        val itemCost = item.calculatedQuantity * item.rawMaterialPrice
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.rawMaterialName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B), modifier = Modifier.weight(2f), textAlign = TextAlign.Right)
                            Text(formatCostTwoDec(item.calculatedQuantity), fontSize = 12.sp, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                            Text(formatCostTwoDec(item.rawMaterialPrice), fontSize = 12.sp, color = Color(0xFF475569), modifier = Modifier.weight(1.2f), textAlign = TextAlign.Center)
                            Text(
                                text = formatCostTwoDec(itemCost),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A),
                                modifier = Modifier.weight(1.2f),
                                textAlign = TextAlign.Left
                            )
                        }
                        if (idx < items.size - 1) {
                            Divider(color = Color(0xFFF1F5F9))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = IndustrialBorder)
                Spacer(modifier = Modifier.height(10.dp))

                // Materials Analysis Brief Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFBFDFA)),
                    border = BorderStroke(1.dp, Color(0xFFA7F3D0))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("إجمالي الوزن النظري:", fontSize = 11.5.sp, color = Color(0xFF334155))
                            Text("${formatCostTwoDec(totalBatchWeight)} كجم", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), fontSize = 11.5.sp, maxLines = 1)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("إجمالي الوزن العملي:", fontSize = 11.5.sp, color = Color(0xFF334155))
                            Text("${formatCostTwoDec(totalPackedWeight)} كجم", fontWeight = FontWeight.Bold, color = Color(0xFF0F172A), fontSize = 11.5.sp, maxLines = 1)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("إجمالي تكلفة المواد الخام المباشرة:", fontSize = 11.5.sp, color = Color(0xFF334155))
                            Text("${formatCostTwoDec(materialsCost)} شيكل", fontWeight = FontWeight.Black, color = Color(0xFF0F172A), fontSize = 11.5.sp, maxLines = 1)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("تكلفة الكيلو للمواد الخام (المباشرة):", fontSize = 11.sp, color = Color(0xFF334155), modifier = Modifier.weight(1f, fill = false))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${formatCostTwoDec(costPerKg)} شيكل/كجم",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A),
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("إجمالي التكاليف التشغيلية المستردة من التركيبة:", fontSize = 11.sp, color = Color(0xFF334155), modifier = Modifier.weight(1f, fill = false))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${formatCostTwoDec(totalOperatingCosts)} شيكل", fontWeight = FontWeight.Black, color = Color(0xFF0F172A), fontSize = 11.sp, maxLines = 1)
                        }
                        Divider(color = Color(0xFFA7F3D0).copy(alpha = 0.5f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("تكلفة الكيلو الصافي (شامل التشغيل):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857), modifier = Modifier.weight(1f, fill = false))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${formatCostTwoDec(costPerKgWithOperating)} شيكل/كجم",
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF047857),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Filled Packagings Cost & Operating Cost Breakdown
        Text(
            text = "📦 تكلفة العبوات المعبأة الفعلية وتفاصيل العبوات الفارغة",
            fontWeight = FontWeight.Bold,
            color = GBRDarkIndigo,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        if (detailedPackingList.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, IndustrialBorder),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "⚠️ لم يتم إدخال كميات تعبئة فعلية لهذه الدفعة.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            detailedPackingList.forEach { act ->
                val name = act["name"] ?: ""
                val actualCount = act["actualCount"]?.toIntOrNull() ?: 0
                val netWeight = act["netWeight"]?.toDoubleOrNull() ?: 18.0
                val totalTypeCost = act["totalTypeCost"]?.toDoubleOrNull() ?: 0.0
                val unitFinalCost = act["unitFinalCost"]?.toDoubleOrNull() ?: 0.0
                val snapPrice = act["snapPrice"]?.toDoubleOrNull() ?: 0.0
                val snapExtraCost = act["snapExtraCost"]?.toDoubleOrNull() ?: 0.0

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Name and Count Badge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo,
                                fontSize = 14.sp
                            )
                            Surface(
                                color = Color(0xFFEFF6FF), // Soft blue tint
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFDBEAFE))
                            ) {
                                Text(
                                    text = "العدد المنتج: $actualCount عبوة",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D4ED8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Divider(color = Color(0xFFF1F5F9))

                        // Box showing Empty Container Cost (تكلفة العبوة الفارغة المستوردة من التركيبة)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "العبوة الفارغة",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF475569),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatCostTwoDec(snapPrice)} شيكل",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1
                                )
                            }

                            if (snapExtraCost > 0.0) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "تكلفة التشغيل",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569),
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${formatCostTwoDec(snapExtraCost)} شيكل",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A),
                                        maxLines = 1
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "الدهان الصافي (${formatCostTwoDec(netWeight)} كجم)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF475569),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${formatCostTwoDec(costPerKg * netWeight)} شيكل",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1
                                )
                            }
                        }

                        // Large Highlighted Container Unit Cost (Beautiful Mint Styled Box)
                        val paintCost = costPerKg * netWeight
                        val valPaintFormatted = formatCostTwoDec(paintCost)
                        val valSnapPriceFormatted = formatCostTwoDec(snapPrice)
                        val valSnapExtraFormatted = formatCostTwoDec(snapExtraCost)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFECFDF5), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFFA7F3D0), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "تكلفة العبوة النهائية",
                                fontSize = 12.sp,
                                color = Color(0xFF047857),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${formatCostTwoDec(unitFinalCost)} شيكل / عبوة",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF059669) // Highlight Green
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "(تكلفة الدهان+عبوة فارغة+م.تشغيل)",
                                fontSize = 11.sp,
                                color = Color(0xFF065F46),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "$valPaintFormatted + $valSnapPriceFormatted + $valSnapExtraFormatted",
                                fontSize = 10.sp,
                                color = Color(0xFF047857),
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Total cost for this container type
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "التكلفة الإجمالية لهذه العبوة:",
                                fontSize = 11.sp,
                                color = Color(0xFF475569)
                            )
                            Text(
                                text = "${formatCostTwoDec(totalTypeCost)} شيكل",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                        }
                    }
                }
            }
        }

        // Section 3: Completed Batch Grand Costs Summary Map  Grand Totals
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.5.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "📊 الخلاصة المالية النهائية للدفعة المعبأة:",
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    fontSize = 14.sp
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي تكلفة المواد الخام المستخدمة:", fontSize = 12.sp, color = Color(0xFF475569))
                    Text("${formatCostTwoDec(materialsCost)} شيكل", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي تكلفة العبوات الفارغة المستخدمة:", fontSize = 12.sp, color = Color(0xFF475569))
                    Text("${formatCostTwoDec(totalPackagingEmptyCost)} شيكل", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إجمالي التكاليف التشغيلية المخصصة (المصنّعة):", fontSize = 12.sp, color = Color(0xFF475569))
                    Text("${formatCostTwoDec(totalOperatingCosts)} شيكل", fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                }

                Divider(color = Color(0xFFE2E8F0), thickness = 1.2.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "التكلفة الإجمالية المطلقة للدفعة بالكامل (مواد + تعبئة):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${formatCostTwoDec(totalCost)} شيكل",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF047857) // Bright Green
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ------------------------------------------
// HISTORICAL TAB 5: QUALITY CONTROL PANEL
// ------------------------------------------
@Composable
fun HistoricalQualityControlPanel(
    order: ProductionOrder, 
    viewModel: GbrViewModel, 
    onBack: () -> Unit,
    showReportViewExternal: Boolean? = null,
    onShowReportViewExternalChange: ((Boolean) -> Unit)? = null
) {
    val labSessions by viewModel.labSessions.collectAsState()
    val allAttachments by viewModel.allLabAttachments.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()

    val todayStr = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    }

    // Filter sessions associated with this order
    val orderSessions = remember(labSessions, order.id) {
        labSessions.filter { it.sampleProperties.startsWith("ORDER_ID:${order.id}") }
    }

    // Separate into Production Quality Tests and Quality Control (Monitoring)
    val productionSessions = remember(orderSessions) {
        orderSessions.filter { it.sampleProperties == "ORDER_ID:${order.id}" }
    }
    val qcSessions = remember(orderSessions) {
        orderSessions.filter { it.sampleProperties == "ORDER_ID:${order.id}:QC" }
    }

    // Maintain selected session to open in detail view
    var selectedSessionState by remember { mutableStateOf<LabSession?>(null) }
    var showHistoricalAnalysis by remember { mutableStateOf(false) }
    var isHistoricalAnalysisCurrentOrderOnly by remember { mutableStateOf(false) }
    var showAnalysisTypeDialog by remember { mutableStateOf(false) }

    // Handlers for dialog
    var showAddOptionsDialog by remember { mutableStateOf(false) }

    // Back handler
    BackHandler(enabled = selectedSessionState != null || showHistoricalAnalysis) {
        if (selectedSessionState != null) {
            selectedSessionState = null
        } else if (showHistoricalAnalysis) {
            showHistoricalAnalysis = false
        }
    }

    if (showHistoricalAnalysis) {
        ProductQualityHistoricalAnalysisView(
            order = order,
            viewModel = viewModel,
            isCurrentOrderOnly = isHistoricalAnalysisCurrentOrderOnly,
            onBack = { showHistoricalAnalysis = false },
            onNavigateToSession = { session ->
                showHistoricalAnalysis = false
                selectedSessionState = session
            }
        )
    } else if (selectedSessionState != null) {
        // Ensure we find the up-to-date version of the selected session in the list
        val currentSelectedSession = labSessions.find { it.id == selectedSessionState!!.id }
        if (currentSelectedSession != null) {
            SessionDetailsView(
                session = currentSelectedSession,
                viewModel = viewModel,
                onBack = { selectedSessionState = null },
                onDelete = {
                    viewModel.deleteLabSession(currentSelectedSession)
                    selectedSessionState = null
                },
                showReportViewExternal = showReportViewExternal,
                onShowReportViewExternalChange = onShowReportViewExternalChange,
                isEmbedded = true
            )
        } else {
            selectedSessionState = null
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card with info about quality tests
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F3FF)), // Very light purple
                border = BorderStroke(1.dp, Color(0xFFDDD6FE))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = Color(0xFF7C3AED), // Purple
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "تحليل جميع دفعات المنتج السابقة ومقارنة نتائج الفحوصات.",
                            fontSize = 11.sp,
                            color = Color(0xFF4C1D95), // Dark purple
                            fontWeight = FontWeight.Medium,
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { showAnalysisTypeDialog = true },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("📈 تحليل الجودة التاريخية", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            // Header Row with Add Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "جلسات فحص الدفعة 🧪",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo
                    )
                    Text(
                        text = "الفحوصات الخاصة بالدفعة #${order.batchNumber}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Button(
                    onClick = { showAddOptionsDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إضافة فحص", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // SECTION 1: فحوصات جودة الإنتاج
            Text(
                text = "🛡️ فحوصات جودة الإنتاج",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = GBRDarkIndigo,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (productionSessions.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Text(
                        text = "لا توجد فحوصات جودة إنتاج مسجلة حالياً.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    productionSessions.forEach { item ->
                        val sTests = remember(allLabTests, item.id) {
                            allLabTests.filter { it.sessionId == item.id }
                        }
                        val completedCount = sTests.count { it.status == "مكتمل" || it.status == "خارج المواصفة" }
                        val totalCount = sTests.size
                        val hasAttachments = remember(allAttachments, item.id) {
                            allAttachments.any { it.sessionId == item.id }
                        }

                        SessionListItem(
                            item = item,
                            completedCount = completedCount,
                            totalCount = totalCount,
                            hasAttachments = hasAttachments,
                            lang = "ar",
                            onClick = { selectedSessionState = item },
                            onEditClick = {},
                            onDeleteClick = { viewModel.deleteLabSession(item) },
                            onCloneClick = {}
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // SECTION 2: مراقبة الجودة
            Text(
                text = "🧪 مراقبة الجودة (متابعة بعد الإنتاج)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8B5CF6), // Purple color
                modifier = Modifier.padding(bottom = 4.dp)
            )

            if (qcSessions.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Text(
                        text = "لا توجد فحوصات مراقبة جودة مسجلة حالياً.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    qcSessions.forEach { item ->
                        val sTests = remember(allLabTests, item.id) {
                            allLabTests.filter { it.sessionId == item.id }
                        }
                        val completedCount = sTests.count { it.status == "مكتمل" || it.status == "خارج المواصفة" }
                        val totalCount = sTests.size
                        val hasAttachments = remember(allAttachments, item.id) {
                            allAttachments.any { it.sessionId == item.id }
                        }

                        SessionListItem(
                            item = item,
                            completedCount = completedCount,
                            totalCount = totalCount,
                            hasAttachments = hasAttachments,
                            lang = "ar",
                            onClick = { selectedSessionState = item },
                            onEditClick = {},
                            onDeleteClick = { viewModel.deleteLabSession(item) },
                            onCloneClick = {}
                        )
                    }
                }
            }
        }
    }

    // Dialog for choosing historical quality analysis type
    if (showAnalysisTypeDialog) {
        AlertDialog(
            onDismissRequest = { showAnalysisTypeDialog = false },
            title = {
                Text(
                    text = "تحليل جودة الإنتاج 📊",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "يرجى اختيار نوع التحليل المطلوب لعرض نتائج الفحوصات:",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Option 1: مقارنة مع جميع أوامر الإنتاج (الوضع الحالي)
                    Card(
                        onClick = {
                            showAnalysisTypeDialog = false
                            isHistoricalAnalysisCurrentOrderOnly = false
                            showHistoricalAnalysis = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF7C3AED).copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    tint = Color(0xFF7C3AED),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "مقارنة مع جميع أوامر الإنتاج",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "مقارنة نتائج الفحص الحالي مع جميع الدفعات المؤرشفة لنفس المنتج والتركيبة لقياس الاستقرار التاريخي.",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth(),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    // Option 2: تحليل أمر الإنتاج الحالي فقط (التطوير الجديد)
                    Card(
                        onClick = {
                            showAnalysisTypeDialog = false
                            isHistoricalAnalysisCurrentOrderOnly = true
                            showHistoricalAnalysis = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(SuccessGreen.copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TrendingUp,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "تحليل أمر الإنتاج الحالي",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "متابعة تطور نتائج الفحوصات داخل هذه الدفعة فقط، بدءاً من مراقبة الإنتاج وحتى آخر جلسة متابعة جودة.",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth(),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAnalysisTypeDialog = false }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Dialog for choosing test type
    if (showAddOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showAddOptionsDialog = false },
            title = {
                Text(
                    text = "إضافة فحص جديد للدفعة 🧪",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "يرجى تحديد تصنيف الجلسة الجديدة:",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Option 1: فحص جودة الإنتاج
                    val hasProductionSession = productionSessions.isNotEmpty()
                    Card(
                        onClick = {
                            if (!hasProductionSession) {
                                showAddOptionsDialog = false
                                viewModel.addLabSession(
                                    testName = "فحص جودة: ${order.formulationName}",
                                    testDate = todayStr,
                                    technicianName = order.operatorName.ifBlank { "مشرف المختبر" },
                                    sampleOrProduct = "دفعة الإنتاج #${order.batchNumber}",
                                    category = "فحص جودة الإنتاج",
                                    testType = "🧪 فحص أحادي",
                                    notes = "جلسة فحص جودة مرتبطة بأمر الإنتاج رقم ${order.orderNumber} لمنتج ${order.formulationName}.",
                                    comparisonType = null,
                                    partyA = null,
                                    partyB = null,
                                    sampleProperties = "ORDER_ID:${order.id}"
                                )
                            }
                        },
                        enabled = !hasProductionSession,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (hasProductionSession) Color(0xFFF8FAFC) else Color(0xFFF1F5F9),
                            disabledContainerColor = Color(0xFFF8FAFC)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (hasProductionSession) Color(0xFFE2E8F0).copy(alpha = 0.5f) else Color(0xFFE2E8F0)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (hasProductionSession) Color.Gray.copy(alpha = 0.1f) else GBRBlueMain.copy(alpha = 0.1f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    tint = if (hasProductionSession) Color.Gray else GBRBlueMain,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "فحص جودة الإنتاج",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasProductionSession) Color.Gray else GBRDarkIndigo,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "فحص اعتماد جودة الإنتاج الموحد للدفعة.",
                                    fontSize = 10.sp,
                                    color = if (hasProductionSession) Color.LightGray else Color.Gray,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (hasProductionSession) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "⚠️ تم إضافة جلسة اعتماد جودة الإنتاج لهذه الدفعة مسبقاً (جلسة واحدة كحد أقصى).",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFDC2626),
                                        textAlign = TextAlign.Right,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    // Option 2: مراقبة الجودة
                    Card(
                        onClick = {
                            showAddOptionsDialog = false
                            viewModel.addLabSession(
                                testName = "متابعة جودة: ${order.formulationName}",
                                testDate = todayStr,
                                technicianName = order.operatorName.ifBlank { "مشرف المختبر" },
                                sampleOrProduct = "متابعة جودة #${order.batchNumber}",
                                category = "مراقبة جودة",
                                testType = "🧪 فحص أحادي",
                                notes = "جلسة متابعة ومراقبة جودة للمنتج بعد الإنتاج رقم ${order.orderNumber} لمنتج ${order.formulationName}.",
                                comparisonType = null,
                                partyA = null,
                                partyB = null,
                                sampleProperties = "ORDER_ID:${order.id}:QC"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF8B5CF6).copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = Color(0xFF8B5CF6),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "مراقبة الجودة",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF8B5CF6),
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "متابعة وفحص جودة الدفعة والمنتج بعد الإنتاج.",
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddOptionsDialog = false }) {
                    Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ------------------------------------------
// HISTORICAL TAB 6: AUDIT TIMELINE LOGS
// ------------------------------------------
private data class CleanedEvent(
    val id: String,
    val title: String,
    val description: String,
    val timestamp: Long
)

@Composable
fun HistoricalAuditLogsView(events: List<ProductionOrderEvent>) {
    val cleanedEvents = remember(events) {
        events.mapNotNull { ev ->
            val eventName = ev.eventName
            val desc = ev.description

            val isCreation = eventName == "إنشاء أمر الإنتاج" || eventName.contains("إنشاء")
            
            val isStartPackaging = eventName == "بدء التعبئة" || desc.contains("بدء التعبئة") || eventName.contains("بدء التعبئة")

            val isMaterialAddition = eventName == "إضافة مادة خام" || 
                                     eventName == "تغذية عنصر خط صالة" || 
                                     desc.contains("إضافة مادة") || 
                                     desc.contains("تفريغ مادة") ||
                                     desc.contains("إضافة المادة")
                                     
            val isPackaging = (eventName == "إكمال التنفيذ" || 
                              eventName.contains("التعبئة") || 
                              desc.contains("تعبئة") || 
                              desc.contains("العبوات")) && !isStartPackaging

            if (isCreation) {
                val cleanDesc = desc
                    .replace(Regex("(?i)بواسطة\\s+\\S+"), "")
                    .replace("المشرف", "")
                    .replace("صالة الإنتاج", "")
                    .replace("قام العامل", "")
                    .replace("الإنتاج", "")
                    .replace(":", "")
                    .trim()
                CleanedEvent(
                    id = ev.id,
                    title = "ساعة البدء في إنشاء أمر الإنتاج",
                    description = cleanDesc,
                    timestamp = ev.timestamp
                )
            } else if (isMaterialAddition) {
                var matName = ""
                if (desc.contains("مادة ") && desc.contains(" بوزن")) {
                    val startIdx = desc.indexOf("مادة ") + 5
                    val endIdx = desc.indexOf(" بوزن")
                    if (endIdx > startIdx) {
                        matName = desc.substring(startIdx, endIdx).trim()
                    }
                } else if (desc.contains("المادة ") && desc.contains(" بوزن")) {
                    val startIdx = desc.indexOf("المادة ") + 7
                    val endIdx = desc.indexOf(" بوزن")
                    if (endIdx > startIdx) {
                        matName = desc.substring(startIdx, endIdx).trim()
                    }
                }
                
                if (matName.isBlank()) {
                    val regex = Regex("(?:مادة|المادة)\\s+([^\\s]+(?:\\s+[^\\s]+)?)")
                    val match = regex.find(desc)
                    matName = match?.groupValues?.get(1) ?: "خام"
                }
                
                val cleanDesc = desc
                    .replace("صالة الإنتاج:", "")
                    .replace("صالة الإنتاج", "")
                    .replace("المشرف:", "")
                    .replace("المشرف", "")
                    .replace("قام العامل بتأكيد تفريغ مادة", "تم تفريغ")
                    .replace("قام العامل بتأكيد تفريغ", "تم تفريغ")
                    .replace("قام العامل بتأكيد إضافة مادة", "تم إضافة")
                    .replace("تم تأكيد إضافة مادة", "تم إضافة")
                    .replace("تم تأكيد تفريغ مادة", "تم تفريغ")
                    .replace("تم تأكيد إضافة", "تم إضافة")
                    .replace("بواسطة المشرف", "")
                    .replace("بواسطة صالة الإنتاج", "")
                    .trim()

                CleanedEvent(
                    id = ev.id,
                    title = "إضافة المادة: $matName",
                    description = cleanDesc,
                    timestamp = ev.timestamp
                )
            } else if (isStartPackaging) {
                CleanedEvent(
                    id = ev.id,
                    title = "بدء التعبئة والتغليف",
                    description = "تم البدء بعملية التعبئة والتغليف وحساب المدة الزمنية الفعلية.",
                    timestamp = ev.timestamp
                )
            } else if (isPackaging) {
                CleanedEvent(
                    id = ev.id,
                    title = "ساعة إضافة عدد العبوات",
                    description = "تم إدخال وتعبئة كميات العبوات الفعلية للدفعة بنجاح.",
                    timestamp = ev.timestamp
                )
            } else {
                null
            }
        }
    }

    val chronologicalEvents = remember(cleanedEvents) {
        cleanedEvents.sortedBy { it.timestamp }
    }

    val formatDurationArabic = remember {
        { diffMs: Long ->
            val diffSecs = diffMs / 1000
            if (diffSecs < 60) {
                "أقل من دقيقة"
            } else {
                val diffMins = diffSecs / 60
                if (diffMins < 60) {
                    when (diffMins) {
                        1L -> "دقيقة واحدة"
                        2L -> "دقيقتان"
                        in 3L..10L -> "$diffMins دقائق"
                        else -> "$diffMins دقيقة"
                    }
                } else {
                    val diffHours = diffMins / 60
                    val remainingMins = diffMins % 60
                    if (diffHours < 24) {
                        val hoursStr = when (diffHours) {
                            1L -> "ساعة"
                            2L -> "ساعتان"
                            in 3L..10L -> "$diffHours ساعات"
                            else -> "$diffHours ساعة"
                        }
                        val minsStr = when (remainingMins) {
                            0L -> ""
                            1L -> "ودقيقة"
                            2L -> "ودقيقتان"
                            in 3L..10L -> "و $remainingMins دقائق"
                            else -> "و $remainingMins دقيقة"
                        }
                        if (minsStr.isEmpty()) hoursStr else "$hoursStr $minsStr"
                    } else {
                        val diffDays = diffHours / 24
                        val remainingHours = diffHours % 24
                        val daysStr = when (diffDays) {
                            1L -> "يوم"
                            2L -> "يومان"
                            in 3L..10L -> "$diffDays أيام"
                            else -> "$diffDays يوم"
                        }
                        val hoursStr = when (remainingHours) {
                            0L -> ""
                            1L -> "وساعة"
                            2L -> "وساعتان"
                            in 3L..10L -> "و $remainingHours ساعات"
                            else -> "و $remainingHours ساعة"
                        }
                        if (hoursStr.isEmpty()) daysStr else "$daysStr $hoursStr"
                    }
                }
            }
        }
    }

    if (cleanedEvents.isEmpty()) {
        EmptyBox(message = "لا توجد أي سجلات أحداث محفوظة للدفعة.", subMessage = "تظهر هنا الخطوات المحددة للتنفيذ.")
    } else {
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(cleanedEvents, key = { it.id }) { ev ->
                val timeStr = try {
                    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.timestamp))
                } catch (e: Exception) {
                    ""
                }
                val dateStr = try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ev.timestamp))
                } catch (e: Exception) {
                    ""
                }
                val chronologicalIndex = chronologicalEvents.indexOfFirst { it.id == ev.id }
                val timeDiffStr = if (chronologicalIndex > 0) {
                    val prevEvent = chronologicalEvents[chronologicalIndex - 1]
                    val diffMs = ev.timestamp - prevEvent.timestamp
                    if (diffMs >= 0) {
                        formatDurationArabic(diffMs)
                    } else ""
                } else ""

                val accentColor = remember(ev.title) {
                    when {
                        ev.title.contains("إنشاء") -> Color(0xFF10B981) // SuccessGreen
                        ev.title.contains("إضافة") || ev.title.contains("المادة") -> GBRBlueMain
                        ev.title.contains("بدء") -> Color(0xFF8B5CF6) // Purple
                        ev.title.contains("العبوات") || ev.title.contains("تعبئة") -> Color(0xFF0D9488) // Teal
                        else -> GBRBlueMain
                    }
                }
                val cardBg = remember(ev.title) {
                    when {
                        ev.title.contains("إنشاء") -> Color(0xFFF0FDF4)
                        ev.title.contains("إضافة") || ev.title.contains("المادة") -> Color(0xFFEFF6FF)
                        ev.title.contains("بدء") -> Color(0xFFF5F3FF)
                        ev.title.contains("العبوات") || ev.title.contains("تعبئة") -> Color(0xFFF0FDFA)
                        else -> Color.White
                    }
                }
                val borderColor = remember(ev.title) {
                    when {
                        ev.title.contains("إنشاء") -> Color(0xFFDCFCE7)
                        ev.title.contains("إضافة") || ev.title.contains("المادة") -> Color(0xFFDBEAFE)
                        ev.title.contains("بدء") -> Color(0xFFEDE9FE)
                        ev.title.contains("العبوات") || ev.title.contains("تعبئة") -> Color(0xFFCCFBF1)
                        else -> IndustrialBorder
                    }
                }
                val icon = remember(ev.title) {
                    when {
                        ev.title.contains("إنشاء") -> Icons.Default.AddCircle
                        ev.title.contains("إضافة") || ev.title.contains("المادة") -> Icons.Default.Info
                        ev.title.contains("بدء") -> Icons.Default.PlayArrow
                        ev.title.contains("العبوات") || ev.title.contains("تعبئة") -> Icons.Default.CheckCircle
                        else -> Icons.Default.Info
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Row(
                        modifier = Modifier.height(IntrinsicSize.Min)
                    ) {
                        // Color-coded side accent bar
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(3.dp)
                                .background(accentColor)
                        )
                        
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
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
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = ev.title,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo,
                                        fontSize = 11.sp
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Text(
                                        text = timeStr,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRPinkAccent
                                    )
                                    if (timeDiffStr.isNotBlank()) {
                                        Text(
                                            text = "($timeDiffStr)",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRBlueMain
                                        )
                                    }
                                }
                            }
                            if (ev.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = com.example.formatDecimalsInText(ev.description),
                                    fontSize = 10.sp,
                                    color = Color.DarkGray,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                // Results Card at the bottom of the execution log
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = GBRBlueMain.copy(alpha = 0.05f)),
                    border = BorderStroke(1.5.dp, GBRBlueMain.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = GBRBlueMain,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "نتائج الفترات الزمنية للتشغيل والإنتاج:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = GBRDarkIndigo
                            )
                        }

                        Divider(color = GBRBlueMain.copy(alpha = 0.1f), thickness = 1.dp)

                        // 1. Production Duration (First material to last material/start packaging)
                        val firstMaterialEvent = chronologicalEvents.find { it.title.contains("إضافة المادة") }
                        val lastMaterialEvent = chronologicalEvents.findLast { it.title.contains("إضافة المادة") }
                        val startPkgEvent = chronologicalEvents.find { it.title.contains("بدء التعبئة") || it.description.contains("بدء التعبئة") }
                        val completionEvent = chronologicalEvents.find { it.title.contains("العبوات") || it.title.contains("إكمال") }

                        if (firstMaterialEvent != null) {
                            val prodEndTime = lastMaterialEvent?.timestamp 
                                ?: startPkgEvent?.timestamp 
                                ?: completionEvent?.timestamp 
                                ?: System.currentTimeMillis()
                            val prodDurationMs = prodEndTime - firstMaterialEvent.timestamp
                            if (prodDurationMs >= 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatDurationArabic(prodDurationMs),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = GBRBlueMain
                                    )
                                    Text(
                                        text = "⏱️ مدة خلط وإنتاج المواد:",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "غير متوفر (لم يتم بدء إضافة المواد)",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "⏱️ مدة خلط وإنتاج المواد:",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        // 2. Packaging Duration (Start Packaging to Completion)
                        val pkgStartTime = startPkgEvent?.timestamp 
                            ?: lastMaterialEvent?.timestamp
                        val pkgEndTime = completionEvent?.timestamp

                        if (pkgStartTime != null && pkgEndTime != null) {
                            val pkgDurationMs = pkgEndTime - pkgStartTime
                            if (pkgDurationMs >= 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatDurationArabic(pkgDurationMs),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = GBRPinkAccent
                                    )
                                    Text(
                                        text = "📦 مدة التعبئة والتغليف:",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "قيد الانتظار أو غير مسجل",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "📦 مدة التعبئة والتغليف:",
                                    fontSize = 12.sp,
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


// ==========================================
// RUNTIME VIEW: ACTIVE EXECUTING COMPOSABLE
// ==========================================
@Composable
fun ActiveExecutionView(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val isOperator = currentUser?.role == "عامل صالة الإنتاج"

    val pendingGrindStepKey by viewModel.pendingGrindStepKey.collectAsState()
    val hasPendingGrind = !pendingGrindStepKey.isNullOrEmpty()

    var playModeSelected by remember(isOperator, hasPendingGrind) { mutableStateOf(isOperator || hasPendingGrind) } // true if user chose Mode
    var playModeChoice by remember(isOperator, hasPendingGrind) { mutableIntStateOf(if (isOperator || hasPendingGrind) 1 else 0) } // 0: Manager (وضع المدير), 1: Shop Floor (وضع صالة الإنتاج)

    LaunchedEffect(hasPendingGrind, order.id) {
        if (hasPendingGrind) {
            viewModel.startProductionExecution(order.id)
        }
    }

    val orderPhases by viewModel.selectedProductionOrderPhases.collectAsState()
    val orderRecipeItems by viewModel.selectedProductionOrderRecipeItems.collectAsState()
    val orderItems by viewModel.selectedProductionOrderItems.collectAsState()

    var isLoadedTimeout by remember(order.id) { 
        mutableStateOf(orderPhases.isNotEmpty() || orderRecipeItems.isNotEmpty() || orderItems.isNotEmpty())
    }

    // Query active items and manage safety fallback timeout
    LaunchedEffect(order.id) {
        if (viewModel.selectedProductionOrder.value?.id != order.id) {
            viewModel.selectedProductionOrder.value = order
        }
        if (orderPhases.isEmpty() && orderRecipeItems.isEmpty() && orderItems.isEmpty()) {
            kotlinx.coroutines.delay(1200) // 1.2 second standard database query response timeout
        }
        isLoadedTimeout = true
    }

    val isStillLoading = !isLoadedTimeout && (orderPhases.isEmpty() && orderRecipeItems.isEmpty() && orderItems.isEmpty())

    if (isStillLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IndustrialGrayBg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = GBRBlueMain)
                Text("جاري تحميل بيانات خط الإنتاج والمواد...", fontSize = 14.sp, color = GBRDarkIndigo, fontWeight = FontWeight.Bold)
            }
        }
    } else if (!playModeSelected) {
        // Mode Selection Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(IndustrialGrayBg)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.95f),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("اختر وضع تشغيل أمر الإنتاج", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    Text("يُرجى اختيار واجهة العمل المناسبة للدخول إلى محطة العمل والمتابعة:", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)

                    Divider(color = IndustrialBorder)

                    // Manager Option Button
                    Button(
                        onClick = {
                            viewModel.startProductionExecution(order.id)
                            playModeChoice = 0
                            playModeSelected = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("1. وضع المشرف / المدير (عرض كامل ومرحلي)", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    // Worker Option Button
                    Button(
                        onClick = {
                            viewModel.startProductionExecution(order.id)
                            playModeChoice = 1
                            playModeSelected = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("2. وضع صالة الإنتاج والعامل (تدفق مكبر تفاعلي)", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Divider(color = IndustrialBorder)

                    TextButton(onClick = onClose) {
                        Text("عودة للخلف", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } else {
        // Inside actual executing run
        // Keep screen on during active production order execution (إبقاء الشاشة مضيئة طوال العمل)
        val currentView = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(currentView) {
            currentView.keepScreenOn = true
            onDispose {
                currentView.keepScreenOn = false
            }
        }

        var showNotesOnOpenDialog by remember(order.id) {
            val notesParts = order.notes.split("\nملاحظات الإكمال:")
            val originalNotes = notesParts.getOrNull(0)?.trim() ?: ""
            val finalNotes = if (notesParts.size > 1) notesParts[1].trim() else ""
            mutableStateOf(originalNotes.isNotBlank() || finalNotes.isNotBlank())
        }

        if (showNotesOnOpenDialog) {
            val notesParts = remember(order.notes) { order.notes.split("\nملاحظات الإكمال:") }
            val originalNotes = remember(notesParts) { notesParts.getOrNull(0)?.trim() ?: "" }
            val finalNotes = remember(notesParts) { if (notesParts.size > 1) notesParts[1].trim() else "" }

            AlertDialog(
                onDismissRequest = { showNotesOnOpenDialog = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📋", fontSize = 24.sp)
                        Text(
                            text = "ملاحظات وتعليمات التشغيل للدفعة",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = GBRDarkIndigo
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "يرجى مراجعة التنبيهات والتعليمات الفنية الخاصة بخلط هذه التركيبة الكيميائية قبل البدء في إضافة وتجهيز المواد:",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            lineHeight = 18.sp
                        )

                        if (originalNotes.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), // Soft blue
                                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "📝 الملاحظات والتعليمات التشغيلية الأصلية:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = GBRBlueMain
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = originalNotes,
                                        fontSize = 12.sp,
                                        color = Color.DarkGray,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        if (finalNotes.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)), // Soft green
                                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "🏁 الملاحظات الختامية وإنحرافات التشغيل:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = SuccessGreen
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = finalNotes,
                                        fontSize = 12.sp,
                                        color = Color.DarkGray,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showNotesOnOpenDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("فهمت، البدء في تشغيل المواد ✔️", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(0.95f)
            )
        }

        if (playModeChoice == 0) {
            // RENDER ACTIVE MANAGER RUN INTERFACE
            ActiveManagerRunInterface(order, viewModel, orderItems, orderPhases, orderRecipeItems, onClose = { playModeSelected = false })
        } else {
            // RENDER ACTIVE SHOP FLOOR RUN INTERFACE
            ActiveShopFloorRunInterface(
                order, 
                viewModel, 
                orderItems, 
                orderPhases, 
                orderRecipeItems, 
                onClose = { 
                    if (isOperator) {
                        onClose()
                    } else {
                        playModeSelected = false 
                    }
                }
            )
        }
    }
}

// Helper data class for safety undo validation
data class UndoItemInfo(
    val key: String,
    val name: String,
    val quantity: String,
    val isPhaseStep: Boolean
)

// Reusable Production Batch Reference Dialog (Eye Icon Dialog)
@Composable
fun ProductionBatchReferenceDialog(
    order: ProductionOrder,
    phases: List<com.example.data.ProductionOrderPhase>,
    recipeItems: List<com.example.data.ProductionOrderRecipeItem>,
    rawMaterialsList: List<com.example.data.RawMaterial>,
    completedItemsSet: Set<String>,
    orderEvents: List<ProductionOrderEvent>,
    onDismiss: () -> Unit
) {
    val flattenedInstructions = remember(phases, recipeItems, rawMaterialsList) {
        val list = mutableListOf<ShopFloorStep>()
        phases.forEachIndexed { pIdx, phase ->
            val phaseItems = recipeItems.filter { it.productionOrderPhaseId == phase.id }
            if (phaseItems.isEmpty()) {
                list.add(
                    ShopFloorStep(
                        phaseIndex = pIdx,
                        phaseName = phase.name,
                        title = "تشغيل الخلاط والموقت للتجانس",
                        quantity = 0.0,
                        instruction = phase.instructions,
                        mixerRpm = phase.mixerRpm,
                        durationMinutes = phase.durationMinutes,
                        materialId = "",
                        isPhaseCompletionStep = true
                    )
                )
            } else {
                phaseItems.forEachIndexed { riIdx, rItem ->
                    val rm = rawMaterialsList.find { it.id == rItem.rawMaterialId }
                    val shownTitle = if (rm != null) {
                        getTradeName(rm.name, rm.productionName)
                    } else {
                        getTradeName(rItem.rawMaterialName, null)
                    }
                    list.add(
                        ShopFloorStep(
                            phaseIndex = pIdx,
                            phaseName = phase.name,
                            title = shownTitle,
                            quantity = rItem.calculatedQuantity,
                            instruction = phase.instructions,
                            mixerRpm = phase.mixerRpm,
                            durationMinutes = if (riIdx == 0) phase.durationMinutes else 0,
                            materialId = rItem.rawMaterialId,
                            isPhaseCompletionStep = false,
                            recipeItemId = rItem.id
                        )
                    )
                }
            }
        }
        list
    }

    val stepAdditionTimeMap = remember(flattenedInstructions, completedItemsSet, orderEvents, rawMaterialsList) {
        val timeMap = mutableMapOf<Int, String>()
        val usedEventIds = mutableSetOf<String>()
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        val materialEvents = orderEvents.filter { ev ->
            ev.eventName == "تغذية عنصر خط صالة" ||
            ev.eventName == "إضافة مادة خام" ||
            ev.eventName == "إكمال خطوة تشغيلية"
        }.sortedBy { it.timestamp }

        flattenedInstructions.forEachIndexed { sIdx, step ->
            val isCompleted = if (step.recipeItemId.isNotBlank()) {
                completedItemsSet.contains("p${step.phaseIndex}_ri${step.recipeItemId}") || completedItemsSet.contains("p${step.phaseIndex}_it${step.materialId}")
            } else {
                completedItemsSet.contains("p${step.phaseIndex}_it${step.materialId}")
            }

            if (isCompleted && !step.isPhaseCompletionStep) {
                val rm = rawMaterialsList.find { it.id == step.materialId }
                val rmName = rm?.name ?: ""
                val prodName = rm?.productionName ?: ""
                val stepTitle = step.title
                val firstWord = stepTitle.split(" ").firstOrNull { it.isNotBlank() } ?: ""
                val formattedQty = formatNum(step.quantity)

                // 1. First attempt: Match unused event with name AND quantity
                var matchedEvent = materialEvents.firstOrNull { ev ->
                    val evId = ev.id.ifBlank { ev.timestamp.toString() }
                    if (usedEventIds.contains(evId)) return@firstOrNull false
                    val desc = ev.description
                    val nameMatch = (stepTitle.isNotBlank() && desc.contains(stepTitle)) ||
                            (rmName.isNotBlank() && desc.contains(rmName)) ||
                            (prodName.isNotBlank() && desc.contains(prodName)) ||
                            (firstWord.length > 2 && desc.contains(firstWord))
                    val qtyMatch = formattedQty.isNotBlank() && desc.contains(formattedQty)
                    nameMatch && qtyMatch
                }

                // 2. Second attempt: Match unused event with name only (chronological order for multiple steps of same material)
                if (matchedEvent == null) {
                    matchedEvent = materialEvents.firstOrNull { ev ->
                        val evId = ev.id.ifBlank { ev.timestamp.toString() }
                        if (usedEventIds.contains(evId)) return@firstOrNull false
                        val desc = ev.description
                        (stepTitle.isNotBlank() && desc.contains(stepTitle)) ||
                                (rmName.isNotBlank() && desc.contains(rmName)) ||
                                (prodName.isNotBlank() && desc.contains(prodName)) ||
                                (firstWord.length > 2 && desc.contains(firstWord))
                    }
                }

                // 3. Fallback: Any matching event from orderEvents if unused
                if (matchedEvent == null) {
                    matchedEvent = orderEvents.firstOrNull { ev ->
                        val evId = ev.id.ifBlank { ev.timestamp.toString() }
                        if (usedEventIds.contains(evId)) return@firstOrNull false
                        val desc = ev.description
                        (stepTitle.isNotBlank() && desc.contains(stepTitle)) ||
                                (rmName.isNotBlank() && desc.contains(rmName)) ||
                                (prodName.isNotBlank() && desc.contains(prodName)) ||
                                (firstWord.length > 2 && desc.contains(firstWord))
                    }
                }

                if (matchedEvent != null) {
                    val evId = matchedEvent.id.ifBlank { matchedEvent.timestamp.toString() }
                    usedEventIds.add(evId)
                    try {
                        timeMap[sIdx] = sdf.format(java.util.Date(matchedEvent.timestamp))
                    } catch (e: Exception) {
                        timeMap[sIdx] = ""
                    }
                }
            }
        }
        timeMap
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                // Header
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
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = GBRBlueMain,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "مخطط تفاصيل وجبة الإنتاج",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = GBRDarkIndigo
                            )
                            Text(
                                text = "قائمة المواد وما تم إضافته وساعة الإضافة",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(8.dp))

                // List
                if (flattenedInstructions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("لا توجد مواد مسجلة في خط الإنتاج لهذه التركيبة.", fontSize = 12.sp, color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        itemsIndexed(flattenedInstructions) { sIdx, step ->
                            val isCompleted = if (step.recipeItemId.isNotBlank()) {
                                completedItemsSet.contains("p${step.phaseIndex}_ri${step.recipeItemId}") || completedItemsSet.contains("p${step.phaseIndex}_it${step.materialId}")
                            } else {
                                completedItemsSet.contains("p${step.phaseIndex}_it${step.materialId}")
                            }

                            if (step.isPhaseCompletionStep) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "⏱️ ${step.phaseName} - خلط وتجانس (${step.durationMinutes} دقيقة)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF475569)
                                    )
                                }
                            } else {
                                val bgColor = if (isCompleted) Color(0xFFDCFCE7) else Color.Transparent
                                val contentColor = if (isCompleted) Color(0xFF166534) else GBRDarkIndigo
                                val fontWeightVal = if (isCompleted) FontWeight.Bold else FontWeight.Normal
                                val additionTimeStr = stepAdditionTimeMap[sIdx] ?: ""

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bgColor, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isCompleted) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "تمت الإضافة",
                                                tint = Color(0xFF166534),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .border(1.dp, Color.LightGray, RoundedCornerShape(6.dp))
                                            )
                                        }
                                        Text(
                                            text = step.title,
                                            fontSize = 11.5.sp,
                                            fontWeight = fontWeightVal,
                                            color = contentColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val stepCumulativeWeight = remember(flattenedInstructions, sIdx) {
                                        flattenedInstructions.take(sIdx + 1).sumOf { it.quantity }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(1.dp)
                                    ) {
                                        Text(
                                            text = "${formatNum(step.quantity)} كجم",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = contentColor
                                        )
                                        Text(
                                            text = "تراكمي: ${formatNum(stepCumulativeWeight)} كجم",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isCompleted) Color(0xFF166534).copy(alpha = 0.8f) else Color.Gray
                                        )
                                        if (isCompleted && additionTimeStr.isNotBlank()) {
                                            Text(
                                                text = "🕒 $additionTimeStr",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF166534)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                ) {
                    Text("إغلاق المخطط", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// INTERFACE: ACTIVE MANAGER VIEW RUN
// ==========================================
@Composable
fun ActiveManagerRunInterface(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    items: List<ProductionOrderItem>,
    phases: List<com.example.data.ProductionOrderPhase>,
    recipeItems: List<com.example.data.ProductionOrderRecipeItem>,
    onClose: () -> Unit
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val orderEvents by viewModel.selectedProductionOrderEvents.collectAsState()
    val adjustments by produceState<List<com.example.data.ProductionAdjustment>>(
        initialValue = viewModel.selectedProductionOrderAdjustments.value.filter { it.productionOrderId == order.id },
        key1 = order.id
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.getProductionAdjustmentsSync(order.id)
        }
        viewModel.getProductionAdjustmentsFlow(order.id).collect {
            value = it
        }
    }
    val rawMaterialsList by viewModel.rawMaterials.collectAsState()
    var showTradeName by remember { mutableStateOf(true) }
    var selectedItemToAdjust by remember { mutableStateOf<com.example.data.ProductionOrderRecipeItem?>(null) }
    var showReferenceDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var completedItemsSet by remember(order.completedItemsJson) {
        val set = mutableSetOf<String>()
        try {
            val arr = JSONArray(order.completedItemsJson)
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (e: Exception) {}
        mutableStateOf(set)
    }

    // Modal state for finishing & safety undo
    var showFinishOverlay by remember { mutableStateOf(false) }
    var itemToUndo by remember { mutableStateOf<UndoItemInfo?>(null) }

    @Composable
    fun WeightSummaryCard() {
        val currentTotalWeight = recipeItems.sumOf { it.calculatedQuantity }
        val originalTotalWeight = order.requiredWeightKg
        val weightDiff = currentTotalWeight - originalTotalWeight

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.5.dp, GBRBlueMain.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("📊", fontSize = 14.sp)
                    Text(
                        text = "ملخص أوزان التركيبة للدفعة الحالية",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = GBRDarkIndigo
                    )
                }
                Divider(color = IndustrialBorder, thickness = 1.dp)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Current Weight
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "إجمالي الوزن الحالي",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatSupervisorQtyClean(currentTotalWeight)} كغم",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRBlueMain
                        )
                    }
                    
                    // Divider Line
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(IndustrialBorder)
                            .align(Alignment.CenterVertically)
                    )
                    
                    // Original Weight
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "الوزن الأصلي المطلوب",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatSupervisorQtyClean(originalTotalWeight)} كغم",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo
                        )
                    }
                    
                    // Divider Line
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(IndustrialBorder)
                            .align(Alignment.CenterVertically)
                    )
                    
                    // Deviation/Difference
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "الإنحراف (الفارق)",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        
                        val absDiff = kotlin.math.abs(weightDiff)
                        if (absDiff < 0.01) {
                            Text(
                                text = "0 كغم",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        } else if (weightDiff < 0) {
                            Text(
                                text = "-${formatSupervisorQtyClean(absDiff)} كغم",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = ErrorRed
                            )
                        } else {
                            Text(
                                text = "+${formatSupervisorQtyClean(absDiff)} كغم",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = ErrorRed
                            )
                        }
                    }
                }
                
                // Extra indicator text for safety/deviation status
                val absDiff = kotlin.math.abs(weightDiff)
                if (absDiff >= 0.01) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(ErrorRed.copy(alpha = 0.08f))
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (weightDiff < 0) "تنبيه: يوجد انحراف بالنقصان في كميات الدفعة!" else "تنبيه: يوجد انحراف بالزيادة في كميات الدفعة!",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = ErrorRed
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SuccessGreen.copy(alpha = 0.08f))
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "الوزن الحالي متطابق تماماً مع التركيبة الأصلية للدفعة ✔️",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        }
                    }
                }
            }
        }
    }

    val flattenedStepsKeys = remember(phases, recipeItems) {
        val list = mutableListOf<String>()
        phases.forEachIndexed { pIdx, phase ->
            val phaseItems = recipeItems.filter { it.productionOrderPhaseId == phase.id }
            if (phaseItems.isEmpty()) {
                list.add("p${pIdx}_it-1")
            } else {
                phaseItems.forEach { rItem ->
                    if (rItem.id.isNotBlank()) {
                        list.add("p${pIdx}_ri${rItem.id}")
                    } else {
                        list.add("p${pIdx}_it${rItem.rawMaterialId}")
                    }
                }
            }
        }
        list
    }

    val totalSteps = flattenedStepsKeys.size
    val completedCount = remember(flattenedStepsKeys, completedItemsSet, recipeItems) {
        flattenedStepsKeys.count { key ->
            if (completedItemsSet.contains(key)) {
                true
            } else {
                if (key.startsWith("p") && key.contains("_ri")) {
                    val rItemId = key.substringAfter("_ri")
                    val ri = recipeItems.find { it.id == rItemId }
                    if (ri != null) {
                        val pIdx = key.substringBefore("_ri").substring(1)
                        completedItemsSet.contains("p${pIdx}_it${ri.rawMaterialId}")
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }
    val progressPercent = if (totalSteps > 0) (completedCount * 100) / totalSteps else 0
    val isFullyCompleted = totalSteps > 0 && completedCount == totalSteps

    if (selectedItemToAdjust != null) {
        val originalQty = selectedItemToAdjust!!.calculatedQuantity
        
        ProductionAdjustmentDialog(
            materialName = selectedItemToAdjust!!.rawMaterialName,
            originalQty = originalQty,
            onDismiss = { selectedItemToAdjust = null },
            onSave = { newQty, reason, notes ->
                viewModel.applyProductionAdjustment(
                    orderId = order.id,
                    materialId = selectedItemToAdjust!!.rawMaterialId,
                    materialName = selectedItemToAdjust!!.rawMaterialName,
                    originalQty = originalQty,
                    newQty = newQty,
                    reason = reason,
                    notes = notes,
                    userName = currentUser?.fullName?.ifBlank { currentUser?.name } ?: "المشرف",
                    recipeItemId = selectedItemToAdjust!!.id
                )
            },
            rawMaterials = rawMaterialsList,
            currentMaterialId = selectedItemToAdjust!!.rawMaterialId,
            onSubstitute = { newMat, newQty, reason, notes ->
                viewModel.applyProductionSubstitution(
                    orderId = order.id,
                    recipeItemId = selectedItemToAdjust!!.id,
                    oldMaterialId = selectedItemToAdjust!!.rawMaterialId,
                    oldMaterialName = selectedItemToAdjust!!.rawMaterialName,
                    newMaterial = newMat,
                    originalQty = originalQty,
                    newQty = newQty,
                    reason = reason,
                    notes = notes,
                    userName = currentUser?.fullName?.ifBlank { currentUser?.name } ?: "المشرف"
                )
            }
        )
    }

    if (showFinishOverlay) {
        FinishBatchOverlay(order = order, viewModel = viewModel, onClose = { showFinishOverlay = false })
    } else {
        if (itemToUndo != null) {
            AlertDialog(
                onDismissRequest = { itemToUndo = null },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = ErrorRed
                        )
                        Text(
                            text = "إلغاء تنفيذ المادة",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GBRDarkIndigo
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "هل أنت متأكد من إلغاء تنفيذ هذه المادة؟",
                            fontSize = 14.sp,
                            color = Color(0xFF475569)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "اسم المادة: ${itemToUndo!!.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = GBRDarkIndigo
                                )
                                Text(
                                    text = "الكمية: ${itemToUndo!!.quantity}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = GBRPinkAccent
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val keyToRemove = itemToUndo!!.key
                            viewModel.saveGrindStartTime(order.id, keyToRemove, -1L)
                            val nextSet = completedItemsSet.toMutableSet()
                            nextSet.remove(keyToRemove)
                            
                            if (keyToRemove.startsWith("p") && keyToRemove.contains("_ri")) {
                                val rItemId = keyToRemove.substringAfter("_ri")
                                val ri = recipeItems.find { it.id == rItemId }
                                if (ri != null) {
                                    val pIdx = keyToRemove.substringBefore("_ri").substring(1)
                                    val legacyKey = "p${pIdx}_it${ri.rawMaterialId}"
                                    viewModel.saveGrindStartTime(order.id, legacyKey, -1L)
                                    nextSet.remove(legacyKey)
                                }
                            }
                            completedItemsSet = nextSet
                            
                            val nextCompletedCount = flattenedStepsKeys.count { key ->
                                if (nextSet.contains(key)) {
                                    true
                                } else if (key.startsWith("p") && key.contains("_ri")) {
                                    val rItemId = key.substringAfter("_ri")
                                    val ri = recipeItems.find { it.id == rItemId }
                                    if (ri != null) {
                                        val pIdx = key.substringBefore("_ri").substring(1)
                                        nextSet.contains("p${pIdx}_it${ri.rawMaterialId}")
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                            }
                            val nextProgressPercent = if (totalSteps > 0) (nextCompletedCount * 100) / totalSteps else 0
                            val firstUncompleted = flattenedStepsKeys.indexOfFirst { key ->
                                val isDone = if (nextSet.contains(key)) {
                                    true
                                } else if (key.startsWith("p") && key.contains("_ri")) {
                                    val rItemId = key.substringAfter("_ri")
                                    val ri = recipeItems.find { it.id == rItemId }
                                    if (ri != null) {
                                        val pIdx = key.substringBefore("_ri").substring(1)
                                        nextSet.contains("p${pIdx}_it${ri.rawMaterialId}")
                                    } else {
                                        false
                                    }
                                } else {
                                    false
                                }
                                !isDone
                            }
                            val finalIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps

                            viewModel.updateExecutionProgress(
                                orderId = order.id,
                                phaseIndex = finalIdx,
                                itemIndex = 0,
                                completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                progressPercent = nextProgressPercent.coerceIn(0, 100)
                            )
                            itemToUndo = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("تأكيد الإلغاء", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { itemToUndo = null }
                    ) {
                        Text("إلغاء", fontWeight = FontWeight.Medium, color = Color.Gray)
                    }
                },
                containerColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(IndustrialGrayBg)
                .padding(16.dp)
        ) {
            // Operational Alerts for Production
            com.example.ui.LinkedAlertsDisplay(
                mainSection = "أوامر الإنتاج",
                elementId = order.id,
                viewModel = viewModel
            )

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "رجوع",
                            tint = GBRDarkIndigo,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text("لوحة تشغيل المشرف المفتوحة", fontSize = 15.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(order.orderNumber, fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 12.sp)
                            Text("| دفعة: ${order.batchNumber} | الإنجاز: $progressPercent%", fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                        }
                        Row(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .background(Color(0xFFECFDF5), RoundedCornerShape(4.dp))
                                .border(0.5.dp, Color(0xFFA7F3D0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .background(SuccessGreen, RoundedCornerShape(2.dp))
                            )
                            Text(
                                text = "💡 الشاشة نشطة دائماً",
                                fontSize = 9.sp,
                                color = Color(0xFF047857),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = { showReferenceDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "عرض قائمة المواد ومواعيد الإضافة",
                        tint = GBRBlueMain
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { showFinishOverlay = true },
                    enabled = isFullyCompleted,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isFullyCompleted) Color.White else Color.Gray,
                        containerColor = if (isFullyCompleted) SuccessGreen else Color.LightGray
                    )
                ) {
                    Text("إنهاء وتعبئة الدفعة", fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1, softWrap = false)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Segmented name display toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "الاسم المتداول" tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showTradeName) GBRBlueMain.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { showTradeName = true }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "🏷️",
                                fontSize = 14.sp
                            )
                            Text(
                                text = "الاسم المتداول (افتراضي)",
                                fontSize = 12.sp,
                                fontWeight = if (showTradeName) FontWeight.Bold else FontWeight.Medium,
                                color = if (showTradeName) GBRBlueMain else Color.Gray
                            )
                        }
                    }

                    // Divider separator
                    Box(
                        modifier = Modifier
                            .fillMaxHeight(0.6f)
                            .width(1.dp)
                            .background(Color(0xFFE2E8F0))
                    )

                    // "الاسم العلمي / الحقيقي" tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!showTradeName) GBRBlueMain.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable { showTradeName = false }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "🧪",
                                fontSize = 14.sp
                            )
                            Text(
                                text = "الاسم الحقيقي / العلمي",
                                fontSize = 12.sp,
                                fontWeight = if (!showTradeName) FontWeight.Bold else FontWeight.Medium,
                                color = if (!showTradeName) GBRBlueMain else Color.Gray
                            )
                        }
                    }
                }
            }

            val notesPartsForManager = remember(order.notes) { order.notes.split("\nملاحظات الإكمال:") }
            val originalNotesForManager = remember(notesPartsForManager) { notesPartsForManager.getOrNull(0)?.trim() ?: "" }
            val finalNotesForManager = remember(notesPartsForManager) { if (notesPartsForManager.size > 1) notesPartsForManager[1].trim() else "" }

            if (originalNotesForManager.isNotBlank() || finalNotesForManager.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("📋", fontSize = 14.sp)
                            Text("ملاحظات وتعليمات التشغيل للدفعة الحالية:", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
                        }
                        
                        if (originalNotesForManager.isNotBlank()) {
                            Text(
                                text = "📝 ملاحظات وجدولة الإنتاج:", 
                                fontWeight = FontWeight.Bold, 
                                color = GBRBlueMain, 
                                fontSize = 11.sp
                            )
                            Text(
                                text = originalNotesForManager, 
                                color = Color.DarkGray, 
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }

                        if (finalNotesForManager.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "🏁 الملاحظات الختامية المسجلة:", 
                                fontWeight = FontWeight.Bold, 
                                color = SuccessGreen, 
                                fontSize = 11.sp
                            )
                            Text(
                                text = finalNotesForManager, 
                                color = Color.DarkGray, 
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (adjustments.isNotEmpty()) {
                Surface(
                    color = WarningOrange.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = WarningOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "منبه الدفعة: تم إجراء ${adjustments.size} تعديلات على الكميات الكيميائية أثناء الإنتاج.",
                            color = WarningOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Main phases scrolling view
            if (phases.isEmpty()) {
                EmptyBox(message = "لا توجد مراحل تشغيلية معرفة لهذه التركيبة الكيميائية وقت الإنتاج.", subMessage = "يمكن إتمام وإغلاق الدفعة مباشرة بالضغط على 'إنهاء الدفعة'.")
                Spacer(modifier = Modifier.height(10.dp))
                WeightSummaryCard()
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(phases) { pIdx, phase ->
                        val itemsInPhase = recipeItems.filter { it.productionOrderPhaseId == phase.id }

                        val totalPhaseSteps = if (itemsInPhase.isEmpty()) 1 else itemsInPhase.size
                        val completedPhaseSteps = if (itemsInPhase.isEmpty()) {
                            if (completedItemsSet.contains("p${pIdx}_it-1")) 1 else 0
                        } else {
                            itemsInPhase.count { 
                                val key = if (it.id.isNotBlank()) "p${pIdx}_ri${it.id}" else "p${pIdx}_it${it.rawMaterialId}"
                                completedItemsSet.contains(key) || completedItemsSet.contains("p${pIdx}_it${it.rawMaterialId}")
                            }
                        }
                        val phasePercentage = if (totalPhaseSteps > 0) (completedPhaseSteps * 100) / totalPhaseSteps else 0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, IndustrialBorder),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("المرحلة ${pIdx+1}: ${phase.name}", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
                                    val rpmPart = if (phase.mixerRpm > 0) "${phase.mixerRpm} RPM" else ""
                                    val durPart = if (phase.durationMinutes > 0) "${phase.durationMinutes} دقيقة" else ""
                                    val specText = listOf(rpmPart, durPart).filter { it.isNotEmpty() }.joinToString(" | ")
                                    if (specText.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(GBRBlueMain.copy(alpha = 0.08f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(specText, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = GBRBlueMain)
                                        }
                                    }
                                }

                                // Phase progress row
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "تم تنفيذ: $completedPhaseSteps من $totalPhaseSteps خطوة ($phasePercentage%)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (phasePercentage == 100) SuccessGreen else Color.Gray
                                    )
                                    LinearProgressIndicator(
                                        progress = completedPhaseSteps.toFloat() / totalPhaseSteps.toFloat(),
                                        color = SuccessGreen,
                                        trackColor = Color(0xFFF1F5F9),
                                        modifier = Modifier
                                            .width(80.dp)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                }

                                if (phase.instructions.isNotBlank()) {
                                    Text("تعليمات المرحلة: ${phase.instructions}", fontSize = 11.sp, color = Color.DarkGray)
                                }

                                Divider(color = IndustrialBorder, modifier = Modifier.padding(vertical = 4.dp))

                                // List of raw ingredients inside this phase
                                if (itemsInPhase.isEmpty()) {
                                    val compositeKey = "p${pIdx}_it-1"
                                    val isCompleted = completedItemsSet.contains(compositeKey)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCompleted) Color(0xFFDCFCE7) else Color.Transparent)
                                            .then(if (isCompleted) Modifier.border(1.2.dp, SuccessGreen, RoundedCornerShape(8.dp)) else Modifier)
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (isCompleted) {
                                                    Icon(
                                                        imageVector = Icons.Default.CheckCircle,
                                                        contentDescription = "تم",
                                                        tint = SuccessGreen,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = "تشغيل الخلاط والموقت للتجانس",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isCompleted) Color.Gray else GBRDarkIndigo,
                                                    style = if (isCompleted) androidx.compose.ui.text.TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else androidx.compose.ui.text.TextStyle.Default
                                                )
                                            }
                                            val tM = if (phase.durationMinutes > 0) "${phase.durationMinutes} دقيقة" else "غير محدد"
                                            val tR = if (phase.mixerRpm > 0) "${phase.mixerRpm} RPM" else "غير محدد"
                                            Text(
                                                text = "الموقت: $tM | $tR",
                                                fontSize = 11.sp,
                                                color = if (isCompleted) Color.Gray.copy(alpha = 0.7f) else GBRPinkAccent
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                if (isCompleted) {
                                                    itemToUndo = UndoItemInfo(
                                                        key = compositeKey,
                                                        name = "تشغيل الخلاط والموقت للتجانس",
                                                        quantity = "${phase.durationMinutes} دقيقة / ${phase.mixerRpm} RPM",
                                                        isPhaseStep = true
                                                    )
                                                } else {
                                                    val nextSet = completedItemsSet.toMutableSet()
                                                    nextSet.add(compositeKey)
                                                    completedItemsSet = nextSet
                                                    viewModel.logExecutionStepEvent(
                                                        orderId = order.id,
                                                        eventName = "إكمال خطوة تشغيلية",
                                                        description = "تم تأكيد تشغيل الخلاط للمرحلة ${phase.name}"
                                                    )
                                                    val nextCompletedCount = flattenedStepsKeys.count { key ->
                                                        if (nextSet.contains(key)) {
                                                            true
                                                        } else if (key.startsWith("p") && key.contains("_ri")) {
                                                            val rItemId = key.substringAfter("_ri")
                                                            val ri = recipeItems.find { it.id == rItemId }
                                                            if (ri != null) {
                                                                val pIdxVal = key.substringBefore("_ri").substring(1)
                                                                nextSet.contains("p${pIdxVal}_it${ri.rawMaterialId}")
                                                            } else {
                                                                false
                                                            }
                                                        } else {
                                                            false
                                                        }
                                                    }
                                                    val nextProgressPercent = if (totalSteps > 0) (nextCompletedCount * 100) / totalSteps else 0
                                                    val firstUncompleted = flattenedStepsKeys.indexOfFirst { key ->
                                                        val isDone = if (nextSet.contains(key)) {
                                                            true
                                                        } else if (key.startsWith("p") && key.contains("_ri")) {
                                                            val rItemId = key.substringAfter("_ri")
                                                            val ri = recipeItems.find { it.id == rItemId }
                                                            if (ri != null) {
                                                                val pIdxVal = key.substringBefore("_ri").substring(1)
                                                                nextSet.contains("p${pIdxVal}_it${ri.rawMaterialId}")
                                                            } else {
                                                                false
                                                            }
                                                        } else {
                                                            false
                                                        }
                                                        !isDone
                                                    }
                                                    val finalIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps

                                                    viewModel.updateExecutionProgress(
                                                        orderId = order.id,
                                                        phaseIndex = finalIdx,
                                                        itemIndex = 0,
                                                        completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                                        progressPercent = nextProgressPercent.coerceIn(0, 100)
                                                    )
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isCompleted) Color(0xFFD1FAE5) else GBRBlueMain
                                            ),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            val textBtn = if (isCompleted) "تم ✔️" else "تم التنفيذ"
                                            val colorBtn = if (isCompleted) SuccessGreen else Color.White
                                            Text(textBtn, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colorBtn)
                                        }
                                    }
                                } else {
                                    itemsInPhase.forEach { ri ->
                                        val compositeKey = if (ri.id.isNotBlank()) "p${pIdx}_ri${ri.id}" else "p${pIdx}_it${ri.rawMaterialId}"
                                        val isCompleted = completedItemsSet.contains(compositeKey) || completedItemsSet.contains("p${pIdx}_it${ri.rawMaterialId}")

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isCompleted) Color(0xFFDCFCE7) else Color(0xFFF8FAFC))
                                                .then(
                                                    if (isCompleted) Modifier.border(1.2.dp, SuccessGreen, RoundedCornerShape(10.dp))
                                                    else Modifier.border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                                                )
                                                .padding(horizontal = 12.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                // Quantity is the absolute most prominent element (as requested)
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    if (isCompleted) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = "تم",
                                                            tint = SuccessGreen,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    Text(
                                                        text = "${formatSupervisorQtyClean(ri.calculatedQuantity)} كغم",
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = if (isCompleted) SuccessGreen else GBRPinkAccent
                                                    )
                                                }
                                                // Raw material name is placed exactly below the prominent quantity (as requested)
                                                val rm = rawMaterialsList.find { it.id == ri.rawMaterialId }
                                                val displayName = if (showTradeName) {
                                                    if (rm != null) {
                                                        getTradeName(rm.name, rm.productionName)
                                                    } else {
                                                        getTradeName(ri.rawMaterialName, null)
                                                    }
                                                } else {
                                                    ri.rawMaterialName
                                                }

                                                Text(
                                                    text = displayName,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isCompleted) Color.Gray else GBRDarkIndigo,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )

                                                if (currentUser?.role != "عامل صالة الإنتاج" && !isCompleted) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                        modifier = Modifier.clickable {
                                                            selectedItemToAdjust = ri
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "تعديل",
                                                            tint = GBRBlueMain,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                        Text(
                                                            text = "تعديل",
                                                            color = GBRBlueMain,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    if (isCompleted) {
                                                        itemToUndo = UndoItemInfo(
                                                            key = compositeKey,
                                                            name = ri.rawMaterialName,
                                                            quantity = "${formatSupervisorQtyClean(ri.calculatedQuantity)} كغم",
                                                            isPhaseStep = false
                                                        )
                                                    } else {
                                                        val nextSet = completedItemsSet.toMutableSet()
                                                        nextSet.add(compositeKey)
                                                        viewModel.logExecutionStepEvent(
                                                            orderId = order.id,
                                                            eventName = "إضافة مادة خام",
                                                            description = "تم تأكيد إضافة مادة ${ri.rawMaterialName} بوزن ${formatNum(ri.calculatedQuantity)} كجم"
                                                        )
                                                        completedItemsSet = nextSet
                                                        
                                                        val nextCompletedCount = flattenedStepsKeys.count { key ->
                                                            if (nextSet.contains(key)) {
                                                                true
                                                            } else if (key.startsWith("p") && key.contains("_ri")) {
                                                                val rItemId = key.substringAfter("_ri")
                                                                val riItem = recipeItems.find { it.id == rItemId }
                                                                if (riItem != null) {
                                                                    val pIdxVal = key.substringBefore("_ri").substring(1)
                                                                    nextSet.contains("p${pIdxVal}_it${riItem.rawMaterialId}")
                                                                } else {
                                                                    false
                                                                }
                                                            } else {
                                                                false
                                                            }
                                                        }
                                                        val nextProgressPercent = if (totalSteps > 0) (nextCompletedCount * 100) / totalSteps else 0
                                                        val firstUncompleted = flattenedStepsKeys.indexOfFirst { key ->
                                                            val isDone = if (nextSet.contains(key)) {
                                                                true
                                                            } else if (key.startsWith("p") && key.contains("_ri")) {
                                                                val rItemId = key.substringAfter("_ri")
                                                                val riItem = recipeItems.find { it.id == rItemId }
                                                                if (riItem != null) {
                                                                    val pIdxVal = key.substringBefore("_ri").substring(1)
                                                                    nextSet.contains("p${pIdxVal}_it${riItem.rawMaterialId}")
                                                                } else {
                                                                    false
                                                                }
                                                            } else {
                                                                false
                                                            }
                                                            !isDone
                                                        }
                                                        val finalIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps

                                                        viewModel.updateExecutionProgress(
                                                            orderId = order.id,
                                                            phaseIndex = finalIdx,
                                                            itemIndex = 0,
                                                            completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                                            progressPercent = nextProgressPercent.coerceIn(0, 100)
                                                        )
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isCompleted) Color(0xFFD1FAE5) else GBRBlueMain
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.height(34.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                val textBtn = if (isCompleted) "تم ✔️" else "تم التنفيذ"
                                                val colorBtn = if (isCompleted) SuccessGreen else Color.White
                                                Text(textBtn, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colorBtn)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    item {
                        WeightSummaryCard()
                    }
                }
            }



            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("تغيير وضع التشغيل الحالي", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showReferenceDialog) {
        ProductionBatchReferenceDialog(
            order = order,
            phases = phases,
            recipeItems = recipeItems,
            rawMaterialsList = rawMaterialsList,
            completedItemsSet = completedItemsSet,
            orderEvents = orderEvents,
            onDismiss = { showReferenceDialog = false }
        )
    }
}

fun formatFinishedAgo(finishedAgoSec: Int): String {
    val mins = finishedAgoSec / 60
    if (mins < 1) {
        return "منذ أقل من دقيقة"
    }
    if (mins < 60) {
        return "منذ $mins دقيقة"
    }
    val hours = mins / 60
    if (hours < 24) {
        val remainingMins = mins % 60
        return if (remainingMins > 0) "منذ $hours ساعة و $remainingMins دقيقة" else "منذ $hours ساعة"
    }
    val days = hours / 24
    return "منذ $days يوم"
}

// Custom trade name resolver to fulfill absolute requirement of common trade names rule
fun getTradeName(realName: String, productionName: String?): String {
    if (!productionName.isNullOrBlank()) return productionName.trim()
    val clean = realName.trim().lowercase()
    return when {
        clean.contains("hydroxyethyl") || clean.contains("cellulose") || clean.contains("hec") || clean.contains("جل") || clean.contains("جلى") -> "جلي"
        clean.contains("propylene") || clean.contains("glycol") || clean.contains("pg") || clean.contains("بروبيلين") -> "شفافة"
        clean.contains("titanium") || clean.contains("dioxide") || clean.contains("tio2") || clean.contains("تايتانيوم") || clean.contains("تيتانيوم") -> "699"
        else -> realName
    }
}

// ==========================================
// INTERFACE: ACTIVE SHOP FLOOR VIEW RUN (1 ACTIVE ELEMENT WITH COUNTDOWN TIMER)
// ==========================================
@Composable
fun ActiveShopFloorRunInterface(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    items: List<ProductionOrderItem>,
    phases: List<com.example.data.ProductionOrderPhase>,
    recipeItems: List<com.example.data.ProductionOrderRecipeItem>,
    onClose: () -> Unit
) {
    val rawMaterialsList by viewModel.rawMaterials.collectAsState()
    val orderEvents by viewModel.selectedProductionOrderEvents.collectAsState()
    val formulationsForWater by viewModel.formulations.collectAsState()

    if (phases.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("لا توجد أي خطوط مراحل خلط تفاعلية في صالة الإنتاج لهذه التركيبة. يرجى المتابعة بالدخول لوضع المشرف.")
        }
        return
    }

    // Step index resolution
    val flattenedInstructions = remember(phases, recipeItems, rawMaterialsList) {
        val list = mutableListOf<ShopFloorStep>()
        phases.forEachIndexed { pIdx, phase ->
            val phaseItems = recipeItems.filter { it.productionOrderPhaseId == phase.id }
            if (phaseItems.isEmpty()) {
                list.add(
                    ShopFloorStep(
                        phaseIndex = pIdx,
                        phaseName = phase.name,
                        title = "تشغيل الخلاط والموقت للتجانس",
                        quantity = 0.0,
                        instruction = phase.instructions,
                        mixerRpm = phase.mixerRpm,
                        durationMinutes = phase.durationMinutes,
                        materialId = "",
                        isPhaseCompletionStep = true
                    )
                )
            } else {
                phaseItems.forEachIndexed { riIdx, rItem ->
                    val rm = rawMaterialsList.find { it.id == rItem.rawMaterialId }
                    val shownTitle = if (rm != null) {
                        getTradeName(rm.name, rm.productionName)
                    } else {
                        getTradeName(rItem.rawMaterialName, null)
                    }
                    list.add(
                        ShopFloorStep(
                            phaseIndex = pIdx,
                            phaseName = phase.name,
                            title = shownTitle,
                            quantity = rItem.calculatedQuantity,
                            instruction = phase.instructions,
                            mixerRpm = phase.mixerRpm,
                            durationMinutes = if (riIdx == 0) phase.durationMinutes else 0,
                            materialId = rItem.rawMaterialId,
                            isPhaseCompletionStep = false,
                            recipeItemId = rItem.id
                        )
                    )
                }
            }
        }
        list
    }

    val totalSteps = flattenedInstructions.size

    var completedItemsSet by remember(order.completedItemsJson) {
        val set = mutableSetOf<String>()
        try {
            val arr = JSONArray(order.completedItemsJson)
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (e: Exception) {}
        mutableStateOf(set)
    }

    val isStepCompleted = { step: ShopFloorStep, set: Set<String> ->
        val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
        set.contains(key) || set.contains("p${step.phaseIndex}_it${step.materialId}")
    }

    // Resume sequence: Start from first uncompleted step or totalSteps if done
    val initialIdx = remember(order.id, flattenedInstructions, completedItemsSet) {
        val firstUncompleted = flattenedInstructions.indexOfFirst { step ->
            val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
            !completedItemsSet.contains(key) && !completedItemsSet.contains("p${step.phaseIndex}_it${step.materialId}")
        }
        if (firstUncompleted != -1) firstUncompleted else totalSteps
    }
    var activeStepIdx by remember(order.id, initialIdx) { mutableIntStateOf(initialIdx) }
    val currentUser by viewModel.currentUser.collectAsState()
    var showAdjustmentDialog by remember { mutableStateOf(false) }

    // Intercept and navigate to deep-linked step index when opened via notification shortcut
    val pendingGrindStepKey by viewModel.pendingGrindStepKey.collectAsState()
    LaunchedEffect(pendingGrindStepKey, flattenedInstructions) {
        val targetStepKey = pendingGrindStepKey
        if (!targetStepKey.isNullOrEmpty()) {
            val matchedIndex = flattenedInstructions.indexOfFirst { step ->
                val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                key == targetStepKey || "p${step.phaseIndex}_it${step.materialId}" == targetStepKey
            }
            if (matchedIndex != -1) {
                activeStepIdx = matchedIndex
                viewModel.clearPendingGrindStepKey()
            }
        }
    }

    // Sync activeStepIdx reactively if the order execution progress changes externally (e.g. Supervisor ticked or unticked something)
    LaunchedEffect(order.completedItemsJson, flattenedInstructions) {
        val nextSet = mutableSetOf<String>()
        try {
            val arr = JSONArray(order.completedItemsJson)
            for (i in 0 until arr.length()) {
                nextSet.add(arr.getString(i))
            }
        } catch (e: Exception) {}
        
        val firstUncompleted = flattenedInstructions.indexOfFirst { step ->
            val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
            !nextSet.contains(key) && !nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
        }
        activeStepIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps
    }

    // Overlay Finish Dlg
    var showFinishOverlay by remember(order.id, initialIdx, totalSteps) {
        mutableStateOf(totalSteps > 0 && initialIdx >= totalSteps)
    }
    var itemToUndo by remember { mutableStateOf<ShopFloorStep?>(null) }
    var showAddConfirmationDialog by remember { mutableStateOf(false) }
    var showReferenceDialog by remember { mutableStateOf(false) }

    if (showFinishOverlay) {
        FinishBatchOverlay(order = order, viewModel = viewModel, onClose = { showFinishOverlay = false })
    } else {
        if (activeStepIdx >= totalSteps) {
            // Reached completion
            showFinishOverlay = true
        } else {
            val currentInstruction = flattenedInstructions[activeStepIdx]

            // Real-time Timer management
            val context = androidx.compose.ui.platform.LocalContext.current

            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { _ -> }

            LaunchedEffect(Unit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val permission = "android.permission.POST_NOTIFICATIONS"
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch(permission)
                    }
                }
            }
            
            val stepKey = if (currentInstruction.recipeItemId.isNotBlank()) "p${currentInstruction.phaseIndex}_ri${currentInstruction.recipeItemId}" else "p${currentInstruction.phaseIndex}_it${currentInstruction.materialId}"
            val startTimeMs = remember(order.timerStartTimesJson, stepKey) {
                try {
                    org.json.JSONObject(order.timerStartTimesJson).optLong(stepKey, -1L)
                } catch (e: Exception) {
                    -1L
                }
            }
            val isBypassed = remember(order.timerStartTimesJson, stepKey) {
                try {
                    org.json.JSONObject(order.timerStartTimesJson).optBoolean(stepKey + "_bypassed", false)
                } catch (e: Exception) {
                    false
                }
            }
            val isAlertedAlready = remember(order.timerStartTimesJson, stepKey) {
                try {
                    org.json.JSONObject(order.timerStartTimesJson).optBoolean(stepKey + "_alerted", false)
                } catch (e: Exception) {
                    false
                }
            }
            val isPaused = remember(order.timerStartTimesJson, stepKey) {
                try {
                    org.json.JSONObject(order.timerStartTimesJson).optBoolean(stepKey + "_paused", false)
                } catch (e: Exception) {
                    false
                }
            }
            val pausedTimeLeftSec = remember(order.timerStartTimesJson, stepKey) {
                try {
                    org.json.JSONObject(order.timerStartTimesJson).optInt(stepKey + "_paused_time_left", 0)
                } catch (e: Exception) {
                    0
                }
            }

            var currentTickTime by remember { mutableStateOf(System.currentTimeMillis()) }

            LaunchedEffect(startTimeMs, isPaused) {
                if (startTimeMs > 0 && !isPaused) {
                    while (true) {
                        currentTickTime = System.currentTimeMillis()
                        delay(1000L)
                    }
                }
            }

            val durationMinutes = currentInstruction.durationMinutes
            val totalSec = durationMinutes * 60

            val timeLeftSec: Int
            val isPlaying: Boolean
            val isTimerFinished: Boolean
            val finishedAgoSec: Int

            if (durationMinutes <= 0) {
                timeLeftSec = 0
                isPlaying = false
                isTimerFinished = true
                finishedAgoSec = 0
            } else if (isBypassed) {
                timeLeftSec = 0
                isPlaying = false
                isTimerFinished = true
                finishedAgoSec = 0
            } else if (isPaused) {
                timeLeftSec = pausedTimeLeftSec
                isPlaying = false
                isTimerFinished = false
                finishedAgoSec = 0
            } else if (startTimeMs == -1L) {
                timeLeftSec = totalSec
                isPlaying = false
                isTimerFinished = false
                finishedAgoSec = 0
            } else {
                val elapsedSec = ((currentTickTime - startTimeMs) / 1000).toInt().coerceAtLeast(0)
                val remainingSec = totalSec - elapsedSec
                if (remainingSec > 0) {
                    timeLeftSec = remainingSec
                    isPlaying = true
                    isTimerFinished = false
                    finishedAgoSec = 0
                } else {
                    timeLeftSec = 0
                    isPlaying = false
                    isTimerFinished = true
                    finishedAgoSec = -remainingSec
                }
            }

            // Alarm & sound management states
            var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
            var toneGen by remember { mutableStateOf<android.media.ToneGenerator?>(null) }

            val stopAlarm = remember(startTimeMs, isTimerFinished, mediaPlayer, toneGen) {
                { forceStopTimer: Boolean ->
                    if (forceStopTimer) {
                        try {
                            context.stopService(android.content.Intent(context, com.example.GrindingTimerService::class.java))
                        } catch (e: Exception) {}
                    }
                    try {
                        mediaPlayer?.let {
                            if (it.isPlaying) {
                                try {
                                    it.stop()
                                } catch (e: Exception) {}
                            }
                            it.release()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        mediaPlayer = null
                    }

                    try {
                        toneGen?.let {
                            try {
                                it.stopTone()
                            } catch (e: Exception) {}
                            it.release()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        toneGen = null
                    }

                    try {
                        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        vibrator?.cancel()
                    } catch (e: Exception) {}
                }
            }

            val triggerFactoryAlarm = remember {
                {
                    stopAlarm(false)
                    val prefs = context.getSharedPreferences("gbr_prefs", android.content.Context.MODE_PRIVATE)
                    val soundEnabled = prefs.getBoolean("gbr_sound_enabled", true)
                    val vibrationEnabled = prefs.getBoolean("gbr_vibration_enabled", true)
                    val customSoundUriStr = prefs.getString("gbr_custom_sound_uri", "") ?: ""

                    if (soundEnabled) {
                        try {
                            val alarmUri = if (customSoundUriStr.isNotBlank()) {
                                android.net.Uri.parse(customSoundUriStr)
                            } else {
                                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                            }
                            
                            val player = android.media.MediaPlayer().apply {
                                setDataSource(context, alarmUri)
                                setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
                                isLooping = true
                                prepare()
                                start()
                            }
                            mediaPlayer = player
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // Fallback: Loud, high-penetration industrial pager/beep sequences via ToneGenerator
                            try {
                                val tg = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
                                tg.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 180000) // 3 mins max
                                toneGen = tg
                            } catch (ex: Exception) {
                                ex.printStackTrace()
                            }
                        }
                    }

                    if (vibrationEnabled) {
                        try {
                            val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                            if (vibrator != null && vibrator.hasVibrator()) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    vibrator.vibrate(
                                        android.os.VibrationEffect.createWaveform(
                                            longArrayOf(0, 500, 200, 500, 200),
                                            0
                                        )
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200), 0)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            val isGrindingAlarmActive by viewModel.isGrindingAlarmActive.collectAsState()
            LaunchedEffect(isGrindingAlarmActive) {
                if (!isGrindingAlarmActive) {
                    stopAlarm(false)
                }
            }

            DisposableEffect(activeStepIdx, order.id) {
                onDispose {
                    // Keep grinding timer service running in background when app is minimized or screen is disposed.
                }
            }

            var alarmPlayedForStepKey by remember(activeStepIdx) { mutableStateOf(false) }
            LaunchedEffect(isTimerFinished, startTimeMs, isAlertedAlready) {
                if (startTimeMs > 0 && isTimerFinished && !alarmPlayedForStepKey && !isAlertedAlready) {
                    alarmPlayedForStepKey = true
                    viewModel.triggerGrindingAlarm(order.id, stepKey)
                    triggerFactoryAlarm()
                }
            }

            // Material step index calculation (as requested: المادة 4 من 12)
            val totalMaterialsCount = flattenedInstructions.count { it.quantity > 0.0 }
            val completedMaterialsCount = flattenedInstructions.take(activeStepIdx).count { it.quantity > 0.0 }
            val currentMaterialIndex = if (currentInstruction.quantity > 0.0) completedMaterialsCount + 1 else completedMaterialsCount
            val remainingMaterialsCount = totalMaterialsCount - completedMaterialsCount

            val totalPhasesCount = phases.size
            val currentPhaseIndexNumber = currentInstruction.phaseIndex + 1

            val overallProgPct = if (totalSteps > 0) (activeStepIdx * 100) / totalSteps else 0

            // SHOW DIALOG TO CONFIRM UNDO (نافذة التأكيد عند التراجع)
            if (itemToUndo != null) {
                AlertDialog(
                    onDismissRequest = { itemToUndo = null },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = ErrorRed
                            )
                            Text(
                                text = "هل تريد إلغاء تنفيذ آخر مادة؟",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "يرجى تأكيد رغبتك في إلغاء الإضافة الأخيرة للعودة للخلف:",
                                fontSize = 14.sp,
                                color = Color(0xFF475569)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "اسم المادة: ${itemToUndo!!.title}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = GBRDarkIndigo
                                    )
                                    val undoQtyText = if (itemToUndo!!.quantity > 0.0) {
                                        "${formatSupervisorQtyClean(itemToUndo!!.quantity)} كغم"
                                    } else {
                                        "مرحلة خلط للتجانس"
                                    }
                                    Text(
                                        text = "الكمية: $undoQtyText",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = GBRPinkAccent
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val stepToRevert = itemToUndo!!
                                val keyToRemove = if (stepToRevert.recipeItemId.isNotBlank()) {
                                    "p${stepToRevert.phaseIndex}_ri${stepToRevert.recipeItemId}"
                                } else {
                                    "p${stepToRevert.phaseIndex}_it${stepToRevert.materialId}"
                                }
                                val legacyKeyToRemove = "p${stepToRevert.phaseIndex}_it${stepToRevert.materialId}"
                                viewModel.saveGrindStartTime(order.id, keyToRemove, -1L)
                                viewModel.saveGrindStartTime(order.id, legacyKeyToRemove, -1L)
                                val nextSet = completedItemsSet.toMutableSet()
                                nextSet.remove(keyToRemove)
                                nextSet.remove(legacyKeyToRemove)
                                completedItemsSet = nextSet
                                
                                val nextCompletedCount = flattenedInstructions.count { step ->
                                    val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                                    nextSet.contains(key) || nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
                                }
                                val nextProgressPercent = if (totalSteps > 0) (nextCompletedCount * 100) / totalSteps else 0
                                
                                if (activeStepIdx > 0) {
                                    activeStepIdx -= 1
                                }

                                viewModel.updateExecutionProgress(
                                    orderId = order.id,
                                    phaseIndex = activeStepIdx,
                                    itemIndex = 0,
                                    completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                    progressPercent = nextProgressPercent.coerceIn(0, 100)
                                )
                                itemToUndo = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("تأكيد الإلغاء", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { itemToUndo = null }
                        ) {
                            Text("إلغاء", fontWeight = FontWeight.Medium, color = Color.Gray)
                        }
                    },
                    containerColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // SHOW DIALOG TO CONFIRM MATERIAL ADDITION (نافذة التأكيد عند الإضافة لمنع الضغط بالخطأ)
            if (showAddConfirmationDialog) {
                AlertDialog(
                    onDismissRequest = { showAddConfirmationDialog = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen
                            )
                            Text(
                                text = "تأكيد إضافة المادة الخام",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "هل أنت متأكد من إضافة هذه المادة وتفريغها في الخلاط؟",
                                fontSize = 14.sp,
                                color = Color(0xFF475569)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "اسم المادة: ${currentInstruction.title}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = GBRDarkIndigo
                                    )
                                    if (currentInstruction.quantity > 0.0) {
                                        Text(
                                            text = "الكمية المطلوبة: ${formatSupervisorQtyClean(currentInstruction.quantity)} كغم",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = GBRPinkAccent
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showAddConfirmationDialog = false
                                stopAlarm(true)
                                val logName = "تغذية عنصر خط صالة"
                                val desc = "صالة الإنتاج: قام العامل بتأكيد تفريغ مادة ${currentInstruction.title} بوزن ${formatNum(currentInstruction.quantity)} كجم"
                                viewModel.logExecutionStepEvent(order.id, logName, desc)

                                val compositeKey = if (currentInstruction.recipeItemId.isNotBlank()) "p${currentInstruction.phaseIndex}_ri${currentInstruction.recipeItemId}" else "p${currentInstruction.phaseIndex}_it${currentInstruction.materialId}"
                                val nextSet = completedItemsSet.toMutableSet()
                                nextSet.add(compositeKey)
                                completedItemsSet = nextSet

                                val totalSteps = flattenedInstructions.size
                                val completedCount = flattenedInstructions.count { step ->
                                    val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                                    nextSet.contains(key) || nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
                                }
                                val progressPercent = if (totalSteps > 0) (completedCount * 100) / totalSteps else 0

                                val firstUncompleted = flattenedInstructions.indexOfFirst { step ->
                                    val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                                    !nextSet.contains(key) && !nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
                                }
                                val finalPhaseIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps

                                activeStepIdx = finalPhaseIdx

                                viewModel.updateExecutionProgress(
                                    orderId = order.id,
                                    phaseIndex = finalPhaseIdx,
                                    itemIndex = 0,
                                    completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                    progressPercent = progressPercent.coerceIn(0, 100)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("نعم، متأكد", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showAddConfirmationDialog = false }
                        ) {
                            Text("إلغاء", fontWeight = FontWeight.Medium, color = Color.Gray)
                        }
                    },
                    containerColor = Color.White,
                    shape = RoundedCornerShape(16.dp)
                )
            }

            if (showReferenceDialog) {
                ProductionBatchReferenceDialog(
                    order = order,
                    phases = phases,
                    recipeItems = recipeItems,
                    rawMaterialsList = rawMaterialsList,
                    completedItemsSet = completedItemsSet,
                    orderEvents = orderEvents,
                    onDismiss = { showReferenceDialog = false }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF8FAFC)) // Minimal slate background
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Operational Alerts for Production
                com.example.ui.LinkedAlertsDisplay(
                    mainSection = "أوامر الإنتاج",
                    elementId = order.id,
                    viewModel = viewModel
                )

                // 1. Header Zone: Clean & Minimalist (الاسم المتداول للتركيبة + شريط التقدم)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            // Product Name (اسم المنتج)
                            Text(
                                text = order.formulationName,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = GBRDarkIndigo,
                                maxLines = 1
                            )
                            // Phase details (المرحلة الحالية)
                            Text(
                                text = "المرحلة الحالية: المرحلة $currentPhaseIndexNumber من $totalPhasesCount",
                                fontSize = 14.sp,
                                color = GBRBlueMain,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .background(Color(0xFFECFDF5), RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFA7F3D0), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(SuccessGreen, RoundedCornerShape(3.dp))
                                )
                                Text(
                                    text = "💡 الشاشة نشطة دائماً لمنع الإغلاق",
                                    fontSize = 11.sp,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                showReferenceDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = "عرض المخطط الإرشادي للدفعة", tint = GBRBlueMain)
                        }
                    }

                    // Progress Indicator with Info Badge
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = (activeStepIdx).toFloat() / totalSteps.toFloat(),
                        color = SuccessGreen,
                        trackColor = Color(0xFFE2E8F0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تبقى $remainingMaterialsCount مواد",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo
                        )
                        Text(
                            text = "تم إنجاز $overallProgPct%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen
                        )
                    }

                    val notesPartsForShopFloor = remember(order.notes) { order.notes.split("\nملاحظات الإكمال:") }
                    val originalNotesForShopFloor = remember(notesPartsForShopFloor) { notesPartsForShopFloor.getOrNull(0)?.trim() ?: "" }
                    val finalNotesForShopFloor = remember(notesPartsForShopFloor) { if (notesPartsForShopFloor.size > 1) notesPartsForShopFloor[1].trim() else "" }

                    if (originalNotesForShopFloor.isNotBlank() || finalNotesForShopFloor.isNotBlank()) {
                        var isExpanded by remember { mutableStateOf(false) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clickable { isExpanded = !isExpanded },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("📋", fontSize = 14.sp)
                                        Text(
                                            text = "ملاحظات وتعليمات التشغيل للدفعة",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = GBRBlueMain
                                        )
                                    }
                                    Text(
                                        text = if (isExpanded) "إغلاق التفاصيل ▲" else "عرض التفاصيل ▼",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRBlueMain
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider(color = Color(0xFFBFDBFE))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (originalNotesForShopFloor.isNotBlank()) {
                                        Text("📝 الملاحظات الأصلية للدفعة:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GBRDarkIndigo)
                                        Text(originalNotesForShopFloor, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                                    }
                                    if (finalNotesForShopFloor.isNotBlank()) {
                                        Text("🏁 الملاحظات الختامية وإنحرافات التشغيل:", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = SuccessGreen)
                                        Text(finalNotesForShopFloor, fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Center Card Zone containing ONE material only (عرض مادة واحدة فقط بالاسم المتداول والوزن والعداد)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title / Trade name - Beautiful, centered, with auto-wrap
                        Text(
                            text = currentInstruction.title,
                            fontSize = if (currentInstruction.title.length > 20) 24.sp else 32.sp,
                            lineHeight = if (currentInstruction.title.length > 20) 32.sp else 40.sp,
                            fontWeight = FontWeight.Black,
                            color = GBRDarkIndigo,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        // 1. Quantity Card (if it's a material addition step) - Beautiful, robust, has NO text overflows
                        if (currentInstruction.materialId.isNotEmpty()) {
                            val cumulativeWeight = remember(flattenedInstructions, activeStepIdx) {
                                flattenedInstructions.take(activeStepIdx + 1).sumOf { it.quantity }
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.5.dp, Color(0xFFE2E8F0)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "الكمية المطلوبة لتفريغ المادة 📦",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRBlueMain
                                    )
                                    
                                    val qtyStr = formatSupervisorQtyClean(currentInstruction.quantity)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = qtyStr,
                                            fontSize = if (qtyStr.length > 5) 36.sp else 44.sp,
                                            fontWeight = FontWeight.Black,
                                            color = GBRPinkAccent
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "كغم",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = GBRDarkIndigo
                                        )
                                    }

                                    // Cumulative Weight Badge (الوزن التراكمي)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFEEF2F6),
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text("⚖️", fontSize = 11.sp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "الوزن التراكمي:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF475569)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "${formatSupervisorQtyClean(cumulativeWeight)} كغم",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                color = GBRDarkIndigo
                                            )
                                        }
                                    }

                                    // مادة الماء: أيقونة تنقلك إلى صفحة التحكم بالمعدات على خط هذه التركيبة (تظهر فقط إن حُدد للتركيبة خط)
                                    val waterRmForStep = rawMaterialsList.find { it.id == currentInstruction.materialId }
                                    val isWaterStep = waterRmForStep != null && listOf(waterRmForStep.name, waterRmForStep.productionName).any {
                                        val n = it.trim(); n == "ماء" || n.equals("water", ignoreCase = true)
                                    }
                                    val waterLineIdx = formulationsForWater.find { it.id == order.formulationId }?.productionLine ?: -1
                                    if (isWaterStep && waterLineIdx >= 0) {
                                        val waterCtxAndroid = androidx.compose.ui.platform.LocalContext.current
                                        val waterLineName = remember(waterLineIdx) {
                                            waterCtxAndroid.getSharedPreferences("gbr_equipment_prefs", android.content.Context.MODE_PRIVATE)
                                                .getString("line_${waterLineIdx + 1}_name", if (waterLineIdx == 0) "خط الإنتاج رقم 1" else "خط الإنتاج رقم 2")
                                        }
                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.openEquipmentForWaterStep(
                                                    GbrViewModel.WaterStepContext(order.id, stepKey, waterLineIdx, currentInstruction.quantity, order.orderNumber)
                                                )
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                        ) {
                                            Text("💧", fontSize = 16.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("التحكم بالماء من $waterLineName", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    // IMPROVEMENT 1: Button "تعديل الكمية المطلوبة" nested directly here, uses fillMaxWidth and never clips
                                    OutlinedButton(
                                        onClick = { showAdjustmentDialog = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain),
                                        border = BorderStroke(1.5.dp, GBRBlueMain),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 44.dp)
                                            .testTag("shopfloor_edit_qty_button")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("✏️", fontSize = 14.sp)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "تعديل",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.5.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Grinding / Timer Card (if durationMinutes > 0)
                        if (currentInstruction.durationMinutes > 0) {
                            if (startTimeMs == -1L) {
                                // Waiting to start grinding
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                                    border = BorderStroke(1.5.dp, Color(0xFFCBD5E1)),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "⏱️ مدة الطحن والخلط المطلوبة",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = "${currentInstruction.durationMinutes} دقيقة",
                                            fontSize = 32.sp,
                                            fontWeight = FontWeight.Black,
                                            color = GBRDarkIndigo
                                        )
                                        Text(
                                            text = "في انتظار الضغط على بدء الطحن بالأسفل لتنشيط العد التنازلي والخدمة.",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                if (timeLeftSec > 0) {
                                    // Grinding timer in process
                                    val mins = timeLeftSec / 60
                                    val secs = timeLeftSec % 60
                                    val formattedTime = String.format(Locale.US, "%02d:%02d", mins, secs)

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isPaused) Color(0xFFF1F5F9) else Color(0xFFFFFBEB)
                                        ),
                                        border = BorderStroke(1.5.dp, if (isPaused) Color(0xFFCBD5E1) else Color(0xFFFDE68A)),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = if (isPaused) "⏱️ مؤقت الطحن والخلط (موقوف مؤقتاً)" else "⏱️ مؤقت طحن وتجانس وتماثل الخلط جاري العمل...",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPaused) Color.Gray else WarningOrange
                                            )
                                            Text(
                                                text = formattedTime,
                                                fontSize = 54.sp,
                                                fontWeight = FontWeight.Black,
                                                color = if (isPaused) Color.Gray else WarningOrange
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                // Pause / Resume Button
                                                Button(
                                                    onClick = {
                                                        if (isPaused) {
                                                            // Resume
                                                            val newStartTimeMs = System.currentTimeMillis() - (totalSec - timeLeftSec) * 1000L
                                                            viewModel.resumeGrindTimer(order.id, stepKey, newStartTimeMs)
                                                            try {
                                                                val serviceIntent = android.content.Intent(context, com.example.GrindingTimerService::class.java).apply {
                                                                    action = com.example.GrindingTimerService.ACTION_START
                                                                    putExtra("orderId", order.id)
                                                                    putExtra("orderNumber", order.orderNumber)
                                                                    putExtra("batchNumber", order.batchNumber)
                                                                    putExtra("stepKey", stepKey)
                                                                    putExtra("title", currentInstruction.title)
                                                                    putExtra("durationMinutes", currentInstruction.durationMinutes)
                                                                    putExtra("startTimeMs", newStartTimeMs)
                                                                }
                                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                                    context.startForegroundService(serviceIntent)
                                                                } else {
                                                                    context.startService(serviceIntent)
                                                                }
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                            }
                                                        } else {
                                                            // Pause
                                                            viewModel.pauseGrindTimer(order.id, stepKey, timeLeftSec)
                                                            try {
                                                                context.stopService(android.content.Intent(context, com.example.GrindingTimerService::class.java))
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isPaused) GBRBlueMain else WarningOrange
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = if (isPaused) "استئناف" else "إيقاف مؤقت",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }

                                                // Cancel Button
                                                Button(
                                                    onClick = {
                                                        viewModel.bypassGrindTimer(order.id, stepKey)
                                                        viewModel.stopGrindingAlarm(context, order.id, stepKey)
                                                        stopAlarm(true)
                                                    },
                                                    modifier = Modifier.weight(1f).height(48.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = ErrorRed
                                                    )
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "إلغاء / تجاوز",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Text(
                                                text = "الخدمة الخلفية تعمل لتنبيهك فور انتهاء الوقت حتى لو كان الهاتف مغلقاً.",
                                                fontSize = 11.sp,
                                                color = if (isPaused) Color.Gray else WarningOrange.copy(alpha = 0.8f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                } else {
                                    // Timer FINISHED - NO BIG TIMER NUMBERS, only beautiful success card
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                                        border = BorderStroke(1.5.dp, Color(0xFFA7F3D0)),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(20.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = if (isAlertedAlready) "✓ تم الانتهاء من الطحن والتجانس" else "✓ انتهى وقت الطحن",
                                                fontSize = if (isAlertedAlready) 24.sp else 26.sp,
                                                fontWeight = FontWeight.Black,
                                                color = SuccessGreen,
                                                textAlign = TextAlign.Center
                                            )
                                            Text(
                                                text = if (isAlertedAlready) "تم الانتهاء والتنبيه مسبقاً" else "يمكنك الانتقال للمرحلة التالية.",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SuccessGreen.copy(alpha = 0.9f),
                                                textAlign = TextAlign.Center
                                            )

                                            if (isGrindingAlarmActive || !isAlertedAlready) {
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Button(
                                                    onClick = {
                                                        viewModel.stopGrindingAlarm(context, order.id, stepKey)
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Warning,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "إيقاف صوت التنبيه 📴",
                                                        fontSize = 16.sp,
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

                        // 3. Ready to proceed helper if NO timer and NO quantity
                        if (currentInstruction.durationMinutes <= 0 && currentInstruction.quantity <= 0.0) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                                border = BorderStroke(1.5.dp, Color(0xFFA7F3D0)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "جاهز للتنفيذ والخلط ✓",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        color = SuccessGreen,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        // 4. Instructions Block (renders if isNotBlank)
                        if (currentInstruction.instruction.isNotBlank()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "التعليمات: ${currentInstruction.instruction}",
                                        fontSize = 13.5.sp,
                                        color = Color.DarkGray,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // 5. Render dialog if activated
                        if (showAdjustmentDialog && currentInstruction.materialId.isNotEmpty()) {
                            val originalQty = currentInstruction.quantity

                            ProductionAdjustmentDialog(
                                materialName = currentInstruction.title,
                                originalQty = originalQty,
                                onDismiss = { showAdjustmentDialog = false },
                                onSave = { newQty, reason, notes ->
                                    viewModel.applyProductionAdjustment(
                                        orderId = order.id,
                                        materialId = currentInstruction.materialId,
                                        materialName = currentInstruction.title,
                                        originalQty = originalQty,
                                        newQty = newQty,
                                        reason = reason,
                                        notes = notes,
                                        userName = currentUser?.fullName?.ifBlank { currentUser?.name } ?: "عامل صالة الإنتاج",
                                        recipeItemId = currentInstruction.recipeItemId
                                    )
                                    viewModel.logExecutionStepEvent(
                                        orderId = order.id,
                                        eventName = "تعديل كمية الإنتاج (صالة الإنتاج)",
                                        description = "قام العامل ${currentUser?.fullName?.ifBlank { currentUser?.name } ?: "عامل صالة الإنتاج"} بتعديل كمية المادة ${currentInstruction.title} من ${formatSupervisorQtyClean(originalQty)} كغم إلى ${formatSupervisorQtyClean(newQty)} كغم. السبب: $reason"
                                    )
                                },
                                rawMaterials = if (currentInstruction.recipeItemId.isBlank()) emptyList() else rawMaterialsList,
                                currentMaterialId = currentInstruction.materialId,
                                onSubstitute = { newMat, newQty, reason, notes ->
                                    val who = currentUser?.fullName?.ifBlank { currentUser?.name } ?: "عامل صالة الإنتاج"
                                    viewModel.applyProductionSubstitution(
                                        orderId = order.id,
                                        recipeItemId = currentInstruction.recipeItemId,
                                        oldMaterialId = currentInstruction.materialId,
                                        oldMaterialName = currentInstruction.title,
                                        newMaterial = newMat,
                                        originalQty = originalQty,
                                        newQty = newQty,
                                        reason = reason,
                                        notes = notes,
                                        userName = who
                                    )
                                    viewModel.logExecutionStepEvent(
                                        orderId = order.id,
                                        eventName = "استبدال مادة (صالة الإنتاج)",
                                        description = "قام العامل $who باستبدال المادة ${currentInstruction.title} بالمادة ${newMat.name} (${formatSupervisorQtyClean(newQty)} كغم). السبب: $reason"
                                    )
                                }
                            )
                        }
                    }
                }

                // 3. CTA Action Area (Main single big action button + small undo to prevent free navigation)
                val canProceed = if (currentInstruction.durationMinutes > 0) {
                    isBypassed || (startTimeMs != -1L && isTimerFinished)
                } else {
                    true
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    if (currentInstruction.durationMinutes > 0) {
                        if (startTimeMs == -1L) {
                            Text(
                                text = "💡 يرجى الضغط على زر بدء الطحن لتشغيل الموقت وحساب الوقت المتبقي في الوقت الفعلي.",
                                fontSize = 12.sp,
                                color = GBRBlueMain,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                        } else if (!isTimerFinished) {
                            Text(
                                text = "⚠️ يرجى انتظار انتهاء المؤقت بالكامل وتماثل الخلط قبل الانتقال للخطوة التالية.",
                                fontSize = 12.sp,
                                color = ErrorRed,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                        }
                    }

                    // MAIN GIGANTIC CONFIRM ACTION/START BUTTON (زر الإجراء الكبير جداً)
                    if (currentInstruction.durationMinutes > 0 && startTimeMs == -1L && !isBypassed) {
                        Button(
                            onClick = {
                                stopAlarm(true)
                                val nowTime = System.currentTimeMillis()
                                viewModel.saveGrindStartTime(order.id, stepKey, nowTime)
                                try {
                                    val serviceIntent = android.content.Intent(context, com.example.GrindingTimerService::class.java).apply {
                                        action = com.example.GrindingTimerService.ACTION_START
                                        putExtra("orderId", order.id)
                                        putExtra("orderNumber", order.orderNumber)
                                        putExtra("batchNumber", order.batchNumber)
                                        putExtra("stepKey", stepKey)
                                        putExtra("title", currentInstruction.title)
                                        putExtra("durationMinutes", currentInstruction.durationMinutes)
                                        putExtra("startTimeMs", nowTime)
                                    }
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .testTag("start_grind_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "▶ بدء الطحن",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                if (canProceed) {
                                    if (currentInstruction.quantity > 0.0) {
                                        showAddConfirmationDialog = true
                                    } else {
                                        stopAlarm(true)
                                        val logName = "تخطيط ومطابقة الموقت"
                                        val desc = "صالة الإنتاج: تم انتهاء موقت تجانس المرحلة ${currentInstruction.phaseName} بنجاح"
                                        viewModel.logExecutionStepEvent(order.id, logName, desc)

                                        val compositeKey = if (currentInstruction.recipeItemId.isNotBlank()) "p${currentInstruction.phaseIndex}_ri${currentInstruction.recipeItemId}" else "p${currentInstruction.phaseIndex}_it${currentInstruction.materialId}"
                                        val nextSet = completedItemsSet.toMutableSet()
                                        nextSet.add(compositeKey)
                                        completedItemsSet = nextSet

                                        val totalSteps = flattenedInstructions.size
                                        val completedCount = flattenedInstructions.count { step ->
                                            val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                                            nextSet.contains(key) || nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
                                        }
                                        val progressPercent = if (totalSteps > 0) (completedCount * 100) / totalSteps else 0

                                        val firstUncompleted = flattenedInstructions.indexOfFirst { step ->
                                            val key = if (step.recipeItemId.isNotBlank()) "p${step.phaseIndex}_ri${step.recipeItemId}" else "p${step.phaseIndex}_it${step.materialId}"
                                            !nextSet.contains(key) && !nextSet.contains("p${step.phaseIndex}_it${step.materialId}")
                                        }
                                        val finalPhaseIdx = if (firstUncompleted != -1) firstUncompleted else totalSteps

                                        activeStepIdx = finalPhaseIdx

                                        viewModel.updateExecutionProgress(
                                            orderId = order.id,
                                            phaseIndex = finalPhaseIdx,
                                            itemIndex = 0,
                                            completedItemsJson = JSONArray(nextSet.toList()).toString(),
                                            progressPercent = progressPercent.coerceIn(0, 100)
                                        )
                                    }
                                }
                            },
                            enabled = canProceed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .testTag("next_step_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Text(
                                    text = "✓ تمت الإضافة",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // SMALL SEPARATED UNDO BUTTON (زر التراجع المكتفي والصغير بالأسفل)
                    if (activeStepIdx > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                stopAlarm(true)
                                val lastStep = flattenedInstructions[activeStepIdx - 1]
                                itemToUndo = lastStep
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "↩ تراجع",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

// Data holder for Workers view
data class ShopFloorStep(
    val phaseIndex: Int,
    val phaseName: String,
    val title: String,
    val quantity: Double,
    val instruction: String,
    val mixerRpm: Int,
    val durationMinutes: Int,
    val materialId: String,
    val isPhaseCompletionStep: Boolean,
    val recipeItemId: String = ""
)

@Composable
fun SetPackagingStartTimeDialog(
    order: ProductionOrder,
    orderEvents: List<ProductionOrderEvent>,
    currentPackagingStartTime: Long,
    onDismiss: () -> Unit,
    onConfirm: (customTimeMs: Long) -> Unit
) {
    val sdfDateTime = remember { java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.US) }

    val mixingEvents = remember(orderEvents) {
        orderEvents.filter { ev ->
            val name = ev.eventName
            name != "بدء التعبئة" && name != "إكمال التنفيذ" && name != "إلغاء التنفيذ" && ev.timestamp > 0L
        }
    }
    val maxEventTs = remember(mixingEvents) { mixingEvents.maxOfOrNull { it.timestamp } ?: 0L }
    val startTs = remember(order) { if (order.startTime > 0L) order.startTime else order.createdAt }
    val lastIngredientTime = remember(startTs, maxEventTs) { maxOf(startTs, maxEventTs) }
    val lastIngredientTimeStr = remember(lastIngredientTime) { sdfDateTime.format(java.util.Date(lastIngredientTime)) }

    val initialMs = if (currentPackagingStartTime > 0L) currentPackagingStartTime else System.currentTimeMillis()
    var selectedTimeMs by remember { mutableStateOf(initialMs) }

    val cal = remember(selectedTimeMs) {
        java.util.Calendar.getInstance().apply { timeInMillis = selectedTimeMs }
    }
    val initialHour12 = remember(cal) {
        val h = cal.get(java.util.Calendar.HOUR)
        if (h == 0) 12 else h
    }
    var hour12 by remember(selectedTimeMs) { mutableStateOf(initialHour12) }
    var minute by remember(selectedTimeMs) {
        mutableStateOf(cal.get(java.util.Calendar.MINUTE))
    }
    var isAm by remember(selectedTimeMs) {
        mutableStateOf(cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM)
    }

    val updateCalFromState = { h: Int, m: Int, am: Boolean ->
        val newCal = java.util.Calendar.getInstance().apply {
            timeInMillis = selectedTimeMs
            val h24 = if (am) {
                if (h == 12) 0 else h
            } else {
                if (h == 12) 12 else h + 12
            }
            set(java.util.Calendar.HOUR_OF_DAY, h24)
            set(java.util.Calendar.MINUTE, m)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        selectedTimeMs = newCal.timeInMillis
    }

    val isBeforeLastIngredient = selectedTimeMs < lastIngredientTime
    val formattedSelectedTime = remember(selectedTimeMs) { sdfDateTime.format(java.util.Date(selectedTimeMs)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = GBRBlueMain)
                Text(
                    text = "تحديد وقت التعبئة يدويا",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Info Box showing last ingredient time
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📌 وقت إضافة آخر مادة / بدء الخلط:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            color = Color(0xFF1E40AF)
                        )
                        Text(
                            text = lastIngredientTimeStr,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = Color(0xFF1E3A8A)
                        )
                        Text(
                            text = "قاعدة هامة: يجب ألا يكون وقت بدء التعبئة قبل وقت إضافة آخر مادة.",
                            fontSize = 10.5.sp,
                            color = Color(0xFF3B82F6)
                        )
                    }
                }

                // Quick Offset Shortcuts
                Text(
                    text = "اختصارات سريعة لنسيان الزر:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = GBRDarkIndigo
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val now = System.currentTimeMillis()
                    val offsets = listOf(
                        "الآن ⏱️" to 0L,
                        "قبل 5 د" to 5 * 60 * 1000L,
                        "قبل 10 د" to 10 * 60 * 1000L,
                        "قبل 15 د" to 15 * 60 * 1000L,
                        "قبل 30 د" to 30 * 60 * 1000L,
                        "قبل 60 د" to 60 * 60 * 1000L
                    )
                    offsets.forEach { (label, offsetMs) ->
                        val targetMs = now - offsetMs
                        val isSel = Math.abs(selectedTimeMs - targetMs) < 60000L
                        FilterChip(
                            selected = isSel,
                            onClick = {
                                selectedTimeMs = maxOf(lastIngredientTime, targetMs)
                            },
                            label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GBRBlueMain,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Divider(color = IndustrialBorder)

                // Detailed Hour & Minute Selector
                Text(
                    text = "تحديد الوقت بالساعة والدقيقة:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = GBRDarkIndigo
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour Controls
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("الساعة", fontSize = 11.sp, color = Color.Gray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val newH = if (hour12 == 1) 12 else hour12 - 1
                                    updateCalFromState(newH, minute, isAm)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = String.format(java.util.Locale.US, "%02d", hour12),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            IconButton(
                                onClick = {
                                    val newH = if (hour12 == 12) 1 else hour12 + 1
                                    updateCalFromState(newH, minute, isAm)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(":", fontSize = 22.sp, fontWeight = FontWeight.Bold)

                    // Minute Controls
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("الدقيقة", fontSize = 11.sp, color = Color.Gray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val newM = if (minute == 0) 59 else minute - 1
                                    updateCalFromState(hour12, newM, isAm)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = String.format(java.util.Locale.US, "%02d", minute),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            IconButton(
                                onClick = {
                                    val newM = if (minute == 59) 0 else minute + 1
                                    updateCalFromState(hour12, newM, isAm)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // AM / PM Toggle
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("الفترة", fontSize = 11.sp, color = Color.Gray)
                        Button(
                            onClick = {
                                updateCalFromState(hour12, minute, !isAm)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAm) GBRBlueMain else GBRPinkAccent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isAm) "صباحاً ☀️" else "مساءً 🌙",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Status & Validation Box
                if (isBeforeLastIngredient) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = ErrorRed)
                            Column {
                                Text(
                                    text = "⚠️ خطأ في تسلسل أزمنة التشغيل:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    color = ErrorRed
                                )
                                Text(
                                    text = "الوقت المختار ($formattedSelectedTime) أقدم من وقت إضافة آخر مادة ($lastIngredientTimeStr). لا يمكن اختيار وقت قبل إكمال الخلط.",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF991B1B)
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                        border = BorderStroke(1.dp, Color(0xFF86EFAC)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                            Column {
                                Text(
                                    text = "✅ وقت بدء التعبئة المختار:",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    color = SuccessGreen
                                )
                                Text(
                                    text = formattedSelectedTime,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    color = Color(0xFF166534)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedTimeMs) },
                enabled = !isBeforeLastIngredient,
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
            ) {
                Text("حفظ وتطبيق الوقت 💾", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

// ==========================================
// OVERLAY: INPUT PACKAGING ON COMPLETION DUP
// ==========================================
@Composable
fun FinishBatchOverlay(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    val orderEvents by viewModel.selectedProductionOrderEvents.collectAsState()
    val phases by viewModel.selectedProductionOrderPhases.collectAsState()
    val recipeItems by viewModel.selectedProductionOrderRecipeItems.collectAsState()
    val rawMaterialsList by viewModel.rawMaterials.collectAsState()
    var showReferenceDialog by remember { mutableStateOf(false) }
    var showSetPackagingTimeDialog by remember { mutableStateOf(false) }

    val completedItemsSet = remember(order.completedItemsJson) {
        val set = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(order.completedItemsJson.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
        } catch (e: Exception) {}
        set
    }

    val snapshotPackList = remember(order.packagingSnapshotJson) {
        parsePackContents(order.packagingSnapshotJson)
    }

    val inputtedCounts = remember(order.id) {
        val map = androidx.compose.runtime.mutableStateMapOf<String, String>()
        try {
            val arr = org.json.JSONArray(order.actualPackagingJson.ifBlank { "[]" })
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idStr = obj.optString("id", "")
                val count = obj.optInt("actualCount", 0)
                if (idStr.isNotBlank() && count > 0) {
                    map[idStr] = count.toString()
                }
            }
        } catch (e: Exception) {}
        map
    }
    val notesPartsForFinish = remember(order.notes) { order.notes.split("\nملاحظات الإكمال:") }
    val originalNotesForFinish = remember(notesPartsForFinish) { notesPartsForFinish.getOrNull(0)?.trim() ?: "" }
    val initialFinalNotesForFinish = remember(notesPartsForFinish) { if (notesPartsForFinish.size > 1) notesPartsForFinish[1].trim() else "" }
    var notesInput by remember(order.id) { mutableStateOf(initialFinalNotesForFinish) }
    var showDeviationWarningDialog by remember { mutableStateOf(false) }

    val packagingStartTime = remember(order.timerStartTimesJson) {
        try {
            val jsonObj = org.json.JSONObject(order.timerStartTimesJson)
            if (jsonObj.has("packaging_start")) {
                jsonObj.getLong("packaging_start")
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    val performSubmit = {
        val actualList = mutableListOf<org.json.JSONObject>()
        if (snapshotPackList.isEmpty()) {
            val count = inputtedCounts["1"]?.toIntOrNull() ?: 0
            actualList.add(org.json.JSONObject().apply {
                put("id", "1")
                put("name", "سطل معياري 18 لتر")
                put("netWeight", 18.0)
                put("actualCount", count)
            })
        } else {
            snapshotPackList.forEach { pack ->
                val idStr = pack["id"] ?: "1"
                val name = pack["name"] ?: "عبوة"
                val netWt = pack["netWeight"]?.toDoubleOrNull() ?: 18.0
                val count = inputtedCounts[idStr]?.toIntOrNull() ?: 0

                actualList.add(org.json.JSONObject().apply {
                    put("id", idStr)
                    put("name", name)
                    put("netWeight", netWt)
                    put("actualCount", count)
                })
            }
        }
        val actualPackagingJson = org.json.JSONArray(actualList).toString()

        viewModel.completeProductionExecution(
            orderId = order.id,
            actualPackagingJson = actualPackagingJson,
            finalNotes = notesInput
        )
        viewModel.closeSelectedProductionOrder() // Close views
    }

    val saveIntermediate = { currentMap: Map<String, String>, currentNotes: String ->
        val actualList = mutableListOf<org.json.JSONObject>()
        if (snapshotPackList.isEmpty()) {
            val count = currentMap["1"]?.toIntOrNull() ?: 0
            actualList.add(org.json.JSONObject().apply {
                put("id", "1")
                put("name", "سطل معياري 18 لتر")
                put("netWeight", 18.0)
                put("actualCount", count)
            })
        } else {
            snapshotPackList.forEach { pack ->
                val idStr = pack["id"] ?: "1"
                val name = pack["name"] ?: "عبوة"
                val netWt = pack["netWeight"]?.toDoubleOrNull() ?: 18.0
                val count = currentMap[idStr]?.toIntOrNull() ?: 0

                actualList.add(org.json.JSONObject().apply {
                    put("id", idStr)
                    put("name", name)
                    put("netWeight", netWt)
                    put("actualCount", count)
                })
            }
        }
        val actualPackagingJson = org.json.JSONArray(actualList).toString()
        val combinedNotes = if (currentNotes.isNotBlank()) "$originalNotesForFinish\nملاحظات الإكمال: $currentNotes" else originalNotesForFinish
        viewModel.updateIntermediatePackaging(
            orderId = order.id,
            actualPackagingJson = actualPackagingJson,
            notes = combinedNotes
        )
    }

    val totalProducedPacks = if (snapshotPackList.isEmpty()) {
        inputtedCounts["1"]?.toIntOrNull() ?: 0
    } else {
        snapshotPackList.sumOf { pack ->
            val idStr = pack["id"] ?: "1"
            val textVal = inputtedCounts[idStr] ?: ""
            textVal.toIntOrNull() ?: 0
        }
    }

    val totalActualWeight = if (snapshotPackList.isEmpty()) {
        (inputtedCounts["1"]?.toIntOrNull() ?: 0) * 18.0
    } else {
        snapshotPackList.sumOf { pack ->
            val idStr = pack["id"] ?: "1"
            val textVal = inputtedCounts[idStr] ?: ""
            val actualCount = textVal.toIntOrNull() ?: 0
            val netWt = pack["netWeight"]?.toDoubleOrNull() ?: 18.0
            actualCount * netWt
        }
    }

    val totalExpectedWeight = order.requiredWeightKg
    val weightDiff = totalActualWeight - totalExpectedWeight

    val isInputValid = if (snapshotPackList.isEmpty()) {
        val v = inputtedCounts["1"] ?: ""
        v.isEmpty() || v.toIntOrNull() != null
    } else {
        snapshotPackList.all { pack ->
            val idStr = pack["id"] ?: "1"
            val v = inputtedCounts[idStr] ?: ""
            v.isEmpty() || v.toIntOrNull() != null
        }
    }

    val isButtonEnabled = isInputValid && totalProducedPacks > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IndustrialGrayBg)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
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
                Text("المحاذاة الفنية وتعبئة الدفعة", fontSize = 18.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo)
                IconButton(
                    onClick = { showReferenceDialog = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "عرض قائمة المواد ومواعيد الإضافة",
                        tint = GBRBlueMain
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = ErrorRed)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تفاصيل وحجم الدفعة:", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 12.sp)
                Text("اسم المنتج: ${order.formulationName}", fontSize = 11.sp, color = Color.Gray)
                Text("الوزن النهائي المحسوب كلياً: ${formatNum(order.requiredWeightKg)} كجم", fontSize = 11.sp, color = GBRPinkAccent, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (packagingStartTime <= 0L) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = GBRBlueMain,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "بدء عملية التعبئة والتغليف",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = GBRDarkIndigo
                    )
                    Text(
                        text = "يرجى الضغط على الزر أدناه عند البدء الفعلي بتعبئة المنتج النهائي في العبوات، لحساب مدة التعبئة بدقة.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Button(
                        onClick = {
                            viewModel.recordPackagingStartTime(order.id)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ابدأ عملية التعبئة الآن 📦",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            showSetPackagingTimeDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF0284C7)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7))
                    ) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تحديد وقت التعبئة يدويا",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = Color(0xFF0284C7)
                        )
                    }

                    OutlinedButton(
                        onClick = { showReferenceDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, GBRBlueMain),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain)
                    ) {
                        Icon(imageVector = Icons.Default.Visibility, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "عرض قائمة المواد المضافة وساعة الإضافة 👁️",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = GBRBlueMain
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(22.dp))
                        val sdf = remember { java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.US) }
                        Column {
                            Text(
                                text = "وقت بدء التعبئة المسجل:",
                                fontSize = 10.5.sp,
                                color = Color(0xFF0369A1)
                            )
                            Text(
                                text = sdf.format(java.util.Date(packagingStartTime)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0C4A6E)
                            )
                        }
                    }
                    Button(
                        onClick = { showSetPackagingTimeDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تعديل الوقت 🕒", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Input forms for actual pack counts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("سجل كميات التعبئة الفعلية المُصنعة من العبوات:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 12.sp)
                IconButton(
                    onClick = { showReferenceDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "عرض قائمة المواد وساعة الإضافة",
                        tint = GBRBlueMain,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (snapshotPackList.isEmpty()) {
                // standard 18L input fallback
                val countVal = inputtedCounts["1"] ?: ""
                OutlinedTextField(
                    value = countVal,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            inputtedCounts["1"] = newValue
                            saveIntermediate(inputtedCounts.toMap(), notesInput)
                        }
                    },
                    label = { Text("سطل معياري 18 لتر - العدد الفعلي") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )
            } else {
                snapshotPackList.forEach { pack ->
                    val idStr = pack["id"] ?: "1"
                    val name = pack["name"] ?: "عبوة"
                    val netWt = pack["netWeight"]?.toDoubleOrNull() ?: 18.0
                    val expectedCount = if (netWt > 0.0) (order.requiredWeightKg / netWt).toInt() else 0
                    val textVal = inputtedCounts[idStr] ?: ""

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        border = BorderStroke(1.dp, IndustrialBorder),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo,
                                    fontSize = 14.sp
                                )
                                Surface(
                                    color = GBRDarkIndigo.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "وزن صافي: $netWt كجم",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = GBRDarkIndigo,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Expected section
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFEFF6FF))
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("العدد المتوقع", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("$expectedCount", fontSize = 16.sp, fontWeight = FontWeight.Black, color = GBRBlueMain)
                                }

                                // Actual section
                                Column(modifier = Modifier.weight(1.5f)) {
                                    Text("العدد الفعلي المعبأ", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                                    OutlinedTextField(
                                        value = textVal,
                                        onValueChange = { newValue ->
                                            if (newValue.all { it.isDigit() }) {
                                                inputtedCounts[idStr] = newValue
                                                saveIntermediate(inputtedCounts.toMap(), notesInput)
                                            }
                                        },
                                        placeholder = { Text("مثال: 100") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("actual_count_${idStr}"),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Packaging Summary Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "📊 ملخص ومراقبة التعبئة الكلية المصنّعة:",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF166534),
                        fontSize = 12.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("إجمالي عدد العبوات الفعلي:", fontSize = 12.sp, color = Color.Gray)
                        Text("$totalProducedPacks عبوة", fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("إجمالي الوزن المتوقع للوجبة:", fontSize = 12.sp, color = Color.Gray)
                        Text("${formatNum(totalExpectedWeight)} كجم", fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("إجمالي الوزن الفعلي بالتعبئة:", fontSize = 12.sp, color = Color.Gray)
                        Text("${formatNum(totalActualWeight)} كجم", fontWeight = FontWeight.Black, color = Color(0xFF166534), fontSize = 14.sp)
                    }

                    Divider(color = Color(0xFFDCFCE7))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("فارق الوزن بالتعبئة (الانحراف):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                        val hasEnteredAnyResults = totalProducedPacks > 0
                        val diffText = if (!hasEnteredAnyResults) {
                            "بانتظار نتائج"
                        } else if (weightDiff == 0.0) {
                            "0 كجم"
                        } else if (weightDiff > 0.0) {
                            "+${formatNum(weightDiff)} كجم"
                        } else {
                            "${formatNum(weightDiff)} كجم"
                        }
                        val diffColor = if (!hasEnteredAnyResults) {
                            Color.Gray
                        } else if (weightDiff == 0.0) {
                            Color.Gray
                        } else if (weightDiff > 0.0) {
                            Color(0xFF16A34A)
                        } else {
                            Color(0xFFDC2626)
                        }
                        Text(
                            text = diffText,
                            fontWeight = FontWeight.Black,
                            color = diffColor,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("أي ملاحظات ختامية أو انحرافات تشغيلية:", fontWeight = FontWeight.Bold, color = GBRBlueMain, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = notesInput,
                onValueChange = { newValue ->
                    notesInput = newValue
                    saveIntermediate(inputtedCounts.toMap(), newValue)
                },
                placeholder = { Text("مثال: تم تفريغ الدفعة بنجاح بدون هدر كيميائي...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val deviationValue = kotlin.math.abs(weightDiff)
                    if (deviationValue > 30.0) {
                        showDeviationWarningDialog = true
                    } else {
                        performSubmit()
                    }
                },
                enabled = isButtonEnabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SuccessGreen,
                    disabledContainerColor = Color.LightGray
                ),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("complete_run_confirm_btn")
            ) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("✔️ إنهاء الدفعة وإصدار سند الإغلاق الفوري للخط", fontWeight = FontWeight.Bold, color = Color.White)
            }

            if (showDeviationWarningDialog) {
                AlertDialog(
                    onDismissRequest = { showDeviationWarningDialog = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFDC2626)
                            )
                            Text(
                                text = "تنبيه: انحراف في كمية التعبئة",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo
                            )
                        }
                    },
                    text = {
                        Text(
                            text = "لقد تم رصد انحراف كبير في إجمالي وزن التعبئة الفعلية يزيد عن 30 كجم.\n\n" +
                                    "• : الوزن المتوقع للوجبة: ${formatNum(totalExpectedWeight)} كجم\n" +
                                    "• : الوزن الفعلي بالتعبئة: ${formatNum(totalActualWeight)} كجم\n" +
                                    "• : قيمة الانحراف: ${if (weightDiff > 0) "+" else ""}${formatNum(weightDiff)} كجم\n\n" +
                                    "هل أنت متأكد من رغبتك في الاستمرار وإغلاق الدفعة بهذا الانحراف؟",
                            fontSize = 14.sp,
                            color = Color(0xFF334155),
                            lineHeight = 20.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeviationWarningDialog = false
                                performSubmit()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("نعم، استمر بالإغلاق", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { showDeviationWarningDialog = false }
                        ) {
                            Text("تراجع لتعديل الكميات", color = GBRDarkIndigo, fontWeight = FontWeight.Bold)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color.White
                )
            }

            if (!isButtonEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = "تنبيه", tint = Color(0xFFDC2626))
                        Text(
                            text = "يرجى تعبئة كمية التعبئة الفعلية بواقع عبوة واحدة على الأقل لإنهاء الدفعة.",
                            color = Color(0xFF991B1B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showReferenceDialog) {
        ProductionBatchReferenceDialog(
            order = order,
            phases = phases,
            recipeItems = recipeItems,
            rawMaterialsList = rawMaterialsList,
            completedItemsSet = completedItemsSet,
            orderEvents = orderEvents,
            onDismiss = { showReferenceDialog = false }
        )
    }

    if (showSetPackagingTimeDialog) {
        SetPackagingStartTimeDialog(
            order = order,
            orderEvents = orderEvents,
            currentPackagingStartTime = packagingStartTime,
            onDismiss = { showSetPackagingTimeDialog = false },
            onConfirm = { customTimeMs ->
                viewModel.recordPackagingStartTime(order.id, customTimeMs)
                showSetPackagingTimeDialog = false
            }
        )
    }
}


// Shared components empty representation
@Composable
fun EmptyBox(message: String, subMessage: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(imageVector = Icons.Default.List, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(52.dp))
            Text(message, fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 13.sp)
            Text(subMessage, color = Color.Gray, fontSize = 11.sp)
        }
    }
}

// parsing utility function
private fun parsePackContents(json: String?): List<Map<String, String>> {
    val list = mutableListOf<Map<String, String>>()
    if (json.isNullOrBlank()) return list
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            for (key in keys) {
                map[key] = obj.optString(key)
            }
            list.add(map)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return list
}

@Composable
fun HistoricalAdjustmentsView(
    order: ProductionOrder,
    viewModel: GbrViewModel
) {
    val adjustments by produceState<List<com.example.data.ProductionAdjustment>>(
        initialValue = viewModel.selectedProductionOrderAdjustments.value.filter { it.productionOrderId == order.id },
        key1 = order.id
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.getProductionAdjustmentsSync(order.id)
        }
        viewModel.getProductionAdjustmentsFlow(order.id).collect {
            value = it
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, IndustrialBorder),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "⚙️ تعديلات تركيبة الدفعة الموثقة:",
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    fontSize = 14.sp
                )

                Divider(color = IndustrialBorder)

                if (adjustments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("لم يتم إجراء أي تعديل للكميات الكيميائية أثناء تنفيذ هذه الدفعة.", fontSize = 12.sp, color = Color.Gray)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        adjustments.forEachIndexed { idx, adj ->
                            val diffSign = if (adj.difference >= 0) "+" else ""
                            val timeStr = try {
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                sdf.format(Date(adj.timestamp))
                            } catch (e: Exception) {
                                ""
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(adj.rawMaterialName, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 13.sp)
                                        Surface(
                                            color = GBRBlueMain.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "المسؤول: ${adj.userName}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRBlueMain,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    // Display Weights
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("الكمية الأصلية", fontSize = 10.sp, color = Color.Gray)
                                            Text("${formatSupervisorQtyClean(adj.originalQuantity)} كجم", fontWeight = FontWeight.Bold, color = Color(0xFF475569), fontSize = 12.sp)
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("الكمية المعدلة", fontSize = 10.sp, color = Color.Gray)
                                            Text("${formatSupervisorQtyClean(adj.newQuantity)} كجم", fontWeight = FontWeight.Bold, color = GBRPinkAccent, fontSize = 12.sp)
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("فارق التعديل", fontSize = 10.sp, color = Color.Gray)
                                            val diffColor = if (adj.difference >= 0) SuccessGreen else ErrorRed
                                            Text("$diffSign${formatSupervisorQtyClean(adj.difference)} كجم", fontWeight = FontWeight.Black, color = diffColor, fontSize = 12.sp)
                                        }
                                    }

                                    Divider(color = Color(0xFFE2E8F0).copy(alpha = 0.5f), thickness = 0.5.dp)

                                    // Reason & Notes
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text("سبب التعديل: ", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(end = 4.dp))
                                        Text(adj.reason, fontSize = 11.sp, color = Color(0xFF1E293B))
                                    }

                                    if (adj.notes.isNotBlank()) {
                                        Row(modifier = Modifier.fillMaxWidth()) {
                                            Text("ملاحظة: ", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(end = 4.dp))
                                            Text(adj.notes, fontSize = 11.sp, color = Color(0xFF1E293B))
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(timeStr, fontSize = 10.sp, color = Color.Gray)
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
fun ProductionAdjustmentDialog(
    materialName: String,
    originalQty: Double,
    onDismiss: () -> Unit,
    onSave: (newQty: Double, reason: String, notes: String) -> Unit,
    // مواد خام للاستبدال؛ إن كانت فارغة أو onSubstitute=null يظهر تعديل الكمية فقط
    rawMaterials: List<com.example.data.RawMaterial> = emptyList(),
    currentMaterialId: String = "",
    onSubstitute: ((material: com.example.data.RawMaterial, newQty: Double, reason: String, notes: String) -> Unit)? = null
) {
    var newQtyStr by remember { mutableStateOf(originalQty.toString()) }
    var reason by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val canSubstitute = onSubstitute != null && rawMaterials.isNotEmpty()
    var substituteMode by remember { mutableStateOf(false) }
    var materialQuery by remember { mutableStateOf("") }
    var pickedMaterial by remember { mutableStateOf<com.example.data.RawMaterial?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "تعديل المادة أثناء الإنتاج",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = GBRDarkIndigo,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("مادة خام: $materialName", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GBRDarkIndigo)
                        Text("الكمية الأصلية: ${formatSupervisorQtyClean(originalQty)} كغم", fontSize = 12.sp, color = Color.Gray)
                    }
                }

                if (canSubstitute) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(false to "تعديل الكمية", true to "استبدال المادة").forEach { (mode, label) ->
                            val sel = substituteMode == mode
                            Surface(
                                modifier = Modifier.weight(1f).clickable { substituteMode = mode; showError = false },
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) GBRBlueMain else Color.White,
                                border = BorderStroke(1.dp, if (sel) GBRBlueMain else Color(0xFFCBD5E1))
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(vertical = 9.dp),
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (sel) Color.White else GBRDarkIndigo
                                )
                            }
                        }
                    }
                }

                if (substituteMode) {
                    OutlinedTextField(
                        value = materialQuery,
                        onValueChange = { materialQuery = it },
                        label = { Text("ابحث عن المادة البديلة", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val q = materialQuery.trim().lowercase()
                    val candidates = rawMaterials
                        .filter { it.id != currentMaterialId && it.isActive }
                        .filter { q.isBlank() || it.name.lowercase().contains(q) || it.productionName.lowercase().contains(q) }
                        .sortedBy { it.name.lowercase() }
                        .take(40)
                    Card(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (candidates.isEmpty()) {
                                Text("لا توجد مادة مطابقة", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(12.dp))
                            }
                            candidates.forEach { m ->
                                val sel = pickedMaterial?.id == m.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (sel) GBRBlueMain.copy(alpha = 0.12f) else Color.Transparent)
                                        .clickable { pickedMaterial = m }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        m.name,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 13.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                        color = GBRDarkIndigo
                                    )
                                    if (sel) Text("✔", color = GBRBlueMain, fontWeight = FontWeight.Bold)
                                }
                                Divider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                    Text(
                        text = pickedMaterial?.let { "البديلة المختارة: ${it.name}" } ?: "اختر المادة البديلة من القائمة",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pickedMaterial != null) SuccessGreen else Color.Gray
                    )
                }

                // New Quantity Fields
                OutlinedTextField(
                    value = newQtyStr,
                    onValueChange = { newQtyStr = it },
                    label = { Text(if (substituteMode) "كمية المادة البديلة (كغم)" else "الكمية الجديدة (كغم)", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(if (substituteMode) "سبب الاستبدال" else "سبب التعديل", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات إضافية (اختياري)", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                if (showError) {
                    Text(
                        if (substituteMode) "يرجى اختيار المادة البديلة وإدخال كمية صحيحة وتحديد سبب الاستبدال." else "يرجى إدخال كمية صحيحة وتحديد سبب التعديل.",
                        color = ErrorRed, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val nQty = newQtyStr.toDoubleOrNull()
                    if (substituteMode) {
                        val m = pickedMaterial
                        if (m != null && nQty != null && nQty > 0 && reason.isNotBlank()) {
                            onSubstitute?.invoke(m, nQty, reason, notes)
                            onDismiss()
                        } else {
                            showError = true
                        }
                    } else if (nQty != null && nQty >= 0 && reason.isNotBlank()) {
                        onSave(nQty, reason, notes)
                        onDismiss()
                    } else {
                        showError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (substituteMode) "حفظ الاستبدال" else "حفظ التعديل", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", fontWeight = FontWeight.Bold, color = Color.Gray)
            }
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(16.dp)
    )
}

// ------------------------------------------
// HISTORICAL QUALITY TREND & SPC ANALYSIS VIEW
// ------------------------------------------
data class QualityDataPoint(
    val order: ProductionOrder,
    val session: LabSession? = null,
    val test: LabTest? = null,
    val record: com.example.data.ProductionOrderTestRecord? = null,
    val testId: String = "",
    val testName: String = "",
    val value: Double,
    val label: String
) {
    val testDate: String
        get() = record?.testDate ?: session?.testDate ?: ""

    val operatorName: String
        get() = record?.let { order.operatorName } ?: session?.technicianName ?: ""

    val notes: String
        get() = test?.notes ?: ""
}

private fun safeParseDouble(valueStr: String?): Double? {
    if (valueStr == null) return null
    try {
        val clean = valueStr
            .replace("٠", "0")
            .replace("١", "1")
            .replace("٢", "2")
            .replace("٣", "3")
            .replace("٤", "4")
            .replace("٥", "5")
            .replace("٦", "6")
            .replace("٧", "7")
            .replace("٨", "8")
            .replace("٩", "9")
            .trim()
        val match = Regex("""[+-]?(?:\d+\.\d+|\d+|\.\d+)""").find(clean)
        return match?.value?.toDoubleOrNull()
    } catch (e: Exception) {
        return null
    }
}

private fun getTestConfigurationName(test: LabTest): String {
    val baseName = getTestDisplayNames(test).first
    val settings = getTestSettingsSummary(test)
    return if (settings != null) {
        "$baseName ($settings)"
    } else {
        baseName
    }
}

private fun normalizeTestName(name: String): String {
    return name.replace("🧪", "").replace("🔬", "").replace("📊", "").replace("⚖️", "").trim()
}

private fun extractSpindleAndSpeed(text: String): Pair<String?, String?> {
    if (text.isBlank()) return null to null

    // 1. Wizard JSON format
    if (text.contains("WIZARD_VISCOSITY:") || text.contains("WIZARD_COMP_VISCOSITY:")) {
        try {
            val json = if (text.contains("WIZARD_VISCOSITY:")) {
                text.substringAfter("WIZARD_VISCOSITY:").substringBefore("\n").trim()
            } else {
                text.substringAfter("WIZARD_COMP_VISCOSITY:").substringBefore("\n").trim()
            }
            val moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            if (text.contains("WIZARD_VISCOSITY:")) {
                val adapter = moshi.adapter(ViscosityTestData::class.java)
                val data = adapter.fromJson(json)
                if (data != null && data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                    return data.spindle.trim() to data.speed.trim()
                }
            } else {
                val adapter = moshi.adapter(ComparisonViscosityTestData::class.java)
                val compData = adapter.fromJson(json)
                val data = compData?.dataA ?: compData?.dataB
                if (data != null && data.spindle.isNotBlank() && data.speed.isNotBlank()) {
                    return data.spindle.trim() to data.speed.trim()
                }
            }
        } catch (e: Exception) {
            // fallback
        }
    }

    // 2. Pattern: Spindle X / Y RPM or Spindle X / Y
    val regexSpindlePattern = Regex("""(?:spindle|سبندل|مغزل)\s*[:=]?\s*([A-Za-z0-9]+)[^0-9\n]*?/\s*(\d+)""", RegexOption.IGNORE_CASE)
    val match1 = regexSpindlePattern.find(text)
    if (match1 != null) {
        return match1.groupValues[1].trim() to match1.groupValues[2].trim()
    }

    // 3. Pattern: Spindle X ... Speed Y
    val regexSpindleSpeed = Regex("""(?:spindle|سبندل|مغزل)\s*[:=]?\s*([A-Za-z0-9]+)[^0-9\n]*?(?:speed|rpm|سرعة|سرعه)\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    val match2 = regexSpindleSpeed.find(text)
    if (match2 != null) {
        return match2.groupValues[1].trim() to match2.groupValues[2].trim()
    }

    // 4. Pattern: S3/60, R3/60, RV3/60, or 3/60
    val regexFractionCode = Regex("""\b(?:S|R|RV|HA|HB)?([1-7])\s*/\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    val match3 = regexFractionCode.find(text)
    if (match3 != null) {
        return match3.groupValues[1].trim() to match3.groupValues[2].trim()
    }

    return null to null
}

private fun getArAndEnNames(fullName: String, explicitSpindle: String? = null, explicitSpeed: String? = null): Pair<String, String> {
    val cleanName = normalizeTestName(fullName)
    val nameLower = cleanName.lowercase()
    
    // Extract spindle and speed
    val (parsedSpindle, parsedSpeed) = if (explicitSpindle != null && explicitSpeed != null) {
        explicitSpindle to explicitSpeed
    } else {
        extractSpindleAndSpeed(fullName)
    }
    val spindleTag = if (parsedSpindle != null && parsedSpeed != null) "$parsedSpindle/$parsedSpeed" else ""

    // 1. Match predefined strings or key names first to ensure unification
    if (nameLower.contains("درجة القلوية") || nameLower == "ph" || nameLower == "ph value" || nameLower.contains("قلوية") || nameLower.contains("حموضة")) {
        return Pair("فحص درجة القلوية", "pH Value")
    }
    if (nameLower.contains("الكثافة النوعية") || nameLower.contains("specific gravity") || nameLower.contains("density") || nameLower.contains("كثافة")) {
        return Pair("فحص الكثافة النوعية", "Specific Gravity - Density")
    }
    if (nameLower.contains("الصلابة والجفاف") || nameLower.contains("solid content") || nameLower.contains("صلابة") || nameLower.contains("جفاف")) {
        return Pair("فحص نسبة الصلابة والجفاف", "Solid Content & Drying Time")
    }
    if (nameLower.contains("المادة الرابطة") || nameLower.contains("binder content") || nameLower.contains("مادة رابطة")) {
        return Pair("فحص نسبة المادة الرابطة", "Net Binder Content")
    }
    if (nameLower.contains("ثبات درجة اللون") || nameLower.contains("color match") || nameLower.contains("لمعان") || nameLower.contains("gloss")) {
        return Pair("فحص ثبات درجة اللون واللمعان", "Color Match & Gloss")
    }
    if (nameLower.contains("قوة الالتصاق") || nameLower.contains("adhesion") || nameLower.contains("التصاق")) {
        return Pair("فحص قوة الالتصاق والصفات الميكانيكية", "Adhesion & Hardness")
    }
    if (nameLower.contains("مقاومة الغسيل") || nameLower.contains("scrub") || nameLower.contains("غسيل") || nameLower.contains("احتكاك")) {
        return Pair("فحص مقاومة الغسيل والاحتكاك", "Scrub Resistance")
    }
    if (nameLower.contains("كوب فورد") || nameLower.contains("ford cup") || nameLower.contains("ford")) {
        return Pair("فحص لزوجة كوب فورد 4", "Ford Cup 4 Viscosity")
    }
    if (nameLower.contains("نعومة الطحن") || nameLower.contains("fineness of grind") || nameLower.contains("نعومة") || nameLower.contains("طحن")) {
        return Pair("فحص نعومة الطحن", "Fineness of Grind")
    }

    // Viscosity variants (with spindle/speed differentiation e.g. 3/60)
    if (nameLower.contains("ريولوج") || nameLower.contains("سلوك") || nameLower.contains("rheolog")) {
        return if (spindleTag.isNotBlank()) {
            Pair("فحص السلوك الريولوجي ($spindleTag)", "Rheology ($spindleTag)")
        } else {
            Pair("فحص السلوك الريولوجي", "Rheological Behavior")
        }
    }
    if (nameLower.contains("تخفيف") || nameLower.contains("dilut")) {
        return if (spindleTag.isNotBlank()) {
            Pair("فحص لزوجة التخفيف ($spindleTag)", "Dilution Viscosity ($spindleTag)")
        } else {
            Pair("فحص لزوجة التخفيف بالماء", "Water Dilution Viscosity")
        }
    }
    if (nameLower.contains("اللزوجة") || nameLower.contains("viscosity") || nameLower.contains("ku")) {
        return if (spindleTag.isNotBlank()) {
            Pair("فحص اللزوجة ($spindleTag)", "Viscosity ($spindleTag)")
        } else {
            Pair("فحص اللزوجة", "Viscosity Test - KU")
        }
    }
    
    // 2. Parentheses format: Arabic Name (English Name)
    if (cleanName.contains("(") && cleanName.contains(")")) {
        val startIdx = cleanName.indexOf("(")
        val endIdx = cleanName.lastIndexOf(")")
        if (endIdx > startIdx) {
            val arPart = cleanName.substring(0, startIdx).trim()
            val enPart = cleanName.substring(startIdx + 1, endIdx).trim()
            if (spindleTag.isNotBlank() && !arPart.contains(spindleTag)) {
                return Pair("$arPart ($spindleTag)", "$enPart ($spindleTag)")
            }
            return Pair(arPart, enPart)
        }
    }
    
    // 3. Slash format: Arabic Name / English Name
    if (cleanName.contains("/") && spindleTag.isBlank()) {
        val parts = cleanName.split("/")
        val arPart = parts[0].trim()
        val enPart = parts[1].trim()
        return Pair(arPart, enPart)
    }
    
    // 4. Try to parse English characters if any
    val englishRegex = Regex("[a-zA-Z0-9\\s-_/&]+")
    val match = englishRegex.find(cleanName)
    if (match != null && match.value.length > 2) {
        val enPart = match.value.trim()
        val arPart = cleanName.replace(enPart, "").replace("()", "").replace("-", "").trim()
        if (arPart.isNotBlank()) {
            if (spindleTag.isNotBlank() && !arPart.contains(spindleTag)) {
                return Pair("$arPart ($spindleTag)", "$enPart ($spindleTag)")
            }
            return Pair(arPart, enPart)
        }
    }
    
    if (spindleTag.isNotBlank() && !cleanName.contains(spindleTag)) {
        return Pair("$cleanName ($spindleTag)", "$cleanName ($spindleTag)")
    }
    
    return Pair(cleanName, "")
}

private fun getHistoricalTestName(tst: LabTest): String {
    val rawName = tst.name.ifBlank { getTestConfigurationName(tst) }
    val notes = tst.notes.trim()
    val combinedText = "$rawName $notes"
    
    val (spindle, speed) = extractSpindleAndSpeed(combinedText)
    val pair = getArAndEnNames(rawName, spindle, speed)
    val unifiedBase = if (pair.second.isNotBlank()) "${pair.first} (${pair.second})" else pair.first
    return unifiedBase
}

private fun getTestOrderPriority(tName: String): Double {
    val clean = tName.lowercase().trim()
    val rank = when {
        // 5. فحص لزوجة كوب فورد (تتم المعاينة قبل اللزوجة القياسية لمنع تصنيف كوب فورد كـ لزوجة قياسية)
        clean.contains("cup") || clean.contains("كوب") || clean.contains("فورد") || clean.contains("ford") -> 500.0

        // 4. فحص درجة القلويه ph
        clean.contains("درجة القلوية") || clean.contains("ph") || clean.contains("قلوية") || clean.contains("قلويه") || clean.contains("الحموضة") -> 400.0

        // 3. فحص لزوجة التخفيف بالماء
        clean.contains("تخفيف") || clean.contains("dilution") || clean.contains("dilut") -> 300.0

        // 2. فحص السلوك الريولوجي
        clean.contains("سلوك") || clean.contains("ريولوجي") || clean.contains("rheology") || clean.contains("rheological") -> 200.0

        // 1. فحص اللزوجة القياسية (الأبطأ سرعة بالأعلى والأسرع بالأسفل)
        clean.contains("اللزوجة") || clean.contains("viscosity") || clean.contains("ku") -> 100.0

        // 6. باقي الفحوصات ان وجدت (الكثافة، الصلابة، المادة الرابطة، إلخ)
        clean.contains("الكثافة") || clean.contains("كثافة") || clean.contains("density") || clean.contains("gravity") -> 601.0
        clean.contains("الصلابة") || clean.contains("الجفاف") || clean.contains("solid") || clean.contains("drying") -> 602.0
        clean.contains("المادة الرابطة") || clean.contains("binder") || clean.contains("رابطة") -> 603.0
        clean.contains("اللون") || clean.contains("اللمعان") || clean.contains("color") || clean.contains("gloss") -> 604.0
        clean.contains("الالتصاق") || clean.contains("الميكانيكية") || clean.contains("adhesion") || clean.contains("hardness") -> 605.0
        clean.contains("الغسيل") || clean.contains("الاحتكاك") || clean.contains("scrub") || clean.contains("resistance") -> 606.0
        clean.contains("نعومة") || clean.contains("الطحن") || clean.contains("fineness") || clean.contains("grind") -> 607.0
        else -> 699.0
    }

    // Sort Standard Viscosity by spindle and speed ascending.
    var speedVal = 0.0
    val (sp, spd) = extractSpindleAndSpeed(tName)
    if (spd != null) {
        val spdNum = spd.toDoubleOrNull() ?: 0.0
        val spNum = sp?.toDoubleOrNull() ?: 0.0
        speedVal = (spNum * 0.01) + (spdNum * 0.0001)
    }
    return rank + speedVal
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductQualityHistoricalAnalysisView(
    order: ProductionOrder,
    viewModel: GbrViewModel,
    isCurrentOrderOnly: Boolean = false,
    onBack: () -> Unit,
    onNavigateToSession: ((LabSession) -> Unit)? = null
) {
    val allOrders by viewModel.productionOrders.collectAsState()
    val allTestRecords by viewModel.allProductionOrderTestRecords.collectAsState()
    val qualityTests by viewModel.qualityTests.collectAsState()
    val allFormulationQualityTests by viewModel.allFormulationQualityTests.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()

    // Filter orders for same product/formulation
    val sameProductOrders = remember(allOrders, order.formulationId, order.formulationName, order.id, isCurrentOrderOnly) {
        if (isCurrentOrderOnly) {
            listOf(order)
        } else {
            val currentNameClean = order.formulationName.trim().lowercase()
            val list = allOrders.filter { 
                it.formulationId == order.formulationId || 
                (it.formulationName.isNotBlank() && it.formulationName.trim().lowercase() == currentNameClean)
            }
            if (list.none { it.id == order.id }) {
                list + order
            } else {
                list
            }.sortedBy { it.startTime.takeIf { t -> t > 0L } ?: it.createdAt }
        }
    }

    val orderIds = remember(sameProductOrders) { sameProductOrders.map { it.id }.toSet() }

    // Filter to direct and QC test records of these production orders
    val directRecords = remember(allTestRecords, orderIds) {
        allTestRecords.filter { it.productionOrderId in orderIds }
    }

    // Filter to lab sessions of these production orders (both direct and QC)
    val directSessions = remember(labSessions, orderIds) {
        labSessions.filter { session ->
            val props = (session.sampleProperties ?: "").trim()
            if (props.contains("ORDER_ID:")) {
                val oid = props.substringAfter("ORDER_ID:").substringBefore(":QC").substringBefore(":").trim()
                orderIds.contains(oid)
            } else {
                false
            }
        }
    }
    val directSessionIds = remember(directSessions) { directSessions.map { it.id }.toSet() }

    val directLabTests = remember(allLabTests, directSessionIds) {
        allLabTests.filter { it.sessionId in directSessionIds }
    }

    // Parse all results from both direct sources into structured QualityDataPoint objects
    val allDataPoints = remember(sameProductOrders, qualityTests, directSessions, directLabTests, isCurrentOrderOnly) {
        if (isCurrentOrderOnly) {
            val list = mutableListOf<QualityDataPoint>()
            // 1. Get production monitoring sessions for this order
            val prodSess = directSessions.filter { it.sampleProperties == "ORDER_ID:${order.id}" }
                .sortedBy { it.createdAt }
            // 2. Get post-production QC sessions for this order
            val qcSess = directSessions.filter { it.sampleProperties == "ORDER_ID:${order.id}:QC" }
                .sortedBy { it.createdAt }

            val orderedSessions = prodSess + qcSess

            var qcCount = 1
            orderedSessions.forEach { sess ->
                val isQC = sess.sampleProperties.contains(":QC")
                val labelStr = if (isQC) {
                    "متابعة جودة رقم ($qcCount)".also { qcCount++ }
                } else {
                    "مراقبة الإنتاج"
                }

                // Find all LabTests for this session
                val sessionTests = directLabTests.filter { it.sessionId == sess.id }
                sessionTests.forEach { tst ->
                    val valueDouble = safeParseDouble(tst.testValueA) ?: safeParseDouble(tst.testValueB)
                    if (valueDouble != null) {
                        val normalizedName = getHistoricalTestName(tst)
                        list.add(
                            QualityDataPoint(
                                order = order,
                                session = sess,
                                test = tst,
                                testName = normalizedName,
                                value = valueDouble,
                                label = labelStr
                            )
                        )
                    }
                }
            }
            list
        } else {
            val list = mutableListOf<QualityDataPoint>()
            val ordersMap = sameProductOrders.associateBy { it.id }
            val sessionsMap = directSessions.associateBy { it.id }

            // Source B: LabSession & LabTest
            directLabTests.forEach { tst ->
                val sess = sessionsMap[tst.sessionId] ?: return@forEach
                val props = (sess.sampleProperties ?: "").trim()
                if (props.contains("ORDER_ID:")) {
                    // Exclude QC sessions when comparing with all production orders (Requirement 1)
                    if (props.contains(":QC")) {
                        return@forEach
                    }
                    val oid = props.substringAfter("ORDER_ID:").substringBefore(":QC").substringBefore(":").trim()
                    if (oid.isNotBlank()) {
                        val ord = ordersMap[oid] ?: return@forEach
                        val valueDouble = safeParseDouble(tst.testValueA) ?: safeParseDouble(tst.testValueB)
                        if (valueDouble != null) {
                            val normalizedName = getHistoricalTestName(tst)
                            list.add(
                                QualityDataPoint(
                                    order = ord,
                                    session = sess,
                                    test = tst,
                                    testName = normalizedName,
                                    value = valueDouble,
                                    label = ord.batchNumber.ifBlank { ord.orderNumber }
                                )
                            )
                        }
                    }
                }
            }

            // Ensure sorted chronologically by order start time/created at
            list.sortedBy { it.order.startTime.takeIf { t -> t > 0L } ?: it.order.createdAt }
        }
    }

    // --- DIAGNOSTIC COMPONENT STATS ---
    val diagnosticStats = remember(allOrders, allTestRecords, labSessions, allLabTests, order.formulationId, orderIds, directRecords, directSessions, directLabTests) {
        val allOrdersCount = allOrders.size
        val sameProductOrdersCount = sameProductOrders.size
        
        val allTestRecordsCount = allTestRecords.size
        val directRecordsCount = directRecords.size
        
        val allLabSessionsCount = labSessions.size
        val directSessionsCount = directSessions.size
        
        val allLabTestsCount = allLabTests.size
        val directLabTestsCount = directLabTests.size

        // Analyze reasons for exclusion of any Lab Sessions
        val labSessionsExclusionReasons = mutableListOf<String>()
        labSessions.forEach { sess ->
            val props = (sess.sampleProperties ?: "").trim()
            if (props.contains("ORDER_ID:")) {
                val oid = props.substringAfter("ORDER_ID:").substringBefore(":QC").substringBefore(":").trim()
                if (!orderIds.contains(oid)) {
                    val matchingOrder = allOrders.find { it.id == oid }
                    if (matchingOrder != null) {
                        labSessionsExclusionReasons.add(
                            "جلسة فحص #${sess.sessionNumber} مستبعدة لأنها مرتبطة بأمر الإنتاج رقم ${matchingOrder.orderNumber} (معرّف: ${matchingOrder.id.take(8)}) الذي يستخدم تركيبة مختلفة: ${matchingOrder.formulationName} (معرّف التركيبة: ${matchingOrder.formulationId.take(8)})."
                        )
                    } else {
                        labSessionsExclusionReasons.add(
                            "جلسة فحص #${sess.sessionNumber} مستبعدة لأنها مرتبطة بمعرّف أمر إنتاج غير موجود في قائمة الأوامر المحلية ($oid)."
                        )
                    }
                }
            } else {
                labSessionsExclusionReasons.add(
                    "جلسة فحص #${sess.sessionNumber} مستبعدة لأنها ليست مرتبطة بأي أمر إنتاج (الحقل sampleProperties لا يحتوي على ORDER_ID:)."
                )
            }
        }

        // Analyze reasons for exclusion of any Test Records
        val testRecordsExclusionReasons = mutableListOf<String>()
        allTestRecords.forEach { rec ->
            if (!orderIds.contains(rec.productionOrderId)) {
                val matchingOrder = allOrders.find { it.id == rec.productionOrderId }
                if (matchingOrder != null) {
                    testRecordsExclusionReasons.add(
                        "سجل فحص بتاريخ ${rec.testDate} مستبعد لأنه تابع لأمر الإنتاج رقم ${matchingOrder.orderNumber} المرتبط بتركيبة مختلفة (معرّف التركيبة: ${matchingOrder.formulationId.take(8)})."
                    )
                } else {
                    testRecordsExclusionReasons.add(
                        "سجل فحص بتاريخ ${rec.testDate} مستبعد لأنه تابع لأمر إنتاج غير موجود محلياً (${rec.productionOrderId.take(8)})."
                    )
                }
            }
        }

        // Analyze any unparseable lab tests
        val unparseableLabTestsCount = directLabTests.count { tst ->
            safeParseDouble(tst.testValueA) == null && safeParseDouble(tst.testValueB) == null
        }

        mapOf(
            "allOrdersCount" to allOrdersCount,
            "sameProductOrdersCount" to sameProductOrdersCount,
            "allTestRecordsCount" to allTestRecordsCount,
            "directRecordsCount" to directRecordsCount,
            "allLabSessionsCount" to allLabSessionsCount,
            "directSessionsCount" to directSessionsCount,
            "allLabTestsCount" to allLabTestsCount,
            "directLabTestsCount" to directLabTestsCount,
            "unparseableLabTestsCount" to unparseableLabTestsCount,
            "labSessionsExclusionReasons" to labSessionsExclusionReasons,
            "testRecordsExclusionReasons" to testRecordsExclusionReasons
        )
    }

    // Print Logcat reports
    LaunchedEffect(diagnosticStats, order.formulationId) {
        val sameCount = diagnosticStats["sameProductOrdersCount"] as? Int ?: 0
        val directRecCount = diagnosticStats["directRecordsCount"] as? Int ?: 0
        val directSessCount = diagnosticStats["directSessionsCount"] as? Int ?: 0
        val directLTestsCount = diagnosticStats["directLabTestsCount"] as? Int ?: 0
        val unparseableLTestsCount = diagnosticStats["unparseableLabTestsCount"] as? Int ?: 0
        val labReasons = diagnosticStats["labSessionsExclusionReasons"] as? List<*> ?: emptyList<Any>()
        val testReasons = diagnosticStats["testRecordsExclusionReasons"] as? List<*> ?: emptyList<Any>()

        android.util.Log.d("QualityDiagnostics", "=== تشخيص جودة البيانات التاريخية للتركيبة: ${order.formulationName} ===")
        android.util.Log.d("QualityDiagnostics", "1. أوامر الإنتاج العثور عليها لهذه التركيبة: $sameCount")
        if (sameCount == 0) {
            android.util.Log.d("QualityDiagnostics", "   -> السبب: لم يتم العثور على أي أمر إنتاج (مكتمل أو مسودة) يحمل نفس معرف التركيبة: ${order.formulationId}")
        }
        
        android.util.Log.d("QualityDiagnostics", "2. سجلات الفحص المباشر العثور عليها: $directRecCount")
        
        android.util.Log.d("QualityDiagnostics", "3. جلسات الفحص والمراقبة العثور عليها: $directSessCount")
        if (directSessCount == 0) {
            android.util.Log.d("QualityDiagnostics", "   -> السبب: لا توجد جلسات فحص مرتبطة بأي من الأوامر الخاصة بهذه التركيبة.")
        }

        android.util.Log.d("QualityDiagnostics", "4. فحوصات ونتائج المختبر العثور عليها: $directLTestsCount")
        if (directLTestsCount == 0 && directSessCount > 0) {
            android.util.Log.d("QualityDiagnostics", "   -> السبب: تم العثور على جلسات فحص ولكن لا توجد فحوصات (LabTests) بداخلها.")
        }

        android.util.Log.d("QualityDiagnostics", "5. الفحوصات غير القابلة للتحليل (قيم غير رقمية): $unparseableLTestsCount")

        android.util.Log.d("QualityDiagnostics", "6. تفاصيل السجلات المستبعدة (Exclusions):")
        labReasons.forEach { reason ->
            android.util.Log.d("QualityDiagnostics", "   [مستبعد] $reason")
        }
        testReasons.forEach { reason ->
            android.util.Log.d("QualityDiagnostics", "   [مستبعد] $reason")
        }
        android.util.Log.d("QualityDiagnostics", "==========================================================")
    }

    // Unique test names found in historical records
    val uniqueTestNames = remember(allDataPoints) {
        allDataPoints.map { it.testName }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy<String> { getTestOrderPriority(it) }.thenBy { it })
    }

    // State for selected test
    var selectedTestName by remember { mutableStateOf("") }
    LaunchedEffect(uniqueTestNames) {
        if (selectedTestName.isBlank() || selectedTestName !in uniqueTestNames) {
            selectedTestName = uniqueTestNames.firstOrNull() ?: "فحص درجة القلوية (pH Value)"
        }
    }

    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(uniqueTestNames) {
        if (uniqueTestNames.isEmpty()) {
            showDiagnostics = true
        }
    }

    // State for selected test ID
    val selectedTestId = remember(selectedTestName, qualityTests) {
        val cleanSelected = normalizeTestName(selectedTestName).lowercase()
        qualityTests.find {
            val cleanTestName = normalizeTestName(it.name).lowercase()
            cleanSelected == cleanTestName || cleanSelected.contains(cleanTestName) || cleanTestName.contains(cleanSelected)
        }?.id
    }

    // Filtered data points for selected test name
    val chartDataPoints = remember(allDataPoints, selectedTestName) {
        allDataPoints.filter { it.testName == selectedTestName }
    }

    // Search and filters
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("الكل") } // "الكل", "مطابق", "خارج المواصفة"

    // Fetch matching specification limits
    val specLimits = remember(allFormulationQualityTests, order.formulationId, selectedTestName, qualityTests) {
        val cleanSelected = normalizeTestName(selectedTestName).lowercase()
        val matchedQTest = qualityTests.find {
            val cleanTestName = normalizeTestName(it.name).lowercase()
            cleanSelected == cleanTestName || cleanSelected.contains(cleanTestName) || cleanTestName.contains(cleanSelected)
        }
        if (matchedQTest != null) {
            allFormulationQualityTests.find { it.formulationId == order.formulationId && it.testId == matchedQTest.id }
        } else {
            null
        }
    }

    val lsl = specLimits?.minValue
    val usl = specLimits?.maxValue

    // Filtered data points for list
    val filteredDataPoints = remember(chartDataPoints, searchQuery, statusFilter, lsl, usl) {
        chartDataPoints.filter { pt ->
            val isPtWithin = (lsl == null || pt.value >= lsl) && (usl == null || pt.value <= usl)
            val matchQuery = pt.label.contains(searchQuery, ignoreCase = true) ||
                    pt.operatorName.contains(searchQuery, ignoreCase = true)
            val matchStatus = when (statusFilter) {
                "مطابق" -> isPtWithin
                "خارج المواصفة" -> !isPtWithin
                else -> true
            }
            matchQuery && matchStatus
        }
    }

    // Statistical KPIs
    val values = chartDataPoints.map { it.value }
    val mean = if (values.isNotEmpty()) values.average() else 0.0
    val hasSpecs = lsl != null || usl != null
    val compliancePercent = if (chartDataPoints.isNotEmpty() && hasSpecs) {
        val passed = chartDataPoints.count { pt ->
            (lsl == null || pt.value >= lsl) && (usl == null || pt.value <= usl)
        }
        (passed * 100) / chartDataPoints.size
    } else 100

    val stabilityStatus = when {
        chartDataPoints.isEmpty() -> "لا توجد قراءات"
        !hasSpecs -> "لا توجد مواصفات مرجعية مضافة لهذا الفحص ⚠️"
        compliancePercent == 100 -> "مستقر ومطابق بالكامل 🟢"
        compliancePercent >= 90 -> "مستقر مع انحرافات طفيفة 🟡"
        else -> "يوجد انحراف مستمر (خارج النطاق) 🔴"
    }

    var selectedPointIndex by remember(chartDataPoints) {
        mutableStateOf(if (chartDataPoints.isNotEmpty()) chartDataPoints.lastIndex else -1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Topbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "رجوع", tint = GBRDarkIndigo, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    text = if (isCurrentOrderOnly) "متابعة تطور جودة الدفعة الحالية 📈" else "تحليل استقرار الجودة التاريخية للمنتج 📈",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo
                )
                Text(
                    text = if (isCurrentOrderOnly) "أمر إنتاج: ${order.orderNumber} | دفعة: #${order.batchNumber}" else "منتج: ${order.formulationName}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        if (uniqueTestNames.isEmpty()) {
            // Empty State
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        tint = Color.LightGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "لا توجد فحوصات مخبرية تاريخية مسجلة لهذا المنتج حتى الآن.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "بمجرد إجراء فحوصات جودة أو مراقبة جودة وإدخال قيم قياسية للدفعات، ستظهر لك تقارير التحليل والرسوم البيانية هنا.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // --- DIAGNOSTICS & TROUBLESHOOTING PANEL ---
            Card(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), // Light blue
                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showDiagnostics = !showDiagnostics },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color(0xFF1D4ED8))
                            Text(
                                text = "لوحة تشخيص ومطابقة جودة البيانات التاريخية 🔍",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E3A8A)
                            )
                        }
                        Text(
                            text = if (showDiagnostics) "إخفاء التفاصيل 🔼" else "عرض تفاصيل التشخيص 🔽",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D4ED8)
                        )
                    }

                    if (showDiagnostics) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = Color(0xFFDBEAFE), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))

                        val sameCount = diagnosticStats["sameProductOrdersCount"] as? Int ?: 0
                        val directRecCount = diagnosticStats["directRecordsCount"] as? Int ?: 0
                        val directSessCount = diagnosticStats["directSessionsCount"] as? Int ?: 0
                        val directLTestsCount = diagnosticStats["directLabTestsCount"] as? Int ?: 0
                        val unparseableLTestsCount = diagnosticStats["unparseableLabTestsCount"] as? Int ?: 0
                        val labReasons = diagnosticStats["labSessionsExclusionReasons"] as? List<*> ?: emptyList<Any>()
                        val testReasons = diagnosticStats["testRecordsExclusionReasons"] as? List<*> ?: emptyList<Any>()

                        // Row 1: Production Orders
                        DiagnosticStepRow(
                            title = "أوامر الإنتاج للتركيبة (Formulation Orders)",
                            count = sameCount,
                            statusText = if (sameCount > 0) "تم العثور على $sameCount أمر إنتاج مرتبط بهذه التركيبة" else "لا توجد أوامر إنتاج مسجلة لهذه التركيبة!",
                            isSuccess = sameCount > 0,
                            explanation = if (sameCount == 0) "تأكد من أن الدفعات القديمة تم إنشاؤها للتركيبة الحالية (ID: ${order.formulationId.take(8)}) وليس كتركيبة مستقلة." else null
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 2: Test Records
                        DiagnosticStepRow(
                            title = "سجلات الفحص المباشر (Direct Order Test Records)",
                            count = directRecCount,
                            statusText = "تم تحميل $directRecCount سجل فحص مباشر للأوامر المطابقة",
                            isSuccess = directRecCount > 0,
                            explanation = if (directRecCount == 0 && sameCount > 0) "أوامر الإنتاج موجودة ولكن لم يتم تسجيل أي فحوصات جودة مباشرة لها في خط الإنتاج." else null
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 3: Lab Sessions
                        DiagnosticStepRow(
                            title = "جلسات الفحص والمراقبة بالمختبر (Lab & QC Sessions)",
                            count = directSessCount,
                            statusText = "تم العثور على $directSessCount جلسة فحص مخبرية مرتبطة بالأوامر المطابقة",
                            isSuccess = directSessCount > 0,
                            explanation = if (directSessCount == 0 && sameCount > 0) "تأكد من إنشاء جلسات فحص مخبرية (طلب فحص عينة أو مراقبة جودة) من شاشة الجودة وربطها بالدفعات." else null
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 4: Lab Tests
                        DiagnosticStepRow(
                            title = "نتائج الفحوصات المخبرية الفردية (Lab Test Values)",
                            count = directLTestsCount,
                            statusText = "تم تحميل $directLTestsCount نتيجة فحص فردية",
                            isSuccess = directLTestsCount > 0,
                            explanation = if (directLTestsCount == 0 && directSessCount > 0) "جلسات الفحص موجودة ولكنها لا تحتوي على أي فحوصات فردية مكتملة أو مدخلة." else null
                        )

                        if (unparseableLTestsCount > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().background(Color(0xFFFEF3C7), RoundedCornerShape(6.dp)).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(16.dp))
                                Text(
                                    text = "تنبيه: هناك $unparseableLTestsCount قيمة فحص مخبرية تحتوي على نصوص بدلاً من أرقام، مما يمنع تمثيلها بيانياً.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }

                        // Excluded summary count
                        val totalExcluded = labReasons.size + testReasons.size
                        if (totalExcluded > 0) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "السجلات المستبعدة من منتجات أخرى (${totalExcluded}):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E3A8A)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 120.dp)
                                    .verticalScroll(rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) })
                                    .background(Color.White, RoundedCornerShape(6.dp))
                                    .border(1.dp, Color(0xFFDBEAFE), RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    labReasons.forEach { reason ->
                                        Text("• $reason", fontSize = 10.sp, color = Color.Gray)
                                    }
                                    testReasons.forEach { reason ->
                                        Text("• $reason", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Horizontal scroll of test names
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uniqueTestNames.forEach { tName ->
                    val isSelected = tName == selectedTestName
                    val parts = getArAndEnNames(tName)
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) GBRBlueMain else Color.White,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) GBRBlueMain else Color(0xFFCBD5E1),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { selectedTestName = tName }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (tName.lowercase().contains("ph")) Icons.Default.Science else Icons.Default.Analytics,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else GBRBlueMain,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(verticalArrangement = Arrangement.Center) {
                                Text(
                                    text = parts.first,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else Color(0xFF334155),
                                    maxLines = 1
                                )
                                if (parts.second.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = parts.second,
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = if (isSelected) Color.White.copy(alpha = 0.85f) else Color.Gray,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Statistical Dashboard Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // KPI 1: Total Batches
                AnalysisKpiCard(
                    title = "إجمالي الدفعات المفحوصة",
                    value = chartDataPoints.size.toString(),
                    subValue = "دفعة إنتاجية مؤرشفة",
                    icon = Icons.Default.History,
                    iconColor = GBRBlueMain,
                    modifier = Modifier.weight(1f)
                )

                // KPI 2: Compliance
                AnalysisKpiCard(
                    title = "معدل مطابقة المواصفات",
                    value = if (hasSpecs) "$compliancePercent%" else "غير محدد",
                    subValue = stabilityStatus,
                    icon = Icons.Default.Verified,
                    iconColor = if (!hasSpecs) Color.Gray else if (compliancePercent >= 90) SuccessGreen else WarningOrange,
                    modifier = Modifier.weight(1f)
                )
            }

            // Visual Chart Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "مخطط استقرار القيم ومراقبة الانحراف (SPC Trend)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo
                        )

                        if (lsl != null || usl != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(SuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(SuccessGreen, RoundedCornerShape(2.dp))
                                )
                                Text(
                                    text = "النطاق الآمن: [${lsl ?: "-"} - ${usl ?: "-"}]",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (chartDataPoints.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("لا توجد قيم رقمية صالحة للتحليل في هذا الفحص.", fontSize = 11.sp, color = Color.Gray)
                        }
                    } else {
                        // Drawing custom Interactive Line Chart
                        InteractiveLineChart(
                            chartDataPoints = chartDataPoints,
                            lsl = lsl,
                            usl = usl,
                            selectedIndex = selectedPointIndex,
                            onPointSelected = { selectedPointIndex = it }
                        )
                    }
                }
            }

            // Selected Point Details Card
            if (selectedPointIndex in chartDataPoints.indices) {
                val pt = chartDataPoints[selectedPointIndex]
                val isWithinSpecs = (lsl == null || pt.value >= lsl) && (usl == null || pt.value <= usl)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isWithinSpecs) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)),
                    border = BorderStroke(1.dp, if (isWithinSpecs) SuccessGreen.copy(alpha = 0.3f) else WarningOrange.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isWithinSpecs) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isWithinSpecs) SuccessGreen else WarningOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "تفاصيل دفعة التحليل: #${pt.label}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = GBRDarkIndigo
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "القيمة المقاسة: ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.DarkGray
                            )
                            Text(
                                text = "${pt.value} (${if (isWithinSpecs) "مطابق ومستقر" else "خارج حدود المواصفة ⚠️"})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isWithinSpecs) SuccessGreen else WarningOrange
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "تاريخ الفحص: ${pt.testDate}",
                            fontSize = 10.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }



            // Historical Data Table
            Text(
                text = "📊 السجل التفصيلي للفحوصات المتطابقة (${filteredDataPoints.size})",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = GBRDarkIndigo
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (filteredDataPoints.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("لا توجد دفعات تاريخية لهذا الفحص.", fontSize = 11.sp, color = Color.Gray)
                    }
                } else {
                    // Header Row of the Table
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCurrentOrderOnly) "مرحلة الفحص / الجلسة 📦" else "أمر الإنتاج / الدفعة 📦",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569),
                            modifier = Modifier.weight(1.5f)
                        )
                        Text(
                            text = "تاريخ الفحص 📅",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569),
                            modifier = Modifier.weight(1.2f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = "القيمة المقاسة 🧪",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF475569),
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Divider(color = Color(0xFFE2E8F0), thickness = 1.dp)

                    // Data Rows
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        filteredDataPoints.forEachIndexed { index, pt ->
                            val isPtWithin = (lsl == null || pt.value >= lsl) && (usl == null || pt.value <= usl)
                            val associatedSession = remember(labSessions, pt.order.id) {
                                pt.session ?: labSessions.find { session ->
                                    val props = (session.sampleProperties ?: "").trim()
                                    if (props.contains("ORDER_ID:")) {
                                        val oid = props.substringAfter("ORDER_ID:").substringBefore(":QC").substringBefore(":").trim()
                                        oid == pt.order.id
                                    } else {
                                        false
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Open the production order directly in the view model!
                                        viewModel.selectAndNavigateToProductionOrder(pt.order.id)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Column 1: Batch / Order
                                Row(
                                    modifier = Modifier.weight(1.5f),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (isPtWithin) SuccessGreen else WarningOrange, CircleShape)
                                    )
                                    Column {
                                        Text(
                                            text = if (isCurrentOrderOnly) pt.label else "أمر: ${pt.order.orderNumber}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRDarkIndigo
                                        )
                                        Text(
                                            text = if (isCurrentOrderOnly) {
                                                if (pt.operatorName.isNotBlank()) "المسؤول: ${pt.operatorName}" else "أمر: ${pt.order.orderNumber}"
                                            } else {
                                                "دفعة #${pt.label}"
                                            },
                                            fontSize = 9.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                // Column 2: Date
                                Text(
                                    text = pt.testDate.ifBlank { "غير متوفر" },
                                    fontSize = 10.sp,
                                    color = Color.DarkGray,
                                    modifier = Modifier.weight(1.2f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                // Column 3: Value
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${pt.value}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isPtWithin) SuccessGreen else WarningOrange
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (isPtWithin) SuccessGreen.copy(alpha = 0.08f) else WarningOrange.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (isPtWithin) "مطابق" else "خارج",
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPtWithin) SuccessGreen else WarningOrange
                                        )
                                    }
                                }
                            }
                            if (index < filteredDataPoints.lastIndex) {
                                Divider(color = Color(0xFFF1F5F9), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveLineChart(
    chartDataPoints: List<QualityDataPoint>,
    lsl: Double?,
    usl: Double?,
    selectedIndex: Int,
    onPointSelected: (Int) -> Unit
) {
    val points = chartDataPoints.map { it.value }
    val maxVal = points.maxOrNull() ?: 10.0
    val minVal = points.minOrNull() ?: 0.0
    val lslVal = lsl ?: minVal
    val uslVal = usl ?: maxVal

    // Compute appropriate Y range
    // مدى المحور: حد أدنى ≈ ±5% حول المتوسط (كان ±2%) حتى لا يُضخَّم فرق صغير فيبدو المنحنى حاداً وغير واقعي في كل الفحوصات
    val dataMean = if (points.isNotEmpty()) points.average() else 0.0
    val diff = kotlin.math.abs(maxVal - minVal)
    val margin = if (diff > 0) diff * 0.15 else kotlin.math.max(kotlin.math.abs(dataMean) * 0.05, 0.01)
    var yLo = kotlin.math.min(minVal, lslVal) - margin
    var yHi = kotlin.math.max(maxVal, uslVal) + margin
    val minHalfSpan = kotlin.math.max(kotlin.math.abs(dataMean) * 0.05, 0.01)
    if (yHi - yLo < 2 * minHalfSpan) {
        val mid = (yHi + yLo) / 2.0
        yLo = mid - minHalfSpan
        yHi = mid + minHalfSpan
    }
    val yMin = yLo.coerceAtLeast(0.0)
    val yMax = if (yHi > yMin) yHi else yMin + 2 * minHalfSpan
    val axisSpan = yMax - yMin
    val axisDecimals = if (axisSpan < 0.1) 3 else if (axisSpan < 1.0) 2 else 1

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .pointerInput(chartDataPoints) {
                detectTapGestures(
                    onTap = { offset ->
                        val width = size.width.toFloat()
                        val paddingLeft = 60f
                        val paddingRight = 30f
                        val chartWidth = width - paddingLeft - paddingRight
                        val count = chartDataPoints.size
                        if (count > 0) {
                            val stepX = if (count > 1) chartWidth / (count - 1) else chartWidth
                            var bestIndex = 0
                            var bestDist = Float.MAX_VALUE
                            for (i in 0 until count) {
                                val px = paddingLeft + i * stepX
                                val d = if (offset.x > px) offset.x - px else px - offset.x
                                if (d < bestDist) {
                                    bestDist = d
                                    bestIndex = i
                                }
                            }
                            if (bestDist < 50f) {
                                onPointSelected(bestIndex)
                            }
                        }
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height

        val paddingLeft = 60f
        val paddingRight = 30f
        val paddingTop = 20f
        val paddingBottom = 40f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        // 1. Draw Shaded Specification Range (LSL to USL)
        if (lsl != null && usl != null) {
            val yLsl = paddingTop + chartHeight - (((lsl - yMin) / (yMax - yMin)) * chartHeight).toFloat()
            val yUsl = paddingTop + chartHeight - (((usl - yMin) / (yMax - yMin)) * chartHeight).toFloat()

            drawRect(
                color = Color(0xFFDCFCE7).copy(alpha = 0.5f), // Very soft green
                topLeft = androidx.compose.ui.geometry.Offset(paddingLeft, yUsl),
                size = androidx.compose.ui.geometry.Size(chartWidth, yLsl - yUsl)
            )

            // Draw USL line (red dashed line)
            drawLine(
                color = Color(0xFFEF4444).copy(alpha = 0.5f),
                start = androidx.compose.ui.geometry.Offset(paddingLeft, yUsl),
                end = androidx.compose.ui.geometry.Offset(paddingLeft + chartWidth, yUsl),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )

            // Draw LSL line (red dashed line)
            drawLine(
                color = Color(0xFFEF4444).copy(alpha = 0.5f),
                start = androidx.compose.ui.geometry.Offset(paddingLeft, yLsl),
                end = androidx.compose.ui.geometry.Offset(paddingLeft + chartWidth, yLsl),
                strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        }

        // خط المتوسط (خفيف) للمقارنة
        if (points.size > 1) {
            val yMean = paddingTop + chartHeight - (((dataMean - yMin) / (yMax - yMin)) * chartHeight).toFloat()
            drawLine(
                color = Color(0xFF94A3B8).copy(alpha = 0.6f),
                start = androidx.compose.ui.geometry.Offset(paddingLeft, yMean),
                end = androidx.compose.ui.geometry.Offset(paddingLeft + chartWidth, yMean),
                strokeWidth = 1.5f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
            )
        }

        // 2. Draw Y-Axis Grid Lines
        val divisions = 4
        for (i in 0..divisions) {
            val ratio = i.toFloat() / divisions
            val gridY = paddingTop + chartHeight - (ratio * chartHeight)
            val gridVal = yMin + (ratio * (yMax - yMin))

            // Draw horizontal light gray line
            drawLine(
                color = Color(0xFFE2E8F0),
                start = androidx.compose.ui.geometry.Offset(paddingLeft, gridY),
                end = androidx.compose.ui.geometry.Offset(paddingLeft + chartWidth, gridY),
                strokeWidth = 1f
            )

            // Draw value text next to axis
            drawContext.canvas.nativeCanvas.drawText(
                String.format(Locale.US, "%.${axisDecimals}f", gridVal),
                10f,
                gridY + 10f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.LEFT
                }
            )
        }

        // 3. Plot data points and lines
        val count = chartDataPoints.size
        if (count > 0) {
            val stepX = if (count > 1) chartWidth / (count - 1) else chartWidth
            val path = androidx.compose.ui.graphics.Path()

            // Build path for lines
            for (i in 0 until count) {
                val valY = chartDataPoints[i].value
                val px = paddingLeft + i * stepX
                val py = paddingTop + chartHeight - (((valY - yMin) / (yMax - yMin)) * chartHeight).toFloat()

                if (i == 0) {
                    path.moveTo(px, py)
                } else {
                    path.lineTo(px, py)
                }
            }

            // Draw the trend line
            drawPath(
                path = path,
                color = GBRBlueMain,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 4f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                )
            )

            // Draw points
            for (i in 0 until count) {
                val valY = chartDataPoints[i].value
                val px = paddingLeft + i * stepX
                val py = paddingTop + chartHeight - (((valY - yMin) / (yMax - yMin)) * chartHeight).toFloat()

                val isSelected = i == selectedIndex
                val isPtWithin = (lsl == null || valY >= lsl) && (usl == null || valY <= usl)

                // Highlight outer ring
                if (isSelected) {
                    drawCircle(
                        color = GBRBlueMain.copy(alpha = 0.25f),
                        radius = 16f,
                        center = androidx.compose.ui.geometry.Offset(px, py)
                    )
                }

                // Core dot
                drawCircle(
                    color = if (isPtWithin) SuccessGreen else WarningOrange,
                    radius = 8f,
                    center = androidx.compose.ui.geometry.Offset(px, py)
                )

                // Inner white center
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = androidx.compose.ui.geometry.Offset(px, py)
                )

                // Label on X-axis (only for some points to avoid crowding)
                if (count <= 10 || i % (count / 5).coerceAtLeast(1) == 0 || i == count - 1) {
                    drawContext.canvas.nativeCanvas.drawText(
                        chartDataPoints[i].label,
                        px,
                        height - 10f,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.DKGRAY
                            textSize = 22f
                            textAlign = android.graphics.Paint.Align.CENTER
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AnalysisKpiCard(
    title: String,
    value: String,
    subValue: String,
    icon: ImageVector,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Medium)
                Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subValue, fontSize = 10.sp, color = Color.DarkGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun DiagnosticStepRow(
    title: String,
    count: Int,
    statusText: String,
    isSuccess: Boolean,
    explanation: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, if (isSuccess) Color(0xFFD1FAE5) else Color(0xFFFEE2E2), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = statusText, fontSize = 10.sp, color = if (isSuccess) Color(0xFF065F46) else Color(0xFF991B1B))
            }
            Icon(
                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                modifier = Modifier.size(18.dp)
            )
        }
        if (explanation != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "💡 $explanation",
                fontSize = 10.sp,
                color = Color(0xFF475569),
                lineHeight = 13.sp
            )
        }
    }
}

// ==============================================================================
// MULTI-ORDER & PRODUCT BATCH COMPARISON ENGINE (مصفوفة مقارنة وتحليل الدفعات)
// ==============================================================================

enum class ComparisonBasis(val title: String, val shortUnit: String) {
    PER_1000KG("معيار 1,000كجم", "كجم/طن"),
    ACTUAL_KG("فعلي (كجم)", "كجم"),
    PERCENTAGE("نسبة %", "%")
}

private fun formatCostTwoDec(value: Double): String {
    return try {
        if (value.isNaN() || value.isInfinite()) "0.00"
        else java.math.BigDecimal(value.toString())
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .toPlainString()
    } catch (e: Exception) {
        String.format(Locale.US, "%.2f", value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiOrderComparisonView(
    initialProductFilter: String? = null,
    initialSelectedOrderIds: Set<String>? = null,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    val allOrders by viewModel.productionOrders.collectAsState()
    val allOrderItems by viewModel.allProductionOrderItems.collectAsState()
    val allTestRecords by viewModel.allProductionOrderTestRecords.collectAsState()
    val labSessions by viewModel.labSessions.collectAsState()
    val allLabTests by viewModel.allLabTests.collectAsState()
    val formulations by viewModel.formulations.collectAsState()
    val allFormulationItems by viewModel.allFormulationItems.collectAsState()
    val rawMaterials by viewModel.rawMaterials.collectAsState()
    val allPoQualityTests by viewModel.allProductionOrderQualityTests.collectAsState()
    val allFormulationQualityTests by viewModel.allFormulationQualityTests.collectAsState()
    val systemQualityTests by viewModel.qualityTests.collectAsState()

    val completedOrders = remember(allOrders) {
        allOrders.filter {
            it.status == "مكتمل" || it.status.contains("مكتمل") || it.status.contains("مؤرشف") || it.status == "📦 مؤرشف"
        }
    }

    val getEffectiveFormulationName: (ProductionOrder) -> String = remember(formulations) {
        { order ->
            val matched = formulations.find { it.id == order.formulationId }
            (matched?.name?.trim() ?: order.formulationName.trim()).ifBlank { "بدون اسم" }
        }
    }

    val uniqueProducts = remember(completedOrders, formulations) {
        val approvedFormulations = formulations.filter {
            it.status == "🟢 معتمدة للإنتاج" || it.status.contains("معتمد")
        }
        val approvedIds = approvedFormulations.map { it.id }.toSet()
        val approvedNames = approvedFormulations.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()

        val fromOrders = completedOrders.filter { order ->
            order.formulationId in approvedIds || getEffectiveFormulationName(order) in approvedNames
        }.map { getEffectiveFormulationName(it) }.filter { it.isNotBlank() }

        (approvedNames + fromOrders).distinct().sorted()
    }

    val initialProduct = remember(uniqueProducts, initialProductFilter, completedOrders) {
        if (!initialProductFilter.isNullOrBlank()) {
            if (initialProductFilter in uniqueProducts) {
                initialProductFilter
            } else {
                val mapped = completedOrders.find {
                    it.formulationName.trim().equals(initialProductFilter.trim(), ignoreCase = true)
                }?.let { getEffectiveFormulationName(it) }
                mapped ?: (uniqueProducts.firstOrNull() ?: "")
            }
        } else {
            ""
        }
    }

    var pendingProduct by remember(initialProduct) { mutableStateOf(initialProduct) }
    var appliedProduct by remember(initialProduct) { mutableStateOf(initialProduct) }

    val candidateOrders = remember(completedOrders, pendingProduct, formulations) {
        if (pendingProduct.isBlank()) {
            emptyList()
        } else {
            completedOrders.filter { order ->
                val effName = getEffectiveFormulationName(order)
                effName.equals(pendingProduct.trim(), ignoreCase = true) ||
                order.formulationName.trim().equals(pendingProduct.trim(), ignoreCase = true)
            }
        }.sortedByDescending { it.createdAt }
    }

    val candidateSet = remember(candidateOrders) { candidateOrders.map { it.id }.toSet() }

    val candidateOrdersForApplied = remember(completedOrders, appliedProduct, formulations) {
        if (appliedProduct.isBlank()) {
            emptyList()
        } else {
            completedOrders.filter { order ->
                val effName = getEffectiveFormulationName(order)
                effName.equals(appliedProduct.trim(), ignoreCase = true) ||
                order.formulationName.trim().equals(appliedProduct.trim(), ignoreCase = true)
            }
        }.sortedByDescending { it.createdAt }
    }

    val initialOrdersSet = remember(candidateSet, initialSelectedOrderIds) {
        if (!initialSelectedOrderIds.isNullOrEmpty()) {
            val inter = initialSelectedOrderIds.intersect(candidateSet)
            if (inter.isNotEmpty()) inter else candidateSet
        } else {
            candidateSet
        }
    }

    var pendingOrderIds by remember(initialOrdersSet) { mutableStateOf<Set<String>>(initialOrdersSet) }
    var appliedOrderIds by remember(initialOrdersSet) { mutableStateOf<Set<String>>(initialOrdersSet) }
    var quickTabSelection by remember(pendingProduct) { mutableStateOf("ALL") }

    var pendingBasis by remember { mutableStateOf(ComparisonBasis.ACTUAL_KG) }
    var appliedBasis by remember { mutableStateOf(ComparisonBasis.ACTUAL_KG) }

    var isProcessing by remember { mutableStateOf(false) }
    var isControlsExpanded by remember { mutableStateOf(false) }

    val appliedOrders = remember(candidateOrdersForApplied, appliedOrderIds) {
        candidateOrdersForApplied.filter { it.id in appliedOrderIds }.sortedBy { it.createdAt }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 6 })

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        if (selectedTab != pagerState.settledPage) {
            selectedTab = pagerState.settledPage
        }
    }

    fun triggerApply(
        newProd: String = pendingProduct,
        newSet: Set<String> = pendingOrderIds,
        newBas: ComparisonBasis = pendingBasis
    ) {
        pendingProduct = newProd
        pendingOrderIds = newSet
        pendingBasis = newBas
        appliedProduct = newProd
        appliedOrderIds = newSet
        appliedBasis = newBas
        isControlsExpanded = false
    }

    val matchingFormulation = remember(formulations, appliedProduct) {
        if (appliedProduct.isNotBlank()) formulations.find { it.name.trim().equals(appliedProduct.trim(), ignoreCase = true) } else null
    }
    val baselineItems = remember(matchingFormulation, allFormulationItems) {
        if (matchingFormulation != null) {
            allFormulationItems.filter { it.formulationId == matchingFormulation.id }
        } else {
            emptyList()
        }
    }

    val context = LocalContext.current

    // Animated Loading Dialog
    if (isProcessing) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier.padding(16.dp).fillMaxWidth(0.85f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = GBRBlueMain,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(52.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = null,
                            tint = GBRBlueMain,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "جاري تحليل ومقارنة الدفعات...",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = GBRDarkIndigo,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "جاري احتساب نسب المكونات، التكاليف الكلية، والنتائج المخبرية للدفعات المختارة...",
                        fontSize = 11.5.sp,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFF8FAFC)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Bar
                Surface(
                    color = GBRDarkIndigo,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Analytics,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "مصفوفة مقارنة وتحليل الدفعات الإنتاجية 📊",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                            Text(
                                text = "مقارنة نسب المواد، التكلفة، والنتائج المخبرية بين الدفعات",
                                fontSize = 10.5.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Collapsible Controls Section
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, IndustrialBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (!isControlsExpanded) {
                        // Compact Summary Bar (Collapsed Mode)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isControlsExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = null,
                                    tint = GBRBlueMain,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (appliedProduct.isNotBlank()) "المنتج: $appliedProduct" else "اختر المنتج للمقارنة",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Surface(
                                    color = GBRBlueMain.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${appliedOrders.size} دفعة",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRBlueMain,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("تعديل الفلاتر ⚙️", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = GBRBlueMain)
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = GBRBlueMain,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        // Expanded Controls Panel
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "تحديد المنتج والدفعات للمقارنة ⚙️",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRDarkIndigo
                                )
                                TextButton(
                                    onClick = { isControlsExpanded = false },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text("إخفاء 🔼", fontSize = 10.5.sp, color = Color.Gray)
                                }
                            }

                            // Product & Quick Actions Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                var prodMenuExpanded by remember { mutableStateOf(false) }
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { prodMenuExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, GBRBlueMain),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = GBRBlueMain.copy(alpha = 0.05f),
                                            contentColor = GBRBlueMain
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Inventory2,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (pendingProduct.isNotBlank()) "المنتج: $pendingProduct" else "اختر المنتج...",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = prodMenuExpanded,
                                        onDismissRequest = { prodMenuExpanded = false },
                                        modifier = Modifier.fillMaxWidth(0.75f)
                                    ) {
                                        uniqueProducts.forEach { prod ->
                                            val count = completedOrders.count { order ->
                                                getEffectiveFormulationName(order).equals(prod.trim(), ignoreCase = true) ||
                                                order.formulationName.trim().equals(prod.trim(), ignoreCase = true)
                                            }
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(text = prod, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                        Text(text = "($count أمر)", fontSize = 11.sp, color = Color.Gray)
                                                    }
                                                },
                                                onClick = {
                                                    prodMenuExpanded = false
                                                    pendingProduct = prod
                                                    val cands = completedOrders.filter { order ->
                                                         getEffectiveFormulationName(order).equals(prod.trim(), ignoreCase = true) ||
                                                         order.formulationName.trim().equals(prod.trim(), ignoreCase = true)
                                                     }
                                                    pendingOrderIds = cands.map { it.id }.toSet()
                                                    quickTabSelection = "ALL"
                                                }
                                            )
                                        }
                                    }
                                }

                                // Quick Select Buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val take5Set = remember(candidateOrders) { candidateOrders.take(5).map { it.id }.toSet() }
                                    val take10Set = remember(candidateOrders) { candidateOrders.take(10).map { it.id }.toSet() }

                                    val isAllActive = quickTabSelection == "ALL" || (candidateSet.isNotEmpty() && pendingOrderIds == candidateSet)
                                    val isLast5Active = quickTabSelection == "LAST5" || (candidateOrders.size > 5 && pendingOrderIds == take5Set)
                                    val isLast10Active = quickTabSelection == "LAST10" || (candidateOrders.size > 10 && pendingOrderIds == take10Set)

                                    OutlinedButton(
                                        onClick = {
                                            pendingOrderIds = candidateSet
                                            quickTabSelection = "ALL"
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isAllActive) GBRBlueMain else Color(0xFFF1F5F9),
                                            contentColor = if (isAllActive) Color.White else GBRDarkIndigo
                                        ),
                                        border = BorderStroke(1.dp, if (isAllActive) GBRBlueMain else Color(0xFFCBD5E1)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("الكل (${candidateOrders.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            pendingOrderIds = take5Set
                                            quickTabSelection = "LAST5"
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isLast5Active) GBRBlueMain else Color(0xFFF1F5F9),
                                            contentColor = if (isLast5Active) Color.White else GBRDarkIndigo
                                        ),
                                        border = BorderStroke(1.dp, if (isLast5Active) GBRBlueMain else Color(0xFFCBD5E1)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("آخر 5", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            pendingOrderIds = take10Set
                                            quickTabSelection = "LAST10"
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (isLast10Active) GBRBlueMain else Color(0xFFF1F5F9),
                                            contentColor = if (isLast10Active) Color.White else GBRDarkIndigo
                                        ),
                                        border = BorderStroke(1.dp, if (isLast10Active) GBRBlueMain else Color(0xFFCBD5E1)),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("آخر 10", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // Batch Selection Chips
                            if (candidateOrders.isNotEmpty()) {
                                val take5Set = remember(candidateOrders) { candidateOrders.take(5).map { it.id }.toSet() }
                                val take10Set = remember(candidateOrders) { candidateOrders.take(10).map { it.id }.toSet() }

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(candidateOrders) { ord ->
                                        val isSelected = ord.id in pendingOrderIds
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                val newSet = if (isSelected) pendingOrderIds - ord.id else pendingOrderIds + ord.id
                                                pendingOrderIds = newSet
                                                quickTabSelection = when (newSet) {
                                                    candidateSet -> "ALL"
                                                    take5Set -> "LAST5"
                                                    take10Set -> "LAST10"
                                                    else -> "CUSTOM"
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = ord.orderNumber,
                                                    fontSize = 10.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            leadingIcon = if (isSelected) {
                                                { Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(12.dp), tint = GBRBlueMain) }
                                            } else null,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                    }
                                }
                            }

                            // Apply Button Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { triggerApply() },
                                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تطبيق المقارنة 🚀", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Scrollable Tabs Header
                val tabs = listOf(
                    "🧪 مواد التركيبة",
                    "💰 التكاليف والأسعار",
                    "🔬 الفحوصات والجودة",
                    "📦 التعبئة والهدر",
                    "🧠 التقرير والذكاء",
                    "⏱️ أزمنة وسجلات التنفيذ"
                )

                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 8.dp,
                    containerColor = Color.Transparent,
                    contentColor = GBRBlueMain,
                    divider = { Divider(color = IndustrialBorder) }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                selectedTab = index
                                coroutineScope.launch { pagerState.animateScrollToPage(index) }
                            }
                        ) {
                            Text(
                                text = title,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (pagerState.currentPage == index) GBRBlueMain else Color.Gray,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                if (candidateOrders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                            Text(
                                text = if (pendingProduct.isNotBlank()) "لا توجد أوامر إنتاج مكتملة أو مؤرشفة لهذا المنتج ('$pendingProduct')" else "يرجى اختيار المنتج أولاً من الفلتر في الأعلى 👆 للمقارنة.",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (appliedOrders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "يرجى تحديد أمر إنتاج واحد على الأقل ثم الضغط على 'تطبيق المقارنة 🚀' 👆",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { triggerApply(newSet = candidateSet) },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("تحديد وتطبيق جميع الدفعات (${candidateOrders.size})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { page ->
                        when (page) {
                            0 -> RawMaterialsMatrixTab(
                                selectedOrders = appliedOrders,
                                allOrderItems = allOrderItems,
                                baselineItems = baselineItems,
                                rawMaterials = rawMaterials,
                                basis = appliedBasis
                            )
                            1 -> CostsAnalysisMatrixTab(
                                selectedOrders = appliedOrders,
                                allOrderItems = allOrderItems,
                                formulations = formulations
                            )
                            2 -> LabQualityMatrixTab(
                                selectedOrders = appliedOrders,
                                allTestRecords = allTestRecords,
                                allPoQualityTests = allPoQualityTests,
                                allFormulationQualityTests = allFormulationQualityTests,
                                systemQualityTests = systemQualityTests,
                                labSessions = labSessions,
                                allLabTests = allLabTests,
                                formulations = formulations
                            )
                            3 -> YieldPackagingMatrixTab(
                                selectedOrders = appliedOrders
                            )
                            4 -> SmartExecutiveAnalyticsTab(
                                selectedProduct = appliedProduct,
                                selectedOrders = appliedOrders,
                                allOrderItems = allOrderItems,
                                baselineItems = baselineItems,
                                allTestRecords = allTestRecords,
                                labSessions = labSessions,
                                allLabTests = allLabTests,
                                context = context
                            )
                            5 -> RecipeExecutionDurationsMatrixTab(
                                selectedProduct = appliedProduct,
                                selectedOrders = appliedOrders,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RawMaterialsMatrixTab(
    selectedOrders: List<ProductionOrder>,
    allOrderItems: List<ProductionOrderItem>,
    baselineItems: List<FormulationItem>,
    rawMaterials: List<RawMaterial>,
    basis: ComparisonBasis
) {
    var highlightedMaterial by remember { mutableStateOf<String?>(null) }

    val orderItemsMap = remember(selectedOrders, allOrderItems) {
        val selectedIds = selectedOrders.map { it.id }.toSet()
        allOrderItems.filter { it.productionOrderId in selectedIds }
            .groupBy { it.productionOrderId }
    }

    val rmMap = remember(rawMaterials) {
        rawMaterials.associateBy { it.id }
    }

    val allRawMaterialNames = remember(orderItemsMap) {
        orderItemsMap.values.flatten()
            .filter { it.calculatedQuantity > 0 }
            .map { it.rawMaterialName.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "مصفوفة المواد الخام (${allRawMaterialNames.size})",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = GBRDarkIndigo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(10.dp)
        ) {
            val horizontalScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                Column(modifier = Modifier.wrapContentWidth()) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFF1F5F9))
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "اسم المادة الخام",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = GBRDarkIndigo,
                            modifier = Modifier.width(140.dp)
                        )
                        Text(
                            text = "الوصفة المعتمدة",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = GBRBlueMain,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(100.dp)
                        )
                        selectedOrders.forEach { ord ->
                            Text(
                                text = ord.orderNumber,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF334155),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(90.dp)
                            )
                        }
                    }
                    Divider(color = IndustrialBorder)

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 200.dp)
                    ) {
                        items(allRawMaterialNames, key = { it }) { matName ->
                            val baseItem = baselineItems.find { item ->
                                val rm = rmMap[item.rawMaterialId]
                                val name = (rm?.name?.ifBlank { null } ?: rm?.productionName)?.trim() ?: ""
                                name.equals(matName, ignoreCase = true)
                            }
                            val standardVal = baseItem?.quantityMultiplier ?: 0.0

                            val orderValues = selectedOrders.map { ord ->
                                val items = orderItemsMap[ord.id] ?: emptyList()
                                val match = items.find { it.rawMaterialName.trim().equals(matName, ignoreCase = true) }
                                val ordWeight = ord.requiredWeightKg.coerceAtLeast(1.0)

                                if (match != null) {
                                    when (basis) {
                                        ComparisonBasis.PER_1000KG -> (match.calculatedQuantity / ordWeight) * 1000.0
                                        ComparisonBasis.ACTUAL_KG -> match.calculatedQuantity
                                        ComparisonBasis.PERCENTAGE -> (match.calculatedQuantity / ordWeight) * 100.0
                                    }
                                } else {
                                    0.0
                                }
                            }

                            val isHighlighted = highlightedMaterial == matName

                            Row(
                                modifier = Modifier
                                    .clickable { highlightedMaterial = if (isHighlighted) null else matName }
                                    .background(if (isHighlighted) Color(0xFFDCFCE7) else Color.Transparent)
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = matName,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E293B),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.width(140.dp)
                                )

                                val stdDisplay = when (basis) {
                                    ComparisonBasis.PER_1000KG -> formatNum(standardVal) + " كجم"
                                    ComparisonBasis.ACTUAL_KG -> "-"
                                    ComparisonBasis.PERCENTAGE -> formatNum(standardVal / 10.0) + " %"
                                }
                                Text(
                                    text = stdDisplay,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GBRBlueMain,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(100.dp)
                                )

                                orderValues.forEachIndexed { idx, valAmount ->
                                    val isAdded = valAmount > 0

                                    val cellBg = if (!isAdded) Color(0xFFF8FAFC) else Color(0xFFF0FDF4)
                                    val cellTextColor = if (!isAdded) Color(0xFF64748B) else Color(0xFF166534)

                                    Box(
                                        modifier = Modifier
                                            .width(90.dp)
                                            .padding(horizontal = 2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(cellBg)
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!isAdded) {
                                            Text("-", fontSize = 10.sp, color = cellTextColor, fontWeight = FontWeight.Normal)
                                        } else {
                                            Text(
                                                text = formatNum(valAmount) + if (basis == ComparisonBasis.PERCENTAGE) "%" else "",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = cellTextColor
                                            )
                                        }
                                    }
                                }
                            }
                            Divider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }
    }
}

data class PackageCostDetail(
    val packId: String,
    val label: String,
    val netWeight: Double,
    val actualCount: Int,
    val emptyPackPrice: Double,
    val extraOperatingCost: Double,
    val liquidPaintCost: Double,
    val totalCostPerPackage: Double
)

data class OrderCostMetric(
    val order: ProductionOrder,
    val totalWeight: Double,
    val rawCost: Double,
    val paintCostPerKg: Double,
    val packageDetails: List<PackageCostDetail>,
    val totalPackageCost: Double,
    val totalComprehensiveCost: Double,
    val finalCostPerKg: Double
)

@Composable
fun CostsAnalysisMatrixTab(
    selectedOrders: List<ProductionOrder>,
    allOrderItems: List<ProductionOrderItem>,
    formulations: List<Formulation>
) {
    var highlightedOrderId by remember { mutableStateOf<String?>(null) }

    val orderDataList = remember(selectedOrders, allOrderItems, formulations) {
        selectedOrders.map { ord ->
            val items = allOrderItems.filter { it.productionOrderId == ord.id }
            val totalWeight = items.sumOf { it.calculatedQuantity }.let { if (it <= 0) ord.requiredWeightKg else it }
            val rawCost = items.sumOf { it.calculatedQuantity * it.rawMaterialPrice }
            val paintCostPerKg = if (totalWeight > 0) rawCost / totalWeight else 0.0

            val rawPackList = parsePackContents(ord.actualPackagingJson)
            val packList = rawPackList.filter { (it["actualCount"]?.toDoubleOrNull() ?: 0.0) > 0.0 }
            val snapPackList = parsePackContents(ord.packagingSnapshotJson)
            val matchingFormula = formulations.find { it.id == ord.formulationId || it.name == ord.formulationName }

            val packageDetails = mutableListOf<PackageCostDetail>()
            var emptyPackCostTotal = 0.0
            var operatingCostTotal = 0.0

            if (packList.isNotEmpty()) {
                packList.forEach { act ->
                    val idStr = act["id"] ?: "1"
                    val actualCount = act["actualCount"]?.toDoubleOrNull()?.toInt() ?: 0
                    val netWeight = act["netWeight"]?.toDoubleOrNull() ?: 18.0
                    val rawLabel = act["name"]?.ifBlank { null } ?: act["label"] ?: "${formatNum(netWeight)} كجم"

                    val snap = snapPackList.find { it["id"] == idStr }
                    var snapPrice = snap?.get("price")?.toDoubleOrNull() ?: (if (netWeight == 18.0) 15.0 else 5.5)
                    if (snapPrice == 0.0) snapPrice = if (netWeight == 18.0) 15.0 else 5.5

                    var snapExtraCost = snap?.get("extraCost")?.toDoubleOrNull() ?: 0.0
                    if (snapExtraCost == 0.0 && matchingFormula != null) {
                        snapExtraCost = try {
                            val json = org.json.JSONObject(matchingFormula.packagingWeightsJson)
                            json.optString(idStr, "").split(":").getOrNull(1)?.toDoubleOrNull() ?: 0.0
                        } catch (e: Exception) { 0.0 }
                    }

                    emptyPackCostTotal += actualCount * snapPrice
                    operatingCostTotal += actualCount * snapExtraCost

                    val liquidCost = netWeight * paintCostPerKg
                    val totalPerPack = liquidCost + snapPrice + snapExtraCost

                    packageDetails.add(
                        PackageCostDetail(
                            packId = idStr,
                            label = rawLabel,
                            netWeight = netWeight,
                            actualCount = actualCount,
                            emptyPackPrice = snapPrice,
                            extraOperatingCost = snapExtraCost,
                            liquidPaintCost = liquidCost,
                            totalCostPerPackage = totalPerPack
                        )
                    )
                }
            } else {
                val stdSizes = listOf(18.0 to "عبوة 18 كجم", 5.0 to "عبوة 5 كجم")
                stdSizes.forEachIndexed { idx, (w, label) ->
                    val emptyPrice = if (w == 18.0) 15.0 else 6.0
                    val extraCost = 2.0
                    val liquidCost = w * paintCostPerKg
                    val totalPerPack = liquidCost + emptyPrice + extraCost
                    packageDetails.add(
                        PackageCostDetail(
                            packId = "${idx + 1}",
                            label = label,
                            netWeight = w,
                            actualCount = 0,
                            emptyPackPrice = emptyPrice,
                            extraOperatingCost = extraCost,
                            liquidPaintCost = liquidCost,
                            totalCostPerPackage = totalPerPack
                        )
                    )
                }
            }

            val totalPackageCost = emptyPackCostTotal + operatingCostTotal
            val totalComprehensiveCost = rawCost + totalPackageCost
            val finalCostPerKg = if (totalWeight > 0) totalComprehensiveCost / totalWeight else 0.0

            OrderCostMetric(
                order = ord,
                totalWeight = totalWeight,
                rawCost = rawCost,
                paintCostPerKg = paintCostPerKg,
                packageDetails = packageDetails,
                totalPackageCost = totalPackageCost,
                totalComprehensiveCost = totalComprehensiveCost,
                finalCostPerKg = finalCostPerKg
            )
        }
    }

    val uniquePackageLabels = remember(orderDataList) {
        orderDataList.flatMap { it.packageDetails }.map { it.label.trim() }.distinct().sorted()
    }

    val primaryLabel = uniquePackageLabels.firstOrNull() ?: "عبوة 18 كجم"
    val primaryPackCosts = orderDataList.mapNotNull { m ->
        val detail = m.packageDetails.find { it.label.trim().equals(primaryLabel, ignoreCase = true) }
        if (detail != null) m.order to detail else null
    }

    val minPackCostItem = primaryPackCosts.minByOrNull { it.second.totalCostPerPackage }
    val maxPackCostItem = primaryPackCosts.maxByOrNull { it.second.totalCostPerPackage }
    val avgPackCostVal = if (primaryPackCosts.isNotEmpty()) primaryPackCosts.map { it.second.totalCostPerPackage }.average() else 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("أدنى تكلفة عبوة ($primaryLabel)", fontSize = 10.sp, color = Color(0xFF065F46), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatCostTwoDec(minPackCostItem?.second?.totalCostPerPackage ?: 0.0)} شيكل",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF047857)
                    )
                    Text("أمر ${minPackCostItem?.first?.orderNumber ?: "-"}", fontSize = 9.5.sp, color = Color(0xFF065F46))
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                border = BorderStroke(1.dp, Color(0xFFFECACA)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("أعلى تكلفة عبوة ($primaryLabel)", fontSize = 10.sp, color = Color(0xFF991B1B), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatCostTwoDec(maxPackCostItem?.second?.totalCostPerPackage ?: 0.0)} شيكل",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFFB91C1C)
                    )
                    Text("أمر ${maxPackCostItem?.first?.orderNumber ?: "-"}", fontSize = 9.5.sp, color = Color(0xFF991B1B))
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("متوسط تكلفة العبوة", fontSize = 10.sp, color = GBRBlueMain, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${formatCostTwoDec(avgPackCostVal)} شيكل",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = GBRDarkIndigo
                    )
                    Text("للحجم: $primaryLabel", fontSize = 9.5.sp, color = Color.Gray)
                }
            }
        }

        uniquePackageLabels.forEach { packLabel ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Inventory2, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "مقارنة تفصيلية لتكلفة العبوة: $packLabel",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo
                            )
                        }
                        Text(
                            text = "حسب مدخلات التركيبة + سند الإنتاج 📦",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val horizScroll = rememberScrollState()

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(horizScroll)
                                .background(Color(0xFFF1F5F9))
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("رقم الأمر", fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(90.dp))
                            Text("سائل الدهان", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))
                            Text("العبوة الفارغة", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                            Text("الإضافات/التشغيل", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))
                            Text("إجمالي تكلفة العبوة", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
                            Text("فرق التكلفة عن المتوسط", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(115.dp))
                        }
                        Divider(color = IndustrialBorder)

                        orderDataList.forEach { metric ->
                            val packDetail = metric.packageDetails.find { it.label.trim().equals(packLabel, ignoreCase = true) }
                            val isHighlighted = highlightedOrderId == metric.order.id

                            val totalPackCost = packDetail?.totalCostPerPackage ?: 0.0
                            val liquidCost = packDetail?.liquidPaintCost ?: (packDetail?.netWeight ?: 18.0) * metric.paintCostPerKg
                            val emptyPrice = packDetail?.emptyPackPrice ?: 15.0
                            val extraCost = packDetail?.extraOperatingCost ?: 2.0

                            val deltaVal = if (avgPackCostVal > 0) totalPackCost - avgPackCostVal else 0.0
                            val deltaPct = if (avgPackCostVal > 0) (deltaVal / avgPackCostVal) * 100.0 else 0.0

                            Row(
                                modifier = Modifier
                                    .horizontalScroll(horizScroll)
                                    .clickable { highlightedOrderId = if (isHighlighted) null else metric.order.id }
                                    .background(if (isHighlighted) Color(0xFFDCFCE7) else Color.Transparent)
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.width(90.dp)) {
                                    Text(metric.order.orderNumber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(metric.order.batchNumber, fontSize = 9.5.sp, color = Color.Gray)
                                }

                                Text("${formatCostTwoDec(liquidCost)} شيكل", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))
                                Text("${formatCostTwoDec(emptyPrice)} شيكل", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                                Text("${formatCostTwoDec(extraCost)} شيكل", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))

                                Text(
                                    text = "${formatCostTwoDec(totalPackCost)} شيكل",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GBRDarkIndigo,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(110.dp)
                                )

                                Surface(
                                    color = when {
                                        deltaPct < -1.0 -> Color(0xFFDCFCE7)
                                        deltaPct > 1.0 -> Color(0xFFFEE2E2)
                                        else -> Color(0xFFF1F5F9)
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.width(115.dp)
                                ) {
                                    Text(
                                        text = when {
                                            deltaPct < -1.0 -> "وفر ${formatNum(-deltaVal)} شيكل (${formatNum(-deltaPct)}%) 🟢"
                                            deltaPct > 1.0 -> "زيادة +${formatNum(deltaVal)} شيكل (+${formatNum(deltaPct)}%) 🔺"
                                            else -> "مطابق للمتوسط ⚖️"
                                        },
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            deltaPct < -1.0 -> Color(0xFF047857)
                                            deltaPct > 1.0 -> Color(0xFFB91C1C)
                                            else -> Color.DarkGray
                                        },
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 3.dp, horizontal = 2.dp)
                                    )
                                }
                            }
                            Divider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "الملخص المالي الإجمالي لأوامر الإنتاج والدفعات 💰",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GBRDarkIndigo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val horizScroll = rememberScrollState()

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(horizScroll)
                            .background(Color(0xFFF1F5F9))
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("رقم الأمر", fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(90.dp))
                        Text("الوزن الإجمالي", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(85.dp))
                        Text("تكلفة الخام الكلية", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(100.dp))
                        Text("إجمالي تكلفة العبوات", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(105.dp))
                        Text("التكلفة الشاملة الكلية", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(115.dp))
                        Text("التكلفة الكلية/كجم", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(105.dp))
                    }
                    Divider(color = IndustrialBorder)

                    orderDataList.forEach { metric ->
                        val isHighlighted = highlightedOrderId == metric.order.id

                        Row(
                            modifier = Modifier
                                .horizontalScroll(horizScroll)
                                .clickable { highlightedOrderId = if (isHighlighted) null else metric.order.id }
                                .background(if (isHighlighted) Color(0xFFDCFCE7) else Color.Transparent)
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.width(90.dp)) {
                                Text(metric.order.orderNumber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(metric.order.batchNumber, fontSize = 9.5.sp, color = Color.Gray)
                            }

                            Text("${formatNoDec(metric.totalWeight)} كجم", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(85.dp))
                            Text("${formatCostTwoDec(metric.rawCost)} شيكل", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(100.dp))
                            Text("${formatCostTwoDec(metric.totalPackageCost)} شيكل", fontSize = 10.5.sp, textAlign = TextAlign.Center, modifier = Modifier.width(105.dp))
                            Text("${formatCostTwoDec(metric.totalComprehensiveCost)} شيكل", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GBRBlueMain, textAlign = TextAlign.Center, modifier = Modifier.width(115.dp))

                            Text(
                                text = "${formatCostTwoDec(metric.finalCostPerKg)} شيكل",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(105.dp)
                            )
                        }
                        Divider(color = Color(0xFFF1F5F9))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(200.dp))
    }
}


// ==============================================================================
// RECIPE EXECUTION & PACKAGING TIME ANALYTICS ENGINE
// ==============================================================================

data class RecipeBatchExecutionMetrics(
    val order: ProductionOrder,
    val orderNumber: String,
    val batchNumber: String,
    val operatorName: String,
    val dateStr: String,
    val totalWeightKg: Double,
    val mixingDurationMins: Double,
    val packagingDurationMins: Double,
    val totalDurationMins: Double,
    val containersCount: Int,
    val mixingRateKgPerMin: Double,
    val packagingRateKgPerMin: Double,
    val totalRateKgPerMin: Double,
    val isFastestMixing: Boolean = false,
    val isFastestPackaging: Boolean = false,
    val isFastestTotal: Boolean = false
)

@Composable
fun rememberRecipeBatchMetrics(
    selectedOrders: List<ProductionOrder>,
    allOrderEventsMap: Map<String, List<ProductionOrderEvent>>
): List<RecipeBatchExecutionMetrics> {
    val sdf = remember { java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US) }

    return remember(selectedOrders, allOrderEventsMap) {
        val list = selectedOrders.map { order ->
            val events = allOrderEventsMap[order.id] ?: emptyList()
            val startTs = if (order.startTime > 0) order.startTime else order.createdAt
            val endTs = if (order.endTime > 0) {
                order.endTime
            } else {
                events.lastOrNull()?.timestamp ?: (startTs + 3600000L)
            }

            val totalMins = maxOf(1.0, (endTs - startTs) / 60000.0)

            val pkgEvent = events.firstOrNull { ev ->
                val name = ev.eventName.lowercase()
                val desc = ev.description.lowercase()
                name.contains("تعبئة") || name.contains("عبوة") || desc.contains("تعبئة") || desc.contains("المحاذاة الفنية")
            }

            val (mixMins, pkgMins) = if (pkgEvent != null && pkgEvent.timestamp > startTs && pkgEvent.timestamp < endTs) {
                val mix = maxOf(1.0, (pkgEvent.timestamp - startTs) / 60000.0)
                val pkg = maxOf(1.0, (endTs - pkgEvent.timestamp) / 60000.0)
                Pair(mix, pkg)
            } else {
                val lastMatEvent = events.lastOrNull { ev ->
                    ev.eventName == "تغذية عنصر خط صالة" || ev.eventName == "إضافة مادة خام" || ev.eventName == "إكمال خطوة تشغيلية"
                }
                if (lastMatEvent != null && lastMatEvent.timestamp > startTs && lastMatEvent.timestamp < endTs) {
                    val mix = maxOf(1.0, (lastMatEvent.timestamp - startTs) / 60000.0)
                    val pkg = maxOf(1.0, (endTs - lastMatEvent.timestamp) / 60000.0)
                    Pair(mix, pkg)
                } else {
                    Pair(maxOf(1.0, totalMins * 0.65), maxOf(1.0, totalMins * 0.35))
                }
            }

            var containers = 0
            if (!order.actualPackagingJson.isNullOrBlank()) {
                try {
                    val arr = org.json.JSONArray(order.actualPackagingJson)
                    for (i in 0 until arr.length()) {
                        containers += arr.getJSONObject(i).optInt("actualCount", 0)
                    }
                } catch (e: Exception) {}
            }

            val wKg = order.requiredWeightKg.coerceAtLeast(1.0)
            val mixRate = wKg / mixMins
            val pkgRate = wKg / pkgMins
            val totalRate = wKg / totalMins
            val dStr = try { sdf.format(java.util.Date(startTs)) } catch(e: Exception) { "" }

            RecipeBatchExecutionMetrics(
                order = order,
                orderNumber = order.orderNumber,
                batchNumber = order.batchNumber,
                operatorName = order.operatorName.ifBlank { "المشرف" },
                dateStr = dStr,
                totalWeightKg = wKg,
                mixingDurationMins = mixMins,
                packagingDurationMins = pkgMins,
                totalDurationMins = totalMins,
                containersCount = containers,
                mixingRateKgPerMin = mixRate,
                packagingRateKgPerMin = pkgRate,
                totalRateKgPerMin = totalRate
            )
        }.sortedByDescending { it.order.createdAt }

        if (list.isEmpty()) return@remember emptyList()
        val minMix = list.minOf { it.mixingDurationMins }
        val minPkg = list.minOf { it.packagingDurationMins }
        val minTotal = list.minOf { it.totalDurationMins }

        list.map { m ->
            m.copy(
                isFastestMixing = (m.mixingDurationMins == minMix),
                isFastestPackaging = (m.packagingDurationMins == minPkg),
                isFastestTotal = (m.totalDurationMins == minTotal)
            )
        }
    }
}

@Composable
fun RecipeExecutionDurationsMatrixTab(
    selectedProduct: String,
    selectedOrders: List<ProductionOrder>,
    viewModel: GbrViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedOrderForDialog by remember { mutableStateOf<ProductionOrder?>(null) }
    var dialogPhases by remember { mutableStateOf<List<com.example.data.ProductionOrderPhase>>(emptyList()) }
    var dialogRecipeItems by remember { mutableStateOf<List<com.example.data.ProductionOrderRecipeItem>>(emptyList()) }
    var dialogEvents by remember { mutableStateOf<List<ProductionOrderEvent>>(emptyList()) }
    var showStepDialog by remember { mutableStateOf(false) }

    val rawMaterialsList by viewModel.rawMaterials.collectAsState()

    val allOrderEventsMap by produceState<Map<String, List<ProductionOrderEvent>>>(
        initialValue = emptyMap(),
        key1 = selectedOrders
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val map = mutableMapOf<String, List<ProductionOrderEvent>>()
            selectedOrders.forEach { ord ->
                try {
                    val evs = viewModel.repository.getProductionOrderEvents(ord.id).firstOrNull() ?: emptyList()
                    map[ord.id] = evs
                } catch (e: Exception) {
                    map[ord.id] = emptyList()
                }
            }
            map
        }
    }

    val metricsList = rememberRecipeBatchMetrics(selectedOrders, allOrderEventsMap)

    val avgMixMins = if (metricsList.isNotEmpty()) metricsList.map { it.mixingDurationMins }.average() else 0.0
    val avgPkgMins = if (metricsList.isNotEmpty()) metricsList.map { it.packagingDurationMins }.average() else 0.0
    val avgTotalMins = if (metricsList.isNotEmpty()) metricsList.map { it.totalDurationMins }.average() else 0.0
    val totalWeightProduced = metricsList.sumOf { it.totalWeightKg }

    val fastestMixing = metricsList.find { it.isFastestMixing }
    val fastestPkg = metricsList.find { it.isFastestPackaging }

    val executiveSummaryText = remember(selectedProduct, metricsList, avgMixMins, avgPkgMins, avgTotalMins, fastestMixing, fastestPkg) {
        val sb = StringBuilder()
        sb.append("⏱️ تقرير سجلات وأزمنة تنفيذ التركيبة: $selectedProduct\n")
        sb.append("إجمالي الدفعات المحللة: ${metricsList.size} دفعة إنتاجية\n")
        sb.append("إجمالي الكمية المصنعة: ${formatNum(totalWeightProduced)} كجم (${formatNum(totalWeightProduced / 1000.0)} طن)\n")
        sb.append("--------------------------------------------------\n\n")

        sb.append("📊 1. أزمنة التشغيل المتوسطة:\n")
        sb.append(" - متوسط وقت الخلط والتصنيع: ${formatNum(avgMixMins)} دقيقة/دفعة\n")
        sb.append(" - متوسط وقت التعبئة والتغليف: ${formatNum(avgPkgMins)} دقيقة/دفعة\n")
        sb.append(" - إجمالي زمن الدورة الإنتاجية: ${formatNum(avgTotalMins)} دقيقة/دفعة\n\n")

        if (fastestMixing != null) {
            sb.append("🥇 2. أسرع دفعة خلط وإنتاج:\n")
            sb.append(" - أمر رقم: ${fastestMixing.orderNumber} (دفعة ${fastestMixing.batchNumber})\n")
            sb.append(" - وقت الخلط القياسي: ${formatNum(fastestMixing.mixingDurationMins)} دقيقة (بسرعة ${formatNum(fastestMixing.mixingRateKgPerMin)} كجم/دقيقة)\n")
            sb.append(" - المشرف: ${fastestMixing.operatorName} | التاريخ: ${fastestMixing.dateStr}\n\n")
        }

        if (fastestPkg != null) {
            sb.append("⚡ 3. أسرع عملية تعبئة وتغليف:\n")
            sb.append(" - أمر رقم: ${fastestPkg.orderNumber} (دفعة ${fastestPkg.batchNumber})\n")
            sb.append(" - وقت التعبئة القياسي: ${formatNum(fastestPkg.packagingDurationMins)} دقيقة (معدل ${formatNum(fastestPkg.packagingRateKgPerMin)} كجم/دقيقة)\n")
            sb.append(" - عدد العبوات: ${fastestPkg.containersCount} عبوة | المشرف: ${fastestPkg.operatorName}\n\n")
        }

        sb.append("📋 4. سجل الدفعات التفصيلي:\n")
        metricsList.forEach { m ->
            sb.append(" - ${m.orderNumber} | خلط: ${formatNum(m.mixingDurationMins)}د | تعبئة: ${formatNum(m.packagingDurationMins)}د | كلي: ${formatNum(m.totalDurationMins)}د | كمية: ${formatNum(m.totalWeightKg)}كجم\n")
        }

        sb.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                        Text(
                            text = "تحليل كفاءة وأزمنة تنفيذ التركيبة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "قياس أزمنة الخلط، سرعتها، والتعبئة لـ ${metricsList.size} دفعات لمنتج ($selectedProduct)",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                Surface(
                    color = Color(0xFF0284C7).copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Text(
                        text = "${formatNum(totalWeightProduced)} كجم",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("متوسط وقت الخلط", fontSize = 10.sp, color = GBRBlueMain, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${formatNum(avgMixMins)} دقيقة", fontSize = 14.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo)
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                border = BorderStroke(1.dp, Color(0xFFFFEDD5)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("متوسط وقت التعبئة", fontSize = 10.sp, color = Color(0xFFC2410C), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${formatNum(avgPkgMins)} دقيقة", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF9A3412))
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("إجمالي زمن الدورة", fontSize = 10.sp, color = Color(0xFF047857), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${formatNum(avgTotalMins)} دقيقة", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF065F46))
                }
            }
        }

        if (fastestMixing != null || fastestPkg != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                        Text("لوحة الأرقام القياسية والتفوق التشغيلي 🏆", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (fastestMixing != null) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFFEF3C7),
                                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("🥇", fontSize = 12.sp)
                                        Text("أسرع خلط وإنتاج", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("أمر ${fastestMixing.orderNumber}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                                    Text("${formatNum(fastestMixing.mixingDurationMins)} دقيقة (${formatNum(fastestMixing.mixingRateKgPerMin)} كجم/د)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF78350F))
                                    Text("المشرف: ${fastestMixing.operatorName}", fontSize = 9.5.sp, color = Color.Gray)
                                }
                            }
                        }

                        if (fastestPkg != null) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFE0F2FE),
                                border = BorderStroke(1.dp, Color(0xFFBAE6FD)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("⚡", fontSize = 12.sp)
                                        Text("أسرع تعبئة وتغليف", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF075985))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("أمر ${fastestPkg.orderNumber}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0284C7))
                                    Text("${formatNum(fastestPkg.packagingDurationMins)} دقيقة (${fastestPkg.containersCount} عبوة)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1))
                                    Text("المشرف: ${fastestPkg.operatorName}", fontSize = 9.5.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(imageVector = Icons.Default.List, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                        Text("سجل أزمنة ومعدلات الدفعات تفصيلياً 📋", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    }
                    Text("${metricsList.size} دفعة", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                metricsList.forEach { m ->
                    val maxMins = maxOf(30.0, avgTotalMins * 1.5)
                    val mixRatio = (m.mixingDurationMins / maxMins).toFloat().coerceIn(0.05f, 1f)
                    val pkgRatio = (m.packagingDurationMins / maxMins).toFloat().coerceIn(0.05f, 1f)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = m.orderNumber,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo
                                    )
                                    Text(
                                        text = "(${m.batchNumber})",
                                        fontSize = 10.5.sp,
                                        color = Color.Gray
                                    )
                                    if (m.isFastestMixing) {
                                        Surface(color = Color(0xFFFEF3C7), shape = RoundedCornerShape(4.dp)) {
                                            Text("🥇 أسرع خلط", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                        }
                                    }
                                    if (m.isFastestPackaging) {
                                        Surface(color = Color(0xFFE0F2FE), shape = RoundedCornerShape(4.dp)) {
                                            Text("⚡ أسرع تعبئة", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0369A1), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                        }
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "${formatNum(m.totalWeightKg)} كجم",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRBlueMain
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedOrderForDialog = m.order
                                            coroutineScope.launch {
                                                dialogPhases = viewModel.repository.getProductionOrderPhases(m.order.id).firstOrNull() ?: emptyList()
                                                dialogRecipeItems = viewModel.repository.getProductionOrderRecipeItems(m.order.id).firstOrNull() ?: emptyList()
                                                dialogEvents = viewModel.repository.getProductionOrderEvents(m.order.id).firstOrNull() ?: emptyList()
                                                showStepDialog = true
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Visibility, contentDescription = "عرض الخطوات", tint = GBRBlueMain, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("خلط:", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.width(36.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(mixRatio)
                                                .background(GBRBlueMain, RoundedCornerShape(4.dp))
                                        )
                                    }
                                    Text("${formatNum(m.mixingDurationMins)} دقيقة", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, modifier = Modifier.width(60.dp))
                                }

                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("تعبئة:", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.width(36.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp)
                                            .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(pkgRatio)
                                                .background(Color(0xFFF97316), RoundedCornerShape(4.dp))
                                        )
                                    }
                                    Text("${formatNum(m.packagingDurationMins)} دقيقة", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC2410C), modifier = Modifier.width(60.dp))
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("المشرف: ${m.operatorName} | التاريخ: ${m.dateStr}", fontSize = 9.5.sp, color = Color.Gray)
                                Text("إجمالي الدورة: ${formatNum(m.totalDurationMins)} دقيقة", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF047857))
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("مستند التقرير الزمني والمؤشرات 📋", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("تقرير أزمنة التركيبة", executiveSummaryText)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "تم نسخ تقرير أزمنة التركيبة إلى الحافظة 📋", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("نسخ التقرير", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = executiveSummaryText,
                        fontSize = 11.sp,
                        color = Color(0xFF334155),
                        lineHeight = 17.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(120.dp))
    }

    if (showStepDialog && selectedOrderForDialog != null) {
        val completedSet = remember(selectedOrderForDialog) {
            val set = mutableSetOf<String>()
            try {
                val arr = org.json.JSONArray(selectedOrderForDialog!!.completedItemsJson.ifBlank { "[]" })
                for (i in 0 until arr.length()) set.add(arr.getString(i))
            } catch (e: Exception) {}
            set
        }

        ProductionBatchReferenceDialog(
            order = selectedOrderForDialog!!,
            phases = dialogPhases,
            recipeItems = dialogRecipeItems,
            rawMaterialsList = rawMaterialsList,
            completedItemsSet = completedSet,
            orderEvents = dialogEvents,
            onDismiss = { showStepDialog = false }
        )
    }
}

@Composable
fun RecipePerformanceAnalyticsModal(
    initialProductFilter: String? = null,
    viewModel: GbrViewModel,
    onClose: () -> Unit
) {
    val allOrders by viewModel.productionOrders.collectAsState()
    val formulations by viewModel.formulations.collectAsState()

    val completedOrders = remember(allOrders) {
        allOrders.filter {
            it.status == "مكتمل" || it.status.contains("مكتمل") || it.status.contains("مؤرشف") || it.status == "📦 مؤرشف"
        }
    }

    val getEffectiveFormulationName: (ProductionOrder) -> String = remember(formulations) {
        { order ->
            val matched = formulations.find { it.id == order.formulationId }
            (matched?.name?.trim() ?: order.formulationName.trim()).ifBlank { "بدون اسم" }
        }
    }

    val uniqueProducts = remember(completedOrders, formulations) {
        val approvedFormulations = formulations.filter {
            it.status == "🟢 معتمدة للإنتاج" || it.status.contains("معتمد")
        }
        val approvedIds = approvedFormulations.map { it.id }.toSet()
        val approvedNames = approvedFormulations.map { it.name.trim() }.filter { it.isNotBlank() }.toSet()

        val fromOrders = completedOrders.filter { order ->
            order.formulationId in approvedIds || getEffectiveFormulationName(order) in approvedNames
        }.map { getEffectiveFormulationName(it) }.filter { it.isNotBlank() }

        (approvedNames + fromOrders).distinct().sorted()
    }

    var selectedProduct by remember {
        mutableStateOf(
            if (!initialProductFilter.isNullOrBlank() && initialProductFilter in uniqueProducts) {
                initialProductFilter
            } else {
                uniqueProducts.firstOrNull() ?: ""
            }
        )
    }

    var showDropdown by remember { mutableStateOf(false) }

    val productOrders = remember(completedOrders, selectedProduct, formulations) {
        if (selectedProduct.isBlank()) {
            emptyList()
        } else {
            completedOrders.filter { order ->
                val effName = getEffectiveFormulationName(order)
                effName.equals(selectedProduct.trim(), ignoreCase = true) ||
                order.formulationName.trim().equals(selectedProduct.trim(), ignoreCase = true)
            }
        }.sortedByDescending { it.createdAt }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(22.dp))
                        Column {
                            Text(
                                text = "تحليل كفاءة وأزمنة تنفيذ التركيبات",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = GBRDarkIndigo
                            )
                            Text(
                                text = "سجلات الخلط، التعبئة، السرعة القياسية وأداء الدفعات",
                                fontSize = 10.5.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق", tint = ErrorRed)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color(0xFFE2E8F0))
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, IndustrialBorder),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                            Text("التركيبة المختارة:", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        }

                        Box {
                            OutlinedButton(
                                onClick = { showDropdown = true },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, GBRBlueMain),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (selectedProduct.isNotBlank()) selectedProduct else "اختر التركيبة 🧪",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GBRBlueMain
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, tint = GBRBlueMain)
                            }

                            DropdownMenu(
                                expanded = showDropdown,
                                onDismissRequest = { showDropdown = false }
                            ) {
                                uniqueProducts.forEach { prod ->
                                    DropdownMenuItem(
                                        text = { Text(prod, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            selectedProduct = prod
                                            showDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (selectedProduct.isBlank() || productOrders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                            Text(
                                text = if (selectedProduct.isNotBlank()) "لا توجد أوامر إنتاج مكتملة حالياً للتركيبة '$selectedProduct'" else "يرجى اختيار التركيبة من القائمة أعلاه لعرض تحليلات الأزمنة.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        RecipeExecutionDurationsMatrixTab(
                            selectedProduct = selectedProduct,
                            selectedOrders = productOrders,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun LabQualityMatrixTab(
    selectedOrders: List<ProductionOrder>,
    allTestRecords: List<ProductionOrderTestRecord>,
    allPoQualityTests: List<ProductionOrderQualityTest> = emptyList(),
    allFormulationQualityTests: List<FormulationQualityTest> = emptyList(),
    systemQualityTests: List<QualityTest> = emptyList(),
    labSessions: List<LabSession> = emptyList(),
    allLabTests: List<LabTest> = emptyList(),
    formulations: List<Formulation> = emptyList()
) {
    var highlightedTest by remember { mutableStateOf<String?>(null) }

    data class QCTestRowItem(
        val testKey: String,
        val displayName: String,
        val targetSpec: String,
        val minVal: Double? = null,
        val maxVal: Double? = null,
        val isViscosity: Boolean = false,
        val spindle: String? = null,
        val speed: String? = null
    )

    fun isNonBrookfieldTest(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("سلوك") || lower.contains("ريولوج") || lower.contains("rheolog") ||
               lower.contains("ثكسوتروب") || lower.contains("thixotrop") || lower.contains("مؤشر") ||
               lower.contains("index") || lower.contains("كوب") || lower.contains("ford") ||
               lower.contains("حساب") || lower.contains("calc") || lower.contains("نسبة") || lower.contains("معامل")
    }

    fun parseViscosityParams(name: String, notes: String): Pair<String?, String?> {
        if (isNonBrookfieldTest(name) || isNonBrookfieldTest(notes)) {
            return null to null
        }
        if (notes.startsWith("WIZARD_VISCOSITY:")) {
            val data = deserializeViscosityData(notes)
            if (data != null && (data.spindle.isNotBlank() || data.speed.isNotBlank())) {
                val sp = if (data.spindle.isNotBlank()) data.spindle else null
                val spd = if (data.speed.isNotBlank()) data.speed else null
                return sp to spd
            }
        }
        val text = "$name $notes"
        val regexSpindleSpeed = Regex("""(?:spindle|سبندل|مغزل)\s*[:=]?\s*([A-Za-z0-9]+).*(?:speed|rpm|سرعة)\s*[:=]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match1 = regexSpindleSpeed.find(text)
        if (match1 != null) {
            return match1.groupValues[1] to match1.groupValues[2]
        }
        val regexPairWithCode = Regex("""\b(R[1-7]|S[1-7]|RV[1-7]|HA[1-7]|HB[1-7])\s*[/,]\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
        val match2 = regexPairWithCode.find(text)
        if (match2 != null) {
            return match2.groupValues[1] to match2.groupValues[2]
        }
        if (text.contains("spindle", ignoreCase = true) || text.contains("سبندل") || text.contains("مغزل")) {
            val regexPairKeyword = Regex("""\b([1-7]|R[1-7])\s*[/,]\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
            val match3 = regexPairKeyword.find(text)
            if (match3 != null) {
                return match3.groupValues[1] to match3.groupValues[2]
            }
        }
        return null to null
    }

    val (qcRows, orderTestResultsMap) = remember(
        selectedOrders,
        allTestRecords,
        allPoQualityTests,
        allFormulationQualityTests,
        systemQualityTests,
        labSessions,
        allLabTests,
        formulations
    ) {
        val orderResultsMap = mutableMapOf<String, MutableMap<String, Double>>()
        selectedOrders.forEach { orderResultsMap[it.id] = mutableMapOf() }

        val rowMap = LinkedHashMap<String, QCTestRowItem>()

        // 1. Filter Laboratory sessions to single test sessions only (جلسات فحص أحادية)
        val singleLabSessions = labSessions.filter { session ->
            session.comparisonType.isNullOrBlank() || session.testType != "⚖️ فحص مقارنة"
        }
        val singleSessionIds = singleLabSessions.map { it.id }.toSet()
        val singleLabTests = allLabTests.filter { it.sessionId in singleSessionIds }

        // 2. Add test names strictly from Laboratory single test items (LabTest)
        singleLabTests.forEach { tst ->
            val cleanName = tst.name.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim()
            if (cleanName.isNotBlank()) {
                val (spindle, speed) = parseViscosityParams(cleanName, tst.notes)
                val isVisc = (cleanName.contains("لزوجة") || cleanName.contains("viscosity")) && !isNonBrookfieldTest(cleanName)
                val testKey = if (isVisc && spindle != null && speed != null) "${cleanName}_${spindle}_${speed}" else cleanName

                var minV: Double? = null
                var maxV: Double? = null

                val matchingPoTest = allPoQualityTests.find {
                    it.testName.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim().equals(cleanName, ignoreCase = true) ||
                    it.testId == tst.id || it.id == tst.id
                }
                if (matchingPoTest != null) {
                    minV = matchingPoTest.minValue
                    maxV = matchingPoTest.maxValue
                } else {
                    val matchingSysTest = systemQualityTests.find {
                        it.name.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim().equals(cleanName, ignoreCase = true) ||
                        it.id == tst.id
                    }
                    if (matchingSysTest != null) {
                        val matchingFq = allFormulationQualityTests.find { fq -> fq.testId == matchingSysTest.id }
                        if (matchingFq != null) {
                            minV = matchingFq.minValue
                            maxV = matchingFq.maxValue
                        }
                    }
                }

                val targetSpecStr = when {
                    minV != null && maxV != null -> "${formatNum(minV)} - ${formatNum(maxV)}"
                    minV != null -> "> ${formatNum(minV)}"
                    maxV != null -> "< ${formatNum(maxV)}"
                    else -> "—"
                }

                if (!rowMap.containsKey(testKey)) {
                    rowMap[testKey] = QCTestRowItem(
                        testKey = testKey,
                        displayName = cleanName,
                        targetSpec = targetSpecStr,
                        minVal = minV,
                        maxVal = maxV,
                        isViscosity = isVisc,
                        spindle = spindle,
                        speed = speed
                    )
                }
            }
        }

        // 3. Add test names from ProductionOrderQualityTests for selected orders
        selectedOrders.forEach { ord ->
            val poTestsForOrder = allPoQualityTests.filter { it.productionOrderId == ord.id }
            poTestsForOrder.forEach { poTest ->
                val cleanName = poTest.testName.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim()
                if (cleanName.isNotBlank()) {
                    val (spindle, speed) = parseViscosityParams(cleanName, "")
                    val isVisc = (cleanName.contains("لزوجة") || cleanName.contains("viscosity")) && !isNonBrookfieldTest(cleanName)
                    val testKey = if (isVisc && spindle != null && speed != null) "${cleanName}_${spindle}_${speed}" else cleanName

                    val minV = poTest.minValue
                    val maxV = poTest.maxValue
                    val targetSpecStr = when {
                        minV != null && maxV != null -> "${formatNum(minV)} - ${formatNum(maxV)}"
                        minV != null -> "> ${formatNum(minV)}"
                        maxV != null -> "< ${formatNum(maxV)}"
                        else -> "—"
                    }

                    val existing = rowMap[testKey]
                    if (existing == null) {
                        rowMap[testKey] = QCTestRowItem(
                            testKey = testKey,
                            displayName = cleanName,
                            targetSpec = targetSpecStr,
                            minVal = minV,
                            maxVal = maxV,
                            isViscosity = isVisc,
                            spindle = spindle,
                            speed = speed
                        )
                    } else if (existing.minVal == null && existing.maxVal == null && (minV != null || maxV != null)) {
                        rowMap[testKey] = existing.copy(
                            targetSpec = targetSpecStr,
                            minVal = minV,
                            maxVal = maxV
                        )
                    }
                }
            }
        }

        // 4. Add test names from system quality tests that are enabled for selected formulations
        val uniqueFormulationIds = selectedOrders.mapNotNull { it.formulationId }.toSet()
        uniqueFormulationIds.forEach { formId ->
            val fqTests = allFormulationQualityTests.filter { it.formulationId == formId }
            fqTests.forEach { fq ->
                val sysTest = systemQualityTests.find { it.id == fq.testId }
                val tName = sysTest?.name ?: fq.testId
                val cleanName = tName.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim()
                if (cleanName.isNotBlank()) {
                    val (spindle, speed) = parseViscosityParams(cleanName, "")
                    val isVisc = (cleanName.contains("لزوجة") || cleanName.contains("viscosity")) && !isNonBrookfieldTest(cleanName)
                    val testKey = if (isVisc && spindle != null && speed != null) "${cleanName}_${spindle}_${speed}" else cleanName

                    val minV = fq.minValue
                    val maxV = fq.maxValue
                    val targetSpecStr = when {
                        minV != null && maxV != null -> "${formatNum(minV)} - ${formatNum(maxV)}"
                        minV != null -> "> ${formatNum(minV)}"
                        maxV != null -> "< ${formatNum(maxV)}"
                        else -> "—"
                    }

                    if (!rowMap.containsKey(testKey)) {
                        rowMap[testKey] = QCTestRowItem(
                            testKey = testKey,
                            displayName = cleanName,
                            targetSpec = targetSpecStr,
                            minVal = minV,
                            maxVal = maxV,
                            isViscosity = isVisc,
                            spindle = spindle,
                            speed = speed
                        )
                    }
                }
            }
        }

        fun findMatchingRowKey(
            rawKey: String,
            rawClean: String,
            spindle: String?,
            speed: String?,
            currentRows: Map<String, QCTestRowItem>
        ): String? {
            if (currentRows.containsKey(rawKey)) return rawKey
            val composedKey = if (spindle != null && speed != null) "${rawClean}_${spindle}_${speed}" else rawClean
            if (currentRows.containsKey(composedKey)) return composedKey

            if (spindle != null && speed != null) {
                val spMatch = currentRows.entries.find { (_, item) ->
                    (item.displayName.equals(rawClean, ignoreCase = true) || item.testKey.contains(rawClean, ignoreCase = true)) &&
                    item.spindle.equals(spindle, ignoreCase = true) &&
                    item.speed.equals(speed, ignoreCase = true)
                }
                if (spMatch != null) return spMatch.key
            }

            val nameMatch = currentRows.entries.find { (k, item) ->
                k.equals(rawClean, ignoreCase = true) || item.displayName.equals(rawClean, ignoreCase = true)
            }
            if (nameMatch != null) return nameMatch.key

            val normRaw = rawClean.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه").replace("ى", "ي").trim().lowercase()
            val normMatch = currentRows.entries.find { (_, item) ->
                val normItem = item.displayName.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا").replace("ة", "ه").replace("ى", "ي").trim().lowercase()
                normItem == normRaw
            }
            if (normMatch != null) return normMatch.key

            if (normRaw.contains("ph") || normRaw.contains("قلوية") || normRaw.contains("حموضة")) {
                val phMatch = currentRows.entries.find { (_, item) ->
                    val n = item.displayName.lowercase()
                    n.contains("ph") || n.contains("قلوية") || n.contains("حموضة")
                }
                if (phMatch != null) return phMatch.key
            }
            if (normRaw.contains("كثافة") || normRaw.contains("density")) {
                val densityMatch = currentRows.entries.find { (_, item) ->
                    val n = item.displayName.lowercase()
                    n.contains("كثافة") || n.contains("density")
                }
                if (densityMatch != null) return densityMatch.key
            }
            if (normRaw.contains("كوب") || normRaw.contains("ford")) {
                val fordMatch = currentRows.entries.find { (_, item) ->
                    val n = item.displayName.lowercase()
                    n.contains("كوب") || n.contains("ford")
                }
                if (fordMatch != null) return fordMatch.key
            }
            if (normRaw.contains("سلوك") || normRaw.contains("ريولوج") || normRaw.contains("rheolog")) {
                val rheoMatch = currentRows.entries.find { (_, item) ->
                    val n = item.displayName.lowercase()
                    n.contains("سلوك") || n.contains("ريولوج") || n.contains("rheolog")
                }
                if (rheoMatch != null) return rheoMatch.key
            }

            return null
        }

        // 5. Extract batch test results for each selected production order based on order test records & lab tests
        selectedOrders.forEach { ord ->
            // A. Results from lab tests in single test sessions linked to this order
            val ordSessions = singleLabSessions.filter { s ->
                s.sampleProperties.contains("ORDER_ID:${ord.id}") ||
                s.sampleProperties.contains("ORDER_NO:${ord.orderNumber}") ||
                s.sampleProperties.contains(ord.id) ||
                s.sampleOrProduct.trim().equals(ord.orderNumber.trim(), ignoreCase = true) ||
                (ord.batchNumber.isNotBlank() && s.sampleOrProduct.trim().equals(ord.batchNumber.trim(), ignoreCase = true)) ||
                (ord.formulationName.isNotBlank() && s.sampleOrProduct.trim().equals(ord.formulationName.trim(), ignoreCase = true))
            }
            val ordSessionIds = ordSessions.map { it.id }.toSet()
            val ordLabTests = singleLabTests.filter { it.sessionId in ordSessionIds }

            ordLabTests.forEach { tst ->
                val cleanName = tst.name.replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim()
                var valDouble = tst.testValueA?.toDoubleOrNull() ?: tst.testValueB?.toDoubleOrNull()

                // Check wizard viscosity notes if value not directly set
                if ((valDouble == null || valDouble == 0.0) && tst.notes.startsWith("WIZARD_VISCOSITY:")) {
                    val data = deserializeViscosityData(tst.notes)
                    if (data != null) {
                        val validReadings = data.readings.mapNotNull { it.viscosity.toDoubleOrNull() }
                        if (validReadings.isNotEmpty()) {
                            valDouble = validReadings.average()
                        }
                    }
                }

                if (valDouble != null && !valDouble.isNaN()) {
                    val (sp, spd) = parseViscosityParams(cleanName, tst.notes)
                    val matchedKey = findMatchingRowKey(cleanName, cleanName, sp, spd, rowMap)
                    val isVisc = (cleanName.contains("لزوجة") || cleanName.contains("viscosity")) && !isNonBrookfieldTest(cleanName)
                    val testKey = matchedKey ?: (if (isVisc && sp != null && spd != null) "${cleanName}_${sp}_${spd}" else cleanName)

                    if (!rowMap.containsKey(testKey)) {
                        rowMap[testKey] = QCTestRowItem(
                            testKey = testKey,
                            displayName = cleanName,
                            targetSpec = "—",
                            isViscosity = isVisc,
                            spindle = sp,
                            speed = spd
                        )
                    }
                    orderResultsMap[ord.id]?.put(testKey, valDouble)
                }
            }

            // B. Results from ProductionOrderTestRecord (resultsJson) for this order
            val directRecs = allTestRecords.filter { it.productionOrderId == ord.id }
                .sortedBy { it.timestamp }
            directRecs.forEach { rec ->
                val rawJson = rec.resultsJson.trim()
                val parsedResults = mutableListOf<Triple<String, Double, Pair<String?, String?>>>()

                if (rawJson.startsWith("{")) {
                    try {
                        val json = org.json.JSONObject(rawJson)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val rawKey = keys.next()
                            var v = json.optDouble(rawKey, Double.NaN)
                            if (v.isNaN()) {
                                v = json.optString(rawKey, "").toDoubleOrNull() ?: Double.NaN
                            }
                            if (!v.isNaN()) {
                                parsedResults.add(Triple(rawKey, v, null to null))
                            }
                        }
                    } catch (e: Exception) { }
                } else if (rawJson.startsWith("[")) {
                    try {
                        val jsonArr = org.json.JSONArray(rawJson)
                        for (i in 0 until jsonArr.length()) {
                            val obj = jsonArr.optJSONObject(i) ?: continue
                            val rawKey = obj.optString("name", obj.optString("testName", obj.optString("testId", ""))).trim()
                            var v = obj.optDouble("value", Double.NaN)
                            if (v.isNaN()) {
                                v = obj.optString("value", obj.optString("result", "")).toDoubleOrNull() ?: Double.NaN
                            }
                            val sp = obj.optString("spindle", "").ifBlank { null }
                            val spd = obj.optString("speed", "").ifBlank { null }
                            if (!v.isNaN() && rawKey.isNotBlank()) {
                                parsedResults.add(Triple(rawKey, v, sp to spd))
                            }
                        }
                    } catch (e: Exception) { }
                }

                parsedResults.forEach { (rawKey, v, jsonParams) ->
                    val poTest = allPoQualityTests.find { it.productionOrderId == ord.id && (it.testId == rawKey || it.id == rawKey || it.testName.equals(rawKey, ignoreCase = true)) }
                    val sysTest = systemQualityTests.find { it.id == rawKey || it.name.equals(rawKey, ignoreCase = true) }
                    val rawClean = (poTest?.testName ?: sysTest?.name ?: rawKey).replace("🧪", "").replace("🔬", "").replace("🛡️", "").trim()

                    val (parsedSp, parsedSpd) = parseViscosityParams(rawClean, "")
                    val finalSp = jsonParams.first ?: parsedSp
                    val finalSpd = jsonParams.second ?: parsedSpd

                    val matchedKey = findMatchingRowKey(rawKey, rawClean, finalSp, finalSpd, rowMap)

                    if (matchedKey != null) {
                        orderResultsMap[ord.id]?.put(matchedKey, v)
                    } else {
                        val isVisc = (rawClean.contains("لزوجة") || rawClean.contains("viscosity")) && !isNonBrookfieldTest(rawClean)
                        val testKey = if (isVisc && finalSp != null && finalSpd != null) "${rawClean}_${finalSp}_${finalSpd}" else rawClean
                        val newRow = QCTestRowItem(
                            testKey = testKey,
                            displayName = rawClean,
                            targetSpec = "—",
                            isViscosity = isVisc,
                            spindle = finalSp,
                            speed = finalSpd
                        )
                        rowMap[testKey] = newRow
                        orderResultsMap[ord.id]?.put(testKey, v)
                    }
                }
            }
        }

        // Helper to sort tests by standard priority rule (Lab Single Session / Archived PO QC Tab order)
        fun getQCRowOrderPriority(item: QCTestRowItem): Double {
            val speedDouble = item.speed?.toDoubleOrNull() ?: 0.0
            val basePriority = getTestOrderPriority(item.displayName)
            if (basePriority >= 100.0 && basePriority < 200.0 && speedDouble > 0.0) {
                return 100.0 + speedDouble
            }
            return basePriority
        }

        val sortedQcRows = rowMap.values.sortedWith(
            compareBy<QCTestRowItem> { getQCRowOrderPriority(it) }
                .thenBy { it.displayName }
        )

        sortedQcRows to orderResultsMap
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        // Upper Header Summary - Matches RawMaterialsMatrixTab & CostsAnalysisMatrixTab height & spacing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "مصفوفة فحوصات ضبط الجودة (${qcRows.size})",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = GBRDarkIndigo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "عدد الدفعات: ${selectedOrders.size}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Gray
            )
        }

        if (qcRows.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                border = BorderStroke(1.dp, IndustrialBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🔬", fontSize = 32.sp)
                        Text("لم يتم تسجيل أو ربط نتائج فحوصات ضبط الجودة مع هذه الدفعات بعد", color = Color.Gray, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Text("يمكنك إدخال نتائج الفحوصات اليومية والمخبرية من لسان 'ضبط الجودة المباشر' داخل تفاصيل أمر الإنتاج أو عبر جلسات المختبر.", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, IndustrialBorder),
                shape = RoundedCornerShape(10.dp)
            ) {
                val horizScroll = rememberScrollState()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizScroll)
                ) {
                    Column(modifier = Modifier.wrapContentWidth()) {
                        // Matrix Table Column Headers
                        Row(
                            modifier = Modifier
                                .background(Color(0xFFF1F5F9))
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "اسم فحص ضبط الجودة المعتمد",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = GBRDarkIndigo,
                                modifier = Modifier.width(180.dp)
                            )
                            Text(
                                text = "المواصفة والحدود",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = GBRBlueMain,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(115.dp)
                            )
                            selectedOrders.forEach { ord ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(105.dp)
                                ) {
                                    Text(
                                        text = ord.orderNumber,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF334155),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (ord.batchNumber.isNotBlank()) {
                                        Text(
                                            text = "#${ord.batchNumber}",
                                            fontSize = 9.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "متوسط الدفعات",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF047857),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(95.dp)
                            )
                            Text(
                                text = "حالة المطابقة",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF475569),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(105.dp)
                            )
                        }
                        Divider(color = IndustrialBorder)

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 180.dp)
                        ) {
                            items(qcRows, key = { it.testKey }) { row ->
                                val values = selectedOrders.map { ord ->
                                    orderTestResultsMap[ord.id]?.get(row.testKey)
                                }
                                val validValues = values.filterNotNull()
                                val avgVal = if (validValues.isNotEmpty()) validValues.average() else null

                                var anyFailed = false
                                var anyMeasured = false

                                values.forEach { v ->
                                    if (v != null) {
                                        anyMeasured = true
                                        if ((row.minVal != null && v < row.minVal) || (row.maxVal != null && v > row.maxVal)) {
                                            anyFailed = true
                                        }
                                    }
                                }

                                val isHighlighted = highlightedTest == row.testKey

                                Row(
                                    modifier = Modifier
                                        .clickable { highlightedTest = if (isHighlighted) null else row.testKey }
                                        .background(if (isHighlighted) Color(0xFFF0FDF4) else Color.Transparent)
                                        .padding(vertical = 9.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 1. Approved Test Name
                                    Column(modifier = Modifier.width(180.dp)) {
                                        Text(
                                            text = row.displayName,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRDarkIndigo,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (row.isViscosity && (row.spindle != null || row.speed != null)) {
                                            val spStr = if (row.spindle != null) "رقم السبندل: ${row.spindle}" else ""
                                            val speedStr = if (row.speed != null) "السرعة: ${row.speed} RPM" else ""
                                            val detailStr = listOf(spStr, speedStr).filter { it.isNotBlank() }.joinToString(" | ")
                                            Text(
                                                text = "⚙️ $detailStr",
                                                fontSize = 9.sp,
                                                color = Color(0xFF2563EB),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    // 2. Target Spec
                                    Box(
                                        modifier = Modifier.width(115.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            color = Color(0xFFF8FAFC),
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(0.5.dp, Color(0xFFCBD5E1))
                                        ) {
                                            Text(
                                                text = row.targetSpec,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF475569),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    // 3. Batch Results for selected orders
                                    values.forEach { valNum ->
                                        Box(
                                            modifier = Modifier
                                                .width(105.dp)
                                                .padding(horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (valNum != null) {
                                                val isOutOfRange = (row.minVal != null && valNum < row.minVal) || (row.maxVal != null && valNum > row.maxVal)
                                                if (isOutOfRange) {
                                                    Surface(
                                                        color = Color(0xFFFEF2F2),
                                                        shape = RoundedCornerShape(4.dp),
                                                        border = BorderStroke(0.5.dp, Color(0xFFFCA5A5))
                                                    ) {
                                                        Text(
                                                            text = "⚠️ ${formatNum(valNum)}",
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFFDC2626),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                } else {
                                                    Surface(
                                                        color = if (row.minVal != null || row.maxVal != null) Color(0xFFECFDF5) else Color(0xFFF0F9FF),
                                                        shape = RoundedCornerShape(4.dp),
                                                        border = BorderStroke(0.5.dp, if (row.minVal != null || row.maxVal != null) Color(0xA10B981) else Color(0xFFBAE6FD))
                                                    ) {
                                                        Text(
                                                            text = "✓ ${formatNum(valNum)}",
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (row.minVal != null || row.maxVal != null) Color(0xFF047857) else Color(0xFF0369A1),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text("—", fontSize = 11.sp, color = Color.LightGray)
                                            }
                                        }
                                    }

                                    // 4. Batch Average
                                    Text(
                                        text = if (avgVal != null) formatNum(avgVal) else "—",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF047857),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(95.dp)
                                    )

                                    // 5. Overall Compliance Evaluation
                                    Box(
                                        modifier = Modifier.width(105.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        when {
                                            !anyMeasured -> {
                                                Text(
                                                    text = "لم يُفحص ⏳",
                                                    fontSize = 9.5.sp,
                                                    color = Color.Gray,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            anyFailed -> {
                                                Surface(
                                                    color = Color(0xFFFEF2F2),
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(0.5.dp, Color(0xFFFCA5A5))
                                                ) {
                                                    Text(
                                                        text = "غير مطابق 🔴",
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFDC2626),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            row.minVal != null || row.maxVal != null -> {
                                                Surface(
                                                    color = Color(0xFFECFDF5),
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(0.5.dp, Color(0xFFA7F3D0))
                                                ) {
                                                    Text(
                                                        text = "مطابق 🟢",
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF047857),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            else -> {
                                                Surface(
                                                    color = Color(0xFFEFF6FF),
                                                    shape = RoundedCornerShape(12.dp),
                                                    border = BorderStroke(0.5.dp, Color(0xFFBFDBFE))
                                                ) {
                                                    Text(
                                                        text = "تم الفحص 🔵",
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF1D4ED8),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Divider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YieldPackagingMatrixTab(
    selectedOrders: List<ProductionOrder>
) {
    var highlightedOrderId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("تحليل الإنتاجية، العبوات، ونسبة الهدر والفاقد", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            val horizScroll = rememberScrollState()

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(horizScroll)
                        .background(Color(0xFFF1F5F9))
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("رقم الأمر", fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(90.dp))
                    Text("المخطط (كجم)", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                    Text("المعبأ الفعلي (كجم)", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(105.dp))
                    Text("نسبة الإنجاز %", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))
                    Text("الفاقد / الهدر", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(95.dp))
                    Text("إجمالي العبوات", fontSize = 11.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                }
                Divider(color = IndustrialBorder)

                selectedOrders.forEach { ord ->
                    val packList = parsePackContents(ord.actualPackagingJson)
                    var totalFilledKg = 0.0
                    var totalContainerCount = 0

                    packList.forEach { act ->
                        val count = act["actualCount"]?.toDoubleOrNull()?.toInt() ?: 0
                        val netWeight = act["netWeight"]?.toDoubleOrNull() ?: 18.0
                        totalFilledKg += count * netWeight
                        totalContainerCount += count
                    }

                    val plannedKg = ord.requiredWeightKg.coerceAtLeast(1.0)
                    val yieldPct = if (plannedKg > 0) (totalFilledKg / plannedKg) * 100.0 else 100.0
                    val lossKg = (plannedKg - totalFilledKg).coerceAtLeast(0.0)
                    val lossPct = if (plannedKg > 0) (lossKg / plannedKg) * 100.0 else 0.0

                    val isHighlighted = highlightedOrderId == ord.id

                    Row(
                        modifier = Modifier
                            .horizontalScroll(horizScroll)
                            .clickable { highlightedOrderId = if (isHighlighted) null else ord.id }
                            .background(if (isHighlighted) Color(0xFFDCFCE7) else Color.Transparent)
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.width(90.dp)) {
                            Text(ord.orderNumber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(ord.batchNumber, fontSize = 9.5.sp, color = Color.Gray)
                        }

                        Text(formatNoDec(plannedKg), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                        Text(formatNoDec(totalFilledKg), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(105.dp))

                        Text(
                            text = "${formatNum(yieldPct)}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (yieldPct >= 98.0) Color(0xFF047857) else Color(0xFFD97706),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(95.dp)
                        )

                        Text(
                            text = "${formatNum(lossKg)} كجم (${formatNum(lossPct)}%)",
                            fontSize = 10.5.sp,
                            color = if (lossPct > 3.0) Color(0xFFB91C1C) else Color(0xFF475569),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(95.dp)
                        )

                        Text("${totalContainerCount} عبوة", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(90.dp))
                    }
                    Divider(color = Color(0xFFF1F5F9))
                }
            }
        }

        Spacer(modifier = Modifier.height(200.dp))
    }
}

@Composable
fun SmartExecutiveAnalyticsTab(
    selectedProduct: String,
    selectedOrders: List<ProductionOrder>,
    allOrderItems: List<ProductionOrderItem>,
    baselineItems: List<FormulationItem>,
    allTestRecords: List<ProductionOrderTestRecord>,
    labSessions: List<LabSession>,
    allLabTests: List<LabTest>,
    context: android.content.Context
) {
    val orderItemsMap = remember(selectedOrders, allOrderItems) {
        selectedOrders.associate { ord ->
            ord.id to allOrderItems.filter { it.productionOrderId == ord.id }
        }
    }

    val allMatNames = remember(orderItemsMap) {
        orderItemsMap.values.flatten().map { it.rawMaterialName.trim() }.distinct().filter { it.isNotBlank() }
    }

    val materialVariances = remember(selectedOrders, orderItemsMap, allMatNames) {
        allMatNames.map { name ->
            val ratios = selectedOrders.map { ord ->
                val items = orderItemsMap[ord.id] ?: emptyList()
                val match = items.find { it.rawMaterialName.trim().equals(name, ignoreCase = true) }
                val ordW = ord.requiredWeightKg.coerceAtLeast(1.0)
                if (match != null) (match.calculatedQuantity / ordW) * 1000.0 else 0.0
            }.filter { it > 0 }

            val avg = if (ratios.isNotEmpty()) ratios.average() else 0.0
            val max = ratios.maxOrNull() ?: 0.0
            val min = ratios.minOrNull() ?: 0.0
            val variance = max - min
            Triple(name, avg, variance)
        }.sortedByDescending { it.third }
    }

    val orderCosts = remember(selectedOrders, orderItemsMap) {
        selectedOrders.map { ord ->
            val items = orderItemsMap[ord.id] ?: emptyList()
            val totalW = items.sumOf { it.calculatedQuantity }.let { if (it <= 0) ord.requiredWeightKg else it }
            val rawCost = items.sumOf { it.calculatedQuantity * it.rawMaterialPrice }
            val paintCostPerKg = if (totalW > 0) rawCost / totalW else 0.0
            val defaultPackCost18 = (18.0 * paintCostPerKg) + 15.0 + 2.0
            Triple(ord, paintCostPerKg, defaultPackCost18)
        }
    }

    val minCostOrder = orderCosts.minByOrNull { it.second }
    val maxCostOrder = orderCosts.maxByOrNull { it.second }
    val avgPaintCostPerKg = if (orderCosts.isNotEmpty()) orderCosts.map { it.second }.average() else 0.0
    val avgPack18Cost = if (orderCosts.isNotEmpty()) orderCosts.map { it.third }.average() else 0.0
    val maxCostVariancePct = if (minCostOrder != null && maxCostOrder != null && minCostOrder.second > 0) {
        ((maxCostOrder.second - minCostOrder.second) / minCostOrder.second) * 100.0
    } else 0.0

    val reportText = remember(selectedProduct, selectedOrders, materialVariances, orderCosts, avgPaintCostPerKg, avgPack18Cost) {
        val sb = StringBuilder()
        sb.append("📊 التقرير التحليلي الذكي لمنتج: $selectedProduct\n")
        sb.append("عدد الأوامر/الدفعات المحللة: ${selectedOrders.size} أوامر إنتاج\n")
        sb.append("تاريخ التقرير: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n")
        sb.append("--------------------------------------------------\n\n")

        sb.append("🧪 1. استقرار المواد الخام ونسب التركيبة:\n")
        materialVariances.take(5).forEach { (name, avg, varVal) ->
            sb.append(" - المادة '$name': بمتوسط ${formatNum(avg)} كجم/طن (تباين أقصى بين الدفعات: ${formatNum(varVal)} كجم)\n")
        }
        sb.append("\n")

        sb.append("💰 2. التحليل المالي وتكاليف العبوات والأثر من سندات الإنتاج:\n")
        sb.append(" - متوسط تكلفة الكيلو خامات: ${formatCostTwoDec(avgPaintCostPerKg)} شيكل/كجم\n")
        sb.append(" - متوسط تكلفة العبوة الرئيسية (18كجم): ${formatCostTwoDec(avgPack18Cost)} شيكل/عبوة\n")
        if (minCostOrder != null) {
            sb.append(" - الدفعة الأكثر كفاءة مالية: أمر ${minCostOrder.first.orderNumber} بتكلفة عبوة ${formatCostTwoDec(minCostOrder.third)} شيكل\n")
        }
        if (maxCostOrder != null) {
            sb.append(" - الدفعة أعلى تكلفة: أمر ${maxCostOrder.first.orderNumber} بتكلفة عبوة ${formatCostTwoDec(maxCostOrder.third)} شيكل\n")
        }
        sb.append("\n")

        sb.append("🔬 3. الجودة والمطابقة المعملية:\n")
        sb.append(" - تم فحص نتائج المعمل المقترنة بأوامر الإنتاج والتأكد من نطاقات اللزوجة والوزن النوعي.\n\n")

        sb.append("💡 4. التوصيات التنفيذية والفنية:\n")
        sb.append(" - اعتماد نطاق تسامح ±2% للمواد الأساسية لضمان ثبات تكلفة العبوات المعبأة.\n")
        sb.append(" - ضبط مدخلات سندات الإنتاج لسعر العبوات الفارغة ومصاريف التشغيل لتسجيل التكاليف بدقة.\n")

        sb.toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = GBRDarkIndigo),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "التقرير التنفيذي الذكي ومقارنة الجودة والتكلفة 📊",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "تحليل شامل لـ ${selectedOrders.size} دفعات إنتاجية لمنتج: $selectedProduct",
                        fontSize = 10.5.sp,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("متوسط العبوة (18 كجم)", fontSize = 10.sp, color = GBRBlueMain, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${formatCostTwoDec(avgPack18Cost)} شيكل", fontSize = 14.sp, fontWeight = FontWeight.Black, color = GBRDarkIndigo)
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("أقصى تباين بالتكلفة", fontSize = 10.sp, color = Color(0xFF92400E), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("${formatNum(maxCostVariancePct)}%", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFFB45309))
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                border = BorderStroke(1.dp, Color(0xFFA7F3D0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("الأفضل كفاءة مالية", fontSize = 10.sp, color = Color(0xFF065F46), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("أمر ${minCostOrder?.first?.orderNumber ?: "-"}", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF047857))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Science, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("1. استقرار المواد الخام والتفاوتات في الخلطة 🧪", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                }
                Spacer(modifier = Modifier.height(8.dp))

                materialVariances.take(4).forEach { (matName, avgVal, varVal) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(matName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                            Text("متوسط الاستهلاك: ${formatNum(avgVal)} كجم/طن", fontSize = 9.5.sp, color = Color.Gray)
                        }

                        Surface(
                            color = if (varVal > 5.0) Color(0xFFFEE2E2) else Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (varVal > 5.0) "تباين مرتفع: ${formatNum(varVal)} كجم ⚠️" else "مستقر: ±${formatNum(varVal)} كجم 🟢",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (varVal > 5.0) Color(0xFFB91C1C) else Color(0xFF047857),
                                modifier = Modifier.padding(vertical = 3.dp, horizontal = 6.dp)
                            )
                        }
                    }
                    Divider(color = Color(0xFFF1F5F9))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Inventory2, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("2. تحليل تكلفة العبوات وأثر سندات الإنتاج 📦", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "• يتراوح سعر العبوة 18كجم المعبأة الشاملة من ${formatCostTwoDec(minCostOrder?.third ?: 0.0)} شيكل إلى ${formatCostTwoDec(maxCostOrder?.third ?: 0.0)} شيكل حسب تغير أسعار الخامات والتعبئة من سند الإنتاج.",
                    fontSize = 11.sp,
                    color = Color(0xFF334155),
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "• التأكد المباشر من أسعار العبوات الفارغة والمطبوعة في سند الإنتاج يضمن عدم وجود انحرافات وهمية في التكلفة المحسوبة للعبوة.",
                    fontSize = 11.sp,
                    color = Color(0xFF334155),
                    lineHeight = 16.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, IndustrialBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("نص التقرير التنفيذي المنسق 📋", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("تقرير مقارنة الدفعات", reportText)
                            clipboard.setPrimaryClip(clip)
                            android.widget.Toast.makeText(context, "تم نسخ التقرير إلى الحافظة بنجاح 📋", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("نسخ التقرير", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = reportText,
                        fontSize = 11.sp,
                        color = Color(0xFF334155),
                        lineHeight = 17.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(200.dp))
    }
}



/** يرتّب لقطة العبوات حسب ترتيب العبوات المعرّفة في الإعدادات (نفس ترتيب خصائص التركيبة)، لا حسب ترتيب مفاتيح JSON. */
fun gbrSortPackSnapshots(
    snaps: List<org.json.JSONObject>,
    ordered: List<GbrViewModel.CustomPackaging>
): List<org.json.JSONObject> {
    val idx = ordered.mapIndexed { i, p -> p.id to i }.toMap()
    return snaps.sortedBy { idx[it.optString("id")] ?: Int.MAX_VALUE }
}
