import { expect, test } from '@playwright/test'
import { answerAuth, mockedLogin, ok } from './support/mockApi'

for(const scenario of ['partial','released'] as const){
  test(`warehouse reserved pick: ${scenario}`,async({page})=>{
    const projectId='prj-reserved-pick'
    const order={workOrderId:'wo-reserved',projectId,workOrderNumber:'WO-RESERVED',workOrderTitle:'Reserved pick',workOrderStatus:'approved',bomId:'bom-readonly',targetQuantity:1}
    const task={taskId:'task-reserved',projectId,taskNo:'WT-RESERVED',taskType:'pick',status:'open',inventoryId:'reserved-source',itemId:'raw',itemCode:'RAW',itemName:'Material',
      lotId:null,lotNo:null,quantity:6,fromLocation:'SOURCE',toLocation:'STAGING',workOrderId:order.workOrderId,workOrderNumber:order.workOrderNumber,
      note:null,createdBy:'demo-owner',createdAt:'2026-10-05T12:00:00Z',finishedBy:null,finishedAt:null,transferId:null,cancelReason:null,assignedTo:null,allocationId:'allocation-reserved'}
    const tasks=[task];const completeRequests:Record<string,unknown>[]=[]
    await page.route(/^https?:\/\/[^/]+\/api\//,async(route)=>{
      const request=route.request();const url=new URL(request.url());const pathname=url.pathname
      if(await answerAuth(route,pathname))return
      if(pathname==='/api/items')return ok(route,[{itemId:'raw',itemCode:'RAW',itemName:'Material',itemStatus:'active'}])
      if(pathname==='/api/work-orders')return ok(route,[order])
      if(pathname==='/api/inventories')return ok(route,[{inventoryId:'reserved-source',projectId,itemId:'raw',quantity:10,reservedQuantity:6,availableQuantity:4,inventoryStatus:'available',location:'SOURCE',lotId:null}])
      if(pathname==='/api/warehouse-tasks'&&request.method()==='GET')return ok(route,tasks.filter((entry)=>!url.searchParams.get('status')||entry.status===url.searchParams.get('status')))
      if(pathname==='/api/warehouse-tasks/pick-list'){
        expect(request.postDataJSON()).toMatchObject({projectId,workOrderId:'wo-reserved',stagingLocation:'STAGING'})
        return ok(route,{tasks:[task],lines:[{itemId:'raw',itemCode:'RAW',required:6,atStaging:0,alreadyPlanned:0,plannedNow:6,shortage:0}]})
      }
      if(pathname==='/api/warehouse-tasks/task-reserved/complete'){
        const body=request.postDataJSON() as Record<string,unknown>;completeRequests.push(body)
        expect(body.expectedQuantity).toBe(6)
        if(scenario==='released')return route.fulfill({status:409,contentType:'application/json',body:JSON.stringify({success:false,message:'Only this work order’s remaining allocated stock can be picked.',data:null})})
        expect(body.quantity).toBe(2);task.quantity=4
        return ok(route,{...task,taskId:'task-part',taskNo:'WT-PART',status:'done',quantity:2,transferId:'transfer-part'})
      }
      if(request.method()!=='GET')throw new Error(`Unexpected write ${pathname}`)
      return ok(route,[])
    })
    await mockedLogin(page)
    await page.goto(`/projects/${projectId}/inventory?tab=tasks`)
    const picker=page.getByRole('form',{name:'Pick list'})
    await picker.getByLabel('A work order').check()
    await picker.getByRole('combobox',{name:/^Work order/}).selectOption('wo-reserved')
    await picker.getByLabel('Pick to').fill('STAGING')
    await picker.getByRole('button',{name:'Plan picks'}).click()
    const row=page.getByRole('table',{name:'Task list'}).getByRole('row',{name:/WT-RESERVED/})
    await expect(row).toContainText('Reserved for work order')
    if(scenario==='released'){
      await row.getByRole('button',{name:'Done',exact:true}).click()
      await expect(page.getByRole('alert')).toContainText('remaining allocated stock')
      await expect(row).toContainText('open');expect(completeRequests).toHaveLength(1)
    }else{
      await row.getByRole('button',{name:'Part…',exact:true}).click()
      await row.getByLabel('Quantity moved').fill('2')
      await row.getByRole('button',{name:'Move',exact:true}).click()
      await expect(row).toContainText('4')
      await expect(row).toContainText('Reserved for work order');expect(completeRequests).toHaveLength(1)
    }
    // The reserved pick does not consume the four free units available for a normal putaway.
    const putaway=page.getByRole('form',{name:'New putaway'})
    await putaway.getByLabel('Stock record').selectOption('reserved-source')
    await expect(putaway).toContainText('4')
  })
}
