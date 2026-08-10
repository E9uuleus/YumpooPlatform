package com.yumpoo.platform.filestorage.application;

import java.io.IOException;
import java.nio.file.Path;

/** 服务端内容/魔数识别端口。 */
public interface AttachmentContentDetector {

    DetectedAttachmentContent detect(Path sealedContent, AttachmentFileName fileName)
            throws IOException;
}
