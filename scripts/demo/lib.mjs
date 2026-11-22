// Shared by the sample processes (docs/status/ROADMAP-2026-11-23.md §8): logs in, makes a project, and builds a process
// through the public API only, so stock goes through the inventory command as any receipt does.

export async function connect() {
  const api = (process.env.FLOWMAT_API ?? 'http://127.0.0.1:8080/api').replace(/\/$/, '')
  const user = process.env.FLOWMAT_USER
  const password = process.env.FLOWMAT_PASSWORD
  if (!user || !password) {
    console.error('Set FLOWMAT_USER and FLOWMAT_PASSWORD (and FLOWMAT_API when the server is not local).')
    process.exit(2)
  }
  let token = ''
  const call = async (method, path, body) => {
    const response = await fetch(`${api}${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const envelope = await response.json().catch(() => ({}))
    if (!response.ok || envelope.success === false) throw new Error(`${method} ${path}: ${response.status} ${envelope.message ?? ''}`)
    return envelope.data
  }
  token = (await call('POST', '/auth/login', { userIdOrEmail: user, password })).accessToken
  return { call, user }
}

/** A new project with one storage location, and helpers that build into it. */
export async function sample(call, user, name) {
  const tag = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
  const { projectId } = await call('POST', '/projects', { projectName: `${name} ${tag}`, ownerId: user })
  await call('POST', '/storage-locations', { projectId, locationCode: 'RAW', locationType: 'location' })
  const items = new Set()
  const helpers = {
    projectId,
    item: async (code, itemName, itemType, unitId) => {
      const { itemId } = await call('POST', '/items', { projectId, itemCode: code, itemName, itemType, unitId })
      if (itemType === 'product') items.add(itemId)
      return itemId
    },
    stock: (itemId, quantity) => call('POST', '/inventories', { projectId, itemId, location: 'RAW', quantity }),
    equipment: async (code) => (await call('POST', '/equipments', {
      projectId, equipmentCode: code, equipmentName: code.toLowerCase(), equipmentType: 'machine',
    })).equipmentId,
    workflow: async (workflowName) => {
      const { workflowId } = await call('POST', '/workflows', { projectId, workflowName })
      let x = 80
      return {
        workflowId,
        node: async (processName) => {
          const { processId } = await call('POST', '/processes', { workflowId, processName, nodeType: 'process', posX: x, posY: 240 })
          x += 340
          return processId
        },
        // Materials drawn from stock have no connection in, so they are not required inputs.
        port: async (processId, direction, itemId, quantity, unit) => (await call('POST', '/process-ios', {
          processId, itemId, direction, quantity, unit, resourceType: items.has(itemId) ? 'product' : 'material', requiredYn: 'N',
        })).processIoId,
        connect: (fromProcessId, fromIoId, toProcessId, toIoId, lossRate) => call('POST', '/process-connections', {
          workflowId, fromProcessId, toProcessId, fromIoId, toIoId, ...(lossRate ? { lossRate } : {}),
        }),
        requirements: (processId, requirements) => call('PUT', `/processes/${processId}/requirements`, { requirements }),
        publish: () => call('POST', `/workflows/${workflowId}/revisions`),
      }
    },
  }
  return helpers
}

/** Prints the plan for a target, as the editor's Plan panel shows it. */
export async function printPlan(call, projectId, workflowId, target) {
  const plan = await call('POST', `/workflows/${workflowId}/plan-previews`, { target })
  console.log(`Project ${projectId}, workflow ${workflowId} (/projects/${projectId}/workflows/${workflowId})`)
  console.log(`For ${target.quantity} ${target.unit}: can make ${plan.buildable} now`)
  for (const row of plan.requirements) {
    console.log(`  ${row.itemName}: ${row.needed} ${row.unit} needed, ${row.available} usable, ${row.shortage} short`)
  }
  for (const limit of plan.limits) console.log(`  limit: ${limit.kind} — ${limit.message}`)
  return plan
}
