# Test Plan — MedicalRecordsSpringWS (CSCB869)

**Purpose:** verify that every requirement in the assignment works, end to end, against the real stack.
**Scope:** 55 REST endpoints, 3 roles, 5 CRUD entities, 10 required reports, and the SPA that drives them.

---

## 0. How to use this plan

Work top to bottom. Each phase gates the next — there is no value in running the role matrix if the app will not boot.

| Phase | What it proves | Time |
|---|---|---|
| **1. Automated suite** | Business rules and access control, on every build | 1 min |
| **2. Stack bring-up** | The app runs against real MySQL and Keycloak | 15 min |
| **3. Smoke path** | Each role can sign in and reach their dashboard | 10 min |
| **4. Spec conformance** | Every graded requirement (§1–§4) is demonstrable | 90 min |
| **5. Negative testing** | The rules refuse what they should refuse | 30 min |
| **6. Data integrity** | Constraints, validation, and edge-case inputs hold | 30 min |

**Record results as you go.** A phase is "pass" only if every check in it passes; note anything unexpected rather than moving on, because later phases assume earlier ones held.

### What the automated suite can and cannot tell you

This matters for deciding where to spend manual effort.

**It proves:** authorization on all 55 endpoints, ownership rules, validation constraints, the insurance window arithmetic, visit double-booking, pagination bounds, and DTO mapping.

**It cannot prove**, because it runs on H2 with Keycloak mocked:

| Untested surface | Why it matters | Covered by |
|---|---|---|
| MySQL dialect behaviour | The insurance query uses `CONCAT(YEAR(...), MONTH(...))`; H2 and MySQL can differ | Phase 2.3, 4.3 |
| `ddl-auto: validate` vs `init.sql` | Schema drift only surfaces at production startup | Phase 2.2 |
| Real Keycloak admin API calls | Account creation, role grant **and revoke**, and password reset are all mocked in tests — the suite only proves they fail loudly when Keycloak is unreachable | Phase 3.4, 4.4h, 4.4j |
| Keycloak data persistence | Nothing in the suite restarts a container | Phase 4.4k |
| PKCE on the browser flow | The suite never performs an authorization-code exchange | Phase 3.1d |
| The SPA itself | No JavaScript tests exist; every UI behaviour is manual | Phases 3–6 |
| Token lifecycle | Refresh, expiry, and role propagation after a role is granted | Phase 3.4 |

---

## 1. Automated suite

```bash
./gradlew clean test
```

**Pass criteria:** `BUILD SUCCESSFUL`, 0 failures, 0 errors.

Coverage report: `build/reports/jacoco/test/html/index.html`

```bash
./gradlew check
```

**Pass criteria:** `BUILD SUCCESSFUL`. `jacocoTestCoverageVerification` is wired into `check`, so
this now enforces the 0.60 minimum in `build.gradle.kts` — previously it was configured but never
attached to anything, so the threshold could not fail.

> The rule measures **instruction** coverage, not line coverage. It currently passes at ~65%
> (line 68%, method 69%). Branch coverage is ~53% — below the number, but not what the rule counts.

> If a test fails here, stop. Everything below assumes a green build.

---

## 2. Stack bring-up

### 2.1 Start infrastructure

```bash
docker compose up -d --wait
```

`--wait` blocks until both healthchecks pass, so there is nothing to poll. To check anyway:

```bash
docker compose ps
```

**Pass:** `medical_records_db` and `medical_records_keycloak` both `healthy`.

> Keycloak stores its own data in the **MySQL container**, in a separate `keycloak` schema created
> by `db/00-keycloak-db.sql`. First start therefore takes a couple of minutes while it builds its
> ~90 tables, and `keycloak` genuinely depends on `mysql` being healthy. Accounts created at runtime
> now survive `docker compose down`; before this change Keycloak ran on in-container H2 with no
> volume and lost them silently, because `--import-realm` brought the seeded users back.

### 2.2 Verify the schema matches the entities

The app runs `ddl-auto: validate`, so it refuses to start if `init.sql` and the JPA entities disagree.

```bash
./gradlew bootRun
```

**Pass:** the log reaches `Started MedicalRecordsSpringWSApplication` with no `SchemaManagementException`.

**Fail symptom:** `Schema-validation: missing table / missing column`. That means `init.sql` and the entity model have drifted — compare the failing column against `src/main/resources/db/init.sql`.

> If you have run an older build before, the MySQL volume already exists and `init.sql` will **not** re-run. To reseed:
> `docker compose down -v && docker compose up -d --wait`
>
> **Since Keycloak persists, `--import-realm` only imports when the realm is absent.** Edits to
> `realm-export.json` therefore take effect on a `down -v` (which also reseeds the medical data) or
> by changing the setting in the admin console — not on a plain restart.

### 2.3 Verify the seed data loaded

Connect and spot-check:

```bash
docker exec -it medical_records_db mysql -uroot -proot medical_records_db -e "SELECT COUNT(*) AS doctors FROM doctor; SELECT COUNT(*) AS patients FROM patient; SELECT COUNT(*) AS visits FROM visit; SELECT patient_id, COUNT(DISTINCT CONCAT(YEAR(insurance_date),'-',MONTH(insurance_date))) AS months FROM paid_insurance GROUP BY patient_id ORDER BY patient_id;"
```

**Pass:** 8 doctors (4 of them GPs), 24 patients, 46 visits, 46 diagnoses, 20 sick leaves.

Insurance is an **unbroken run** of paid months, anchored at the current month when it has been paid
and at the previous one when it has not — the current month is not over, so an unpaid one is not yet
a lapse. Seeded runs:

| Run | Patients | Meaning |
|---|---|---|
| 6 | 1, 2, 3, 9, 16 | Insured, paid up to and including this month |
| 6 | **20, 21** | Insured **on the grace period** — this month unpaid, unbroken through last month |
| 5 | 5, 6 | One month short — the boundary the rule turns on |
| 3 | 4, 10, 17 | |
| 1 | 7, 8, 11, 18 | Paid this month only |
| 0 | **12, 19** | Three paid months, but they stopped two months ago — no anchor. A plain tally would call these "3" and could not tell them from the row above |
| 0 | the rest | Never paid |

> Doctors 6–8 and patients 16–24 have a NULL `keycloak_id` on purpose — records-only entries, people
> the practice tracks who hold no portal account. The realm only ships 5 doctor and 15 patient logins.

Confirm Keycloak got its own schema rather than falling back to H2:

```bash
docker exec medical_records_db mysql -uroot -proot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak';"
```

**Pass:** ~90. A `0` means the schema exists but Keycloak never populated it; an error means
`00-keycloak-db.sql` did not run — reseed with `down -v`.

> **Every date in the seed is relative to `CURDATE()`** — visits and sick leaves as well as
> insurance. Patients 1, 2, 3, 9 and 16 must therefore show exactly 6 months whenever you run this,
> and the visits must span roughly the last 8 months. If the fully covered patients show 5, or the
> visits cluster in one fixed month, the seed did not apply relative dates — reseed as above.

---

## 3. Smoke path

Open `http://localhost:8080`.

**Signed out**, the page is the wordmark, a title and two buttons — **Sign in** and **Create an
account** — and nothing else. The Login/Register pair that used to sit in the header appeared under
exactly the same condition as these, so it was a duplicate and was removed.

### 3.1 Sign-in and role routing

Tabs appear in one order for every role — the role's own view first, then the shared directories,
visits, reports, and the administrator-only log. **The landing screen is not always the first tab:**
an administrator lands on Reports, which is fourth.

| # | Sign in as | Expect header | Expect tabs | Lands on |
|---|---|---|---|---|
| 3.1a | `admin` / `admin` | `admin (ADMIN)` | Patients · Doctors · Visits · **Reports** · Audit Logs | Reports |
| 3.1b | `doctor` / `doctor` | `doctor (DOCTOR)` | **My Patients** · All Patients · All Doctors · Visits · Reports | My Patients |
| 3.1c | `patient` / `patient` | `patient (PATIENT)` | **My Medical History** · Doctors Directory | My Medical History |

**Pass:** the highlighted tab is the one whose screen is showing. Both come from the same `landing`
flag in `TABS_BY_ROLE`, so they cannot disagree.

Sign out fully between roles (or use a private window) — a stale token will send you to the wrong dashboard.

### 3.1d Sign-in uses PKCE

Nothing to configure: the realm sets `pkce.code.challenge.method=S256`, so Keycloak **refuses** a
code exchange without a verifier. A successful sign-in is itself the proof. To see it, watch the
redirect to `/auth` in the Network tab — the query string carries `code_challenge` and
`code_challenge_method=S256`.

### 3.2 Every landing screen renders data

| # | Role | Screen | Expect |
|---|---|---|---|
| 3.2a | ADMIN | Reports | Report cards render, no error banner; the pending-approvals banner appears only when a doctor is awaiting approval |
| 3.2b | ADMIN | Visits | 46 visits listed with patient, doctor, diagnosis |
| 3.2c | DOCTOR | My Patients | Dr. Georgi Ivanov's roster — **9 patients** (ids 1–9), the largest of the four |
| 3.2c-2 | DOCTOR (`doctor2`) | My Patients | Dr. Dimitar Petrov is not a GP, so the screen is titled **Patients You Have Seen** and lists the distinct patients from his own visits — not "No patients assigned to you." Same for `doctor4` |
| 3.2d | PATIENT | My Medical History | Ivan Dimitrov's visits, each with diagnosis, prescription and any sick leave, plus a green **Health Insurance Active** badge with no months outstanding |
| 3.2e | ADMIN | Patients | An **Insurance** column on every row — verdict plus the run, e.g. *Insured 6/6*, *Not insured 3/6* |
| 3.2f | DOCTOR | My Patients | The same Insurance column and a **Visits** action, and **no + Insurance button** on any row — recording a payment is an administrator's action. Previously this screen showed name and EGN and nothing else |
| 3.2f-2 | DOCTOR | All Patients | Likewise no **+ Insurance**. A direct `POST /api/patients/1/insurance` with the doctor's token returns **403** |
| 3.2g | ADMIN | Visits | A **Sick Leave** column. The other two visit views always had it; this one did not |
| 3.2h | ADMIN | Audit Logs | The **Actor** column shows usernames (`admin`, `doctor`), not Keycloak UUIDs |

**Fail symptom to watch for:** an HTTP 500 on the Visits or My Medical History screens. Those two load the full visit graph (patient, doctor, diagnoses, prescriptions, sick leave) in one query, which is the most fragile read in the system.

### 3.3 Console is clean

With DevTools open, click through every tab for each role.

**Pass:** no uncaught JavaScript errors, no 4xx/5xx in the Network tab other than deliberate negative tests.

### 3.4 Keycloak round trip — new user onboarding

This is the only flow that writes to Keycloak, and it is entirely mocked in the automated suite.

1. Sign out. Click **Create an account** on the landing page. Create `testpatient` / `testpatient`.
   Keycloak's own sign-up form asks for an email — that is its account attribute, not a field this
   application stores or uses; any address will do.
2. Sign in as that user → the role-selection screen appears (header shows `(New User)`).
3. Choose **I am a Patient**, enter EGN `9001011234`, submit. There is no GP picker here — the GP is
   chosen from the dashboard afterwards, because a role-less user cannot yet read the doctor list.
4. **Pass:** the app grants the PATIENT role and lands on My Medical History without a manual sign-out.
5. Confirm in Keycloak (`http://localhost:8081`, `admin`/`admin` → realm `medical-records` → Users → `testpatient` → Role mapping): **PATIENT** is assigned.

**Fail symptom:** registration succeeds but the role never appears. Check the app log for `Could not assign PATIENT role in Keycloak` — role assignment is deliberately non-fatal, so the profile is created either way and only the log reveals the failure.

---

## 4. Spec conformance

### 4.1 §1 — CRUD for all five entities

As **admin** unless noted. For each: create, read back, edit, delete.

| # | Entity | Steps | Pass |
|---|---|---|---|
| 4.1a | **Doctor** | Doctors → **+ Add New Doctor**: `Krasimir Tonev`, tick GP, pick 2 specializations → Save | Appears in list with GP=Yes and both specializations |
| 4.1b | Doctor edit | Edit him → untick GP → Save | GP column becomes No |
| 4.1c | Doctor delete | Delete him | Row disappears; list reloads |
| 4.1d | **Patient** | Patients → **+ Add New Patient**: `Test Patient`, EGN `9001011111`, GP Dr. Georgi Ivanov, **Portal access left blank** | Appears with correct GP, no credentials dialog |
| 4.1e | Patient edit | Edit → change first name → Save | Name updates |
| 4.1f | **Visit** | Visits → **+ Add New Visit**: patient Ivan Dimitrov, doctor Georgi Ivanov, tomorrow, 09:00 | Visit created |
| 4.1g | **Diagnosis** | Open that visit → add diagnosis `Test Diagnosis` | Appears in the visit's diagnosis list |
| 4.1h | **Prescription** | Same visit → add prescription `Test medication 10mg` | Appears in the prescription list |
| 4.1i | **Sick leave** | Same visit → issue sick leave from today, 5 days | Appears; the form is replaced by the issued record |
| 4.1i-2 | **Edit clinical items** | Same visit → ✎ on the diagnosis, change it to `Revised Diagnosis`; ✎ on the prescription, change the dose; ✎ on the sick leave, change it to 7 days and **Save** | Each shows the new value. The audit log records `UPDATE Diagnosis`, `UPDATE Prescription` and `UPDATE SickLeave` — not a delete followed by a create |
| 4.1i-3 | Sick-leave bound on edit | ✎ the sick leave, enter `400` days, **Save** | Refused with the 1–365 message; **Cancel** restores the issued record unchanged |
| 4.1j | Delete clinical items | Remove the diagnosis, prescription, and sick leave via ✕ | Each disappears |
| 4.1k | Visit delete | Delete the visit | Row disappears |
| 4.1l | Patient delete | Delete `Test Patient` | Row disappears |

### 4.2 §2 — Role model

| # | As | Action | Expect |
|---|---|---|---|
| 4.2a | DOCTOR (`doctor`) | Open **All Patients** | Sees all 24 patients, not only their own roster of 9 |
| 4.2b | DOCTOR | Open **All Doctors** | Sees all 8 doctors |
| 4.2c | DOCTOR (`doctor`) | Visits tab → open a visit **they performed** (visit 1, 2 or 3) and add a diagnosis | Succeeds |
| 4.2d | DOCTOR (`doctor`) | Try to edit visit **8** (performed by Dr. Dimitar Petrov) | Edit/Delete controls absent; a direct API call returns 403 |
| 4.2e | PATIENT (`patient`) | My Medical History | Sees only Ivan Dimitrov's own visits |
| 4.2f | ADMIN | Any screen | Full read and write everywhere |

### 4.3 §3 — All ten reports

The Reports tab is available to both **admin** and **doctor**. The cards are laid out in the order
the assignment lists them and each carries its letter, so the mapping needs no explaining — that
order also groups cleanly by subject, which is why the colour coding survives it.

| # | Card | Where | Pass |
|---|---|---|---|
| 4.3a | **a** · Patients with a Given Diagnosis | Reports → card a → pick `Acute Bronchitis` | 7 visits' worth of patients, each with EGN and GP |
| 4.3b | **b** · Times Diagnosed per Condition | Reports → card b | Ranked, headed by *Acute Bronchitis* 7, *Hypertension* 6, *Influenza Type A* 5 |
| 4.3c | **c** · Patients Registered with a Given GP | Reports → card c → pick Dr. Georgi Ivanov | His 9 patients. The picker lists only GPs. Also reachable from the **Patients** button on a GP row in Doctors |
| 4.3d | **d** · Patients Registered per GP | Reports → card d | Only the 4 GPs, ranked 9 · 6 · 5 · 3 |
| 4.3e | **e** · Visits Recorded per Doctor | Reports → card e | All 8 doctors, ranked 11 · 8 · 7 · 6 · 5 · 4 · 3 · 2, summing to 46 |
| 4.3f | **f** · Visits for a Given Patient | Reports → card f → pick a patient | Visit, date, doctor, diagnosis, prescription **and sick leave** — the last column is the one §1 asks for ("излизал ли е в болнични и за колко време") |
| 4.3g | **g** · Visits in a Given Date Range | Reports → card g → last 12 months | All 46 visits. Narrow to one month and the count drops |
| 4.3h | **h** · Visits for a Given Doctor in a Date Range | Reports → card h → Dr. Georgi Ivanov, last 12 months | 11 visits, all his |
| 4.3i | **i** · Sick Leaves Issued per Month | Reports → card i | 6 distinct months, ranked; the busiest is the top row |
| 4.3j | **j** · Sick Leaves Issued per Doctor | Reports → card j | Ranked, headed by Dr. Georgi Ivanov with 9 |

**Beyond the ten (4.3l):** the **Unpaid Months per Patient** card — the *справка* §1 requires of patient
records. Previously this existed only as a true/false badge on the patient's own dashboard, invisible
to the staff who need it.

**Pass:** only patients owing something appear — the fully paid are left out. Spot checks against
the seed: Elena Nikolova (5) shows one month, the oldest in the window; Desislava Petkova (12) shows
three; Teodora Simeonova (15) shows all six. **Martin Zlatev (20) appears showing only the current
month while his status still reads Insured** — the grace period, stated plainly: covered, with this
month outstanding.

No month count appears anywhere in the interface. The run understates anyone who paid on either side
of a gap, so the verdict answers the question and the months say what is outstanding.

> Counts above come from the seed and hold on a fresh `down -v`. They shift as soon as you create
> visits during Phase 4.1, which is expected — the rankings are what matter, not the absolute numbers.

**Also verify (4.3k):** CSV export. There is one **Export CSV** button above the results table and it
writes whatever report is currently on screen, filters included — it is built from the rendered rows
rather than from a per-report endpoint, so every report exports. Check one download opens correctly
in a spreadsheet, and that Cyrillic names are not mangled (the file carries a UTF-8 BOM for Excel).

### 4.4 §4 — Technology requirements

| # | Requirement | How to demonstrate |
|---|---|---|
| 4.4a | RESTful services | DevTools Network tab shows `/api/...` calls with correct verbs and status codes |
| 4.4b | Database | Phase 2.3 |
| 4.4c | User interface | Phases 3–4 |
| 4.4d | Validation | Phase 6 |
| 4.4e | Exception handling | Phase 5 — errors return structured JSON, never a stack trace |
| 4.4f | Tests | Phase 1 |
| 4.4g | **User management** | Phase 3.4 plus the doctor approval flow below |

**Doctor approval lifecycle (4.4h)** — the second Keycloak write path:

1. Register a new Keycloak user `testdoctor` / `testdoctor`, sign in, choose **I am a Doctor**, submit.
2. **Pass:** confirmation says the application is awaiting approval. There is **no GP checkbox** on this form.
3. Sign out, sign back in as `testdoctor`.
   **Pass:** shows *"Application under review"* — **not** the role-selection screen again.
4. Sign in as `admin` → Reports.
   **Pass:** banner *"🔔 Doctors Awaiting Approval (1)"* showing the name, not an entity id.
5. Click **Approve**, confirm.
   **Pass:** the banner disappears. Verify in Keycloak that `testdoctor` now holds **DOCTOR**.
6. Reload the admin dashboard.
   **Pass:** the banner does not come back.
7. Sign in as `testdoctor`.
   **Pass:** the doctor dashboard loads with the five doctor tabs.

**Fail symptom:** if the banner persists after approval, the approval was not recorded — check the Audit Logs tab for an `APPROVE_DOCTOR` entry.

**Administrator-provisioned accounts (4.4j)** — the third Keycloak write path, and the one that lets
staff give portal access to someone who has not registered themselves.

Each patient and doctor form has a **Portal access** section. It has three states, and the form
mirrors what the server accepts so the rules appear before a request is sent:

| Filled in | Result |
|---|---|
| Nothing | A records-only entry — a person with no portal access. Legitimate and common. |
| **New account username** | Creates the Keycloak account, grants the role, returns a one-time password |
| **Existing account UUID** | Links the record to an account that already exists, and grants the role |

1. As admin: **Patients → + Add New Patient**, `Test Provision`, EGN `9901017777`, any GP, username
   `tprovision` → Save.
   **Pass:** an *Account ready* dialog shows `Username: tprovision` and a 12-character password.
   Shown once — copy it.
2. Sign out, sign in as `tprovision` with that password.
   **Pass:** Keycloak forces an **Update password** screen (the credential is issued `temporary`),
   and after setting one you land on My Medical History for Test Provision.
3. As admin: add a second patient, `Records Only`, EGN `9901017778`, **no username**.
   **Pass:** saves with no credentials dialog; `keycloak_id` is NULL.
4. Edit that patient, add username `ronly` → Save.
   **Pass:** credentials dialog appears. Granting access later is what makes a blank username a
   deferral rather than a dead end.
5. Edit **Test Provision** (now linked).
   **Pass:** the username box is **disabled** with an amber note pointing at Re-issue password —
   a second account would strand the first, so the server refuses it and the form says so first.
6. Click **Re-issue password**, confirm.
   **Pass:** the dialog reads `Username: tprovision` — the real Keycloak username, read back from
   Keycloak, not the person's name and not the account UUID.
7. Edit Test Provision → clear the **Existing account UUID** box → Save.
   **Pass:** the record is unlinked, and the `tprovision` account **loses `PATIENT`** while the
   account itself survives. Deleting the record does the same.

Check the role with the admin API (get `$TOKEN` from §5, or use the Keycloak console):

```bash
ID=$(curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8081/admin/realms/medical-records/users?username=tprovision" | python -c "import sys,json;d=json.load(sys.stdin);print(d[0]['id'] if d else 'NONE')")
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8081/admin/realms/medical-records/users/$ID/role-mappings/realm" | python -c "import sys,json;print([r['name'] for r in json.load(sys.stdin)])"
```

> **The account outliving the record is deliberate.** Whether to delete, disable or merely de-role an
> identity is identity-provider policy; revoking the role is enough to cut the stale account off from
> clinical data without this application destroying an identity it does not own.

**Persistence (4.4k)** — the point of putting Keycloak on MySQL.

```bash
docker compose down          # NO -v
docker compose up -d --wait
```

**Pass:** `ronly` still exists with `PATIENT`, and signing in as them still works. If the account has
vanished, Keycloak is still on in-container H2.

**Audit trail (4.4i)** — as admin, open **Audit Logs** after running Phase 4.1.

| Action performed in 4.1 | Expected entry |
|---|---|
| Created a doctor | `CREATE` · `Doctor` · actor `admin` |
| Created a patient | `CREATE` · `Patient` · actor `admin` |
| Created a visit | `CREATE` · `Visit` |
| Added a diagnosis | `CREATE` · `Diagnosis` |
| Issued a sick leave | `CREATE` · `SickLeave` |
| Deleted the visit | `DELETE` · `Visit` |
| Doctor self-registered (4.4h) | `SELF_REGISTER` · `Doctor` |
| Approved that doctor (4.4h) | `APPROVE_DOCTOR` · `Doctor` |

**Pass:** entries appear newest first, each with an actor, entity type, entity id, and timestamp.

> Actions that were **refused** are not recorded — the audit log captures completed changes only.

---

## 5. Negative testing

The automated suite asserts these at the API layer; this phase confirms the UI does not expose a way around them.

### 5.1 Role enforcement

Get a bearer token to probe the API directly:

> **The password grant is disabled — take the token from the browser instead.**
>
> This plan originally obtained a token with `grant_type=password` against `frontend-client`.
> `directAccessGrantsEnabled` is now `false`: that grant POSTs credentials straight to the token
> endpoint, bypassing the login page and with it brute-force detection, required actions and MFA,
> and OAuth 2.1 removes it. The old command returns `unauthorized_client`, which is correct.
>
> Sections 5.1–5.3 still need a bearer token. Sign in through the UI as the role you want, then in
> the browser console:
>
> ```js
> copy(keycloak.token)     // or: console.log(keycloak.token)
> ```
>
> Paste it into your shell. This is *better* evidence than the old approach — it is the token the
> application actually issued through the authorization-code flow, rather than one minted by a
> back door. Tokens expire in ~5 minutes; re-copy when a request starts returning 401.
>
> ```bash
> TOKEN='paste-here'
> curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/patients
> ```
>
> The automated suite is unaffected — `TestAuth` builds `JwtAuthenticationToken` instances directly
> and never contacts Keycloak.

Then, with `TOKEN` set to a **patient** token:

| # | Request | Expect |
|---|---|---|
| 5.1a | `GET /api/patients` | **403** — patients may not list everyone |
| 5.1b | `GET /api/audit-logs` | **403** |
| 5.1c | `POST /api/doctors` | **403** |
| 5.1d | `DELETE /api/visits/1` | **403** |
| 5.1e | `GET /api/reports/diagnoses/common` | **403** |
| 5.1f | Any `/api/**` with no token | **401** |

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/patients
```

### 5.2 Cross-patient access (horizontal escalation)

Signed in as `patient` (Ivan Dimitrov, patient id 1), with a patient token:

| # | Request | Expect |
|---|---|---|
| 5.2a | `GET /api/patients/2` | **403** — another patient's record |
| 5.2b | `GET /api/patients/1` | **200** — their own |
| 5.2c | `GET /api/visits/8` | **403** — another patient's visit |
| 5.2d | `GET /api/visits/patient/2` | **403** |
| 5.2e | `GET /api/patients/keycloak/c0222222-3333-3333-3333-333333333333` | **403** — someone else's profile |

### 5.3 Cross-doctor access

With a token for `doctor` (Dr. Georgi Ivanov, doctor id 1):

| # | Request | Expect |
|---|---|---|
| 5.3a | `PUT /api/visits/8` (Dr. Petrov's visit) | **403** |
| 5.3b | `DELETE /api/visits/9` (Dr. Georgiev's visit) | **403** |
| 5.3c | `POST /api/visits/diagnosis` with `visitId: 8` | **403** |
| 5.3d | `POST /api/visits` naming `doctorId: 2` | **403** — cannot book for a colleague |
| 5.3e | `PATCH /api/patients/1/gp?gpDoctorId=3` | **403** — assigning a GP is administrative |

### 5.4 Error handling

| # | Request | Expect |
|---|---|---|
| 5.4a | `GET /api/patients/99999` | **404** with `RESOURCE_NOT_FOUND` JSON |
| 5.4b | `GET /api/patients/abc` | **400** `TYPE_MISMATCH` |
| 5.4c | `POST /api/patients` with `{}` | **400** `VALIDATION_ERROR` naming the missing fields |
| 5.4d | `POST /api/patients` with a malformed body | **400** `MALFORMED_REQUEST` |
| 5.4e | **Admin** creates a patient with a duplicate EGN | **409** `DATA_CONFLICT` |
| 5.4f | **Self-register** with an EGN an admin already recorded | **400** — *"A patient record with this EGN already exists. Ask an administrator to link it to your account."* |
| 5.4g | Provision an account with a username the realm already has | **409** `USERNAME_TAKEN` |

> 5.4f is deliberately not the generic conflict: someone whose record staff entered before they
> signed up would otherwise be stuck holding no role, with a message that named no way forward.
> The way out is an administrator linking the existing record, so the message says so.

**Pass criteria for all:** a JSON body with `code`, `message`, `path`, `timestamp` — never an HTML error page or a Java stack trace.

---

## 6. Data integrity and validation

### 6.1 Business rules

| # | Attempt | Expect |
|---|---|---|
| 6.1a | Book two visits for the same doctor, same date and time | Rejected — *"Doctor is already booked"* |
| 6.1b | Book two visits for the same patient, same date and time | Rejected — *"Patient already has an appointment"* |
| 6.1c | Edit a patient and change their EGN | Rejected — *"EGN cannot be changed after registration"* |
| 6.1d | Assign a non-GP (Dr. Dimitar Petrov) as a patient's GP | Rejected — *"is not a General Practitioner"*. **API-level only:** the GP dropdown now lists GPs exclusively, so the UI no longer offers the invalid choice. Test with a direct `PUT /api/patients/1` naming `gpDoctorId: 2`. |
| 6.1e | Issue a sick leave of 0 days, or 400 days | Rejected by validation |
| 6.1f | Self-register twice with the same account | Rejected — *"A profile already exists for this account"* |

### 6.2 Insurance coverage — the six-month rule

Coverage is an unbroken run of paid months, anchored at the current month when it has been paid and
at the previous one when it has not.

| # | Patient | Seeded run | Expect |
|---|---|---|---|
| 6.2a | Ivan Dimitrov (id 1) | every month paid, including this one | **Insured**, nothing outstanding |
| 6.2b | Martin Zlatev (id 20) | unbroken through last month, **this month unpaid** | **Insured** — the grace period. The badge still lists this month as outstanding |
| 6.2c | Elena Nikolova (id 5) | one month short, the oldest | **Not insured** — the boundary. One month listed |
| 6.2d | Maria Vasileva (id 4) | three months paid, most recent | **Not insured**, three listed |
| 6.2e | Desislava Petkova (id 12) | three months paid, but she **stopped two months ago** | **Not insured**, three listed — the three most recent, where 6.2d owes the three oldest |
| 6.2f | Teodora Simeonova (id 15) | none | **Not insured**, all six listed rather than omitted |

**6.2g — crossing the boundary.** Patient 5 is seeded one month short on purpose. As admin, use
**+ Insurance** to record the one missing month (the 1st of whichever month has no row).
**Pass:** the patient flips to **Insured**, and the Unpaid Months per Patient report drops them
altogether — they now owe nothing. Removing the payment again brings them back.

**6.2h — future dating.** Try to record a payment dated next month.
**Pass:** rejected — *"Insurance date cannot be in the future"*.

### 6.3 Input validation

| # | Field | Input | Expect |
|---|---|---|---|
| 6.3a | EGN | `123` | Rejected — must be exactly 10 digits |
| 6.3b | EGN | `12345678ab` | Rejected — digits only |
| 6.3c | First name | empty | Rejected |
| 6.3d | First name | 150 characters | Rejected — max 100 |
| 6.3e | Diagnosis name | 300 characters | Rejected — max 255 |
| 6.3f | Prescription note | 5000 characters | Rejected — max 4000 |
| 6.3g | Visit date | year 9999 | Rejected — not more than 1 year ahead |
| 6.3h | Specialization name | 150 characters | Rejected — max 100 |

### 6.4 Output escaping

Medical text is entered by doctors and rendered across several screens.

1. As `doctor`, add a diagnosis to one of your own visits named exactly:
   `<script>alert('xss')</script>`
2. As `admin`, open Visits, the Reports dashboard, and that patient's Visits view.

**Pass:** the text renders **literally** on every screen. No alert box appears.

**6.4b:** repeat with a prescription note of `"><img src=x onerror=alert(1)>`. Same expectation.

**6.4c:** create a patient whose surname is `O'Brien` and open the Patients list.
**Pass:** the name renders correctly and the **Visits** and **+ Insurance** buttons on that row still work — an apostrophe must not break the row's controls.

### 6.5 Pagination bounds

Only the audit log paginates. Reports return their complete result — they used to default to 50 rows
and slice the rest away in memory, which saved nothing (the full list was already built) and silently
dropped rows off the end of a report.

| # | Request | Expect |
|---|---|---|
| 6.5a | `/api/audit-logs?page=0&size=2` | 2 entries, `page.size` 2, `page.totalElements` the full count |
| 6.5b | `/api/audit-logs?page=715827883&size=3` | **200** with empty `content` — not a 500 |
| 6.5c | `/api/audit-logs?page=-1&size=-5` | **200**, clamped to the first page |
| 6.5d | `/api/audit-logs?size=100000` | **200**, at most 100 entries |
| 6.5e | `/api/reports/doctors/visits?page=0&size=2` | **200** with **every** doctor — the leftover query string is ignored |

---

## 7. Sign-off checklist

| Phase | Result | Notes |
|---|---|---|
| 1. Automated suite green | ☐ | |
| 2. Stack starts, schema validates, seed data correct | ☐ | |
| 3. All three roles sign in and land correctly | ☐ | |
| 3.1d Sign-in completes (which is itself proof PKCE holds) | ☐ | |
| 3.4 Patient self-registration grants the role in Keycloak | ☐ | |
| 4.1 CRUD works for all five entities | ☐ | |
| 4.2 Role model behaves per §2 | ☐ | |
| 4.3 All ten reports produce correct data | ☐ | |
| 4.4h Doctor approval lifecycle completes and the banner clears | ☐ | |
| 4.4j Provisioning, re-issue, link/unlink and role revocation | ☐ | |
| 4.4k Runtime-created accounts survive `docker compose down` | ☐ | |
| 5. Every negative case is refused with the right status | ☐ | |
| 6. Validation, insurance rule, escaping, pagination all hold | ☐ | |

**Ready to demonstrate when every box is ticked.**

---

## Appendix A — Test personas

Realm `medical-records`, seeded from `realm-export.json` **the first time Keycloak starts against an
empty database**. Because Keycloak now persists, a later `docker compose up` does not re-import —
see §2.2.

The sign-in, registration and update-password screens use the `medical-records` login theme
(`keycloak-theme/`), so they carry the application's own styling rather than stock Keycloak.

| Username | Password | Role | Linked record |
|---|---|---|---|
| `admin` | `admin` | ADMIN | — |
| `doctor` | `doctor` | DOCTOR | Dr. Georgi Ivanov (id 1, **GP**) |
| `doctor2` | `doctor` | DOCTOR | Dr. Dimitar Petrov (id 2) |
| `doctor3` | `doctor` | DOCTOR | Dr. Elena Koleva (id 3, **GP**) |
| `doctor4` | `doctor` | DOCTOR | Dr. Stefan Georgiev (id 4) |
| `doctor5` | `doctor` | DOCTOR | Dr. Maria Stoyanova (id 5, **GP**) |
| `patient` | `patient` | PATIENT | Ivan Dimitrov (id 1, GP 1, **insured**) |
| `patient2` | `patient` | PATIENT | Petar Ivanov (id 2, GP 1, **insured**) |
| `patient3` | `patient` | PATIENT | Georgi Stoyanov (id 3, GP 1, **insured**) |
| `patient4`–`patient15` | `patient` | PATIENT | Patients 4–15. `patient5` is the one seeded one month short — the boundary |

## Appendix B — Seeded clinical data

Shaped so each report returns a ranking rather than a flat list. **All dates are relative to
`CURDATE()`**, so the data never goes stale — a fixed July 2026 would have quietly emptied the
period reports and un-insured every patient once the calendar rolled past it.

| | |
|---|---|
| Doctors | 8 — GPs are Georgi Ivanov (1), Elena Koleva (3), Maria Stoyanova (5), Nikolay Dimitrov (6) |
| Patients | 24, one of whom (id 24) has no GP yet |
| Visits | 46, spread over roughly the last 8 months |
| Diagnoses / prescriptions | 46 each |
| Sick leaves | 20, across 6 distinct months |

**Rankings the reports depend on:**

| Report | Shape |
|---|---|
| b · diagnoses | Acute Bronchitis 7 · Hypertension 6 · Influenza Type A 5 · Type 2 Diabetes 4 · Migraine 4 |
| d · patients per GP | Georgi Ivanov 9 · Elena Koleva 6 · Maria Stoyanova 5 · Nikolay Dimitrov 3 |
| e · visits per doctor | 11 · 8 · 7 · 6 · 5 · 4 · 3 · 2 — no ties, all 8 doctors present |
| i · sick leaves per month | 6 distinct months with a clear busiest one |
| j · sick leaves per doctor | Georgi Ivanov 9 · Maria Stoyanova 6 · Dimitar Petrov 3 · Elena Koleva 2 |
| Insurance | 7 insured — **2 of them on the grace period** · **2 one month short** (boundary) · 3 owing three months · 4 owing five · **2 who stopped two months ago** · 6 never paid |

> Doctors 6–8 and patients 16–24 carry a NULL `keycloak_id`: records-only entries, which is a
> supported state and worth having visible. The realm ships 5 doctor and 15 patient logins.

## Appendix C — Reset to a clean state

```bash
docker compose down -v && docker compose up -d
```

Destroys the MySQL volume so `init.sql` and `test-data.sql` re-run. Required after any test that deletes seeded records, and before a final demonstration run.
