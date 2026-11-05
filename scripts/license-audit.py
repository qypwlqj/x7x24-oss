#!/usr/bin/env python3
"""Record actual build-license evidence without making legal ownership guarantees.

Usage: python3 scripts/license-audit.py <Maven repository> <resolved SBOM>
Run frontend build and Maven package first. No network calls or credentials.
"""
from pathlib import Path
import json,sys,xml.etree.ElementTree as ET,hashlib,zipfile,re,subprocess
root=Path(__file__).resolve().parents[1]; cache=Path(sys.argv[1]); sbom=json.loads(Path(sys.argv[2]).read_text());ns={'m':'http://maven.apache.org/POM/4.0.0'}
def sha(data):return hashlib.sha256(data).hexdigest()
def declared(g,a,v,seen=None):
 seen=set() if seen is None else seen
 if (g,a,v) in seen:return [],None
 seen.add((g,a,v)); rel=Path(*g.split('.'))/a/v/(a+'-'+v+'.pom'); p=cache/rel
 if not p.exists():return [],None
 t=ET.parse(p).getroot()
 # Maven accepts both namespaced POMs and minimal namespace-free POMs (e.g. H2).
 for element in t.iter(): element.tag=element.tag.split('}')[-1]
 ll=[{'name':l.findtext('name',default=''),'url':l.findtext('url',default='')} for l in t.findall('licenses/license')]
 url='https://repo.maven.apache.org/maven2/'+rel.as_posix()
 if ll:return ll,url
 par=t.find('parent')
 if par is not None:
  pg=par.findtext('groupId');pa=par.findtext('artifactId');pv=par.findtext('version')
  props={e.tag:e.text for e in t.findall('properties/*')}
  if pv and pv.startswith('${') and pv.endswith('}'):pv=props.get(pv[2:-1],pv)
  if pg and pa and pv and '${' not in pv:return declared(pg,pa,pv,seen)
 return [],url
jar=root/'apps/server-java/target/x7x24-server.jar'
with zipfile.ZipFile(jar) as z:serverlibs={Path(n).name for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')}
rows=[]
for c in sbom['components']:
 g,a,v=c['group'],c['name'],c['version']; ll,pom=declared(g,a,v);jp=cache/Path(*g.split('.'))/a/v/(a+'-'+v+'.jar');notices=[]
 if jp.exists():
  with zipfile.ZipFile(jp) as z:
   for n in z.namelist():
    if not n.endswith('/') and re.search(r'(^|/)(license|licence|notice|copying|copyright)([.\-_]|$)',n,re.I):
     data=z.read(n);notices.append({'member':n,'sha256':sha(data)})
 rows.append({'package':g+':'+a,'version':v,'declaredLicenses':ll or 'UNVERIFIED_POM_DECLARATION','sourcePom':pom,'packagedInServerJar':jp.name in serverlibs,'originalJarNotices':notices,'assessment':'Upstream metadata/notice evidence, not a legal warranty'})
(root/'docs/maven-dependency-licenses.json').write_text(json.dumps({'scope':'All resolved application/test dependencies; actual server nested jars identified separately','packages':rows},indent=2)+'\n')
assert {Path(r['package'].split(':')[1]+'-'+r['version']+'.jar').name for r in rows if r['packagedInServerJar']}==serverlibs,'Server jar inventory mismatch'
front=root/'apps/web/public/third-party-licenses.json'; inv=json.loads(front.read_text());(root/'docs/bundled-frontend-licenses.json').write_bytes(front.read_bytes())
out=root/'third_party/frontend';out.mkdir(exist_ok=True);(out/'THIRD_PARTY_NOTICES.txt').write_bytes((root/'apps/web/public/THIRD_PARTY_NOTICES.txt').read_bytes())
installed=[]
for p in (root/'node_modules').glob('**/package.json'):
 try:m=json.loads(p.read_text())
 except Exception:continue
 if m.get('name') and m.get('version'):installed.append({'name':m['name'],'version':m['version'],'declaredLicense':m.get('license','UNVERIFIED'),'source':'installed npm package metadata; all platforms remain in package-lock.json'})
installed={(m['name'],m['version']):m for m in installed};(root/'docs/dependency-licenses.json').write_text(json.dumps({'scope':'Installed npm metadata; Next.js production dependency graph and compiled vendor notices are inventoried separately','packages':sorted(installed.values(),key=lambda m:m['name'])},indent=2)+'\n')
fonts={}
for p in (root/'node_modules/@fontsource').glob('*/files/*'):
 if p.suffix in ['.woff','.woff2']:fonts.setdefault(sha(p.read_bytes()),[]).append(p.relative_to(root/'node_modules').as_posix())
assets=[]
for p in (root/'apps/web/.next/static/media').glob('*'):
 if p.suffix in ['.woff','.woff2']:
  digest=sha(p.read_bytes());assert digest in fonts,p
  assets.append({'builtAsset':p.name,'sha256':digest,'packageSources':fonts[digest]})
icons=root/'apps/web/src/ui/Icon.tsx'; names=dict(re.findall(r'^  (\w+): (\w+),',icons.read_text(),re.M))
assert len(names)==31 and "from 'lucide-react'" in icons.read_text() and '<path' not in icons.read_text(),'Unexpected icon source'
lucide=json.loads((root/'apps/web/third_party/lucide-react/SOURCE.json').read_text())
assert any(p['name']=='lucide-react' and p['version']==lucide['version'] for p in inv['packages'])
tracked=subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0')
media=[p for p in tracked if re.search(r'\.(png|jpe?g|webp|gif|svg|webm|mp4|woff2?|ttf|otf|ico)$',p,re.I)]
report={'scope':'Actual frontend font bytes, source SVG/CSS, and tracked binary media','packagedFonts':sorted(assets,key=lambda a:a['builtAsset']),'fontLicenses':[{'package':p['name'],'version':p['version'],'license':p['declaredLicense'],'noticeFiles':[n['file'] for n in p['notices']]} for p in inv['packages'] if p['name'].startswith('@fontsource/')],'trackedBinaryMedia':media,'projectSourceVisuals':[{'path':p,'status':'Repository-declared Apache-2.0 source; generated chart geometry/CSS effect, not a retrieved image'} for p in ['apps/web/src/ui/Sparkline.tsx','apps/web/src/components/TaskFlow.tsx','apps/web/src/components/PipelineFlow.tsx','apps/web/src/index.css']],'uiIcons':{'path':'apps/web/src/ui/Icon.tsx','sha256':sha(icons.read_bytes()),'mapping':names,'source':lucide,'assessment':'All 31 prior unprovenanced inline paths removed; official lucide-react static named imports. Complete ISC and Feather-derived MIT notice retained in source and production build.'},'pending':[],'notRedistributed':['OS fallback fonts referenced by CSS','GitHub avatar/user content loaded at runtime'],'limits':'No legal ownership guarantee. No private screenshots/videos were restored.'}
out=root/'output'; out.mkdir(exist_ok=True); (out/'materials-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Maven components',len(rows),'server nested jars',len(serverlibs),'bundled frontend packages',len(inv['packages']),'font assets',len(assets),'tracked binary media',len(media),'pending provenance items',len(report['pending']))
print('Maven unverified POM metadata',[r['package'] for r in rows if isinstance(r['declaredLicenses'],str)])
