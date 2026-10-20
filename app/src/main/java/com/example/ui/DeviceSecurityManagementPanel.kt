package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DeviceSecurityManager
import com.example.data.SyncManager
import com.example.data.awaitTask
import com.example.ui.theme.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSecurityBlockScreen(onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceStatus by DeviceSecurityManager.deviceStatus.collectAsState()
    
    var recoveryKeyInput by remember { mutableStateOf("") }
    var isRecovering by remember { mutableStateOf(false) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }

    val currentLang = LocalAppLanguage.current
    fun t(ar: String, en: String): String = if (currentLang == "en") en else ar

    // Actively listen for approval on Firestore while blocked screen is displayed
    DisposableEffect(Unit) {
        var listenerReg: com.google.firebase.firestore.ListenerRegistration? = null
        var pollJob: kotlinx.coroutines.Job? = null
        if (SyncManager.initializeFirebase(context)) {
            try {
                // Immediately submit or ensure registration document is pushed to Firestore
                scope.launch(Dispatchers.IO) {
                    val isPrimaryLocal = context.getSharedPreferences("gbr_device_security_prefs", Context.MODE_PRIVATE).getBoolean("is_primary", false)
                    if (isPrimaryLocal) {
                        DeviceSecurityManager.ensureDeviceRegisteredOnCloud(context)
                    } else {
                        DeviceSecurityManager.sendRegistrationRequestToCloud(context)
                    }
                }

                val db = FirebaseFirestore.getInstance()
                val myId = DeviceSecurityManager.getDeviceId(context)
                listenerReg = db.collection("device_registrations").document(myId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        val rawStatus = snapshot.getString("status") ?: "PENDING"
                        val isPrimary = snapshot.getBoolean("isPrimary") ?: false
                        val status = when {
                            rawStatus.equals(DeviceSecurityManager.STATUS_APPROVED, ignoreCase = true) || rawStatus == "معتمد" -> DeviceSecurityManager.STATUS_APPROVED
                            rawStatus.equals(DeviceSecurityManager.STATUS_BLOCKED, ignoreCase = true) || rawStatus == "محظور" -> DeviceSecurityManager.STATUS_BLOCKED
                            rawStatus.equals(DeviceSecurityManager.STATUS_SUSPENDED, ignoreCase = true) || rawStatus == "موقوف" -> DeviceSecurityManager.STATUS_SUSPENDED
                            else -> DeviceSecurityManager.STATUS_PENDING
                        }
                        if (status == DeviceSecurityManager.STATUS_APPROVED) {
                            DeviceSecurityManager.updateDeviceStatusLocally(context, status, isPrimary)
                            Toast.makeText(context, t("🎉 تم اعتماد وتنشيط جهازك بنجاح من قبل الإدارة!", "🎉 Device approved successfully!"), Toast.LENGTH_LONG).show()
                            onVerified()
                        }
                    }
                
                // Secondary fallback polling loop (low frequency)
                pollJob = scope.launch(Dispatchers.IO) {
                    while (true) {
                        kotlinx.coroutines.delay(60000)
                        try {
                            val status = DeviceSecurityManager.verifyDeviceStatus(context)
                            if (status == DeviceSecurityManager.STATUS_APPROVED) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    Toast.makeText(context, t("🎉 تم اعتماد وتنشيط جهازك بنجاح!", "🎉 Device approved successfully!"), Toast.LENGTH_LONG).show()
                                    onVerified()
                                }
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceSecurity", "Failed to register snapshot listener in BlockScreen", e)
            }
        }
        onDispose {
            listenerReg?.remove()
            pollJob?.cancel()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFAFAFC))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // حد أقصى للعرض كي لا تتمدد البطاقة على شاشات الكمبيوتر العريضة (الهاتف أضيق من الحد فلا يتأثر)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            when (deviceStatus) {
                DeviceSecurityManager.STATUS_CHECKING -> {
                    CircularProgressIndicator(color = GBRBlueMain)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = t("جاري التحقق من أمن وصلاحية الجهاز...", "Verifying device security & authorization..."),
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                DeviceSecurityManager.STATUS_PENDING -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.5.dp, IndustrialBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = WarningOrange,
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = t("بانتظار اعتماد الجهاز ⏳", "Device Pending Approval ⏳"),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = t(
                                    "هذا الجهاز لم يتم اعتماده بعد من قِبل مسؤول النظام المعتمد لشركة دهانات GBR.",
                                    "This device is not yet approved by the GBR Paints administrator."
                                ),
                                fontSize = 13.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            // Device details box with copy & share ID buttons
                            val clipboardManager = LocalClipboardManager.current
                            val curDevId = DeviceSecurityManager.getDeviceId(context)
                            val curDevName = DeviceSecurityManager.getDeviceName(context)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF3F4F6), RoundedCornerShape(12.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = t("معلومات هذا الجهاز:", "This Device Details:"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.DarkGray
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                SecurityDetailRow(label = t("اسم الجهاز:", "Device Name:"), value = curDevName)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = t("معرّف الجهاز الكامل (Device ID):", "Full Device ID:"),
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White, RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = curDevId,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = GBRDarkIndigo,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(curDevId))
                                            Toast.makeText(context, t("📋 تم نسخ معرّف الجهاز الكامل بنجاح!", "📋 Full Device ID copied!"), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(t("📋 نسخ المعرّف", "📋 Copy ID"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                val sendIntent = android.content.Intent().apply {
                                                    action = android.content.Intent.ACTION_SEND
                                                    putExtra(android.content.Intent.EXTRA_TEXT, curDevId)
                                                    type = "text/plain"
                                                }
                                                val shareIntent = android.content.Intent.createChooser(sendIntent, t("مشاركة معرّف الجهاز مع المسؤول", "Share Device ID"))
                                                context.startActivity(shareIntent)
                                            } catch (e: Exception) {
                                                clipboardManager.setText(AnnotatedString(curDevId))
                                                Toast.makeText(context, t("📋 تم نسخ معرّف الجهاز!", "📋 Device ID copied!"), Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(36.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(t("📲 مشاركة المعرّف", "📲 Share ID"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            

                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        isChecking = true
                                        val sent = DeviceSecurityManager.sendRegistrationRequestToCloud(context)
                                        val status = DeviceSecurityManager.verifyDeviceStatus(context)
                                        isChecking = false
                                        if (status == DeviceSecurityManager.STATUS_APPROVED) {
                                            val isPrimaryNow = DeviceSecurityManager.isPrimary.value || context.getSharedPreferences("gbr_device_security_prefs", Context.MODE_PRIVATE).getBoolean("is_primary", false)
                                            DeviceSecurityManager.updateDeviceStatusLocally(context, DeviceSecurityManager.STATUS_APPROVED, isPrimaryNow)
                                            Toast.makeText(context, t("🎉 تم اعتماد وتنشيط جهازك بنجاح!", "🎉 Device approved successfully!"), Toast.LENGTH_LONG).show()
                                            onVerified()
                                        } else if (sent) {
                                            Toast.makeText(context, t("✅ تم إرسال طلب الاعتماد إلى السحابة بنجاح! يظهر الآن لدى هاتف المدير لاعتماده.", "✅ Request sent to cloud successfully! Awaiting admin approval."), Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, t("⏳ فشل إرسال الطلب، يرجى التأكد من الاتصال بالإنترنت وبيانات السحابة.", "⏳ Failed to send request, check network and cloud configuration."), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isChecking
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Default.Refresh, "Refresh")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(t("🔄 إرسال طلب الاعتماد وتحديث الحالة", "🔄 Send Request & Refresh Status"), fontSize = 13.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            TextButton(
                                onClick = { showRecoveryDialog = true }
                            ) {
                                Icon(Icons.Default.Lock, "Recover")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(t("🔑 استرداد المسؤول والتحكم بالنظام", "🔑 System & Admin Recovery"), color = GBRBlueMain)
                            }
                        }
                    }
                }

                DeviceSecurityManager.STATUS_SUSPENDED -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                        border = BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Suspended",
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = t("⏸️ تم تعليق صلاحية هذا الجهاز مؤقتاً!", "⏸️ Device Temporarily Suspended!"),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = t(
                                    "تم تعليق هذا الجهاز وإيقاف مزامنة السحابة مؤقتاً من قِبل إدارة مصنع GBR لحماية البيانات.\nتواصل مع المشرف الرئيسي لاستئناف الاعتماد.",
                                    "Access for this device was suspended temporarily by the admin.\nCloud synchronization is paused. Contact the admin to resume access."
                                ),
                                fontSize = 13.sp,
                                color = Color(0xFF78350F),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        isChecking = true
                                        DeviceSecurityManager.verifyDeviceStatus(context)
                                        isChecking = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(t("إعادة التحقق من استئناف الصلاحية 🔄", "Re-Verify Status 🔄"), fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            TextButton(
                                onClick = { showRecoveryDialog = true }
                            ) {
                                Text(t("🔑 استرداد النظام بمفتاح الطوارئ", "🔑 Emergency Recovery Key"), color = Color(0xFFD97706))
                            }
                        }
                    }
                }

                DeviceSecurityManager.STATUS_BLOCKED -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.5.dp, Color(0xFFFCA5A5)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Blocked",
                                tint = Color(0xFFDC2626),
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = t("🚫 تم حظر الجهاز وتدمير البيانات المحلية!", "🚫 Device Blocked & Local Data Wiped!"),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF991B1B),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = t(
                                    "تم حظر هذا الجهاز وإلغاء تسجيله نهائياً من قِبل إدارة مصنع دهانات GBR.\nتم مسح وتدمير كافة قواعد البيانات والتركيبات المحلية المسجلة على هذا الهاتف فوراً لحماية الأسرار الصناعية.",
                                    "This device has been permanently blocked by GBR Paints management.\nAll local databases, formulations, and cached data have been completely wiped to secure industrial secrets."
                                ),
                                fontSize = 13.sp,
                                color = Color(0xFF7F1D1D),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        isChecking = true
                                        DeviceSecurityManager.verifyDeviceStatus(context)
                                        isChecking = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(t("إعادة التحقق من الصلاحية 🔄", "Re-Verify Status 🔄"), fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            TextButton(
                                onClick = { showRecoveryDialog = true }
                            ) {
                                Text(t("🔑 استرداد النظام بمفتاح الطوارئ", "🔑 Emergency Recovery Key"), color = Color(0xFFDC2626))
                            }
                        }
                    }
                }

                DeviceSecurityManager.STATUS_OFFLINE_EXPIRED -> {
                    var extensionCodeInput by remember { mutableStateOf("") }
                    var showExtensionDialog by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                        border = BorderStroke(1.5.dp, Color(0xFFFDE68A)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Expired",
                                tint = WarningOrange,
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = t("⏳ يجب الاتصال بالإنترنت لتحديث قواعد البيانات", "⏳ Internet Connection Required"),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = t(
                                    "يجب الاتصال بالإنترنت لتحديث قواعد البيانات نظراً لانقطاع اتصالك عن الإنترنت مدة 48 ساعة.",
                                    "You must connect to the internet to update databases because your connection has been cut off for 48 hours."
                                ),
                                fontSize = 13.sp,
                                color = Color(0xFF78350F),
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        isChecking = true
                                        val newStatus = DeviceSecurityManager.verifyDeviceStatus(context)
                                        isChecking = false
                                        if (newStatus == DeviceSecurityManager.STATUS_APPROVED) {
                                            onVerified()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                } else {
                                    Text(t("فحص الاتصال وتحديث الحالة 🌐", "Connect & Verify Online 🌐"), fontSize = 13.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            TextButton(
                                onClick = { showExtensionDialog = true }
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF92400E), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = t("🔑 إدخال كود تمديد الطوارئ (دون إنترنت)", "🔑 Enter Offline Emergency Extension Code"),
                                    color = Color(0xFF92400E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (showExtensionDialog) {
                        AlertDialog(
                            onDismissRequest = { 
                                showExtensionDialog = false
                                extensionCodeInput = ""
                            },
                            title = {
                                Text(
                                    text = t("🔑 تمديد فترة العمل دون اتصال", "🔑 Extend Offline Period"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = GBRDarkIndigo
                                )
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = t(
                                            "أدخل كود تمديد الطوارئ المعتمد لتمديد فترة العمل دون إنترنت لمدة 48 ساعة إضافية في الحالات الاستثنائية:",
                                            "Enter the approved emergency extension code to extend offline access for an additional 48 hours:"
                                        ),
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = extensionCodeInput,
                                        onValueChange = { extensionCodeInput = it },
                                        placeholder = { Text("GBR-XXXX-XXXX-XXXX") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = GBRBlueMain,
                                            unfocusedBorderColor = Color.LightGray
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        val extended = DeviceSecurityManager.applyOfflineExtension(context, extensionCodeInput)
                                        if (extended) {
                                            showExtensionDialog = false
                                            extensionCodeInput = ""
                                            Toast.makeText(context, t("🎉 تم تمديد فترة العمل دون اتصال بالإنترنت بنجاح لمدة 48 ساعة إضافية بنظام الطوارئ!", "🎉 Offline period successfully extended for 48 hours!"), Toast.LENGTH_LONG).show()
                                            onVerified()
                                        } else {
                                            Toast.makeText(context, t("❌ الكود المدخل غير صحيح!", "❌ Invalid extension code!"), Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                                ) {
                                    Text(t("تأكيد التمديد", "Confirm Extension"), fontSize = 11.sp)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { 
                                    showExtensionDialog = false
                                    extensionCodeInput = ""
                                }) {
                                    Text(t("إلغاء", "Cancel"), fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // States for Recovery Dialog

    // Recovery Dialog
    if (showRecoveryDialog) {
        AlertDialog(
            onDismissRequest = { 
                showRecoveryDialog = false 
                recoveryKeyInput = ""
            },
            title = {
                Text(
                    text = t("🔑 اعتماد واسترداد صلاحية المسؤول", "🔑 Authorize & Recover Admin Control"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GBRDarkIndigo
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t(
                            "أدخل الرمز الأمني الثابت المعتمد لاسترداد السيطرة المباشرة وتفعيل هذا الجهاز كجهاز رئيسي للنظام:",
                            "Enter the fixed system security code to recover master control and activate this device:"
                        ),
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recoveryKeyInput,
                        onValueChange = { recoveryKeyInput = it },
                        placeholder = { Text("GBR-XXXX-XXXX-XXXX") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GBRBlueMain,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = {
                            scope.launch {
                                isRecovering = true
                                val success = DeviceSecurityManager.recoverSystem(context, recoveryKeyInput)
                                isRecovering = false
                                if (success) {
                                    showRecoveryDialog = false
                                    recoveryKeyInput = ""
                                    Toast.makeText(context, t("🎉 تم استرداد المسؤول بنجاح باستخدام الرمز المعتمد!", "🎉 Recovery succeeded via approved key!"), Toast.LENGTH_LONG).show()
                                    onVerified()
                                } else {
                                    Toast.makeText(context, t("❌ رمز غير صحيح أو لا يوجد اتصال بالإنترنت!", "❌ Invalid key or no internet connection!"), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = recoveryKeyInput.isNotEmpty() && !isRecovering
                    ) {
                        if (isRecovering) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text(t("استرداد واعتماد الآن 🔓", "Recover & Authorize Now 🔓"), fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showRecoveryDialog = false 
                    recoveryKeyInput = ""
                }) {
                    Text(t("إلغاء", "Cancel"), fontSize = 11.sp, color = Color.Gray)
                }
            }
        )
    }
}

@Composable
fun SecurityDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Text(text = value, fontSize = 11.sp, color = Color.Black, fontFamily = FontFamily.Monospace)
    }
}

// -------------------------------------------------------------
// SETTINGS VIEWS: Device Security & Administration
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSecurityManagementSubView(viewModel: GbrViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isConnected = SyncManager.isNetworkAvailable(context)
    
    var devicesList by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var currentDeviceIsPrimary by remember { mutableStateOf(false) }
    var cloudRecoveryKeyHash by remember { mutableStateOf("") }
    
    var showMasterKeyInUI by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, APPROVED, PENDING, SUSPENDED_BLOCKED
    
    // Rename device dialog state (Primary device only)
    var deviceToRename by remember { mutableStateOf<Map<String, Any>?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // Self-promotion dialog state
    var showPromoteSelfDialog by remember { mutableStateOf(false) }
    var promoteSelfInput by remember { mutableStateOf("") }
    var isPromotingSelf by remember { mutableStateOf(false) }

    // Action Confirmation Dialog States
    var actionTargetDevice by remember { mutableStateOf<Map<String, Any>?>(null) }
    var actionType by remember { mutableStateOf<String?>(null) } // SUSPEND, BLOCK_WIPE, DELETE, APPROVE, RESUME, DECOMMISSION_SELF
    var isPerformingAction by remember { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<Job?>(null) }

    // Master Key Verification Dialog for Transfer/Promote Admin
    var showVerificationDialogForTransferAdmin by remember { mutableStateOf(false) }
    var verificationCodeInput by remember { mutableStateOf("") }
    var isVerifyingCode by remember { mutableStateOf(false) }
    var pendingTargetDeviceIdForPromotion by remember { mutableStateOf("") }

    // Handle back button for dialogs within Device Security
    BackHandler(enabled = actionTargetDevice != null || showVerificationDialogForTransferAdmin || showPromoteSelfDialog) {
        if (actionTargetDevice != null) {
            actionJob?.cancel()
            actionJob = null
            isPerformingAction = false
            actionTargetDevice = null
            actionType = null
        } else if (showVerificationDialogForTransferAdmin) {
            showVerificationDialogForTransferAdmin = false
            verificationCodeInput = ""
        } else if (showPromoteSelfDialog) {
            showPromoteSelfDialog = false
            promoteSelfInput = ""
        }
    }

    val currentLang = LocalAppLanguage.current
    fun t(ar: String, en: String): String = if (currentLang == "en") en else ar

    // Read local prefs for current device details
    val sharedPrefs = remember { context.getSharedPreferences("gbr_device_security_prefs", Context.MODE_PRIVATE) }
    val localIsPrimary = sharedPrefs.getBoolean("is_primary", false)
    val isPrimaryFlow by DeviceSecurityManager.isPrimary.collectAsState()
    val isMasterAdmin = currentDeviceIsPrimary || localIsPrimary || isPrimaryFlow

    val cachedStatus = sharedPrefs.getString("cached_status", "PENDING") ?: "PENDING"
    val myDeviceId = DeviceSecurityManager.getDeviceId(context)
    
    val parseAndSetSnapshot: (com.google.firebase.firestore.QuerySnapshot?) -> Unit = { snapshot ->
        if (snapshot != null) {
            val list = mutableListOf<Map<String, Any>>()
            for (doc in snapshot.documents) {
                val map = mutableMapOf<String, Any>()
                doc.data?.forEach { (k, v) ->
                    if (v != null) {
                        map[k] = v
                    }
                }
                map["id"] = doc.id
                if (!map.containsKey("deviceId") || (map["deviceId"] as? String).isNullOrBlank()) {
                    map["deviceId"] = doc.id
                }
                val rawSt = (map["status"] as? String ?: "PENDING").trim()
                val normSt = when {
                    rawSt.equals("APPROVED", ignoreCase = true) || rawSt == "معتمد" -> "APPROVED"
                    rawSt.equals("BLOCKED", ignoreCase = true) || rawSt == "محظور" -> "BLOCKED"
                    rawSt.equals("SUSPENDED", ignoreCase = true) || rawSt == "موقوف" -> "SUSPENDED"
                    else -> "PENDING"
                }
                map["status"] = normSt
                list.add(map)
            }
            // Sort: PENDING devices at the top, then primary device, then others
            devicesList = list.sortedWith(
                compareByDescending<Map<String, Any>> { (it["status"] as? String) == "PENDING" }
                    .thenByDescending { (it["isPrimary"] as? Boolean) == true }
            )
        }
    }

    val fetchCloudDevices: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            if (!isConnected) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, t("⚠️ يتطلب الاتصال بالإنترنت لإدارة أجهزة السحابة!", "⚠️ Cloud Device Management requires internet!"), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                isLoading = true
            }
            try {
                withTimeout(8000L) {
                    if (SyncManager.initializeFirebase(context)) {
                        val db = FirebaseFirestore.getInstance()
                        // 1. Fetch all devices from SERVER to bypass any stale local tombstones
                        val snapshot = try {
                            db.collection("device_registrations").get(com.google.firebase.firestore.Source.SERVER).awaitTask()
                        } catch (_: Exception) {
                            db.collection("device_registrations").get().awaitTask()
                        }
                        withContext(Dispatchers.Main) {
                            parseAndSetSnapshot(snapshot)
                        }

                        // 2. Fetch primary status of this device from doc
                        val myDoc = db.collection("device_registrations").document(myDeviceId).get().awaitTask()
                        if (myDoc.exists()) {
                            val remotePrimary = myDoc.getBoolean("isPrimary") ?: false
                            // The cloud document is the single source of truth. A device that still has a stale local primary flag
                            // may only re-claim the role when no OTHER device is primary in the cloud.
                            val otherPrimaryExists = snapshot.documents.any { it.id != myDeviceId && (it.getBoolean("isPrimary") ?: false) }
                            withContext(Dispatchers.Main) {
                                if (remotePrimary) {
                                    currentDeviceIsPrimary = true
                                } else if ((localIsPrimary || isPrimaryFlow) && !otherPrimaryExists) {
                                    currentDeviceIsPrimary = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            db.collection("device_registrations").document(myDeviceId).update("isPrimary", true).awaitTask()
                                        } catch (_: Exception) {}
                                    }
                                } else {
                                    currentDeviceIsPrimary = false
                                }
                            }
                        } else if (localIsPrimary || isPrimaryFlow) {
                            withContext(Dispatchers.Main) {
                                currentDeviceIsPrimary = true
                            }
                        }

                        // 3. Fetch recovery key hash
                        val securityDoc = db.collection("settings").document("device_security").get().awaitTask()
                        if (securityDoc.exists()) {
                            withContext(Dispatchers.Main) {
                                cloudRecoveryKeyHash = securityDoc.getString("recoveryKey") ?: ""
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("DeviceSecurity", "fetchCloudDevices timed out or failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    // Real-time snapshot listener on device_registrations
    DisposableEffect(Unit) {
        var listenerReg: com.google.firebase.firestore.ListenerRegistration? = null
        var pollJob: kotlinx.coroutines.Job? = null
        if (SyncManager.initializeFirebase(context)) {
            try {
                val db = FirebaseFirestore.getInstance()
                listenerReg = db.collection("device_registrations").addSnapshotListener { snapshot, err ->
                    if (err != null) {
                        Log.e("DeviceSecurity", "Snapshot listener error", err)
                        return@addSnapshotListener
                    }
                    parseAndSetSnapshot(snapshot)
                    isLoading = false
                }
                
                // Secondary fallback poll backup (low frequency, main sync is via snapshot listener)
                pollJob = scope.launch(Dispatchers.IO) {
                    while (true) {
                        kotlinx.coroutines.delay(60000)
                        try {
                            val snapshot = try {
                                db.collection("device_registrations").get(com.google.firebase.firestore.Source.SERVER).awaitTask()
                            } catch (_: Exception) {
                                db.collection("device_registrations").get().awaitTask()
                            }
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                parseAndSetSnapshot(snapshot)
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceSecurity", "Failed registering snapshot listener", e)
            }
        }
        onDispose {
            listenerReg?.remove()
            pollJob?.cancel()
        }
    }

    LaunchedEffect(Unit) {
        fetchCloudDevices()
    }

    // Action Execution Handler
    val executeSecurityAction: (String, Map<String, Any>) -> Unit = { action, dev ->
        val devId = (dev["deviceId"] as? String)?.takeIf { it.isNotBlank() } ?: (dev["id"] as? String) ?: ""
        val devName = ((dev["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: (dev["deviceName"] as? String)?.takeIf { it.isNotBlank() }) ?: t("هاتف أندرويد", "Android Device")
        
        actionJob?.cancel()
        actionJob = scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isPerformingAction = true
            }
            try {
                val success = when (action) {
                    "APPROVE", "RESUME" -> {
                        val ok = DeviceSecurityManager.updateDeviceStatusOnCloud(context, devId, DeviceSecurityManager.STATUS_APPROVED)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                Toast.makeText(context, t("✅ تم اعتماد وتنشيط الجهاز ($devName) بنجاح!", "✅ Device ($devName) approved successfully!"), Toast.LENGTH_LONG).show()
                                devicesList = devicesList.map {
                                    val currentId = (it["deviceId"] as? String)?.takeIf { s -> s.isNotBlank() } ?: (it["id"] as? String) ?: ""
                                    if (currentId == devId) it + ("status" to "APPROVED") else it
                                }
                            } else {
                                Toast.makeText(context, t("❌ فشل اعتماد الجهاز أو انتهت المهلة. تحقق من الاتصال.", "❌ Failed to approve device. Check connection."), Toast.LENGTH_SHORT).show()
                            }
                        }
                        ok
                    }
                    "SUSPEND" -> {
                        val ok = DeviceSecurityManager.updateDeviceStatusOnCloud(context, devId, DeviceSecurityManager.STATUS_SUSPENDED)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                Toast.makeText(context, t("⏸️ تم تعليق الجهاز ($devName) وقفل التطبيق عليه فوراً!", "⏸️ Device ($devName) suspended successfully!"), Toast.LENGTH_LONG).show()
                                devicesList = devicesList.map {
                                    val currentId = (it["deviceId"] as? String)?.takeIf { s -> s.isNotBlank() } ?: (it["id"] as? String) ?: ""
                                    if (currentId == devId) it + ("status" to "SUSPENDED") else it
                                }
                            } else {
                                Toast.makeText(context, t("❌ فشل تعليق الجهاز أو انتهت المهلة. تحقق من الاتصال.", "❌ Failed to suspend device. Check connection."), Toast.LENGTH_SHORT).show()
                            }
                        }
                        ok
                    }
                    "BLOCK_WIPE" -> {
                        val ok = DeviceSecurityManager.updateDeviceStatusOnCloud(context, devId, DeviceSecurityManager.STATUS_BLOCKED)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                Toast.makeText(context, t("🚫 تم حظر الجهاز ($devName) وإرسال أمر تدمير البيانات ومسح الذاكرة فوراً!", "🚫 Device ($devName) blocked and wipe command issued!"), Toast.LENGTH_LONG).show()
                                devicesList = devicesList.map {
                                    val currentId = (it["deviceId"] as? String)?.takeIf { s -> s.isNotBlank() } ?: (it["id"] as? String) ?: ""
                                    if (currentId == devId) it + ("status" to "BLOCKED") else it
                                }
                            } else {
                                Toast.makeText(context, t("❌ فشل تنفيذ الحظر أو انتهت المهلة. تحقق من الاتصال.", "❌ Failed to block device. Check connection."), Toast.LENGTH_SHORT).show()
                            }
                        }
                        ok
                    }
                    "DELETE" -> {
                        val ok = DeviceSecurityManager.deleteDeviceFromCloud(context, devId)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                Toast.makeText(context, t("🗑️ تم حذف الجهاز ($devName) من السحابة بنجاح!", "🗑️ Device ($devName) deleted from cloud!"), Toast.LENGTH_LONG).show()
                                devicesList = devicesList.filterNot {
                                    val currentId = (it["deviceId"] as? String)?.takeIf { s -> s.isNotBlank() } ?: (it["id"] as? String) ?: ""
                                    currentId == devId
                                }
                            } else {
                                Toast.makeText(context, t("❌ فشل حذف الجهاز أو انتهت المهلة. تحقق من الاتصال.", "❌ Failed to delete device. Check connection."), Toast.LENGTH_SHORT).show()
                            }
                        }
                        ok
                    }
                    "DECOMMISSION_SELF" -> {
                        DeviceSecurityManager.deleteDeviceFromCloud(context, myDeviceId)
                        DeviceSecurityManager.wipeDeviceData(context)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, t("🔴 تم إلغاء تسجيل وتدمير بيانات هذا الهاتف بنجاح.", "🔴 Device decommissioned & local data wiped."), Toast.LENGTH_LONG).show()
                        }
                        true
                    }
                    else -> false
                }
                if (success && action != "DECOMMISSION_SELF") {
                    fetchCloudDevices()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, t("خطأ: ${e.localizedMessage ?: "فشلت العملية"}", "Error: ${e.localizedMessage ?: "Action failed"}"), Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isPerformingAction = false
                    actionTargetDevice = null
                    actionType = null
                    actionJob = null
                }
            }
        }
    }

    // Confirmation Dialog for Security Actions (Suspend / Block & Wipe / Delete / Decommission)
    if (actionTargetDevice != null && actionType != null) {
        val targetDev = actionTargetDevice!!
        val currentAction = actionType!!
        val devId = (targetDev["deviceId"] as? String)?.takeIf { it.isNotBlank() } ?: (targetDev["id"] as? String) ?: ""
        val devName = ((targetDev["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: (targetDev["deviceName"] as? String)?.takeIf { it.isNotBlank() }) ?: t("هاتف أندرويد", "Android Phone")

        AlertDialog(
            onDismissRequest = {
                actionJob?.cancel()
                actionJob = null
                isPerformingAction = false
                actionTargetDevice = null
                actionType = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (currentAction) {
                        "SUSPEND" -> "⏸️"
                        "BLOCK_WIPE" -> "🚫"
                        "DELETE" -> "🗑️"
                        "APPROVE", "RESUME" -> "✅"
                        "DECOMMISSION_SELF" -> "🔴"
                        else -> "⚠️"
                    }
                    Text(
                        text = "$icon " + when (currentAction) {
                            "SUSPEND" -> t("تأكيد تعليق الجهاز", "Confirm Device Suspension")
                            "BLOCK_WIPE" -> t("تأكيد الحظر وتدمير البيانات المحلية", "Confirm Block & Remote Data Wipe")
                            "DELETE" -> t("تأكيد الحذف النهائي من السحابة", "Confirm Cloud Device Deletion")
                            "APPROVE", "RESUME" -> t("تأكيد اعتماد وتفعيل الجهاز", "Confirm Device Authorization")
                            "DECOMMISSION_SELF" -> t("تأكيد إلغاء وتدمير بيانات هذا الهاتف", "Confirm Decommission & Local Wipe")
                            else -> t("تأكيد العملية الأمنية", "Confirm Security Action")
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = when (currentAction) {
                            "BLOCK_WIPE", "DELETE", "DECOMMISSION_SELF" -> Color(0xFF991B1B)
                            "SUSPEND" -> Color(0xFF92400E)
                            else -> GBRDarkIndigo
                        }
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = t("الجهاز المستهدف: ", "Target Device: ") + devName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "ID: $devId",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color.DarkGray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val explanationText = when (currentAction) {
                        "SUSPEND" -> t(
                            "سيتم إيقاف المزامنة السحابية وقفل شاشة التطبيق فوراً على هذا الهاتف في الوقت الفعلي لحماية البيانات حتى تقوم باستئنافه لاحقاً.",
                            "Cloud sync will be paused and the app screen locked immediately on this device in real-time until you resume it."
                        )
                        "BLOCK_WIPE" -> t(
                            "⚠️ تحذير أمني شديد: سيتم قطع الاتصال بالسحابة وإرسال أمر فوري لتدمير ومسح كافة قواعد البيانات المحلية والتركيبات المخزنة على هذا الهاتف لحماية أسرار المصنع نهائياً!",
                            "⚠️ Critical Security Warning: Cloud access will be cut off and an immediate remote wipe command will destroy all local databases, formulations, and cached data on this device to protect trade secrets!"
                        )
                        "DELETE" -> t(
                            "سيتم حذف سجل الجهاز من السحابة نهائياً. إذا كان الجهاز متصلاً فسيتم فصله فوراً ومسح البيانات والتركيبات المحلية المسجلة عليه.",
                            "The device record will be permanently deleted from the cloud. If connected, it will be immediately disconnected and local data wiped."
                        )
                        "APPROVE", "RESUME" -> t(
                            "سيتم اعتماد هذا الجهاز وتنشيط المزامنة السحابية الشاملة معه فوراً.",
                            "This device will be authorized and cloud synchronization activated immediately."
                        )
                        "DECOMMISSION_SELF" -> t(
                            "⚠️ تحذير: سيتم إلغاء تسجيل هذا الهاتف من السحابة ومسح وتدمير كافة قواعد البيانات والتركيبات المحلية من هذا الهاتف الآن.",
                            "⚠️ Warning: This phone will be unregistered from the cloud and all local databases and formulations will be destroyed immediately."
                        )
                        else -> ""
                    }

                    Text(
                        text = explanationText,
                        fontSize = 12.sp,
                        color = when (currentAction) {
                            "BLOCK_WIPE", "DELETE", "DECOMMISSION_SELF" -> Color(0xFF7F1D1D)
                            "SUSPEND" -> Color(0xFF78350F)
                            else -> Color.DarkGray
                        },
                        lineHeight = 17.sp,
                        fontWeight = if (currentAction == "BLOCK_WIPE" || currentAction == "DECOMMISSION_SELF") FontWeight.Bold else FontWeight.Normal
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        executeSecurityAction(currentAction, targetDev)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (currentAction) {
                            "BLOCK_WIPE", "DELETE", "DECOMMISSION_SELF" -> Color(0xFFDC2626)
                            "SUSPEND" -> Color(0xFFD97706)
                            else -> SuccessGreen
                        }
                    ),
                    enabled = !isPerformingAction
                ) {
                    if (isPerformingAction) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text(
                            text = when (currentAction) {
                                "SUSPEND" -> t("تأكيد التعليق ⏸️", "Confirm Suspend ⏸️")
                                "BLOCK_WIPE" -> t("تأكيد الحظر والتدمير 🚫", "Confirm Block & Wipe 🚫")
                                "DELETE" -> t("تأكيد الحذف 🗑️", "Confirm Delete 🗑️")
                                "APPROVE", "RESUME" -> t("تأكيد الاعتماد ✅", "Confirm Approve ✅")
                                "DECOMMISSION_SELF" -> t("تأكيد التدمير النهائي 🔴", "Confirm Decommission 🔴")
                                else -> t("تأكيد", "Confirm")
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        actionJob?.cancel()
                        actionJob = null
                        isPerformingAction = false
                        actionTargetDevice = null
                        actionType = null
                    },
                    enabled = true
                ) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontSize = 12.sp)
                }
            }
        )
    }

    // Master Key Verification Dialog for Transfer/Promote Admin
    if (showVerificationDialogForTransferAdmin) {
        AlertDialog(
            onDismissRequest = {
                showVerificationDialogForTransferAdmin = false
                verificationCodeInput = ""
            },
            title = {
                Text(
                    text = t("👑 ترقية جهاز إلى مسؤول رئيسي", "👑 Promote Device to Master Admin"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GBRDarkIndigo
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t(
                            "لترقية هذا الجهاز ونقل صلاحيات المسؤول الأول إليه، يرجى إدخال رمز الأمان الثابت للنظام:",
                            "To promote this device and transfer master admin privileges to it, please enter the system's fixed security code:"
                        ),
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        lineHeight = 16.sp
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = verificationCodeInput,
                        onValueChange = { verificationCodeInput = it },
                        placeholder = { Text("GBR-XXXX-XXXX-XXXX") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GBRBlueMain,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isVerifyingCode = true
                            val isValid = DeviceSecurityManager.isMasterCode(verificationCodeInput)
                            if (isValid) {
                                try {
                                    if (SyncManager.initializeFirebase(context)) {
                                        val db = FirebaseFirestore.getInstance()
                                        val allDevices = db.collection("device_registrations").get().awaitTask()
                                        for (doc in allDevices.documents) {
                                            val docId = doc.id
                                            val wasPrimary = doc.getBoolean("isPrimary") ?: false
                                            if (wasPrimary) {
                                                db.collection("device_registrations").document(docId)
                                                    .update("isPrimary", false, "status", "PENDING").awaitTask()
                                            }
                                        }
                                        
                                        db.collection("device_registrations").document(pendingTargetDeviceIdForPromotion)
                                            .update("isPrimary", true, "status", "APPROVED").awaitTask()
                                        
                                        DeviceSecurityManager.updateDeviceStatusLocally(context, "APPROVED", isPrimaryVal = false)
                                        currentDeviceIsPrimary = false
                                        
                                        Toast.makeText(context, t("👑 تم نقل صلاحية المسؤول الأول بنجاح!", "👑 Master Admin transferred successfully!"), Toast.LENGTH_LONG).show()
                                        fetchCloudDevices()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                                showVerificationDialogForTransferAdmin = false
                                verificationCodeInput = ""
                            } else {
                                Toast.makeText(context, t("❌ رمز أمان غير صحيح! يرجى إعادة المحاولة.", "❌ Incorrect code! Please try again."), Toast.LENGTH_LONG).show()
                            }
                            isVerifyingCode = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    enabled = verificationCodeInput.isNotEmpty() && !isVerifyingCode
                ) {
                    if (isVerifyingCode) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text(t("تأكيد الترقية ✔️", "Confirm Promotion ✔️"), fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showVerificationDialogForTransferAdmin = false
                        verificationCodeInput = ""
                    }
                ) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontSize = 11.sp)
                }
            }
        )
    }

    // Master Key Dialog for Promoting Self to Primary Admin
    if (showPromoteSelfDialog) {
        AlertDialog(
            onDismissRequest = {
                showPromoteSelfDialog = false
                promoteSelfInput = ""
            },
            title = {
                Text(
                    text = t("👑 ترقية هذا الهاتف إلى مسؤول رئيسي", "👑 Promote Device to Primary Admin"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GBRDarkIndigo
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = t(
                            "أدخل رمز اعتماد المسؤول الرئيسي الثابت للنظام لترقية هذا الهاتف فوراً ومنحه كافة صلاحيات الإدارة وحذف وتعليق الأجهزة:",
                            "Enter the master admin security code to promote this phone to Primary Admin with full security controls:"
                        ),
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = promoteSelfInput,
                        onValueChange = { promoteSelfInput = it },
                        placeholder = { Text("GBR-XXXX-XXXX-XXXX") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GBRBlueMain,
                            unfocusedBorderColor = Color.LightGray
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isPromotingSelf = true
                            val success = DeviceSecurityManager.recoverSystem(context, promoteSelfInput)
                            isPromotingSelf = false
                            if (success) {
                                currentDeviceIsPrimary = true
                                showPromoteSelfDialog = false
                                promoteSelfInput = ""
                                Toast.makeText(context, t("👑 تم ترقية هاتفك بنجاح إلى مسؤول رئيسي!", "👑 Promoted to Primary Admin successfully!"), Toast.LENGTH_LONG).show()
                                fetchCloudDevices()
                            } else {
                                Toast.makeText(context, t("❌ رمز غير صحيح! يرجى التأكد وإعادة المحاولة.", "❌ Incorrect code! Please check and retry."), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    enabled = promoteSelfInput.isNotEmpty() && !isPromotingSelf
                ) {
                    if (isPromotingSelf) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text(t("تأكيد الترقية ✔️", "Confirm Promotion ✔️"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPromoteSelfDialog = false
                    promoteSelfInput = ""
                }) {
                    Text(t("إلغاء", "Cancel"), color = Color.Gray, fontSize = 11.sp)
                }
            }
        )
    }

    deviceToRename?.let { renameDev ->
        val renameDevId = (renameDev["deviceId"] as? String)?.takeIf { it.isNotBlank() } ?: (renameDev["id"] as? String) ?: ""
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            title = { Text(t("✏️ تسمية الجهاز", "✏️ Rename device"), fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                Column {
                    Text(
                        t("اكتب اسماً يميّز هذا الجهاز (مثل: جهاز البيت، جهاز أحمد). اتركه فارغاً للعودة للاسم الأصلي.",
                          "Type a name to identify this device (e.g. Home device). Leave empty to restore the original name."),
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newName = renameInput.trim()
                    deviceToRename = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (SyncManager.initializeFirebase(context) && renameDevId.isNotBlank()) {
                                FirebaseFirestore.getInstance().collection("device_registrations").document(renameDevId)
                                    .set(hashMapOf<String, Any>("displayName" to newName), com.google.firebase.firestore.SetOptions.merge())
                                    .awaitTask()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                        withContext(Dispatchers.Main) { fetchCloudDevices() }
                    }
                }) { Text(t("حفظ", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) { Text(t("إلغاء", "Cancel"), color = Color.Gray) }
            }
        )
    }

    if (!isMasterAdmin) {
        // Non-primary device: the page shows nothing except the admin-code entry used to take over the Primary role
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                border = BorderStroke(1.2.dp, Color(0xFFCBD5E1))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = t("🔒 إدارة الأجهزة مقتصرة على الجهاز الرئيسي", "🔒 Device management is restricted to the Primary device"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF1E293B)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showPromoteSelfDialog = true
                            promoteSelfInput = ""
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain),
                        border = BorderStroke(1.2.dp, GBRBlueMain),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = t("🔑 أدخل كود المدير", "🔑 Enter Admin Code"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        // Active Device info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = t("📲 جهازك الحالي المتصل", "📲 Your Active Device"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = GBRDarkIndigo
                    )
                    
                    // Self decommission button
                    OutlinedButton(
                        onClick = {
                            actionTargetDevice = mapOf(
                                "deviceId" to myDeviceId,
                                "deviceName" to DeviceSecurityManager.getDeviceName()
                            )
                            actionType = "DECOMMISSION_SELF"
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(t("إلغاء ومسح هذا الهاتف", "Wipe this device"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                SecurityDetailRow(label = t("الاسم والموديل:", "Name & Model:"), value = DeviceSecurityManager.getDeviceName())
                SecurityDetailRow(label = t("المعرّف الفريد:", "Device ID:"), value = myDeviceId)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = t("الرتبة والدور:", "Role class:"), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (isMasterAdmin) {
                            t("👑 المشرف والمسؤول الأول (Primary Admin)", "👑 Primary Admin")
                        } else if (cachedStatus == "APPROVED") {
                            t("📱 مسؤول فرعي معتمد (Sub-Admin)", "📱 Subordinate Approved Admin")
                        } else {
                            t("⏳ جهاز بانتظار الاعتماد", "⏳ Awaiting Approval")
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMasterAdmin) Color(0xFF1E3A8A) else GBRBlueMain
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = t("حالة الترخيص والمزامنة:", "License & sync status:"), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(
                        text = when (cachedStatus) {
                            "APPROVED" -> t("🟢 معتمد ومزامن بالكامل", "🟢 Approved & Fully Synced")
                            "PENDING" -> t("🟡 بانتظار الاعتماد", "🟡 Pending Approval")
                            "SUSPENDED" -> t("⏸️ موقوف مؤقتاً", "⏸️ Suspended")
                            "BLOCKED" -> t("🚫 محظور وممسوح", "🚫 Blocked & Wiped")
                            else -> cachedStatus
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (cachedStatus == "APPROVED") SuccessGreen else Color.Red
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // If current device is Primary Admin, show system configuration & recovery options
        if (isMasterAdmin) {
            // (The admin code is intentionally never displayed in the app.)
        } else {
            // Sub-Admin info card & promotion button
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                border = BorderStroke(1.2.dp, Color(0xFFCBD5E1))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF475569), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = t("🔒 صلاحيات إدارة الأجهزة مقتصرة على المسؤول الرئيسي", "🔒 Device management is restricted to Primary Admin"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF1E293B)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = t(
                            "رتبة هذا الهاتف هي (مسؤول فرعي معتمد). يتيح لك النظام مزامنة واستخدام كافة بيانات المصنع والتركيبات وسجلات الإنتاج بكامل الصلاحيات، بينما تقتصر عمليات حذف الأجهزة أو تعليقها أو تغيير أذوناتها على هاتف المسؤول الرئيسي.",
                            "This device is registered as a Subordinate Admin with full sync and factory data access. Device security controls (suspend, delete, approve) are restricted to Primary Admin."
                        ),
                        fontSize = 11.sp,
                        color = Color(0xFF475569),
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showPromoteSelfDialog = true
                            promoteSelfInput = ""
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain),
                        border = BorderStroke(1.2.dp, GBRBlueMain),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = t("🔑 إدخال رمز الاعتماد للترقية إلى مسؤول رئيسي", "🔑 Enter Admin Code to Become Primary Admin"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }


            Spacer(modifier = Modifier.height(14.dp))

        // Statistical Counters Card
        val totalCount = devicesList.size
        val approvedCount = devicesList.count { (it["status"] as? String) == "APPROVED" }
        val pendingCount = devicesList.count { (it["status"] as? String) == "PENDING" }
        val suspendedBlockedCount = devicesList.count { (it["status"] as? String) in listOf("SUSPENDED", "BLOCKED") }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$totalCount", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GBRDarkIndigo)
                    Text(text = t("الإجمالي", "Total"), fontSize = 10.sp, color = Color.Gray)
                }
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFFE2E8F0)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$approvedCount", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SuccessGreen)
                    Text(text = t("معتمد ✅", "Approved"), fontSize = 10.sp, color = SuccessGreen)
                }
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFFE2E8F0)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$pendingCount", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = WarningOrange)
                    Text(text = t("معلق ⏳", "Pending"), fontSize = 10.sp, color = WarningOrange)
                }
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color(0xFFE2E8F0)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "$suspendedBlockedCount", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFDC2626))
                    Text(text = t("موقوف/محظور 🚫", "Suspended"), fontSize = 10.sp, color = Color(0xFFDC2626))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Device List Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t("👥 قائمة الأجهزة السحابية المرتبطة", "👥 Linked Cloud Devices"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GBRDarkIndigo
                )
                if (pendingCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFEF3C7), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = t("⚡ $pendingCount ينتظر الاعتماد", "⚡ $pendingCount Pending"),
                            color = Color(0xFFB45309),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            IconButton(onClick = { fetchCloudDevices() }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = GBRBlueMain)
            }
        }

        if (isMasterAdmin && pendingCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            var isApprovingAll by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                border = BorderStroke(1.5.dp, Color(0xFFF59E0B))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔔", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = t(
                                "يوجد ($pendingCount) جهاز جديد بانتظار موافقتك واعتماده للاتصال بالنظام ومزامنة البيانات.",
                                "($pendingCount) new device(s) awaiting approval to sync factory data."
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E),
                            lineHeight = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isApprovingAll = true
                                    val count = DeviceSecurityManager.approveAllPendingDevices(context)
                                    isApprovingAll = false
                                    if (count > 0) {
                                        Toast.makeText(context, t("🎉 تم اعتماد وتفعيل ($count) جهاز بنجاح!", "🎉 Approved ($count) devices successfully!"), Toast.LENGTH_LONG).show()
                                        fetchCloudDevices()
                                    } else {
                                        Toast.makeText(context, t("⚠️ لم يتم اعتماد أي جهاز، تأكد من الاتصال.", "⚠️ Approval failed, check network."), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(38.dp),
                            enabled = !isApprovingAll
                        ) {
                            if (isApprovingAll) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Check, "Approve All", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(t("✅ اعتماد وتنشيط الكل", "Approve All Pending"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text(t("الكل ($totalCount)", "All ($totalCount)"), fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == "APPROVED",
                onClick = { selectedFilter = "APPROVED" },
                label = { Text(t("المعتمدة ($approvedCount)", "Approved ($approvedCount)"), fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == "PENDING",
                onClick = { selectedFilter = "PENDING" },
                label = { Text(t("المعلقة ($pendingCount)", "Pending ($pendingCount)"), fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == "SUSPENDED_BLOCKED",
                onClick = { selectedFilter = "SUSPENDED_BLOCKED" },
                label = { Text(t("الموقوفة ($suspendedBlockedCount)", "Suspended ($suspendedBlockedCount)"), fontSize = 11.sp) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filtered List
        val filteredList = devicesList.filter { dev ->
            val devStatus = (dev["status"] as? String) ?: "PENDING"

            when (selectedFilter) {
                "APPROVED" -> devStatus == "APPROVED"
                "PENDING" -> devStatus == "PENDING"
                "SUSPENDED_BLOCKED" -> devStatus in listOf("SUSPENDED", "BLOCKED")
                else -> true
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = GBRBlueMain)
            }
        } else if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Color(0xFFF9FAFB), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👥", fontSize = 24.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = t("لا توجد أجهزة مسجلة في هذا القسم.", "No devices registered in this filter."),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            filteredList.forEach { dev ->
                val devId = (dev["deviceId"] as? String)?.takeIf { it.isNotBlank() } ?: (dev["id"] as? String) ?: ""
                val devName = ((dev["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: (dev["deviceName"] as? String)?.takeIf { it.isNotBlank() }) ?: t("هاتف أندرويد", "Android Phone")
                val devStatus = dev["status"] as? String ?: "PENDING"
                val devIsPrimary = dev["isPrimary"] as? Boolean ?: false
                val devLastActive = dev["lastActive"] as? String ?: ""
                val devRegisteredAt = dev["registeredAt"] as? String ?: ""
                val devModel = dev["model"] as? String ?: ""
                val devMfg = dev["manufacturer"] as? String ?: ""
                
                val isMe = devId == myDeviceId
                val isPending = devStatus == "PENDING"
                val isSuspended = devStatus == "SUSPENDED"
                val isBlocked = devStatus == "BLOCKED"

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isPending -> Color(0xFFFFFDF5)
                            isSuspended -> Color(0xFFFFFBEB)
                            isBlocked -> Color(0xFFFEF2F2)
                            else -> Color.White
                        }
                    ),
                    border = BorderStroke(
                        if (isPending || isBlocked) 1.8.dp else 1.2.dp,
                        when {
                            isPending -> Color(0xFFF59E0B)
                            isSuspended -> Color(0xFFD97706)
                            isBlocked -> Color(0xFFDC2626)
                            isMe -> GBRBlueMain
                            else -> SuccessGreen.copy(alpha = 0.7f)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = devName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = GBRDarkIndigo,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isMasterAdmin) {
                                        IconButton(
                                            onClick = {
                                                renameInput = (dev["displayName"] as? String) ?: ""
                                                deviceToRename = dev
                                            },
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = GBRBlueMain, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    if (isMe) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFEFF6FF),
                                            border = BorderStroke(0.8.dp, Color(0xFFBFDBFE))
                                        ) {
                                            Text(
                                                text = t("جهازك الحالي", "My Device"),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRBlueMain,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (devIsPrimary) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFFEF3C7),
                                            border = BorderStroke(0.8.dp, Color(0xFFFDE68A))
                                        ) {
                                            Text(
                                                text = "👑 " + t("مسؤول أول", "Master Admin"),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB45309),
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                
                                if (devModel.isNotBlank() || devMfg.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$devMfg $devModel".trim() + ((dev["deviceName"] as? String)?.takeIf { (dev["displayName"] as? String).orEmpty().isNotBlank() && it.isNotBlank() }?.let { " • $it" } ?: ""),
                                        fontSize = 11.sp,
                                        color = Color.DarkGray
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "ID: $devId",
                                        fontSize = 10.sp,
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    val clipboard = LocalClipboardManager.current
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(devId))
                                            Toast.makeText(context, t("تم نسخ المعرف!", "ID Copied!"), Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Copy ID", tint = Color.Gray, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                            
                            // Badge Status
                            val badgeColor = when (devStatus) {
                                "APPROVED" -> SuccessGreen
                                "PENDING" -> WarningOrange
                                "SUSPENDED" -> Color(0xFFD97706)
                                else -> Color(0xFFDC2626)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = when (devStatus) {
                                        "APPROVED" -> t("🟢 معتمد", "🟢 Approved")
                                        "PENDING" -> t("🟡 معلق بانتظار الاعتماد", "🟡 Pending")
                                        "SUSPENDED" -> t("⏸️ موقوف مؤقتاً", "⏸️ Suspended")
                                        "BLOCKED" -> t("🚫 محظور وممسوح", "🚫 Blocked/Wiped")
                                        else -> devStatus
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (devLastActive.isNotBlank()) {
                                Text(
                                    text = t("آخر اتصال نشط: ", "Last connected: ") + devLastActive,
                                    fontSize = 10.sp,
                                    color = Color.DarkGray
                                )
                            }
                            if (devRegisteredAt.isNotBlank()) {
                                Text(
                                    text = t("التسجيل: ", "Registered: ") + devRegisteredAt,
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }

                        // Action Controls for Every Device (Primary Admin only)
                        if (!isMe && isMasterAdmin) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Divider(color = Color.LightGray.copy(alpha = 0.4f), thickness = 0.8.dp)
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                when (devStatus) {
                                    "APPROVED" -> {
                                        // 1. Suspend Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "SUSPEND"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Lock, "Suspend", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("تعليق ⏸️", "Suspend ⏸️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 2. Block & Remote Wipe Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "BLOCK_WIPE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1.2f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Warning, "Block & Wipe", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حظر ومسح 🚫", "Block & Wipe 🚫"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 3. Final Delete Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "DELETE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(0.9f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حذف 🗑️", "Delete 🗑️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 4. Promote Button (if not already primary)
                                        if (!devIsPrimary) {
                                            IconButton(
                                                onClick = {
                                                    pendingTargetDeviceIdForPromotion = devId
                                                    showVerificationDialogForTransferAdmin = true
                                                    verificationCodeInput = ""
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(Icons.Default.Star, "Promote", tint = GBRPurpleAccent, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                    "PENDING" -> {
                                        // 1. Direct Approve Button
                                        var isDirectApproving by remember { mutableStateOf(false) }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isDirectApproving = true
                                                    val ok = DeviceSecurityManager.updateDeviceStatusOnCloud(context, devId, DeviceSecurityManager.STATUS_APPROVED)
                                                    isDirectApproving = false
                                                    if (ok) {
                                                        Toast.makeText(context, t("🎉 تم اعتماد وتنشيط ($devName) بنجاح!", "🎉 Device approved!"), Toast.LENGTH_SHORT).show()
                                                        fetchCloudDevices()
                                                    } else {
                                                        Toast.makeText(context, t("❌ فشل اعتماد الجهاز، تحقق من الاتصال.", "❌ Approval failed."), Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1.3f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                            enabled = !isDirectApproving
                                        ) {
                                            if (isDirectApproving) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp))
                                            } else {
                                                Icon(Icons.Default.Check, "Approve", modifier = Modifier.size(14.dp), tint = Color.White)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(t("اعتماد فوري 📥", "Approve 📥"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        // 2. Block & Wipe Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "BLOCK_WIPE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Warning, "Reject", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("رفض وحظر 🚫", "Reject 🚫"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 3. Delete Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "DELETE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(0.9f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حذف 🗑️", "Delete 🗑️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    "SUSPENDED" -> {
                                        // 1. Resume / Re-approve Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "RESUME"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1.2f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, "Resume", modifier = Modifier.size(14.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(t("استئناف الصلاحية ▶️", "Resume ▶️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 2. Block & Wipe Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "BLOCK_WIPE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Warning, "Block & Wipe", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حظر ومسح 🚫", "Block & Wipe 🚫"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 3. Delete Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "DELETE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(0.9f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حذف 🗑️", "Delete 🗑️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    "BLOCKED" -> {
                                        // 1. Re-approve Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "APPROVE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1.2f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Check, "Re-approve", modifier = Modifier.size(14.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(t("إعادة الاعتماد والتفعيل 🔄", "Re-Approve 🔄"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        // 2. Delete from list Button
                                        Button(
                                            onClick = {
                                                actionTargetDevice = dev
                                                actionType = "DELETE"
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete from list", modifier = Modifier.size(13.dp), tint = Color.White)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(t("حذف من السجل 🗑️", "Remove 🗑️"), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
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
