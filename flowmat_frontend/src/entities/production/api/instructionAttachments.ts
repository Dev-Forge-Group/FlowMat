import { tokenStorage, refreshAccessToken } from '../../auth/lib/authSession'
import { normalizeUiError } from '../../../shared/lib/normalizeUiError'
export interface InstructionAttachment {
 attachmentId:string; instructionId:string; fileName:string; contentType:string; sizeBytes:number; sha256:string; createdBy:string|null; createdAt:string|null
}
export interface AttachmentUpload {attachmentId:string; file:File}
const base=(instructionId:string)=>`/work-instructions/${encodeURIComponent(instructionId)}/attachments`
function invalid():never {throw new Error('Attachment response is invalid; reload or retry the same upload.')}
function metadata(value:unknown,instructionId:string):InstructionAttachment {
 if(!value || typeof value!=='object') return invalid()
 const row=value as InstructionAttachment
 if(row.instructionId!==instructionId || typeof row.attachmentId!=='string' || !/^[0-9a-f-]{36}$/.test(row.attachmentId)
  || typeof row.fileName!=='string' || !row.fileName || row.fileName.length>255 || typeof row.contentType!=='string'
  || !Number.isSafeInteger(row.sizeBytes) || row.sizeBytes<=0 || typeof row.sha256!=='string' || !/^[0-9a-f]{64}$/.test(row.sha256)) return invalid()
 return row
}
export function readAttachments(value:unknown,instructionId:string):InstructionAttachment[] {
 if(!Array.isArray(value)) return invalid()
 return value.map(row=>metadata(row,instructionId))
}
function assertCurrentSession(version:number) {
 if(version!==tokenStorage.getSessionVersion()) throw new Error('Your session changed. Please try again.')
}
async function request(path:string,options:RequestInit,version:number,retried=false):Promise<Response> {
 assertCurrentSession(version)
 const headers=new Headers(options.headers),token=tokenStorage.getAccess()
 if(token) headers.set('Authorization',`Bearer ${token}`)
 const response=await fetch('/api'+path,{...options,headers,credentials:'same-origin'})
 assertCurrentSession(version)
 if(response.status===401 && !retried) {
  const refreshed=await refreshAccessToken()
  assertCurrentSession(version)
  if(refreshed) return request(path,options,version,true)
 }
 if(!response.ok) {
  let message='Attachment request failed.'
  try {const body=await response.json();if(typeof body?.message==='string') message=body.message} catch { /* Keep a safe error for proxy HTML responses. */ }
  assertCurrentSession(version)
  throw normalizeUiError(response.status,message)
 }
 return response
}
export async function uploadInstructionAttachment(instructionId:string,input:AttachmentUpload):Promise<InstructionAttachment> {
 const version=tokenStorage.getSessionVersion()
 const form=new FormData();form.append('file',input.file)
 const response=await request(`${base(instructionId)}/${encodeURIComponent(input.attachmentId)}`,{method:'PUT',body:form},version)
 const envelope=await response.json()
 assertCurrentSession(version)
 if(envelope?.success!==true) return invalid()
 const row=metadata(envelope.data,instructionId)
 if(row.attachmentId!==input.attachmentId || row.fileName!==input.file.name || row.sizeBytes!==input.file.size) return invalid()
 return row
}
export async function downloadInstructionAttachment(row:InstructionAttachment):Promise<Blob> {
 const version=tokenStorage.getSessionVersion()
 const response=await request(`${base(row.instructionId)}/${encodeURIComponent(row.attachmentId)}/download`,{method:'GET'},version)
 const blob=await response.blob()
 assertCurrentSession(version)
 if(blob.size!==row.sizeBytes) throw new Error('Attachment download is incomplete. Retry the download.')
 return blob
}
