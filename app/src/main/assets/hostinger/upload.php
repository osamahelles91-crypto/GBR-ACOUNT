<?php
/**
 * GBR Paints PHP Storage System - Secure Upload Service
 * 
 * Supports multipart/form-data uploads.
 * Restricts materials TDS to PDF formats, and formulations to image formats.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Access-Control-Allow-Methods: POST, OPTIONS');

// Handle preflight OPTIONS request
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    exit(0);
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    echo json_encode([
        'success' => false,
        'error' => 'Only POST requests are supported for file upload.'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Helper to find existing matching subdirectory case-insensitively, defaulting to the proposed lowercase/standard name
function resolveTargetSubDir($proposed) {
    $proposedLower = strtolower($proposed);
    $files = scandir(__DIR__);
    if ($files !== false) {
        foreach ($files as $file) {
            if ($file === '.' || $file === '..') continue;
            if (is_dir(__DIR__ . '/' . $file) && strtolower($file) === $proposedLower) {
                return $file; // Use existing directory casing (e.g., "Lab" instead of "lab")
            }
        }
    }
    return $proposed; // Default fallback (e.g., "lab", "tds", "images")
}

// Check parameters: 'type' (must be 'tds' or 'image') and optionally custom 'filename'
$type = isset($_POST['type']) ? trim($_POST['type']) : '';
$customName = isset($_POST['filename']) ? trim($_POST['filename']) : '';

if ($type !== 'tds' && $type !== 'image' && $type !== 'lab') {
    echo json_encode([
        'success' => false,
        'error' => 'نوع الرفع غير صحيح. يجب أن يكون "tds" لملفات PDF، "image" لصور التركيبات، أو "lab" لمستندات وصور المختبر.'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Check if file key is present
if (!isset($_FILES['file'])) {
    echo json_encode([
        'success' => false,
        'error' => 'لم يتم إرسال أي ملف في حقل الرفع "file"'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

$file = $_FILES['file'];

// Check upload error codes
if ($file['error'] !== UPLOAD_ERR_OK) {
    echo json_encode([
        'success' => false,
        'error' => 'خطأ في رفع الملف. رمز الخطأ البرمجي: ' . $file['error']
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Select target directory with case-insensitive check to support custom casing like "Lab" or "TDS"
if ($type === 'tds') {
    $targetSubDir = resolveTargetSubDir('tds');
} elseif ($type === 'lab') {
    $targetSubDir = resolveTargetSubDir('lab');
} else {
    $targetSubDir = resolveTargetSubDir('images');
}
$targetDir = __DIR__ . '/' . $targetSubDir;

// Ensure directories exist with correct permissions
if (!is_dir($targetDir)) {
    if (!mkdir($targetDir, 0755, true)) {
        echo json_encode([
            'success' => false,
            'error' => "فشل إنشاء مجلد التخزين المخصص للنوع: $targetSubDir Check host permissions"
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

if (!is_writable($targetDir)) {
    echo json_encode([
        'success' => false,
        'error' => "المجلد المخصص: $targetSubDir غير قابل للكتابة وحفظ الملفات. يرجى تفعيل صلاحية Chmod 755 أو 777"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// Verify file extension and mime-type
$originalName = $file['name'];
$ext = strtolower(pathinfo($originalName, PATHINFO_EXTENSION));

if ($type === 'tds') {
    if ($ext !== 'pdf') {
        echo json_encode([
            'success' => false,
            'error' => 'ملف TDS الفني للمادة الخام يجب أن يكون بصيغة PDF فقط'
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
} elseif ($type === 'lab') {
    $allowedLabExts = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'txt'];
    if (!in_array($ext, $allowedLabExts)) {
        echo json_encode([
            'success' => false,
            'error' => 'خطأ بصيغة الملف المرفق. الصيغ المسموحة للمختبر: ' . implode(', ', $allowedLabExts)
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
} else {
    $allowedImageExts = ['jpg', 'jpeg', 'png', 'webp', 'gif'];
    if (!in_array($ext, $allowedImageExts)) {
        echo json_encode([
            'success' => false,
            'error' => 'خطأ بصيغة الصورة. الصيغ المسموحة لـ صور التركيبات: ' . implode(', ', $allowedImageExts)
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

// Sanitize filename to prevent directory traversal
if (!empty($customName)) {
    // Sanitize any path characters out
    $cleanName = preg_replace('/[^a-zA-Z0-9_\.-]/', '', $customName);
    // Ensure it terminates with correct extension
    if (strtolower(pathinfo($cleanName, PATHINFO_EXTENSION)) !== $ext) {
        $destName = pathinfo($cleanName, PATHINFO_FILENAME) . '.' . $ext;
    } else {
        $destName = $cleanName;
    }
} else {
    // Generate a secure unique identifier
    $destName = $type . '_' . uniqid() . '.' . $ext;
}

$targetFilePath = $targetDir . '/' . $destName;

// Move temporary uploaded file to persistent host directory
if (move_uploaded_file($file['tmp_name'], $targetFilePath)) {
    // Base address calculation
    $protocol = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? "https://" : "http://";
    $host = $_SERVER['HTTP_HOST'];
    $scriptDir = dirname($_SERVER['SCRIPT_NAME']);
    $normalizedDir = ($scriptDir === '/' || $scriptDir === '\\') ? '' : $scriptDir;
    
    $fileUrl = $protocol . $host . $normalizedDir . '/' . $targetSubDir . '/' . $destName;
    
    echo json_encode([
        'success' => true,
        'message' => 'تم رفع وحفظ الملف بنجاح على الاستضافة',
        'filename' => $destName,
        'url' => $fileUrl,
        'size' => $file['size'],
        'type' => $type
    ], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
} else {
    echo json_encode([
        'success' => false,
        'error' => 'فشل خادم Hostinger في نقل الملف المرفوع من المجلد المؤقت إلى مجلد التخزين الدائم'
    ], JSON_UNESCAPED_UNICODE);
}
