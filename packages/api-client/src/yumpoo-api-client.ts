import {
  Configuration,
  type ConfigurationParameters,
} from './generated/runtime.js'

export function createYumpooApiClient(
  parameters: ConfigurationParameters = {},
): Configuration {
  return new Configuration({
    ...parameters,
    basePath: parameters.basePath ?? '/api/v1',
    credentials: parameters.credentials ?? 'include',
  })
}
