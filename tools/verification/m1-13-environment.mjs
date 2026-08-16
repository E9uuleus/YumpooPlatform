const PORTABLE_OVERRIDES = Object.freeze({
  YUMPOO_CONTROLLED_AUTH_ENABLED: 'false',
  YUMPOO_CONTROLLED_AUTH_CORP_ID: '',
  YUMPOO_CONTROLLED_AUTH_MEMBER_ID: '',
  YUMPOO_M113_FIXTURE_ENABLED: 'false',
  YUMPOO_M113_BACKUP_MEMBER_ID: '',
})

export function m113PortableEnvironment(environment) {
  return {
    ...environment,
    ...PORTABLE_OVERRIDES,
  }
}
