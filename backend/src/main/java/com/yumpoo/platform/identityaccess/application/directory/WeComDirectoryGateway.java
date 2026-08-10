package com.yumpoo.platform.identityaccess.application.directory;

/**
 * 企业微信通讯录成员 ID 分页边界。
 *
 * <p>实现不得记录 access token、游标、原始成员 ID 或供应商完整响应。</p>
 */
public interface WeComDirectoryGateway {

    WeComDirectoryPage fetchPage(String cursor, int limit);
}
