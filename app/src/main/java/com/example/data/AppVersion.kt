package com.example.data

/** رقم الإصدار المثبّت على هذا الجهاز (الجوال: من BuildConfig). */
object AppVersion {
    const val PLATFORM = "mobile"
    val name: String = com.example.BuildConfig.VERSION_NAME
    val display: String = "${com.example.BuildConfig.VERSION_NAME} (${com.example.BuildConfig.VERSION_CODE})"
}
