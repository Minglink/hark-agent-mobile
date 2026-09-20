import hashlib, hmac, base64

passphrase = b'correct horse battery staple'
salt = base64.b64decode('AAECAwQFBgcICQoLDA0ODw==')
kek = hashlib.pbkdf2_hmac('sha256', passphrase, salt, 1000, 32)

def hkdf(ikm, info):
    prk = hmac.new(b'', ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b'\x01', hashlib.sha256).digest()

data = hkdf(kek, b'minisbak/data')
sec = hkdf(kek, b'minisbak/secrets')
mac = hkdf(kek, b'minisbak/mac')
ver = hkdf(kek, b'minisbak/verify')

print('dataKey    ', data.hex())
print('secretsKey ', sec.hex())
print('macKey     ', mac.hex())
print('verifierKey', ver.hex())
print('verifier   ', base64.b64encode(hmac.new(ver, b'minisbak-v1', hashlib.sha256).digest()[:16]).decode())
raw = b'{"format":"minisbak/1","backup_id":"vector"}'
print('manifestMac', base64.b64encode(hmac.new(mac, raw, hashlib.sha256).digest()).decode())

