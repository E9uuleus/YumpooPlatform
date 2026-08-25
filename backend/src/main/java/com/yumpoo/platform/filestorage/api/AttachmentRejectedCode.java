package com.yumpoo.platform.filestorage.api;

public enum AttachmentRejectedCode {
    FILE_TOO_LARGE,
    FILE_TYPE_NOT_ALLOWED,
    MALWARE_DETECTED,
    SCAN_UNAVAILABLE,
    UPLOAD_INCOMPLETE,
    INTEGRITY_CHECK_FAILED,
    PARENT_NOT_WRITABLE,
    QUOTA_EXCEEDED
}
