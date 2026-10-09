# Medical Records System

Patient medical-record system built for **CSCB869 Java Web Services** (НБУ) - the *Медицинско досие
на пациента* assignment. A Spring Boot application exposing RESTful services over a MySQL database,
with a browser front end and Keycloak for user management.

---

## Stack

| | |
|---|---|
| Java | 21 (Gradle toolchain) |
| Framework | Spring Boot 3.4.0 - Web, Data JPA, Security, OAuth2 Resource Server, Validation, Thymeleaf |
| Database | MySQL 8.0 |
| Identity | Keycloak 23.0.4 (OIDC; the app is a resource server) |
| Front end | Vanilla JS single page, no build step |
| Tests | JUnit 5, Mockito, Spring Security Test, H2 in memory |

---

## Running it

**Prerequisites:** Docker Compose and JDK 21.

```bash
docker compose up -d
```

This command starts MySQL on `3306` and Keycloak on `8081`. MySQL runs `db/init.sql` then `db/test-data.sql`
on first start, and Keycloak imports `realm-export.json` (if absent). Wait for both to report healthy:

```bash
docker compose ps
```

Then start the application:

```bash
./gradlew bootRun
```

Open **http://localhost:8080** and press *Login*.

Both the medical records and Keycloak's own data live in the MySQL container, in separate schemas
(`medical_records_db` and `keycloak`). Accounts created at runtime therefore survive
`docker compose down`.

### Resetting

The seed scripts run **only when the MySQL volume is created**, so re-running `docker compose up`
will not reload them. To get back to a clean database:

```bash
docker compose down -v && docker compose up -d
```

> **Editing `realm-export.json`.** Since Keycloak has a persistent database, `--import-realm`
> imports only when the realm is *absent*.

### Schema notes

`spring.jpa.hibernate.ddl-auto` is `validate`, not `update`. The schema is owned by
`src/main/resources/db/init.sql`; Hibernate only checks that the entities match it. If you change an
entity, change the DDL too, or the application will refuse to start - which is the intended
behaviour, since a silently altered production schema is worse than a failed boot.

---

## Sign-in accounts

Seeded by `realm-export.json`. Passwords match the role name.

| Username | Password | Role | Sees                                                                                           |
|---|---|---|------------------------------------------------------------------------------------------------|
| `admin` | `admin` | ADMIN | Everything, plus the audit log                                                                 |
| `doctor` | `doctor` | DOCTOR | Dr. Georgi Ivanov - a GP with patients and visits                                              |
| `doctor2` … `doctor5` | `doctor` | DOCTOR | The other practitioners. Doctors 6-8 and patients 16-24 exist as records only, with no account |
| `patient` | `patient` | PATIENT | Ivan Dimitrov - visits, diagnoses, sick leave, fully insured                                   |
| `patient2` … `patient15` | `patient` | PATIENT | The rest of the roster. `patient5` is seeded one month short of insured - a boundary case      |

Self-registration is open: sign up through Keycloak, then the application asks whether you are a
patient or a doctor. Patients are activated immediately; doctors wait for an administrator to
approve them from the **Reports** tab.

Sign-in uses the authorization code flow with **PKCE** (`S256`). `frontend-client` is a public
client, so it has no secret and the token endpoint would otherwise exchange an authorization code
for whoever presented it - and the code travels in a redirect URL, where it can leak through
history, a `Referer` header or a browser extension. PKCE binds the code to the browser that began
the flow. The SPA opts in via `pkceMethod: 'S256'` and the realm sets
`pkce.code.challenge.method=S256` so the server *refuses* an exchange without a verifier; both
halves are needed. The direct access (password) grant is disabled on that client, so credentials
cannot be POSTed straight to the token endpoint, bypassing the login page and everything attached
to it.

### The sign-in pages

Keycloak hosts the login, registration and password-change screens, so those the application does not render itself. `keycloak-theme/medical-records` is a custom login
theme that gives them the application's own dark glassmorphism - the same panel, fields, buttons and
wordmark - so signing in does not read as being handed off somewhere else. It is mounted into the
container by `docker-compose.yml` and selected by `"loginTheme"` in `realm-export.json`; see
[`keycloak-theme/README.md`](keycloak-theme/README.md), which also covers applying it to a realm
that already exists.

---

## Where the assignment's requirements live

### §1 - CRUD

Doctors, patients, diagnoses, visits and sick leaves all support create, read, update and delete.
Prescriptions are included too, covering *"назначава лечение (изписва лекарства)"*.

### §2 - Roles

| Role | Rule |
|---|---|
| Patient | Own visits only |
| Doctor | Reads every patient and doctor; creates and edits **only visits they performed** |
| Admin | Everything |

### §3 - The ten reports

| # | Report | Where | Endpoint |
|---|---|---|---|
| **a** | Patients with a given diagnosis | Reports → card a | `GET /api/reports/diagnoses/patients?diagnosis=` |
| **b** | Times diagnosed per condition | Reports → card b | `GET /api/reports/diagnoses/common` |
| **c** | Patients registered with a given GP | Reports → card c, or the **Patients** button on a GP row | `GET /api/doctors/{id}/patients` |
| **d** | Patients registered per GP | Reports → card d | `GET /api/reports/doctors/gp-patients` |
| **e** | Visits recorded per doctor | Reports → card e | `GET /api/reports/doctors/visits` |
| **f** | Visits for a given patient | Reports → card f | `GET /api/visits/patient/{id}` |
| **g** | Visits in a given date range | Reports → card g | `GET /api/reports/visits/period` |
| **h** | Visits for a given doctor in a date range | Reports → card h | `GET /api/reports/visits/doctor/period` |
| **i** | Sick leaves issued per month | Reports → card i | `GET /api/reports/sickleaves/months` |
| **j** | Sick leaves issued per doctor | Reports → card j | `GET /api/reports/sickleaves/doctors` |

The cards are laid out in the order the assignment lists them and each carries its letter. That
order also groups cleanly by subject - a,b diagnoses; c,d,e doctors; f,g,h visits; i,j sick leaves -
so following the specification and keeping the colour coding are the same arrangement.

**Titles state what a report contains, never what it concludes.** Counts read *"<what is counted>
per <group>"*; filtered lists read *"<what is listed> for a given <filter>"*. So (i) is *Sick Leaves
Issued per Month* rather than "Busiest Months", and (j) is *Sick Leaves Issued per Doctor* rather
than "Doctors Issuing the Most" - both are ranked, and the top row is the answer the assignment
asks for, but the reader is shown the data rather than asked to trust a verdict in a heading.

Report **f** includes sick leave alongside diagnosis and treatment, covering *"излизал ли е в
болнични пациентът и за колко време"*.

**Beyond the ten:** an **Unpaid Months per Patient** report
(`GET /api/reports/patients/insurance`) - the *справка* §1 requires of patient records. It names
*which* of the last six months each patient still owes, listing only those who owe something.
"You are three months short" does not tell a patient what to pay; the months do.

The patient's own dashboard shows the same check for themselves, and both patient lists carry the
verdict as a column, so staff see it without opening anything.

**No month count is reported anywhere.** The rule works on an unbroken run, and a run understates a
patient who paid on either side of a gap - four of six months paid with a hole in the middle scores
one, which reads far worse than their position is. That number needs explaining wherever it appears,
so the verdict decides the question and the months say what is outstanding.

Coverage is an **unbroken run** of paid months, not a tally of six. The two agree on the verdict but
not on anyone else, and the run is the number a person acts on: a patient who stopped paying two
months ago is not covered, however many older months they paid, while one who missed only the oldest
month very nearly is. A count of "three" describes both.

The run is anchored at the current month when it has been paid and at the previous one when it has
not - **the current month is not over, so an unpaid one is not yet a lapse**, exactly as this
month's bill is not yet overdue. Paying simply keeps the run going.

Every report exports to CSV from the button above the results table.

### §4 - Technology

REST services, MySQL, a user interface, Bean Validation on every request DTO, a
`@ControllerAdvice` exception handler, 292 automated tests, and Keycloak for user management.

---

## Architecture

**Layering.** `Controller → Service → Repository → Entity`, with Java records as DTOs at the
boundary. Entities never leave the service layer; every response record has a `fromEntity` factory.

**Authorization runs on two independent planes**, and §2 holds only because both exist:

- **Role gate** - `@PreAuthorize` on controllers answers *"is this a DOCTOR?"*
- **Ownership gate** - `AuthorizationService` inside services answers *"is this **your** visit?"*

A patient cannot reach another patient's record by guessing an id, and a doctor cannot annotate a
colleague's visit, because the second check re-reads the record and compares it against the token
subject. `EndpointAuthorizationMatrixTest` covers the first plane across 100 cases.

**`Visit` is the aggregate root** of the clinical record. Diagnoses, prescriptions and the sick leave
cascade from it, which is why authorization for all of them delegates to the visit's doctor - one
rule, five entry points.

---

## Identity lifecycle

Two systems hold state about a person, and this is the policy that keeps them coherent.

> **Keycloak is the system of record for authentication and roles. The application is the system of
> record for clinical data.** `patient.keycloak_id` / `doctor.keycloak_id` is the join between them.

Their lifecycles are deliberately independent, because *"this person has an account"* and *"this
person is a current patient"* are different claims. Deleting a medical record should not destroy
someone's identity, and deleting an identity must not erase visit history.

**What the application does propagate:**

| Action | Effect in Keycloak |
|---|---|
| Admin creates a record with a username | Account created with a temporary password; role granted |
| Admin links a record to an existing account UUID | Role granted |
| Admin re-links or unlinks a record | Role revoked from the previous account |
| Admin deletes a record | Role revoked; **the account itself is left alone** |
| Patient self-registers | PATIENT granted immediately |
| Admin approves a doctor | DOCTOR granted |

The account survives deletion on purpose: choosing whether to delete, disable or merely de-role an
identity is identity-provider policy. Revoking the role is enough to cut off clinical data without
this application destroying an identity it does not own.

**Where the two drift, the application reconciles on sign-in.** An account holding a role with no
record behind it is routed back to registration rather than to a broken dashboard.

### The account link field

The *Existing account UUID* box in the patient and doctor forms has three states:

| State | Meaning |
|---|---|
| Field omitted from the request | Leave the link unchanged - protects API callers sending partial bodies |
| Empty | Unlink, and revoke the previous holder's role |
| A UUID | Link or re-link, revoking the previous holder if it changed |

The box is prefilled when a record is already linked, so clearing it is a deliberate act rather than
an accident.

### Giving someone portal access

Filling in a **username** creates the Keycloak account and returns a one-time password, shown once.
The holder must change it at first sign-in. Leaving it blank records someone with **no portal
access**, which is a normal and supported case - a patient who never uses the portal still needs
their visits recorded.

The username field is disabled on a record that is already linked, so the form mirrors what the
server will accept and the rule shows up before a request is sent rather than as a 400 after.

**The application collects no email address.** This realm has no SMTP server, `resetPasswordAllowed`
is false and `verifyEmail` is false, so an address the application stored could never be mailed to,
verified, or used to recover a login - it would be a write-only attribute, and a field that appears
to do something while doing nothing is worse than no field.

Keycloak's own **registration page does still ask for one**, and that is not a contradiction: email
is an account attribute Keycloak owns, exactly like the username and the credentials, and it is
collected by its sign-up form rather than by anything here. The split is the one stated at the top of
this section - Keycloak is the system of record for authentication, this application for clinical
data - and an email address belongs on the authentication side of it. Nothing in the medical record
has, or wants, an email column.

The requirement is enforced server-side by Keycloak's user-profile validation, not by the sign-up
template, so removing the field would mean configuring the realm's user profile (a preview feature in
Keycloak 23) rather than editing the theme. It was left in place deliberately: it is also the only
place a future SMTP configuration would find an address to send to. Administrators who want addresses
on accounts they create themselves can set them in the Keycloak console.

Core Keycloak has no admin endpoint that returns a single-use magic link; those are delivered by
e-mail and require SMTP. A temporary credential is the supported equivalent.

If the password is lost before it is handed over, use **Re-issue password** on the record. A username
is accepted only on a record with no account, so creating a second account for someone who already
has one is refused - the message names re-issue and re-link, which are what is actually wanted.

---

## Deliberate decisions

**A GP is optional.** The specification says every patient is registered with a personal doctor, but
in practice a patient can be between registrations. The unassigned state is surfaced prominently on
the patient's dashboard in amber with a *Choose GP* action, rather than blocking registration.

**Deleting a doctor with recorded visits is refused.** `visit.doctor_id` is `ON DELETE CASCADE`, so
removing such a doctor would take their visits - and the diagnoses, prescriptions and sick leaves
below them - with it. That would erase patient history as a side effect of a staff change, so the
application refuses instead.

**EGN is immutable** after creation, and unique. A self-registration whose EGN an administrator has
already recorded is told to ask for the existing record to be linked, rather than being handed a bare
constraint violation.

**Cross-system writes never run inside a transaction.** `AccountProvisioningService` is deliberately
not `@Transactional`: a database rollback cannot undo a Keycloak write, so a rolled-back transaction
must not be able to leave a real account created or a live user de-roled.

## Known limitations

- **A failed record save can strand a Keycloak account.** Provisioning creates the account before the
  record, so that a Keycloak failure stops the write rather than reporting an account that does not
  exist. If the database save then fails, the account remains - but with no role, since the grant
  happens afterwards, so it is inert. Deleting it is a manual step in the Keycloak console. The
  alternative, a compensating delete, adds rollback machinery for a benign outcome.
- **Keycloak admin calls use the master-realm administrator credentials** via a password grant. That
  is adequate for a local teaching deployment; a real one would use a dedicated service account with
  only `manage-users` on this realm.
- **The application requires Keycloak to be reachable to render at all** - `keycloak.js` is loaded
  from the Keycloak server itself, so there is no offline error page.
- **Self-registering doctors cannot pick specializations.** The specialization endpoint requires a
  role, which an applicant does not yet hold; an administrator adds them on approval - **Approve**
  offers the edit form straight afterwards for exactly that. Saving a pending applicant from that
  form also approves them, because the save grants the DOCTOR role; the approval is recorded either
  way, so the pending banner clears.
- **Approval has no column of its own.** A doctor is pending when the audit log holds a
  `SELF_REGISTER` entry for them and no `APPROVE_DOCTOR`. The doctor list carries that as a derived
  `pending` flag, which keeps applicants out of the visit form, the patients' directory and report e.
- **Deleting a Keycloak account externally** leaves the clinical record with a dangling
  `keycloak_id`. Nobody can authenticate as them, the history is preserved, and an administrator can
  clear or re-point the link.

---

## Tests

```bash
./gradlew test
```

385 tests, no Docker required - the suite runs against H2 with a mocked identity provider. A JaCoCo
report is written to `build/reports/jacoco/test/html/index.html`.

| Suite | Covers |
|---|---|
| `EndpointAuthorizationMatrixTest` | All 100 role/endpoint combinations against the real `SecurityConfig` |
| `AuthorizationServiceTest` | The ownership rules of §2 |
| `AccountProvisioningServiceTest` | When an account is created, and what happens to the role when it is not |
| `VisitServiceTest` | The clinical workflow and its business rules |
| `ReportControllerTest` | The ten required reports |
| `DtoValidationTest` | Bean Validation constraints |
| `GlobalExceptionHandlerTest` | Status codes for each error class |

The controller suites install a permit-all filter chain, so their `@WithMockUser` roles do not
exercise `@PreAuthorize`. `EndpointAuthorizationMatrixTest` and `RegistrationEndpointSecurityTest`
exist to cover that gap and import the real security configuration.
