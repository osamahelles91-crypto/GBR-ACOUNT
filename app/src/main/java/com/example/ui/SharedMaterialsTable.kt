package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GBRBlueMain
import com.example.ui.theme.GBRDarkIndigo
import com.example.ui.theme.t

data class SharedMaterialItem(
    val id: String,
    val rawMaterialId: String,
    val rawMaterialName: String,
    val displayValue: String, // e.g., "5.2 كجم" or "12.5 غم" or "34.5%"
    val needsGrinding: Boolean = false,
    val grindingDurationMinutes: Int = 0
)

@Composable
fun SharedMaterialsTable(
    items: List<SharedMaterialItem>,
    isReadOnly: Boolean,
    isEditable: Boolean, // if true, up/down/delete can be visible
    onItemClick: ((SharedMaterialItem) -> Unit)? = null,
    onMoveUp: ((SharedMaterialItem, index: Int) -> Unit)? = null,
    onMoveDown: ((SharedMaterialItem, index: Int) -> Unit)? = null,
    onDelete: ((SharedMaterialItem) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.2.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = onItemClick != null) {
                            onItemClick?.invoke(item)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Raw Material Name and Grinding badge
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = item.rawMaterialName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = GBRDarkIndigo
                        )
                        if (item.needsGrinding) {
                            Surface(
                                color = Color(0xFFFEF3C7),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${t("طحن:", "Grinding:")} ${item.grindingDurationMinutes} ${t("د", "min")}",
                                    fontSize = 11.sp,
                                    color = Color(0xFFB45309),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Value and controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = item.displayValue,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = GBRBlueMain
                        )

                        if (isEditable) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { onMoveUp?.invoke(item, index) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = t("تحريك صعوداً", "Move up"),
                                        tint = if (index > 0) GBRBlueMain else Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { onMoveDown?.invoke(item, index) },
                                    enabled = index < items.size - 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = t("تحريك نزولاً", "Move down"),
                                        tint = if (index < items.size - 1) GBRBlueMain else Color.LightGray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { onDelete?.invoke(item) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = t("حذف المادة", "Delete material"),
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        } else {
                            // Read-only info tag
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (isReadOnly) t("للقراءة فقط 🔒", "Read-only 🔒") else t("للاطلاع فقط 🔒", "View-only 🔒"),
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (index < items.size - 1) {
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                }
            }
        }
    }
}
