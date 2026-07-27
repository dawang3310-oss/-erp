import { createPrivateKey, sign } from 'node:crypto'

const privateKeyPem = `-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDCM994XVG3Pk5v
NbP++/dVydosjANVMBYSiH1sNDzCRSnjc6F897d2wCyBKM6zm1UhAgJma0dSIJWK
QlKQgUyuCHandG1fv6nC2RyX8N6BqGG0g+6LbnrNfn56BEcP52jsAIjIUhboO8Uo
T67A6HFxaqXdR6EfkM2CqhvdWwrpdrWua47uar6Uzk8LI9jCzGSFEuPvwoTDmpcr
h02ujw9qKtKF0nDLg3wYbF9XIhnjADI+zeKYUqxchvsLqESG8qkf1XIaRS9lIA5v
mh0czUlMSYA5pr2zIAqKgHF++dW+h+DkuMGQeuw60y7Cp+faHeTVs64izof3IZTf
NjoOPGMXAgMBAAECggEAJx1rlUK2gsH56kxWxLTbTFwrpW1N/oqA55q2KGUdF8P2
e+l+TT1XpoNuc1VUzLgsnUlaUmapJtGJTR+uoYxpdWQfAfthTDe+aZBxQekx58uS
YllwYoUOFvWzY8AY0As8BszUwARIuN3RCB2EhTZMIxvn7HnQ6hqwSfEZS6xatkro
zMNVmqAvZQU4C1+/E9MePU09IifKhxvb5vyyPJXKvRFyRgWpjrIJOpfrZI7Tm50A
H3ze4JsT9E55+tCxNMo03QZuBQbSFU2c9jZ+3shsm7RPT+E7c4FUBnCtFDxw++R/
SqX9fUyFlBCsUUu42Ofe9AZYZaLHtfaUMnj/mh8FAQKBgQDnAvHrgl9xEPikplUG
l6a2DfB7cMB+JUnzQRgicJvscH2KIfF1zYE0O+liG/7B3476beEe4jVr9YEAaVAL
tCEgkcgtdCrvx38CjFz22edKAR9kfkHze/uEBkMjOQECURJxuCypzNy8YcHryLpc
YPUWCsF48vwvEDwtHTmKjbYqbwKBgQDXNaKNbhqfpPKUqTvYTis6F/WFrSXGaDLK
9XA7Mp+F6BBIPsyCMyf75woqSxNcJ7xk4M8B0iik89qR4b/wuhVNdQIxf4f1N9sH
eB4FRE74E5o5cJmhyLy8+q8eZDJw5l7gJBEOFLkgobZV3dg/W/ocI0ecSQy8VvLc
kQN41AXF2QKBgQDmCPy1s8EuaePusOMCCYksyHyrrv8/ngohfLR3twLNUsbwAhTb
ZaQ/S1l/JLlufRt3LGt7wW3I71NiAXx/6wMB16kp+f+3fURwWS1JcnrqKmwEOeWa
e99c/I9mR6FFmU1wiCGRhDpaLE5aaCuLSdFD/bniorOUeeoyUmO2IJ9BaQKBgQCZ
Niz4pTc9CpBMt8LMNrJdlGsN9PvcqZfnmB6DdoHNMi5NULAFzWec1ZoODA7HX96m
rsmREU4wSQ8FJoOgXMoHr9KU7KcdM9uyEJjGxR+3SzVTyU8Gt8NugsWjTFAAwnEu
/15I+QXnLlmB/gMS8Gc6Gv/DStpPR1N1JaQkJEXL2QKBgFyVtq3STr5cluzNFyhR
xXiTRd8dRdpOr5lNNLOqwT8N3V1ebss/s9rUwiTnzp90OKfxToX4HXNbfy/jn2u7
GWMfPio6DAP20Agy/guubsntOju+MrunHjec6ZyMcDdnIpM5RcdYH4uN7sgtz5+y
dmwjmDWNPBuIGXDG6GeK6HKp
-----END PRIVATE KEY-----`

export const e2ePublicJwk = {
  kty: 'RSA',
  n: 'wjPfeF1Rtz5ObzWz_vv3VcnaLIwDVTAWEoh9bDQ8wkUp43OhfPe3dsAsgSjOs5tVIQICZmtHUiCVikJSkIFMrgh2p3RtX7-pwtkcl_DegahhtIPui256zX5-egRHD-do7ACIyFIW6DvFKE-uwOhxcWql3UehH5DNgqob3VsK6Xa1rmuO7mq-lM5PCyPYwsxkhRLj78KEw5qXK4dNro8PairShdJwy4N8GGxfVyIZ4wAyPs3imFKsXIb7C6hEhvKpH9VyGkUvZSAOb5odHM1JTEmAOaa9syAKioBxfvnVvofg5LjBkHrsOtMuwqfn2h3k1bOuIs6H9yGU3zY6DjxjFw',
  e: 'AQAB',
  alg: 'RS256',
  use: 'sig',
  kid: 'erp-e2e',
}

function base64Url(value: object) {
  return Buffer.from(JSON.stringify(value)).toString('base64url')
}

export function createE2eJwt() {
  const now = Math.floor(Date.now() / 1000)
  const unsigned = [
    base64Url({ alg: 'RS256', typ: 'JWT', kid: e2ePublicJwk.kid }),
    base64Url({
      sub: 'e2e-product-admin',
      iat: now - 10,
      exp: now + 3600,
      roles: ['PRODUCT_VIEW', 'PRODUCT_ADMIN'],
    }),
  ].join('.')
  const signature = sign('RSA-SHA256', Buffer.from(unsigned), createPrivateKey(privateKeyPem))
  return `${unsigned}.${signature.toString('base64url')}`
}
