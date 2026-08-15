import { useSession } from './useSession'

export function useIdentityAdmin() {
  const session = useSession()

  return {
    authentication: session.authentication,
    isReader: session.isIdentityReader,
    canWrite: session.canManageIdentity,
  }
}
