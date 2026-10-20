package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun GbrLogo(
    modifier: Modifier = Modifier,
    scale: Float = 1.0f,
    showPaintsText: Boolean = true
) {
    Box(
        modifier = modifier
            .size((280 * scale).dp, (130 * scale).dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_gbr_logo),
            contentDescription = "GBR Paints Logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

/** شعار صفحة "حول التطبيق" (الشعار الجديد) بنفس أبعاد GbrLogo تماماً. */
@Composable
fun GbrAboutLogo(modifier: Modifier = Modifier, scale: Float = 1.0f) {
    Box(
        modifier = modifier.size((280 * scale).dp, (130 * scale).dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_gbr_logo_about),
            contentDescription = "GBR Paints Logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

/**
 * الشعار الرسمي (نفس صورة «حول التطبيق») لصفحة الدخول والشريط العلوي.
 * الصورة القديمة كانت بهوامش بيضاء واسعة (الجزء المرئي ≈164×77.5 داخل إطار 280×130)،
 * والجديدة مقصوصة بلا هوامش؛ فتُرسم داخل صندوق 164×80 ليبقى الحجم المرئي كما كان، ويبقى الإطار الخارجي نفسه.
 */
@Composable
fun GbrOfficialLogo(modifier: Modifier = Modifier, scale: Float = 1.0f) {
    Box(
        modifier = modifier.size((280 * scale).dp, (130 * scale).dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_gbr_logo_about),
            contentDescription = "GBR Paints Logo",
            modifier = Modifier.size((164 * scale).dp, (80 * scale).dp),
            contentScale = ContentScale.Fit
        )
    }
}
