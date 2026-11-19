#!/usr/bin/env python3
# mac 侧 HTTP 中转：手机上 lpac 做 APDU，HTTPS 请求由本机(有网)代发。
# 用法: python3 relay.py 'LPA:1$smdp.example.com$MATCHINGID'
import sys, json, ssl, subprocess, urllib.request, binascii

if len(sys.argv) < 2:
    print("用法: python3 relay.py '<激活码 LPA:1$smdp$matchid>'"); sys.exit(1)
code = sys.argv[1]

# 把激活码写到手机，避免 $ 引号问题
subprocess.run(["adb","shell","sh","-c","cat > /data/local/tmp/lp/code.txt"],
               input=code.encode(), check=True)
subprocess.run(["adb","push","dl.sh","/data/local/tmp/lp/dl.sh"],
               stdout=subprocess.DEVNULL, check=True)
subprocess.run(["adb","shell","chmod","755","/data/local/tmp/lp/dl.sh"], check=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

p = subprocess.Popen(["adb","shell","su","-c","sh /data/local/tmp/lp/dl.sh"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=1)

def do_http(payload):
    url = payload["url"]
    body = binascii.unhexlify(payload.get("tx","") or "")
    req = urllib.request.Request(url, data=body, method="POST")
    for h in payload.get("headers",[]):
        k,_,v = h.partition(":")
        req.add_header(k.strip(), v.strip())
    try:
        r = urllib.request.urlopen(req, context=ctx, timeout=30)
        rcode, rx = r.getcode(), r.read()
    except urllib.error.HTTPError as e:
        rcode, rx = e.code, e.read()
    print(f"  [HTTP] {rcode} {url}", file=sys.stderr)
    return {"type":"http","payload":{"rcode":rcode,"rx":binascii.hexlify(rx).decode()}}

for raw in p.stdout:
    line = raw.decode(errors="replace").strip()
    if not line: continue
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        print("  [lpac] "+line, file=sys.stderr); continue
    if msg.get("type") == "http":
        resp = do_http(msg["payload"])
        p.stdin.write((json.dumps(resp)+"\n").encode()); p.stdin.flush()
    else:
        print("  [lpac] "+json.dumps(msg, ensure_ascii=False), file=sys.stderr)

p.wait()
print("=== lpac 退出码:", p.returncode, "（错误日志: adb shell cat /data/local/tmp/lp/err.log）", file=sys.stderr)
