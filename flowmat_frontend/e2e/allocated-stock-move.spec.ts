import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for (const scenario of ['partial', 'retry', 'viewer'] as const) {
  test(`allocated stock move: ${scenario}`, async ({ page }) => {
    const projectId='prj-allocated-move'
    const order={workOrderId:'wo-pick',projectId,workflowId:null,workOrderNumber:'WO-PICK',workOrderTitle:'Reserved batch',
      workOrderStatus:'approved',priority:'normal',targetItemId:null,targetQuantity:10,plannedStartAt:null,plannedEndAt:null,
      actualStartAt:null,actualEndAt:null,assignedTo:null,bomId:null,producedQuantity:0,runCount:0}
    const allocation={allocationId:'allocation-1',inventoryId:'source-1',itemId:'material-1',itemCode:'MATERIAL',lotId:null,
      lotNo:null,location:'SOURCE',quantity:6,consumedQuantity:0,releasedQuantity:0,remaining:6,status:'open',createdBy:'demo-owner',createdAt:null}
    const allocations=[allocation]
    const requests:Record<string,unknown>[]=[]
    let release!:()=>void
    const gate=new Promise<void>((resolve)=>{release=resolve})
    let moves=0
    await page.route(/^https?:\/\/[^/]+\/api\//,async(route)=>{
      const request=route.request();const {pathname}=new URL(request.url())
      if(await answerAuth(route,pathname))return
      if(pathname==='/api/project-members')return ok(route,[{projectMemberId:'pm-1',projectId,userId:'demo-owner',projectRole:scenario==='viewer'?'viewer':'editor',memberStatus:'active'}])
      if(pathname==='/api/work-orders')return ok(route,[order])
      if(pathname==='/api/work-orders/wo-pick/readiness')return ok(route,{workOrderId:'wo-pick',ready:true,checks:[],materials:[]})
      if(pathname==='/api/work-orders/wo-pick/allocations')return ok(route,{workOrderId:'wo-pick',workOrderNumber:'WO-PICK',workOrderStatus:'approved',allocations,plan:[]})
      if(pathname==='/api/allocated-stock-transfers'){
        requests.push(request.postDataJSON() as Record<string,unknown>)
        if(!moves){moves++;allocation.quantity=3;allocation.remaining=3;allocations.push({...allocation,allocationId:'allocation-2',inventoryId:'destination-1',location:'STAGING'})}
        if(scenario==='retry'&&requests.length===1){await gate;return route.abort('failed')}
        return ok(route,{transferId:'transfer-1'})
      }
      if(request.method()!=='GET')throw new Error(`Unexpected write ${pathname}`)
      return ok(route,[])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/runs?view=work-orders`)
    await page.getByRole('row',{name:/Reserved batch/}).getByRole('button',{name:'Readiness',exact:true}).click()
    const panel=page.getByRole('region',{name:'Allocated stock',exact:true})
    await panel.getByRole('button',{name:'Move allocated',exact:true}).click()
    if(scenario==='viewer'){
      await expect(panel).toContainText('Only an editor or owner')
      await expect(panel.getByRole('button',{name:'Move reserved stock'})).toHaveCount(0)
      expect(requests).toHaveLength(0);return
    }
    const form=panel.getByRole('form',{name:'Move allocated stock'})
    await form.getByLabel('Allocated destination').fill('STAGING')
    await form.getByLabel('Allocated quantity').fill('7')
    await form.getByRole('button',{name:'Move reserved stock'}).click()
    await expect(form.getByRole('alert')).toContainText('within the remaining allocation')
    expect(requests).toHaveLength(0)
    await form.getByLabel('Allocated quantity').fill('3')
    await form.getByRole('button',{name:'Move reserved stock'}).click()
    if(scenario==='retry'){
      await expect(form.getByLabel('Allocated quantity')).toBeDisabled();release()
      await expect(form).toContainText('The result is unconfirmed.')
      await expect(panel.getByRole('button',{name:'Release all'})).toBeDisabled()
      await form.getByRole('button',{name:'Retry allocated stock move'}).click()
      await expect.poll(() => requests.length).toBe(2);expect(requests[0]).toEqual(requests[1])
    }
    await expect(form.getByRole('status')).toContainText('Allocated stock moved.')
    await expect(panel.getByRole('row',{name:/STAGING/})).toContainText('3')
    expect(requests[0]).toMatchObject({projectId,workOrderId:'wo-pick',allocationId:'allocation-1',fromInventoryId:'source-1',toLocation:'STAGING',quantity:3})
    expect(moves).toBe(1)
  })
}
