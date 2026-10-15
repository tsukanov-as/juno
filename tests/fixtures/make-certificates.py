#!/usr/bin/env python3
"""Local test-only CA/server/client PKCS12 fixtures. Password is public: changeit."""
import argparse,subprocess,tempfile,shutil,json,hashlib
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--keytool',required=True);a=p.parse_args()
root=Path(__file__).resolve().parent/'tls';root.mkdir(exist_ok=True)
def run(*args):
 r=subprocess.run([a.keytool,*args],capture_output=True,timeout=20)
 if r.returncode:raise RuntimeError(r.stderr.decode())
 return r.stdout.decode()+r.stderr.decode()
with tempfile.TemporaryDirectory(prefix='juno-test-certs-') as d:
 d=Path(d)
 def key(name,cn,ext):
  run('-genkeypair','-alias',name,'-keyalg','RSA','-keysize','2048','-dname','CN='+cn,'-validity','3650','-ext',ext,'-keystore',str(d/(name+'.p12')),'-storetype','PKCS12','-storepass','changeit','-keypass','changeit','-noprompt')
 key('ca','Juno Test CA','bc:c')
 run('-exportcert','-alias','ca','-rfc','-keystore',str(d/'ca.p12'),'-storepass','changeit','-file',str(root/'ca.pem'))
 for name,ext in [('server','eku=serverAuth'),('client','eku=clientAuth')]:
  key(name,'localhost' if name=='server' else 'Juno Test Client',ext)
  if name=='server':shutil.copy(d/'server.p12',root/'selfsigned.p12')
  run('-certreq','-alias',name,'-keystore',str(d/(name+'.p12')),'-storepass','changeit','-file',str(d/(name+'.csr')))
  args=['-gencert','-alias','ca','-keystore',str(d/'ca.p12'),'-storepass','changeit','-infile',str(d/(name+'.csr')),'-outfile',str(d/(name+'.pem')),'-rfc','-validity','3650','-ext',ext]
  if name=='server':args+=['-ext','san=dns:localhost']
  run(*args)
  run('-importcert','-alias','ca','-keystore',str(d/(name+'.p12')),'-storepass','changeit','-file',str(root/'ca.pem'),'-noprompt')
  run('-importcert','-alias',name,'-keystore',str(d/(name+'.p12')),'-storepass','changeit','-file',str(d/(name+'.pem')),'-noprompt')
  shutil.copy(d/(name+'.p12'),root/(name+'.p12'))
result=dict(purpose=__doc__,keytool_version=run('-J-version'),files={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(root.iterdir()) if f.suffix in ('.pem','.p12')})
root.joinpath('provenance.json').write_text(json.dumps(result,indent=2)+'\n')
print('Generated test CA, localhost-only server SAN, clientAuth certificate, and selfsigned server')
