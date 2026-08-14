package com.yumpoo.platform.identityaccess.application.account;

public interface AccountStatusRepository {

    AccountStatusSnapshot change(AccountStatusChangeCommand command);
}
