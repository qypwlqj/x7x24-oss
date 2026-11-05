#!/usr/bin/env python3
"""Convert Maven dependency:tree TGF nodes into a scanner-readable CycloneDX inventory.

Run Maven first: mvn -f apps/server-java/pom.xml dependency:tree \
  -DoutputType=tgf -DoutputFile=target/dependencies.tgf
No repository source, credentials or local paths are put into the inventory.
"""
import json, pathlib, sys, urllib.parse, zipfile, io, re
source, target = map(pathlib.Path, sys.argv[1:])
components = {}
for line in source.read_text().splitlines():
    if line == '#': break
    _, gav = line.split(' ', 1)
    bits = gav.split(':')
    if len(bits) < 4: raise ValueError('Unexpected Maven node format')
    group, artifact, kind = bits[:3]
    version = bits[-2] if bits[-1] in {'compile','runtime','test','provided','system','import'} else bits[-1]
    if version == '0.3.0' and group == 'com.x7x24': continue
    name = group + ':' + artifact
    purl = 'pkg:maven/' + urllib.parse.quote(group,safe='.') + '/' + urllib.parse.quote(artifact,safe='') + '@' + urllib.parse.quote(version,safe='')
    components[purl] = {'type':'library','group':group,'name':artifact,'version':version,'purl':purl}
if not components: raise RuntimeError('No resolved Maven dependencies; refuse an empty scan')
jar = pathlib.Path(__file__).resolve().parents[1] / 'apps/server-java/target/x7x24-server.jar'
if jar.exists():
    # Boot packaging injects this helper outside dependency:tree. Inventory actual bytes too.
    known = {c['name']+'-'+c['version']+'.jar' for c in components.values()}
    with zipfile.ZipFile(jar) as built:
        for member in built.namelist():
            name=pathlib.Path(member).name
            if not member.startswith('BOOT-INF/lib/') or not name.endswith('.jar') or name in known: continue
            match=re.fullmatch(r'spring-boot-jarmode-tools-(.+)\.jar',name)
            if not match: raise RuntimeError('Uninventoried packaged library: '+name)
            version=match[1]
            with zipfile.ZipFile(io.BytesIO(built.read(member))) as nested:
                manifest=nested.read('META-INF/MANIFEST.MF').decode()
                if 'Implementation-Version: '+version not in manifest: raise RuntimeError('Injected helper version mismatch')
            purl='pkg:maven/org.springframework.boot/spring-boot-jarmode-tools@'+version
            components[purl]={'type':'library','group':'org.springframework.boot','name':'spring-boot-jarmode-tools','version':version,'purl':purl,'properties':[{'name':'x7x24:origin','value':'Injected by Spring Boot packaging; verified actual nested JAR manifest'}]}
target.parent.mkdir(parents=True,exist_ok=True)
target.write_text(json.dumps({'bomFormat':'CycloneDX','specVersion':'1.5','version':1,'components':sorted(components.values(),key=lambda x:x['purl'])},indent=2)+'\n')
print('Resolved Maven components:',len(components))
