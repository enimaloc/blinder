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

### Webhooks

Requête `POST` JSON (`User-Agent: BlinderWebhook/1.0`, succès = 2xx, pas de
redirection suivie, délai de 5 s). En-têtes :

- `X-Blinder-Event` : `VISIT`, `THRESHOLD`, `EXHAUSTED`, `EXPIRED`, `DENIALS` ou `TEST` ;
- `X-Blinder-Delivery` : identifiant de l'envoi (identique entre les essais) ;
- `X-Blinder-Timestamp` : secondes depuis l'epoch ;
- `X-Blinder-Signature` : `sha256=` + HMAC-SHA256 hexadécimal de
  `<timestamp>.<corps>` avec le secret du canal (affiché dans le formulaire du canal).

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
