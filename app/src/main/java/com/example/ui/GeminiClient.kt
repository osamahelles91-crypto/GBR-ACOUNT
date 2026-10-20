package com.example.ui

import android.util.Log
import com.example.BuildConfig
import com.example.data.LabSession
import com.example.data.LabTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    // List of modern models to try sequentially in case of service errors or unavailability
    private val MODELS_LIST = listOf(
        "gemini-3.5-flash",
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite-preview"
    )
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Attempts to call the Gemini API sequentially using a list of alternative models.
     * This provides extreme resilience against 503 (Service Unavailable) and model-specific errors.
     */
    private suspend fun makeApiCallWithFallback(
        apiKey: String,
        requestJson: JSONObject
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        
        for (model in MODELS_LIST) {
            val url = "$BASE_URL$model:generateContent?key=$apiKey"
            val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
                
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseString = response.body?.string().orEmpty()
                    if (responseString.isNotBlank()) {
                        val responseJson = JSONObject(responseString)
                        val candidates = responseJson.optJSONArray("candidates")
                        val firstCandidate = candidates?.optJSONObject(0)
                        val content = firstCandidate?.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        val text = parts?.optJSONObject(0)?.optString("text")
                        if (!text.isNullOrBlank()) {
                            Log.i(TAG, "Successfully generated content using model: $model")
                            return@withContext Pair(true, text)
                        }
                    }
                    errors.add("$model: استجابة فارغة")
                } else {
                    val errMsg = "رمز الخطأ: ${response.code} - ${response.message}"
                    errors.add("$model: $errMsg")
                    Log.w(TAG, "API call failed for model $model. Error: $errMsg")
                }
            } catch (e: Exception) {
                errors.add("$model: خطأ اتصال (${e.message})")
                Log.e(TAG, "Exception during API call for model $model", e)
            }
        }
        
        return@withContext Pair(false, errors.joinToString(" | "))
    }

    suspend fun analyzeLabSession(
        session: LabSession,
        tests: List<LabTest>
    ): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext """
                ⚠️ لم يتم تهيئة مفتاح واجهة برمجة التطبيقات للذكاء الاصطناعي (Gemini API Key) بشكل صحيح في النظام.
                يرجى إدخال مفتاح API صالح في لوحة Secrets أو ملف التكوين .env للتمكن من الاتصال بالخدمة السحابية.
                
                💡 كحل مؤقت، إليك الهيكل الاستشاري القياسي بناءً على البيانات المتوفرة:
                • عينة المقارنة الأولى (A): ${getPartyName(session.partyA)}
                • عينة المقارنة الثانية (B): ${getPartyName(session.partyB)}
                • عدد الفحوصات المنجزة: ${tests.count { it.status == "مكتمل" }}
                
                التحليل الافتراضي المستند للقواعد الفنية:
                1. اللزوجة (Viscosity): إذا كانت لزوجة (B) أعلى، فهذا يشير إلى ثبات كيميائي للبوليميرات أو زيادة نسبة المواد المتثخنة.
                2. كثافة السوائل (Density): تشير الكثافة الأعلى لعينة عن الأخرى إلى فروقات في التعبئة الصبغية (مثل كربونات الكالسيوم أو الكاولين) أو مواد مالئة أثقل.
                3. المواد الصلبة (Solid Content): زيادة المواد الصلبة تدعم جفافاً أسرع وقدرة سد عالية وتزيد من متانة الفيلم الجاف.
                4. المادة الرابطة (Binder): هي العمود القبلي لمقاومة الطلاء، وجود نسبة مادة رابطة مثالية يعطي الطلاء خواص فيزيقية ممتازة ومقاومة للاحتكاك الرطب.
            """.trimIndent()
        }

        val prompt = buildAnalysisPrompt(session, tests)

        try {
            // Build request JSON
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", prompt)
                            }
                            put(partObj)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                // Add system instructions as recommended in gemini-api SKILL.md
                val systemInstructionObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", "أنت مدير البحث والتطوير (R&D Group Leader) والخبير الكيميائي الأقدم في شركة دهانات GBR العالمية. مهمتك هي تحليل البيانات المخبرية للفحوصات الكيميائية لعينات الدهان ومقارنتها وتقديم تقارير واستشارات علمية رصينة ومفصلة باللغة العربية الفصحى لمساعدة الكيميائيين في المختبر على تطوير المنتجات واتخاذ القرارات الفنية دون الحاجة لتلخيص مكرر للأرقام.")
                        }
                        put(partObj)
                    }
                    put("parts", partsArray)
                }
                put("systemInstruction", systemInstructionObj)

                // Optional configuration
                val configObj = JSONObject().apply {
                    put("temperature", 0.7)
                }
                put("generationConfig", configObj)
            }

            val (success, resultText) = makeApiCallWithFallback(apiKey, requestJson)
            if (success) {
                resultText
            } else {
                "فشل استعلام الذكاء الاصطناعي للتحليل المخبري بعد تجربة عدة نماذج. تفاصيل الخطأ: $resultText"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating AI analysis", e)
            "حدث خطأ أثناء الاتصال بالخادم الذكي: ${e.message}"
        }
    }

    suspend fun generateComparisonAdvice(
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext """
                ⚠️ لم يتم تهيئة مفتاح واجهة برمجة التطبيقات للذكاء الاصطناعي (Gemini API Key) بشكل صحيح في النظام.
                يرجى إدخال مفتاح API صالح في لوحة Secrets للتمكن من الاتصال بالخدمة السحابية.
                
                💡 كحل فني مؤقت لمقارنة العينات:
                1. الكثافة (Density): العينة ذات الكثافة الأقل تساهم في فرد وانتشار أفضل، مما يقلل الكلفة على العميل.
                2. اللزوجة (Viscosity): اللزوجة الكافية تمنع ترسيب الأكاسيد والملونات أثناء التخزين وتحسن تماسك الطلاء على الجدران.
                3. نسبة المواد الصلبة (Solid %): النسبة العالية تعزز حماية السطح والامتلاء وسماكة الطلاء الجاف.
                4. المادة الرابطة (Binder %): رفع نسبة البوليمر الرابط يزيد من تماسك وثبات الفيلم الجاف ومقاومته للغسيل ومختلف العوامل الجوية.
            """.trimIndent()
        }

        try {
            // Build request JSON
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray().apply {
                            val partObj = JSONObject().apply {
                                put("text", prompt)
                            }
                            put(partObj)
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                // Add system instructions
                val systemInstructionObj = JSONObject().apply {
                    val partsArray = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", "أنت مدير البحث والتطوير (R&D Group Leader) والخبير الكيميائي الأقدم في شركة دهانات GBR العالمية. مهمتك هي مقارنة جودة عينتين فحص أحاديتين من خلال خصائصهما المخبرية، وتحليل مميزات كل عينة وعيوبها، وتوفير نصائح تفصيلية للتعديل والصيغ، وتوصية بالصيغة الأفضل لخط الإنتاج الصناعي بصيغة عربية جذابة ومرتبة كلياً.")
                        }
                        put(partObj)
                    }
                    put("parts", partsArray)
                }
                put("systemInstruction", systemInstructionObj)

                val configObj = JSONObject().apply {
                    put("temperature", 0.7)
                }
                put("generationConfig", configObj)
            }

            val (success, resultText) = makeApiCallWithFallback(apiKey, requestJson)
            if (success) {
                resultText
            } else {
                "فشل استعلام الذكاء الاصطناعي للمقارنة المباشرة بعد تجربة عدة نماذج. تفاصيل الخطأ: $resultText"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating AI comparison", e)
            "حدث خطأ أثناء الاتصال بالخادم الذكي للمقارنة المباشرة: ${e.message}"
        }
    }

    private fun buildAnalysisPrompt(session: LabSession, tests: List<LabTest>): String {
        val builder = StringBuilder()
        builder.append("يرجى إجراء تحليل مخبري متكامل وشامل (R&D Analysis) لمقارنة العينتين التاليتين في مختبر الدهانات والمواد الكيميائية:\n\n")
        builder.append("📌 تفاصيل الجلسة والمقارنة:\n")
        builder.append("- اسم الجلسة/رقمها: ${session.sessionNumber} - ${session.testName}\n")
        builder.append("- نوع المقارنة وتطبيقها: ${session.comparisonType ?: "مقارنة مواصفات فنية ومخبرية للمنتج"}\n")
        builder.append("- العينة/الطرف الأول (A): ${getPartyName(session.partyA)} | التفاصيل: ${getPartyDesc(session.partyA)}\n")
        builder.append("- العينة/الطرف الثاني (B): ${getPartyName(session.partyB)} | التفاصيل: ${getPartyDesc(session.partyB)}\n\n")

        builder.append("📊 الفحوصات والقياسات المنجزة مخبرياً:\n")
        val completedTests = tests.filter { it.status == "مكتمل" }
        if (completedTests.isEmpty()) {
            builder.append("- لا توجد قراءات فحوصات مكتملة بالكامل حتى الآن لمقارنتها.\n")
        } else {
            completedTests.forEachIndexed { index, test ->
                builder.append("${index + 1}. فحص (${test.name}):\n")
                builder.append("   • نتيجة الطرف (A): ${test.testValueA ?: "غير متوفر"}\n")
                builder.append("   • نتيجة الطرف (B): ${test.testValueB ?: "غير متوفر"}\n")
                if (test.notes.isNotBlank() && !test.notes.startsWith("WIZARD_VISCOSITY:")) {
                    builder.append("   • تفاصيل إضافية: ${test.notes}\n")
                }
            }
        }

        if (session.notes.isNotBlank()) {
            builder.append("\n📝 الملاحظات الأولية والقرار الفني المسجل حالياً بالجلسة:\n• ${session.notes}\n")
        }

        builder.append("\n🎯 المطلوب منك كمدير R&D هو إنتاج تفرعات تحليلية عميقة تغطي:\n")
        builder.append("1. **تفسير فيزيائي-كيميائي للأرقام**: شرح الفروقات المباشرة في اللزوجة، الكثافة، المواد الصلبة، أو المواد الرابطة وتأثيرها على خصائص كـ (تغطية الطلاء Opacity، الانسيابية والمستوى Levelling، الثباتية في التخزين Can Stability، سهولة التطبيق Brushability، مقاومة الرطوبة والطقس).\n")
        builder.append("2. **تقييم أثر التعديل للمطور**: كيف أثر التغيير بين العينات على النواتج؟ وهل المادة الرابطة والبوليمر متلائمان مع الكثافة واللزوجة بالطرف B مقارنة بـ A؟\n")
        builder.append("3. **مقارنة نقاط القوة والضعف (Pros and Cons)** لكل عينة بأسلوب علمي واضح.\n")
        builder.append("4. **توصيات علمية لخطوط البحث والتطوير (R&D Recommendations)**: مثل تعديل نسبة المثخنات (Thickeners)، أو البوليمر الرابط (Binder Co-polymer)، أو المواد المشتتة والمبللة والملونة.\n")
        builder.append("5. **توصية نهائية موجزة ومباشرة لاتخاذ القرار الفني**: أي عينة يجب اعتمادها للإنتاج الصناعي والتشغيل؟ أو ما الإجراء الذي يتطلبه المختبر للمضي قدماً في الصيغة الجديدة.\n\n")
        builder.append("يرجى صياغة هذا الرد بأسلوب كيميائي علمي غني بالمصطلحات الفنية المعتمدة وبطريقة منسقة كلياً لعرضها بوضوح في واجهة مستخدم أندرويد.")

        return builder.toString()
    }
}
