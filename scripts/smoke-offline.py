#!/usr/bin/env python3
"""Read-only smoke test for an isolated installation; never starts OAuth or writes data."""
import json, sys, time, urllib.request, urllib.error
base=sys.argv[1].rstrip('/')
api_only='--api-only' in sys.argv[2:]
if not base.startswith(('http://127.0.0.1:','http://localhost:')):
    raise SystemExit('Smoke test only permits explicit loopback targets')
def get(path):
    try:
        with urllib.request.urlopen(base+path,timeout=3) as r:return r.status,r.read().decode()
    except urllib.error.HTTPError as e:return e.code,e.read().decode()
for _ in range(45):
    try:
        if get('/api/health')[0]==200:break
    except OSError:pass
    time.sleep(1)
else:raise SystemExit('Health check never became ready')
assert get('/api/health')[0]==200
for path in ['/api/tasks','/api/me/workspaces','/api/integrations/github','/api/audit']:
    assert get(path)[0]==401,path+' must require authentication'
status,body=get('/api/auth/config'); config=json.loads(body)
assert status==200 and config['required'] is True
assert config['clientId']=='' and config['deviceFlow'] is False and config['webFlow'] is False
checks=['health','unauthenticated API denied','authentication required','no inherited OAuth app']
if not api_only:
    status,body=get('/')
    assert status==200 and '<div id="root">' in body
    assert '/_next/static/' in body
    for path in ['/admin/integrations/connect', '/p/smoke-project/tasks']:
        status,body=get(path)
        assert status==200 and '<div id="root">' in body,path+' App Router deep link'
    checks.append('Next.js frontend and history deep links')
print(json.dumps({'ok':True,'checks':checks,'externalWrites':False}))
