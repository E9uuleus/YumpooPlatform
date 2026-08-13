export function parseSsListeners(output, port) {
  const expectedSuffix = `:${port}`
  return String(output)
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const columns = line.split(/\s+/u)
      if (columns[0] !== 'LISTEN' || columns.length < 5) return undefined
      const localAddress = columns[3]
      if (!localAddress.endsWith(expectedSuffix)) return undefined
      const processMatch = line.match(/users:\(\("([^"]+)",pid=(\d+),/u)
      return {
        localAddress,
        processName: processMatch?.[1],
        pid: processMatch ? Number(processMatch[2]) : undefined,
      }
    })
    .filter(Boolean)
}
