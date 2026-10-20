<?php
/**
 * GBR Paints PHP Storage System - Health Check Service
 * 
 * This file checks if the target directories exist and are writable, 
 * and verifies PHP environment configurations for uploading.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Access-Control-Allow-Methods: GET, OPTIONS');

// Handle preflight OPTIONS request
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    exit(0);
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

// Check write permissions on folders (tds for PDF, images for formula images, lab for laboratory attachments)
$tdsSubDir = resolveTargetSubDir('tds');
$imagesSubDir = resolveTargetSubDir('images');
$labSubDir = resolveTargetSubDir('lab');

$tdsDir = __DIR__ . '/' . $tdsSubDir;
$imagesDir = __DIR__ . '/' . $imagesSubDir;
$labDir = __DIR__ . '/' . $labSubDir;

$tdsWritable = false;
$imagesWritable = false;
$labWritable = false;

if (is_dir($tdsDir)) {
    $tdsWritable = is_writable($tdsDir);
} else {
    // Attempt to create it
    if (@mkdir($tdsDir, 0755, true)) {
        $tdsWritable = is_writable($tdsDir);
    }
}

if (is_dir($imagesDir)) {
    $imagesWritable = is_writable($imagesDir);
} else {
    // Attempt to create it
    if (@mkdir($imagesDir, 0755, true)) {
        $imagesWritable = is_writable($imagesDir);
    }
}

if (is_dir($labDir)) {
    $labWritable = is_writable($labDir);
} else {
    // Attempt to create it
    if (@mkdir($labDir, 0755, true)) {
        $labWritable = is_writable($labDir);
    }
}

$response = [
    'status' => ($tdsWritable && $imagesWritable && $labWritable) ? 'healthy' : 'degraded',
    'timestamp' => time(),
    'datetime' => date('Y-m-d H:i:s'),
    'php_version' => PHP_VERSION,
    'upload_max_filesize' => ini_get('upload_max_filesize'),
    'post_max_size' => ini_get('post_max_size'),
    'server_software' => $_SERVER['SERVER_SOFTWARE'] ?? 'Unknown',
    'directories' => [
        'tds' => [
            'exists' => is_dir($tdsDir),
            'writable' => $tdsWritable,
            'path' => $tdsDir
        ],
        'images' => [
            'exists' => is_dir($imagesDir),
            'writable' => $imagesWritable,
            'path' => $imagesDir
        ],
        'lab' => [
            'exists' => is_dir($labDir),
            'writable' => $labWritable,
            'path' => $labDir
        ]
    ],
    'system_permission' => [
        'upload_php_writable' => is_writable(__FILE__),
        'directory_writable' => is_writable(__DIR__)
    ]
];

echo json_encode($response, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
