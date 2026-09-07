# Keycloak login theme

`medical-records/` is a Keycloak login theme that repaints the sign-in, registration,
update-password, error and page-expired screens in the application's own dark glassmorphism, so
signing in does not feel like being handed off to a different product.

```
medical-records/
└── login/
    ├── theme.properties                     parent=keycloak, plus our stylesheet
    ├── messages/messages_en.properties      the "MedicalRecords" wordmark and page title
    └── resources/
        ├── css/medical-records.css          the whole visual override
        └── img/favicon.ico                  the application's favicon
```

It only adds a stylesheet: every FreeMarker template still comes from the built-in `keycloak` theme
via `parent=keycloak`, so a Keycloak upgrade cannot break a screen this theme forgot to copy. The
tokens at the top of `medical-records.css` are the same values as
`src/main/resources/static/style.css` — **change them together.**

## How it is wired up

| Piece | Where |
|---|---|
| Theme files reach the container | bind mount in `docker-compose.yml` → `/opt/keycloak/themes/medical-records` |
| The realm selects it | `"loginTheme": "medical-records"` in `realm-export.json` |

`/opt/keycloak/themes` holds nothing but a README in the distribution (the built-in themes live
inside `org.keycloak.keycloak-themes-*.jar`), so mounting one directory in beside it is safe.

## Editing it

`start-dev` disables theme caching, so a browser refresh is enough — no restart:

```bash
docker compose restart keycloak   # only needed after changing theme.properties or messages
```

## Applying it to a realm that already exists

`--import-realm` only imports a realm that is **not** already there, so an existing container keeps
whatever login theme it had. Either recreate Keycloak (this wipes its H2 database — every account
created since the last import, including self-registrations, goes with it):

```bash
docker compose up -d --force-recreate keycloak
```

or set it on the running realm, which keeps the accounts:

```bash
docker exec medical_records_keycloak /opt/keycloak/bin/kcadm.sh update realms/medical-records -s loginTheme=medical-records -s displayName=MedicalRecords --no-config --server http://localhost:8080 --realm master --user admin --password admin
```

Admin console → *Realm settings* → *Themes* → *Login theme* does the same thing by hand.

## What is not themed

The **account console** (`/realms/medical-records/account`) still uses Keycloak's own look. It is a
separate React application rather than a set of server-rendered pages, this application never links
to it, and theming it would be a much larger job for a screen no user here reaches.
