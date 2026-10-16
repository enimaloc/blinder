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
  # variable d'environnement (barres obliques simples) :
  SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES='172\.18\.\d{1,3}\.\d{1,3}'
  # application.properties (barres obliques doublées) :
  server.tomcat.remoteip.internal-proxies=172\\.18\\.\\d{1,3}\\.\\d{1,3}
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
sinon à personne) ; seule la case « Réserver aux appareils autorisés » du formulaire de lien retire cette protection (décocher les appareils ne suffit pas).

Compromis acceptés :

- La limite agrégée par lien (50 échecs / 15 min) peut être maintenue par un
  attaquant distribué : les visiteurs qui n'ont que le mot de passe reçoivent
  alors un 429, tandis que les appareils autorisés et les IP autorisées ne sont
  pas affectés.
- La connexion admin est limitée par client uniquement : un mot de passe admin
  fort est donc indispensable.

## Statistiques

Chaque visite d'un lien (redirigée, refusée, défi, aperçu) est consignée de façon
asynchrone : le chemin de redirection n'attend jamais la base. Les visites passent
par une file mémoire (`blinder.stats.queue-capacity`) vidée par un thread d'écriture
au plus toutes les `blinder.stats.flush-interval`. Si la file est pleine, la visite
est abandonnée et le compteur « visites perdues » du tableau de bord augmente.

- Tableau de bord global : `/_/stats` ; par lien : `/_/links/{id}/stats` (période
  7, 30, 90 ou 365 jours).
- Export CSV : `/_/stats.csv` (tous les liens) ou `/_/links/{id}/stats.csv`, avec le
  paramètre `days`. Les cellules ressemblant à une formule de tableur sont neutralisées.
- Rétention : `blinder.stats.retention` (défaut `90d`, `0` = illimitée). Une purge
  quotidienne supprime les visites détaillées plus anciennes ; les totaux journaliers
  restent.

### RGPD

L'IP et le User-Agent des visiteurs sont enregistrés **en clair** : ce sont des
données personnelles. Choisissez une rétention adaptée, informez les visiteurs de
cette collecte et sachez que supprimer un lien efface toutes ses visites (c'est le
moyen de répondre à une demande d'effacement).

## Géolocalisation

Optionnelle. Créez un compte MaxMind gratuit, téléchargez la base **GeoLite2-City**
(pays et ville) ou **GeoLite2-Country** (pays) au format `.mmdb`, puis indiquez son
chemin :

```
blinder.geoip.database=/var/lib/geoip/GeoLite2-City.mmdb
```

Sans cette propriété, aucune géolocalisation n'est faite. Un chemin illisible
provoque un avertissement au démarrage. MaxMind publie la base plusieurs fois par
semaine : remplacez le fichier régulièrement (puis redémarrez l'application). Les
adresses non publiques n'ont pas de position.

## Notifications

Un lien peut notifier ses propriétaires par des canaux (menu Notifications) :
chaque visite, seuil de visites, épuisement, expiration, refus répétés. Les envois
sont faits hors transaction, par un répartiteur toutes les 15 s ; un échec est
retenté après 1 min, 5 min, 30 min puis 2 h, et devient `FAILED` au 5e échec (bouton
« Relancer » dans l'interface).

### E-mail

Configuration SMTP standard de Spring Boot, par exemple :

```
spring.mail.host=smtp.example.com
spring.mail.port=587
spring.mail.username=...
spring.mail.password=...
blinder.notifications.mail-from=blinder@example.com
```

Sans `spring.mail.host`, les envois e-mail échouent avec « SMTP non configuré ».

Les connexions SMTP sont limitées à 5 s (connexion, lecture, écriture) et les tâches
planifiées s'exécutent sur un pool de 3 threads (`spring.task.scheduling.pool.size`).

### Webhooks

Requête `POST` JSON (`User-Agent: BlinderWebhook/1.0`, succès = 2xx, pas de
redirection suivie, délai de 5 s). En-têtes :

- `X-Blinder-Event` : `VISIT`, `THRESHOLD`, `EXHAUSTED`, `EXPIRED`, `DENIALS` ou `TEST` ;
- `X-Blinder-Delivery` : identifiant de l'envoi (identique entre les essais) ;
- `X-Blinder-Timestamp` : secondes depuis l'epoch ;
- `X-Blinder-Signature` : `sha256=` + HMAC-SHA256 hexadécimal de
  `<timestamp>.<corps>` avec le secret du canal (affiché dans le formulaire du canal).

Livraison au moins une fois : dédupliquer sur `X-Blinder-Delivery`.

Limites connues : le temps de résolution DNS n'est pas couvert par le délai de 5 s ;
des visites peuvent être perdues si la file déborde (compteur affiché dans les
statistiques) ; un lien supprimé en cours de lot fait perdre les visites de ce lot.

Vérification en shell (`BODY` = corps brut reçu) :

```sh
expected="sha256=$(printf '%s.%s' "$TIMESTAMP" "$BODY" \
  | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')"
[ "$expected" = "$SIGNATURE" ] && echo valide
```

ou en Python :

```python
import hmac, hashlib
expected = "sha256=" + hmac.new(secret.encode(), f"{timestamp}.{body}".encode(),
                                hashlib.sha256).hexdigest()
valide = hmac.compare_digest(expected, signature)
```

Pensez à rejeter les horodatages trop anciens pour éviter le rejeu. La charge utile
contient aussi `text` (Slack) et `content` (Discord) : une URL de webhook entrant
Slack ou Discord fonctionne donc telle quelle (sans vérification de signature de
leur côté). Elle ne contient jamais l'URL cible ni de mot de passe, seulement l'URL
courte.

Par défaut, les cibles qui résolvent vers une adresse privée ou locale sont
refusées. Cochez `allowPrivateNetwork` sur le canal pour viser un service interne.

### Réarmement des alertes

Une alerte déjà déclenchée (épuisé, expiré, seuil) n'est réarmée que par une
modification qui la concerne, et non plus par n'importe quelle modification :

- `EXHAUSTED` : changement de `maxUses`, remise à zéro du compteur, ou alerte
  « épuisé » qui passe de désactivée à activée ;
- `EXPIRED` : changement de `notBefore`, `expiresAt` ou du TTL après première
  utilisation, remise à zéro du compteur (le TTL repart), ou alerte activée ;
- `THRESHOLD` : changement du seuil, remise à zéro du compteur, ou alerte activée.

Tout autre changement (cible, tags, métadonnées, protections…) ne réarme rien.

### Propriétés

| Propriété | Défaut | Rôle |
|---|---|---|
| `blinder.stats.retention` | `90d` | Conservation des visites détaillées (`0` = illimitée) |
| `blinder.stats.queue-capacity` | `10000` | Taille de la file d'écriture des visites |
| `blinder.stats.flush-interval` | `1s` | Délai maximal avant écriture d'un lot |
| `blinder.stats.writer-autostart` | `true` | Démarre le thread d'écriture (désactivé dans les tests) |
| `blinder.geoip.database` | vide | Chemin de la base GeoLite2 `.mmdb` |
| `blinder.notifications.mail-from` | `blinder@localhost` | Expéditeur des e-mails |
| `blinder.notifications.denials.threshold` | `10` | Refus déclenchant l'alerte |
| `blinder.notifications.denials.window` | `10m` | Fenêtre glissante de comptage des refus |
| `blinder.notifications.denials.cooldown` | `1h` | Silence après une alerte de refus |
| `blinder.scheduling.enabled` | `true` | Active les tâches planifiées (purge, envois, expiration) |
| `spring.mail.*` | vide | Serveur SMTP |

## Tags

Les tags classent les liens. Un nom a 1 à 40 caractères (espaces rognés, pas de
virgule), sans distinction de casse ; la couleur est facultative (`#rrggbb`). Dans
le formulaire d'un lien, le champ « tags » prend des noms séparés par des virgules
(20 au plus) : les noms inconnus sont créés à la volée, sans couleur.

- Page « Tags » (`/_/tags`) : liste avec le nombre de liens, renommage, couleur,
  suppression (le tag est retiré des liens, les liens restent).
- La liste des liens se filtre par `?tag=<id>` (combinable avec `?domain=`).
- Le tableau de bord global des statistiques et son export CSV se filtrent par
  `?tag=<id>` : ils agrègent les liens portant ce tag.

## QR codes

Chaque lien a un QR code (correction d'erreur M, noir sur blanc) qui encode son URL
publique. Dans le panel : `/_/links/{id}/qr.svg` et `/_/links/{id}/qr.png?size=`
(128 à 2048 px, 512 par défaut) ; `?download` force le téléchargement. La fiche du
lien affiche le QR et les liens de téléchargement. Les réponses ne sont pas mises
en cache (`Cache-Control: no-store`). L'API expose les mêmes images (voir ci-dessous).

## API REST

L'API vit sous `/_/api/v1`, quel que soit l'hôte, et parle JSON. Elle utilise les
mêmes services que le panel : mêmes règles, mêmes messages (en français). Spring
Data REST a été retiré : l'API est écrite à la main et n'expose jamais les entités brutes.

### Jeton d'API

1. Connectez-vous au panel, ouvrez « Jetons d'API » (`/_/tokens`), saisissez un nom
   (1 à 100 caractères) et créez le jeton.
2. Le jeton complet (`bl_` + 43 caractères) n'est affiché **qu'une seule fois** ;
   seul son hachage SHA-256 est conservé. Perdu, il se révoque et se recrée.
3. Envoyez-le dans chaque requête :

```sh
export BLINDER=https://go.example.com/_/api/v1
export TOKEN=bl_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
curl -H "Authorization: Bearer $TOKEN" "$BLINDER/domains"
```

La session du panel n'authentifie **pas** l'API : seul `Authorization: Bearer bl_...`
est accepté. Changer le mot de passe du compte ne révoque pas les jetons (révoquez-les
explicitement dans la page). La date de dernière utilisation est mise à jour au plus
une fois par minute.

Un en-tête absent, mal formé ou un jeton inconnu donne un 401 avec
`WWW-Authenticate: Bearer`. Après 20 jetons refusés par IP en 15 minutes
(`blinder.rate-limit.api-auth.max-failures` et `.window`), l'API répond 429 avec
`Retry-After`, même pour un jeton valide ; un succès ne remet pas le compteur à zéro.

| Propriété | Défaut | Rôle |
|---|---|---|
| `blinder.rate-limit.api-auth.max-failures` | `20` | Jetons d'API refusés par IP avant le 429 |
| `blinder.rate-limit.api-auth.window` | `15m` | Fenêtre de comptage des jetons refusés |

### Erreurs

Les erreurs sont des `application/problem+json` (RFC 9457) :

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Données invalides",
 "instance":"/_/api/v1/links","errors":{"targetUrl":"URL http(s) absolue requise"}}
```

`instance` est absent des 401 et 429, écrits avant d'atteindre l'API.

- 400 : valeur invalide (`errors` donne un message par champ), corps illisible,
  identifiant invalide ou inconnu **dans le corps JSON** (domaine, canal, appareil) ;
  un slug déjà pris donne `errors.slug` ; un champ inconnu (faute de frappe) donne
  `errors.<champ>` = « Champ inconnu » (les champs en lecture seule d'une représentation
  lue sont ignorés).
- 401 / 429 : voir ci-dessus.
- 404 : identifiant inconnu ou appartenant à un autre propriétaire **dans le chemin ou
  la requête** (`/links/{id}`, `?tag=`, `?domain=`, `?link=`...).
- 409 : `version` fournie différente de la version courante, modification concurrente,
  ou course entre deux créations du même slug.

Les dates sont en ISO-8601 avec décalage (`2026-10-16T14:30:00+02:00`), dans le fuseau
de l'administrateur.

### Domaines

```sh
curl -H "Authorization: Bearer $TOKEN" "$BLINDER/domains"
```

```json
[{"id":"6f1c0a52-5a53-4d1e-9d2e-0b0f6a1f2c11","host":"go.example.com"}]
```

### Liens

`GET /links?domain=&tag=&q=&page=0&size=50` : `size` au plus 100, `q` filtre sans casse
sur le slug ou l'URL cible, tri par création décroissante.

```sh
curl -H "Authorization: Bearer $TOKEN" "$BLINDER/links?tag=$TAG_ID&q=promo&size=20"
```

```json
{"items":[{"id":"...","slug":"promo"}],"page":0,"size":20,"total":1}
```

`GET /links/{id}` renvoie la représentation d'un lien :

```json
{
  "id": "0b8a4c7e-1f43-4c0e-8d57-5a1f8d3b2e90",
  "domainId": "6f1c0a52-5a53-4d1e-9d2e-0b0f6a1f2c11",
  "domain": "go.example.com",
  "slug": "promo",
  "url": "https://go.example.com/promo",
  "targetUrl": "https://example.com/promo",
  "enabled": true,
  "notBefore": null, "expiresAt": null, "ttlMinutes": null,
  "maxUses": 100, "useCount": 0,
  "deniedUrl": null,
  "hasPassword": false,
  "ipAllowlist": [], "deviceProtected": false, "deviceIds": [],
  "redirectStatus": 302, "forwardQuery": false,
  "interstitial": false, "interstitialSeconds": 0,
  "metaMode": "NONE", "metaTitle": null, "metaDescription": null, "metaImageUrl": null,
  "tags": ["campagne"],
  "notifications": {"exhausted": true, "expired": false, "denials": false,
                    "eachVisit": true, "threshold": null, "channelIds": ["..."]},
  "status": "ACTIVE",
  "version": 0,
  "createdAt": "2026-10-16T14:30:00+02:00",
  "updatedAt": "2026-10-16T14:30:00+02:00"
}
```

Le mot de passe n'est **jamais** renvoyé : `hasPassword` indique seulement s'il existe.
`id`, `domain`, `url`, `useCount`, `hasPassword`, `status`, `createdAt` et `updatedAt`
sont en lecture seule et ignorés à l'écriture : une représentation lue peut être
renvoyée telle quelle.

`POST /links` crée un lien (201, en-tête `Location`, corps = représentation). Les
champs absents prennent les valeurs par défaut du formulaire ; les tags inconnus sont
créés, et le lien et ses réglages de notification sont enregistrés dans une seule transaction :

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"domainId":"6f1c0a52-5a53-4d1e-9d2e-0b0f6a1f2c11","slug":"promo",
       "targetUrl":"https://example.com/promo","tags":["campagne"],
       "notifications":{"eachVisit":true,"channelIds":["..."]}}' \
  "$BLINDER/links"
```

`PUT /links/{id}` remplace le lien : un champ absent reprend sa valeur par défaut, à
une exception près, le mot de passe. Sans `password`, le mot de passe actuel est
conservé ; `"password": "..."` le remplace et `"removePassword": true` le supprime.

```sh
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"domainId":"6f1c0a52-5a53-4d1e-9d2e-0b0f6a1f2c11","slug":"promo",
       "targetUrl":"https://example.com/v2","removePassword":true}' \
  "$BLINDER/links/$LINK_ID"
```

`PATCH /links/{id}` ne change que les champs présents (fusion JSON sur la
représentation courante ; `null` efface une valeur facultative ; `notifications` est
fusionné champ par champ) :

```sh
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"targetUrl":"https://example.com/v2"}' "$BLINDER/links/$LINK_ID"
```

Si `version` est fourni et diffère de la version courante : 409. Seul un
changement qui les concerne réarme les alertes (voir « Notifications »).

- `DELETE /links/{id}` : 204, supprime le lien et ses visites.
- `POST /links/{id}/reset` : 204, remet le compteur d'utilisations à zéro.
- `GET /links/{id}/qr.svg` et `GET /links/{id}/qr.png?size=512` : le QR code.

```sh
curl -X DELETE -H "Authorization: Bearer $TOKEN" "$BLINDER/links/$LINK_ID"
curl -H "Authorization: Bearer $TOKEN" -o promo.png "$BLINDER/links/$LINK_ID/qr.png?size=512"
```

### Statistiques

`GET /stats?link=&tag=&days=30` : `link` et `tag` s'excluent (les deux : 400) ;
`days` vaut 7, 30, 90 ou 365 (toute autre valeur : 30). `byOutcome` utilise les noms `REDIRECTED`, `PREVIEWED`, `CHALLENGED`, `PASSWORD_REJECTED`, `DENIED`, `THROTTLED`. `topLinks` n'est présent que sans `link`.

```sh
curl -H "Authorization: Bearer $TOKEN" "$BLINDER/stats?tag=$TAG_ID&days=7"
```

```json
{
  "days": 7,
  "totals": {"total": 1, "byOutcome": {"REDIRECTED": 1}, "uniqueIps": 1, "bots": 0},
  "daily": [{"day": "2026-10-16", "redirected": 1, "refused": 0, "other": 0}],
  "countries": [], "referers": [], "browsers": [], "os": [],
  "topLinks": [{"label": "go.example.com/promo", "count": 1}]
}
```

### Tags

`GET /tags` liste les tags avec leur nombre de liens ; `POST /tags` en crée un (201) ;
`PATCH /tags/{id}` modifie `name` et/ou `color` ; `DELETE /tags/{id}` répond 204
(les liens sont conservés).

```sh
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"campagne","color":"#ff8800"}' "$BLINDER/tags"
curl -X PATCH -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"color":""}' "$BLINDER/tags/$TAG_ID"
```

```json
{"id":"3d2f6c1a-8e4b-4a5c-9b7d-1c2e3f4a5b6c","name":"campagne","color":"#ff8800","links":0}
```

Dans un PATCH de tag, `"color": ""` ou `null` retire la couleur ; un champ absent
reste inchangé.
