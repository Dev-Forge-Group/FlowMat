import { useHttpProfileReadiness } from '../../../entities/connector/api/useHttpProfileReadiness'

export function HTTPProfileReadinessCheck({ projectId, profileVersionId, profileName, disabled }: {
  projectId: string; profileVersionId: string; profileName: string; disabled: boolean
}) {
  const check = useHttpProfileReadiness(projectId, profileVersionId)
  const result = check.isSuccess ? check.data : undefined
  const sameVersion = result?.profileVersionId === profileVersionId
  const valid = sameVersion && typeof result?.configurationReady === 'boolean' && Array.isArray(result.blockers)
    && result.credentialValuesChecked === false && result.connectionTested === false
  const ready = valid && result.configurationReady && result.blockers.length === 0
  const messages = valid ? [...new Set(result.blockers.map(blockerMessage))] : []
  return <section aria-label={`Execution configuration for ${profileName}`} style={{ display: 'grid', gap: 6, minWidth: 0 }}>
    <button type="button" disabled={disabled || check.isPending} onClick={() => { check.mutate() }}>
      {check.isPending ? 'Checking execution configuration...' : 'Check execution configuration'}
    </button>
    <small>Credential values have not been checked, and no connection has been tested. This check does not send an HTTP request.</small>
    {check.isError && <p role="alert">Could not check execution configuration. Try again.</p>}
    {result && !sameVersion && <p role="alert">The profile changed while checking. Check the current revision again.</p>}
    {result && sameVersion && !valid && <p role="alert">Execution configuration could not be verified. Check again.</p>}
    {ready && <p role="status">Configuration ready</p>}
    {valid && !ready && <div>
      <p role="status">Configuration is not ready.</p>
      {messages.length ? <ul style={{ margin: 0, paddingLeft: 20 }}>{messages.map((message) => <li key={message}>{message}</li>)}</ul>
        : <p>Execution configuration could not be verified. Check again.</p>}
    </div>}
  </section>
}

function blockerMessage(code: unknown): string {
  switch (code) {
    case 'CONNECTOR_DISABLED': return 'HTTP execution is disabled in the server configuration.'
    case 'PROFILE_RETIRED': return 'This profile has been removed. Its revisions cannot send new requests.'
    case 'PROFILE_DISABLED': return 'This profile is disabled.'
    case 'TARGET_POLICY_REJECTED': return 'The destination does not meet the approved server target policy.'
    case 'CREDENTIAL_CONFIGURATION_UNAVAILABLE': return 'The registered credential configuration is unavailable for this project.'
    default: return 'An execution configuration requirement could not be verified.'
  }
}
