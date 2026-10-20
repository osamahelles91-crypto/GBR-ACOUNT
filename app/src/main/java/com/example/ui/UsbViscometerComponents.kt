package com.example.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.UsbViscometerManager
import com.example.data.ParsedViscosityData
import java.util.Locale

// GBR Colors Matching the Application
private val LabBlueMain = Color(0xFF0056B3)
private val LabDarkIndigo = Color(0xFF0F172A)
private val LabLightBg = Color(0xFFF8FAFC)
private val LabBorder = Color(0xFFE2E8F0)
private val LabSuccessGreen = Color(0xFF10B981)
private val LabWarningYellow = Color(0xFFF59E0B)
private val LabErrorRed = Color(0xFFEF4444)
private val LabPurple = Color(0xFF8B5CF6)

@Composable
fun UsbViscometerControlPanel(
    modifier: Modifier = Modifier,
    onAutoFillRequested: (ParsedViscosityData) -> Unit = {}
) {
    val context = LocalContext.current
    val manager = remember { UsbViscometerManager.getInstance() }
    
    val connectionStatus by manager.connectionStatus.collectAsState()
    val deviceName by manager.deviceName.collectAsState()
    val parsedReading by manager.parsedReading.collectAsState()
    
    val countdown by manager.timerCountdown.collectAsState()
    val isTimerRunning by manager.isTimerRunning.collectAsState()
    
    var isSimulating by remember { mutableStateOf(connectionStatus == "simulation") }
    var autoFillOnTimerEnd by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LabBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection Status Label and Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val (statusText, statusColor) = when (connectionStatus) {
                        "connected" -> "متصل بجهاز NDJ-8S" to LabSuccessGreen
                        "scanning" -> "جاري البحث عن الجهاز..." to LabWarningYellow
                        "simulation" -> "وضع المحاكاة نشط" to LabPurple
                        else -> "غير متصل بجهاز NDJ-8S" to Color.Gray
                    }
                    
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo
                    )
                }

                // Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = LabBlueMain,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "منفذ الاتصال التلقائي (USB OTG)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabBlueMain
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main interaction layout: Connection controls and Simulation switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connect/Disconnect Button
                if (connectionStatus == "connected") {
                    Button(
                        onClick = {
                            manager.disconnectDevice()
                            Toast.makeText(context, "تم فصل جهاز قياس اللزوجة", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabErrorRed.copy(alpha = 0.1f), contentColor = LabErrorRed),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("قطع الاتصال", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            isSimulating = false
                            manager.toggleSimulation(false)
                            val success = manager.connectDevice(context)
                            if (success) {
                                Toast.makeText(context, "تم الاتصال بنجاح بجهاز NDJ-8S", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "فشل الاتصال: يرجى التحقق من كابل USB OTG وتوصيله بالجهاز", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain.copy(alpha = 0.1f), contentColor = LabBlueMain),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                        enabled = (connectionStatus != "scanning")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (connectionStatus == "scanning") "جاري الاتصال..." else "اتصال بالجهاز", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Simulation Toggle Button
                val simColor = if (connectionStatus == "simulation") LabPurple else Color.Gray
                OutlinedButton(
                    onClick = {
                        val targetSim = (connectionStatus != "simulation")
                        isSimulating = targetSim
                        manager.toggleSimulation(targetSim)
                        if (targetSim) {
                            Toast.makeText(context, "تفعيل وضع المحاكاة الذكية", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "إيقاف وضع المحاكاة", Toast.LENGTH_SHORT).show()
                        }
                    },
                    border = BorderStroke(1.dp, simColor.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = simColor,
                        containerColor = if (connectionStatus == "simulation") LabPurple.copy(alpha = 0.05f) else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Science, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (connectionStatus == "simulation") "إيقاف المحاكاة" else "تشغيل المحاكاة 🧪", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Expanded Data Stream section when connected or simulating
            AnimatedVisibility(
                visible = connectionStatus == "connected" || connectionStatus == "simulation",
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Live readings board
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LabLightBg)
                            .border(1.dp, LabBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "بيانات البث المباشر (المستقبلة من الجهاز)",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = if (connectionStatus == "simulation") "محاكي NDJ-8S نشط" else "جهاز NDJ-8S نشط",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (connectionStatus == "simulation") LabPurple else LabSuccessGreen
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (parsedReading != null) {
                                val reading = parsedReading!!
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    // Viscosity Card
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1.3f)
                                    ) {
                                        Text("اللزوجة (cP)", fontSize = 9.sp, color = Color.Gray)
                                        Text(
                                            text = String.format(Locale.US, "%,.0f", reading.viscosityCp),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = LabBlueMain
                                        )
                                    }

                                    // Torque Card with feedback color
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val torque = reading.torquePct
                                        val (torqueColor, torqueFeedback) = when {
                                            torque < 10.0 || torque > 90.0 -> LabErrorRed to "🔴 عزم غير مقبول"
                                            torque < 20.0 || torque > 80.0 -> LabWarningYellow to "🟡 عزم مقبول بدقة منخفضة"
                                            else -> LabSuccessGreen to "🟢 عزم مثالي ومطابق"
                                        }

                                        Text("عزم الدوران (%)", fontSize = 9.sp, color = Color.Gray)
                                        Text(
                                            text = String.format(Locale.US, "%.1f%%", torque),
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = torqueColor
                                        )
                                        Text(
                                            text = torqueFeedback,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = torqueColor
                                        )
                                    }

                                    // Parameters (Spindle & Speed)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("إبرة / سرعة", fontSize = 9.sp, color = Color.Gray)
                                        Text(
                                            text = "S${reading.spindle} / ${reading.speedRpm}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabDarkIndigo
                                        )
                                        Text(
                                            text = "حرارة: ${reading.temperature}°م",
                                            fontSize = 8.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Raw string footer
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White)
                                        .border(0.5.dp, LabBorder, RoundedCornerShape(4.dp))
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "الرمز الخام المستلم: \"${reading.rawString}\"",
                                        fontSize = 8.sp,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = LabBlueMain
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "بانتظار استقبال أول إشارة لخط القراءة من الجهاز...",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Timer & Automatic Recording Block
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = LabLightBg),
                        border = BorderStroke(0.5.dp, LabBorder)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Checkbox(
                                        checked = autoFillOnTimerEnd,
                                        onCheckedChange = { autoFillOnTimerEnd = it },
                                        colors = CheckboxDefaults.colors(checkedColor = LabBlueMain)
                                    )
                                    Text(
                                        text = "تسجيل القراءة تلقائياً في الجدول فور انتهاء المؤقت",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabDarkIndigo
                                    )
                                }

                                Text(
                                    text = "مؤقت استقرار القراءة (30 ثانية) ⏱️",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = LabDarkIndigo
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (isTimerRunning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Cancel Button
                                    OutlinedButton(
                                        onClick = { manager.cancelTimer() },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LabErrorRed),
                                        border = BorderStroke(1.dp, LabErrorRed.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إلغاء المؤقت", fontSize = 10.sp)
                                    }

                                    // Countdown Progress Bar & Label
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        LinearProgressIndicator(
                                            progress = { countdown.toFloat() / 30f },
                                            color = LabBlueMain,
                                            trackColor = LabBorder,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                        Text(
                                            text = "باقي $countdown ثانية",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = LabBlueMain
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Insert Current Manual Button
                                    Button(
                                        onClick = {
                                            parsedReading?.let {
                                                onAutoFillRequested(it)
                                                Toast.makeText(context, "تم إدراج القراءة الحالية بنجاح 📥", Toast.LENGTH_SHORT).show()
                                            } ?: Toast.makeText(context, "عذراً، لا توجد قراءة مستقرة حالياً لسحبها", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabSuccessGreen),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                        enabled = parsedReading != null
                                    ) {
                                        Icon(imageVector = Icons.Default.Input, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("سحب وإدراج القراءة الحالية 📥", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Start 30s Timer Button
                                    Button(
                                        onClick = {
                                            manager.startTimer(30) { reading ->
                                                if (autoFillOnTimerEnd) {
                                                    onAutoFillRequested(reading)
                                                    Toast.makeText(context, "⏱️ انتهى المؤقت وتم تسجيل القراءة بنجاح!", Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, "⏱️ انتهى المؤقت واستقرت القراءة: cP=${reading.viscosityCp}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("بدء العد التنازلي (30ث)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
fun UsbRowAutoPullButton(
    modifier: Modifier = Modifier,
    onReadingPulled: (viscosity: String, torque: String) -> Unit
) {
    val manager = remember { UsbViscometerManager.getInstance() }
    val connectionStatus by manager.connectionStatus.collectAsState()
    val parsedReading by manager.parsedReading.collectAsState()

    val isEnabled = (connectionStatus == "connected" || connectionStatus == "simulation") && parsedReading != null

    if (isEnabled) {
        IconButton(
            onClick = {
                parsedReading?.let {
                    onReadingPulled(
                        String.format(Locale.US, "%.0f", it.viscosityCp),
                        String.format(Locale.US, "%.1f", it.torquePct)
                    )
                }
            },
            modifier = modifier
                .size(36.dp)
                .background(LabSuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
            enabled = isEnabled
        ) {
            Icon(
                imageVector = Icons.Default.Input,
                contentDescription = "استيراد القراءة الحالية",
                tint = LabSuccessGreen,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
