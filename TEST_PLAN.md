# Test Plan — MedicalRecordsSpringWS (CSCB869)

**Purpose:** verify that every requirement in the assignment works, end to end, against the real stack.
**Scope:** 55 REST endpoints, 3 roles, 5 CRUD entities, 10 required reports, and the SPA that drives them.

---

## 0. How to use this plan

Work top to bottom. Each phase gates the next — there is no value in running the role matrix if the app will not boot.

| Phase | What it proves | Time |
|---|---|---|
| **1. Automated suite** | Business rules and access control, on every build | 1 min |
| **2. Stack bring-up** | The app runs against real MySQL and Keycloak | 10 min |
| **3. Smoke path** | Each role can sign in and reach their dashboard | 10 min |
| **4. Spec conformance** | Every graded requirement (§1–§4) is demonstrable | 60 min |
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
| Real Keycloak admin API calls | Role assignment on registration and approval is mocked in tests | Phase 3.4, 4.4 |
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
./gradlew jacocoTestCoverageVerification
```

**Pass criteria:** passes at the 60% line minimum configured in `build.gradle.kts`.

> If a test fails here, stop. Everything below assumes a green build.

---

## 2. Stack bring-up

### 2.1 Start infrastructure

```bash
docker compose up -d
```

Wait for both containers to report healthy:

```bash
docker compose ps
```

**Pass:** `medical_records_db` and `medical_records_keycloak` both `healthy`.

> Keycloak takes ~30s on first start while it imports the realm.

### 2.2 Verify the schema matches the entities

The app runs `ddl-auto: validate`, so it refuses to start if `init.sql` and the JPA entities disagree.

```bash
./gradlew bootRun
```

**Pass:** the log reaches `Started MedicalRecordsSpringWSApplication` with no `SchemaManagementException`.

**Fail symptom:** `Schema-validation: missing table / missing column`. That means `init.sql` and the entity model have drifted — compare the failing column against `src/main/resources/db/init.sql`.

> If you have run an older build before, the MySQL volume already exists and `init.sql` will **not** re-run. To reseed:
> `docker compose down -v && docker compose up -d`

### 2.3 Verify the seed data loaded

Connect and spot-check:

```bash
docker exec -it medical_records_db mysql -uroot -proot medical_records_db -e "SELECT COUNT(*) AS doctors FROM doctor; SELECT COUNT(*) AS patients FROM patient; SELECT COUNT(*) AS visits FROM visit; SELECT patient_id, COUNT(DISTINCT CONCAT(YEAR(insurance_date),'-',MONTH(insurance_date))) AS months FROM paid_insurance GROUP BY patient_id ORDER BY patient_id;"
```

**Pass:** 5 doctors, 15 patients, 10 visits. Patients **1 and 2 show 6 months**; patients 3–8 show 1; patients 9–15 absent.

> The insurance rows are seeded relative to `CURDATE()`, so patients 1 and 2 must show exactly 6 regardless of when you run this. If they show 5, the seed did not apply relative dates — reseed as above.

---

## 3. Smoke path

Open `http://localhost:8080`.

### 3.1 Sign-in and role routing

| # | Sign in as | Expect header | Expect tabs |
|---|---|---|---|
| 3.1a | `admin` / `admin` | `admin (ADMIN)` | All Reports · Doctors · Patients · Visits · Audit Logs |
| 3.1b | `doctor` / `doctor` | `doctor (DOCTOR)` | My Patients · All Patients · Visits · All Doctors · Reports |
| 3.1c | `patient` / `patient` | `patient (PATIENT)` | My Medical History · Doctors Directory |

Sign out fully between roles (or use a private window) — a stale token will send you to the wrong dashboard.

### 3.2 Every landing screen renders data

| # | Role | Screen | Expect |
|---|---|---|---|
| 3.2a | ADMIN | All Reports | Report cards render, no error banner |
| 3.2b | ADMIN | Visits | 10 visits listed with patient, doctor, diagnosis |
| 3.2c | DOCTOR | My Patients | Dr. Georgi Ivanov's roster (patients 1, 2, 3, 8, 11, 14) |
| 3.2d | PATIENT | My Medical History | Ivan Dimitrov's visit of 2026-07-01, diagnosis *Acute Bronchitis* |

**Fail symptom to watch for:** an HTTP 500 on the Visits or My Medical History screens. Those two load the full visit graph (patient, doctor, diagnoses, prescriptions, sick leave) in one query, which is the most fragile read in the system.

### 3.3 Console is clean

With DevTools open, click through every tab for each role.

**Pass:** no uncaught JavaScript errors, no 4xx/5xx in the Network tab other than deliberate negative tests.

### 3.4 Keycloak round trip — new user onboarding

This is the only flow that writes to Keycloak, and it is entirely mocked in the automated suite.

1. Sign out. Click **Register**. Create `testpatient` / `testpatient`.
2. Sign in as that user → the role-selection screen appears (header shows `(New User)`).
3. Choose **I am a Patient**, enter EGN `9001011234`, pick a GP, submit.
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
| 4.1d | **Patient** | Patients → **+ Add New Patient**: `Test Patient`, EGN `9001011111`, GP Dr. Georgi Ivanov | Appears with correct GP |
| 4.1e | Patient edit | Edit → change first name → Save | Name updates |
| 4.1f | **Visit** | Visits → **+ Add New Visit**: patient Ivan Dimitrov, doctor Georgi Ivanov, tomorrow, 09:00 | Visit created |
| 4.1g | **Diagnosis** | Open that visit → add diagnosis `Test Diagnosis` | Appears in the visit's diagnosis list |
| 4.1h | **Prescription** | Same visit → add prescription `Test medication 10mg` | Appears in the prescription list |
| 4.1i | **Sick leave** | Same visit → issue sick leave from today, 5 days | Appears; the form is replaced by the issued record |
| 4.1j | Delete clinical items | Remove the diagnosis, prescription, and sick leave via ✕ | Each disappears |
| 4.1k | Visit delete | Delete the visit | Row disappears |
| 4.1l | Patient delete | Delete `Test Patient` | Row disappears |

### 4.2 §2 — Role model

| # | As | Action | Expect |
|---|---|---|---|
| 4.2a | DOCTOR (`doctor`) | Open **All Patients** | Sees all 15 patients, not only their own roster |
| 4.2b | DOCTOR | Open **All Doctors** | Sees all 5 doctors |
| 4.2c | DOCTOR (`doctor`) | Visits tab → open a visit **they performed** (visit 1, 2 or 3) and add a diagnosis | Succeeds |
| 4.2d | DOCTOR (`doctor`) | Try to edit visit **8** (performed by Dr. Dimitar Petrov) | Edit/Delete controls absent; a direct API call returns 403 |
| 4.2e | PATIENT (`patient`) | My Medical History | Sees only Ivan Dimitrov's own visits |
| 4.2f | ADMIN | Any screen | Full read and write everywhere |

### 4.3 §3 — All ten reports

Reports are reachable as **admin** (All Reports) and as **doctor** (Reports).

| # | Req | Report | Where | Pass |
|---|---|---|---|---|
| 4.3a | 3a | Patients with a given diagnosis | Reports → filter by diagnosis → `Influenza Type A` | Lists Petar Ivanov |
| 4.3b | 3b | Most frequently diagnosed | Reports → *Most Common Diagnoses* | Ranked list of the 10 seeded diagnoses |
| 4.3c | 3c | **Patients registered with a given GP** | Doctors → **Patients** button on a GP row | Dr. Georgi Ivanov shows 6 patients |
| 4.3d | 3d | Patient count per GP | Reports → *Patients per GP* | Only GPs listed (1, 3, 5), with counts |
| 4.3e | 3e | Visit count per doctor | Reports → *Visits per Doctor* | All 5 doctors, counts sum to 10 |
| 4.3f | 3f | **Visits per patient** | Patients → **Visits** button on a row | That patient's visits with diagnosis and prescription |
| 4.3g | 3g | Visits in a period | Reports → date filter `2026-07-01`–`2026-07-31` | All 10 seeded visits |
| 4.3h | 3h | Visits for one doctor in a period | Reports → doctor + date filter, Dr. Georgi Ivanov, July 2026 | 3 visits |
| 4.3i | 3i | Month with most sick leaves | Reports → *Sick Leaves per Month* | July 2026 with 6 |
| 4.3j | 3j | Doctors issuing most sick leaves | Reports → *Sick Leaves per Doctor* | Dr. Georgi Ivanov top with 3 |

**Also verify (4.3k):** the three CSV exports download and open correctly in a spreadsheet — *Most Common Diagnoses*, *Visits per Doctor*, *Sick Leaves per Doctor*.

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
4. Sign in as `admin` → All Reports.
   **Pass:** banner *"🔔 Doctors Awaiting Approval (1)"* showing the name, not an entity id.
5. Click **Approve**, confirm.
   **Pass:** the banner disappears. Verify in Keycloak that `testdoctor` now holds **DOCTOR**.
6. Reload the admin dashboard.
   **Pass:** the banner does not come back.
7. Sign in as `testdoctor`.
   **Pass:** the doctor dashboard loads with the five doctor tabs.

**Fail symptom:** if the banner persists after approval, the approval was not recorded — check the Audit Logs tab for an `APPROVE_DOCTOR` entry.

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

```bash
curl -s -X POST "http://localhost:8081/realms/medical-records/protocol/openid-connect/token" \
  -d "client_id=frontend-client" -d "grant_type=password" \
  -d "username=patient" -d "password=patient" | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])"
```

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
| 5.4e | Create a patient with a duplicate EGN | **409** `DATA_CONFLICT` |

**Pass criteria for all:** a JSON body with `code`, `message`, `path`, `timestamp` — never an HTML error page or a Java stack trace.

---

## 6. Data integrity and validation

### 6.1 Business rules

| # | Attempt | Expect |
|---|---|---|
| 6.1a | Book two visits for the same doctor, same date and time | Rejected — *"Doctor is already booked"* |
| 6.1b | Book two visits for the same patient, same date and time | Rejected — *"Patient already has an appointment"* |
| 6.1c | Edit a patient and change their EGN | Rejected — *"EGN cannot be changed after registration"* |
| 6.1d | Assign a non-GP (Dr. Dimitar Petrov) as a patient's GP | Rejected — *"is not a General Practitioner"* |
| 6.1e | Issue a sick leave of 0 days, or 400 days | Rejected by validation |
| 6.1f | Self-register twice with the same account | Rejected — *"A profile already exists for this account"* |

### 6.2 Insurance coverage — the six-month rule

| # | Patient | Expect |
|---|---|---|
| 6.2a | Ivan Dimitrov (id 1) — 6 consecutive months | **Insured** |
| 6.2b | Georgi Stoyanov (id 3) — 1 month only | **Not insured** |
| 6.2c | Yordan Angelov (id 9) — no payments | **Not insured** |

**6.2d — the boundary.** As admin, record insurance payments for patient 9 for exactly **five** of the last six months (use the +Insurance button, setting the insurance date to the 1st of each month).
**Pass:** still **not insured**. Add the sixth → **insured**.

**6.2e — future dating.** Try to record a payment dated next month.
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

| # | Request | Expect |
|---|---|---|
| 6.5a | `/api/reports/doctors/visits?page=0&size=2` | 2 rows |
| 6.5b | `/api/reports/doctors/visits?page=715827883&size=3` | **200** with `[]` — not a 500 |
| 6.5c | `/api/reports/doctors/visits?page=-1&size=-5` | **200**, clamped to the first page |
| 6.5d | `/api/reports/doctors/visits?size=100000` | **200**, at most 100 rows |

---

## 7. Sign-off checklist

| Phase | Result | Notes |
|---|---|---|
| 1. Automated suite green | ☐ | |
| 2. Stack starts, schema validates, seed data correct | ☐ | |
| 3. All three roles sign in and land correctly | ☐ | |
| 3.4 Patient self-registration grants the role in Keycloak | ☐ | |
| 4.1 CRUD works for all five entities | ☐ | |
| 4.2 Role model behaves per §2 | ☐ | |
| 4.3 All ten reports produce correct data | ☐ | |
| 4.4h Doctor approval lifecycle completes and the banner clears | ☐ | |
| 5. Every negative case is refused with the right status | ☐ | |
| 6. Validation, insurance rule, escaping, pagination all hold | ☐ | |

**Ready to demonstrate when every box is ticked.**

---

## Appendix A — Test personas

Realm `medical-records`, seeded from `realm-export.json` on first Keycloak start.

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
| `patient3` | `patient` | PATIENT | Georgi Stoyanov (id 3, GP 1, partial) |
| `patient4`–`patient15` | `patient` | PATIENT | Patients 4–15 |

## Appendix B — Seeded clinical data

| Visit | Patient | Doctor | Date | Diagnosis | Sick leave |
|---|---|---|---|---|---|
| 1 | Ivan Dimitrov | Georgi Ivanov | 2026-07-01 | Acute Bronchitis | 3 days |
| 2 | Petar Ivanov | Georgi Ivanov | 2026-07-02 | Influenza Type A | 5 days |
| 3 | Georgi Stoyanov | Georgi Ivanov | 2026-07-03 | Migraine | 2 days |
| 4 | Maria Vasileva | Elena Koleva | 2026-07-05 | Type 2 Diabetes | — |
| 5 | Elena Nikolova | Elena Koleva | 2026-07-06 | Hypertension | 7 days |
| 6 | Stefan Hristov | Maria Stoyanova | 2026-07-07 | Pneumonia | 4 days |
| 7 | Nikolay Popov | Maria Stoyanova | 2026-07-08 | Allergic Rhinitis | — |
| 8 | Aleksandar Todorov | Dimitar Petrov | 2026-07-09 | Routine Checkup | — |
| 9 | Yordan Angelov | Stefan Georgiev | 2026-07-10 | Arrhythmia | — |
| 10 | Borislav Krastev | Maria Stoyanova | 2026-07-11 | Bronchial Asthma | 5 days |

Sick leaves: 6 total, all July 2026 — Dr. Georgi Ivanov 3, Dr. Elena Koleva 1, Dr. Maria Stoyanova 2.

> Visit dates are fixed at July 2026; only the insurance rows are relative to the current date. The period reports in 4.3g/4.3h therefore always query July 2026.

## Appendix C — Reset to a clean state

```bash
docker compose down -v && docker compose up -d
```

Destroys the MySQL volume so `init.sql` and `test-data.sql` re-run. Required after any test that deletes seeded records, and before a final demonstration run.
