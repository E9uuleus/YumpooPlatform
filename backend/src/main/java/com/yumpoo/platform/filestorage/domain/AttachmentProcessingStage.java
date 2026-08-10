package com.yumpoo.platform.filestorage.domain;

/** 仅供内部诊断的上传处理阶段，不是对外业务状态。 */
public enum AttachmentProcessingStage {
    RECEIVING,
    QUEUED_SCAN,
    SCANNING,
    FINALIZING
}
