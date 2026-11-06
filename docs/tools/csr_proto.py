import subprocess, sys

def der_len(n):
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b

def tlv(tag, content):
    return bytes([tag]) + der_len(len(content)) + content

def seq(*parts):  return tlv(0x30, b"".join(parts))
def set_(*parts): return tlv(0x31, b"".join(parts))
def oid(dotted):
    parts = [int(p) for p in dotted.split(".")]
    body = bytes([40 * parts[0] + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.insert(0, 0x80 | (p & 0x7F))
            p >>= 7
        body += bytes(chunk)
    return tlv(0x06, body)

def utf8(s): return tlv(0x0C, s.encode("utf-8"))
def ia5(s):  return tlv(0x16, s.encode("ascii"))

OID_CN          = "2.5.4.3"
OID_EXT_REQ     = "1.2.840.113549.1.9.14"
OID_SAN         = "2.5.29.17"
OID_KU          = "2.5.29.15"
OID_EKU         = "2.5.29.37"
OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"

def build_csr(cn, dns_names, spki_der, sign):
    subject = seq(set_(seq(oid(OID_CN), utf8(cn))))
    san_value = seq(*[tlv(0x82, n.encode("ascii")) for n in dns_names])
    ku_value = tlv(0x03, bytes([0x07, 0x80]))            # BIT STRING: digitalSignature, 7 unused bits
    eku_value = seq(oid(OID_SERVER_AUTH), oid(OID_CLIENT_AUTH))
    extensions = seq(
        seq(oid(OID_SAN), tlv(0x04, san_value)),
        seq(oid(OID_KU), tlv(0x01, b"\xff"), tlv(0x04, ku_value)),
        seq(oid(OID_EKU), tlv(0x04, eku_value)),
    )
    attributes = tlv(0xA0, seq(oid(OID_EXT_REQ), set_(extensions)))
    info = seq(tlv(0x02, b"\x00"), subject, spki_der, attributes)
    signature = sign(info)
    sig_alg = seq(oid(OID_ECDSA_SHA256))
    return seq(info, sig_alg, tlv(0x03, b"\x00" + signature))

if __name__ == "__main__":
    spki = open("spki.der", "rb").read()
    def sign(data):
        open("info.bin", "wb").write(data)
        return subprocess.run(["openssl", "dgst", "-sha256", "-sign", "key.pem", "info.bin"],
                              capture_output=True, check=True).stdout
    csr = build_csr("ai-master-companion", ["companion.local", "localhost"], spki, sign)
    open("out.csr.der", "wb").write(csr)
    print("csr bytes:", len(csr))
