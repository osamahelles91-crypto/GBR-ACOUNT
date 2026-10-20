<?php
/**
 * GBR Paints PHP Storage System - Secure Delete Service
 * 
 * Safely removes a document (TDS) or formulation image from Hostinger storage.
 * Uses strict sanitization (basename) to prevent file system traversal attacks.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Access-Control-Allow-Methods: POST, DELETE, OPTIONS');

// Handle preflight OPTIONS request
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    exit(0);
}

// Support both standard application/x-www-form-urlencoded and raw application/json payloads
$payload = [];
$rawInput = file_get_contents('php://input');
if (!empty($rawInput)) {
    $decoded = json_decode($rawInput, true);
    if (is_array($decoded)) {
        $payload = $decoded;
    }
}
$payload = array_merge($_POST, $payload);

$type = isset($payload['type']) ? trim($payload['type']) : '';
$filename = isset($payload['filename']) ? trim($payload['filename']) : '';

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

if ($type !== 'tds' && $type !== 'image' && $type !== 'lab') {
    echo json_encode([
        'success' => false,
        'error' => 'الفئة غير صحيحة. يجب تعيين نوع الملف "tds" أو "image" أو "lab"'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if (empty($filename)) {
    echo json_encode([
        'success' => false,
        'error' => 'اسم الملف المطلوب حذفه "filename" فارغ أو مفقود'
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

// STRICT SANITIZATION: Force file extraction to base name only
// This prevents dangerous commands like: type=tds, filename=../../index.php
$filename = basename($filename);

if ($type === 'tds') {
    $targetSubDir = resolveTargetSubDir('tds');
} elseif ($type === 'lab') {
    $targetSubDir = resolveTargetSubDir('lab');
} else {
    $targetSubDir = resolveTargetSubDir('images');
}
$filePath = __DIR__ . '/' . $targetSubDir . '/' . $filename;

if (!file_exists($filePath)) {
    echo json_encode([
        'success' => false,
        'error' => "الملف المطلوب غير موجود بالفعل في مجلد $targetSubDir: $filename"
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if (unlink($filePath)) {
    echo json_encode([
        'success' => true,
        'message' => "تم حذف الملف بنجاح من الاستضافة السحابية: $filename"
    ], JSON_UNESCAPED_UNICODE);
} else {
    echo json_encode([
        'success' => false,
        'error' => 'فشل نظام الملفات في حذف الملف من القرص. تأكد من إعدادات الصلاحيات للمجلد'
    ], JSON_UNESCAPED_UNICODE);
}
