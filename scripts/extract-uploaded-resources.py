#!/usr/bin/env python3
"""Export gameplay image/audio assets from the user-provided 8.057.016 ZIP.
Requires UnityPy==1.25.4. Does not execute anything from the input archive.
"""
import argparse,zipfile,struct,pathlib,json,re,collections
import UnityPy,lz4.block
parser=argparse.ArgumentParser()
parser.add_argument('archive',type=pathlib.Path)
parser.add_argument('output',type=pathlib.Path)
args=parser.parse_args()
z=zipfile.ZipFile(args.archive)
out=args.output;out.mkdir(parents=True,exist_ok=True)
def normalize(b):
 p=b.index(b'\0')+1
 version=b[p:p+4];p+=4
 end=b.index(b'\0',p)+1;vs=b[p:end];p=end
 end=b.index(b'\0',p)+1;engine=b[p:end];p=end
 size,usize,csize,flags=struct.unpack_from('>QIII',b,p);p+=20
 start=(p+15)//16*16
 h=b'UnityFS\0'+version+vs+engine+struct.pack('>QIII',size,csize,usize,flags)
 h+=b'\0'*(start-len(h))
 
 for key in range(256):
  info=bytes(v^key for v in b[start:start+csize])
  try:
   d=lz4.block.decompress(info,uncompressed_size=usize)
   if len(d)==usize and d[:16]==b'\0'*16: return h+info+b[start+csize:]
  except Exception: pass
 raise ValueError('Unsupported bundle directory encoding')
records=[];errors=[]
for n in z.namelist():
 if not n.endswith('.bundle'):continue
 if not any(v in n for v in ['sound_split','playingdlc_common_ui_playing','operatingcommon_card_default','scene_in_001_freetime2d','commonui_commonuiatlas','playingdlc_common_ui_misc']):continue
 folder=out/pathlib.Path(n).stem;folder.mkdir(exist_ok=True)
 try:e=UnityPy.load(normalize(z.read(n)))
 except Exception as ex:errors.append([n,str(ex)]);continue
 for o in e.objects:
  if o.type.name not in ['AudioClip','Sprite','Texture2D']:continue
  try:
   d=o.read(); name=re.sub(r'[^\w.\-()\u4e00-\u9fff]','_',d.m_Name)
   r={'bundle':n,'type':o.type.name,'name':d.m_Name,'id':o.path_id}
   if o.type.name=='AudioClip':
    r['duration']=d.m_Length
    for fn,data in d.samples.items():
     dest=folder/(name+'_'+str(o.path_id)+'_'+re.sub(r'[^\w.\-()]','_',fn));dest.write_bytes(data);r['file']=str(dest.relative_to(out))
   else:
    img=d.image.copy();img.load();dest=folder/(name+'_'+str(o.path_id)+'.png');img.save(dest);r.update(file=str(dest.relative_to(out)),width=img.width,height=img.height)
   records.append(r)
  except Exception as ex:errors.append([n,o.path_id,str(ex)])
 print(pathlib.Path(n).stem,len(records),len(errors),flush=True)
 (out/'index.json').write_text(json.dumps(records,ensure_ascii=False,indent=2))
 (out/'errors.json').write_text(json.dumps(errors,ensure_ascii=False,indent=2))
print('DONE',len(records),len(errors))
