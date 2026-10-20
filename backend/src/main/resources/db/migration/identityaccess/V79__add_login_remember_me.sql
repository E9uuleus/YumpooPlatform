ALTER TABLE yumpoo.login_session
    ADD COLUMN remember_me boolean NOT NULL DEFAULT false;

ALTER TABLE yumpoo.desktop_auth_attempt
    ADD COLUMN remember_me boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN yumpoo.login_session.remember_me IS
    '7-day sign-in: idle expiry equals absolute expiry and the session cookie outlives the browser session';

COMMENT ON COLUMN yumpoo.desktop_auth_attempt.remember_me IS
    'Remember choice sent by the desktop shell; shells that omit it keep the 7-day sign-in';
