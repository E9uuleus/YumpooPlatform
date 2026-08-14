package com.yumpoo.platform.identityaccess.application.directory;

import java.util.Map;

/** 使用成员资料凭据读取白名单资料与临时部门字典。 */
public interface WeComDirectoryProfileGateway {

    Map<Long, String> fetchDepartmentNames();

    WeComRawMemberProfile fetchMemberProfile(String externalUserId);
}
