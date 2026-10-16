# Blinder

Redirecteur de liens courts (Spring Boot 4 / Java 25) avec protection des liens
par appareil autorisé, IP autorisée ou mot de passe.

## Déploiement derrière un reverse proxy

Blinder lit l'IP client, l'hôte et le schéma dans les en-têtes `X-Forwarded-*`
(Tomcat `RemoteIpValve`), mais **uniquement** quand la connexion TCP vient d'un
proxy de confiance.

- `server.tomcat.remoteip.internal-proxies` : expression régulière des adresses
  de proxys de confiance. Par défaut, seul le loopback (`127.x.x.x`, `::1`).
  Derrière un proxy Docker, élargissez-la au réseau du proxy, par exemple pour
  `172.18.0.0/16` :

  ```
  SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=172\\.18\\.\\d{1,3}\\.\\d{1,3}
  ```

- Le proxy **doit écraser** (et non compléter) `X-Forwarded-For`,
  `X-Forwarded-Host` et `X-Forwarded-Proto` avec les valeurs qu'il a observées.
  Un client ne doit jamais pouvoir les fournir lui-même.

### Conséquences d'une mauvaise configuration

- Proxy absent de `internal-proxies` : tous les clients partagent l'IP du proxy.
  Un seul attaquant peut déclencher le 429 pour tout le monde (liens à mot de
  passe, connexion admin) ; les listes d'IP autorisées ne correspondent jamais.
- `X-Forwarded-Proto` absent ou non écrasé : la chaîne de synchronisation des
  appareils passe en `http` et les cookies perdent l'attribut `Secure`.
- En-têtes ajoutés au lieu d'être écrasés : l'IP client est falsifiable, donc
  les limites de débit et les listes d'IP autorisées sont contournables.

## Protection des liens

Un lien peut combiner appareils autorisés, IP/CIDR autorisées et mot de passe :
un seul moyen valide suffit. Un lien protégé par appareils reste fermé même si
tous ses appareils sont révoqués (il ne s'ouvre qu'avec un autre moyen configuré,
sinon à personne) ; décocher tous les appareils dans le panel retire la protection.

Compromis acceptés :

- La limite agrégée par lien (50 échecs / 15 min) peut être maintenue par un
  attaquant distribué : les visiteurs qui n'ont que le mot de passe reçoivent
  alors un 429, tandis que les appareils autorisés et les IP autorisées ne sont
  pas affectés.
- La connexion admin est limitée par client uniquement : un mot de passe admin
  fort est donc indispensable.
