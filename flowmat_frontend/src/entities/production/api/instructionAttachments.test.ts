import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { uploadInstructionAttachment, downloadInstructionAttachment, readAttachments } from './instructionAttachments'
const auth=vi.hoisted(()=>({ getAccess:vi.fn(()=> 'test-access'), refresh:vi.fn() }))
vi.mock('../../auth/lib/authSession',()=>({ tokenStorage:{getAccess:auth.getAccess,getSessionVersion:()=>0}, refreshAccessToken:auth.refresh }))
const id='00000000-0000-4000-8000-000000000001', instructionId='instruction'
const row={attachmentId:id,instructionId,fileName:'Guard.txt',contentType:'text/plain',sizeBytes:5,sha256:'a'.repeat(64),createdBy:'owner',createdAt:'2026-10-09T00:00:00Z'}
const envelope=(data:unknown,status=200)=>new Response(JSON.stringify({success:status===200,data,message:'storage unavailable'}),{status,headers:{'Content-Type':'application/json'}})
const fetchMock=vi.fn()
beforeEach(()=>{ vi.stubGlobal('fetch',fetchMock); auth.refresh.mockResolvedValue(true) })
afterEach(()=>{vi.unstubAllGlobals();vi.resetAllMocks()})
it('sends multipart bytes with the caller UUID and no JSON content type',async()=>{
 fetchMock.mockResolvedValue(envelope(row));const file=new File(['Guard'],'Guard.txt',{type:'text/plain'})
 expect(await uploadInstructionAttachment(instructionId,{attachmentId:id,file})).toEqual(row)
 const [url,options]=fetchMock.mock.calls[0]
 expect(url).toBe(`/api/work-instructions/${instructionId}/attachments/${id}`)
 expect(options.method).toBe('PUT');expect(options.body).toBeInstanceOf(FormData)
 expect(options.body.get('file')).toBe(file);expect(options.headers).not.toHaveProperty('Content-Type')
})
it('refreshes once and repeats the same multipart payload',async()=>{
 fetchMock.mockResolvedValueOnce(envelope(null,401)).mockResolvedValueOnce(envelope(row))
 await uploadInstructionAttachment(instructionId,{attachmentId:id,file:new File(['Guard'],'Guard.txt',{type:'text/plain'})})
 expect(auth.refresh).toHaveBeenCalledTimes(1);expect(fetchMock).toHaveBeenCalledTimes(2)
 expect(fetchMock.mock.calls[0][1].body).toBe(fetchMock.mock.calls[1][1].body)
})
it('does not automatically repeat an unconfirmed upload',async()=>{
 fetchMock.mockResolvedValue(envelope(null,503))
 await expect(uploadInstructionAttachment(instructionId,{attachmentId:id,file:new File(['Guard'],'Guard.txt')})).rejects.toMatchObject({httpStatus:503})
 expect(fetchMock).toHaveBeenCalledTimes(1)
})
it.each([null,{...row,attachmentId:'wrong'},{...row,instructionId:'other'},{...row,sizeBytes:9},{...row,fileName:'other.txt'}])('rejects incorrect acknowledgement %j',async data=>{
 fetchMock.mockResolvedValue(envelope(data))
 await expect(uploadInstructionAttachment(instructionId,{attachmentId:id,file:new File(['Guard'],'Guard.txt')})).rejects.toThrow('response is invalid')
})
it('reads metadata only for the selected revision',()=>{
 expect(readAttachments([row],instructionId)).toEqual([row])
 expect(()=>readAttachments([{...row,instructionId:'other'}],instructionId)).toThrow('response is invalid')
})
it('downloads authenticated bytes and rejects truncated content',async()=>{
 fetchMock.mockResolvedValueOnce(new Response('Guard',{headers:{'Content-Type':'text/plain'}}))
 expect((await downloadInstructionAttachment(row)).size).toBe(5)
 fetchMock.mockResolvedValueOnce(new Response('bad',{headers:{'Content-Type':'text/plain'}}))
 await expect(downloadInstructionAttachment(row)).rejects.toThrow('incomplete')
})
