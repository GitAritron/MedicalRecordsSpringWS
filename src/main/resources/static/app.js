let keycloak;
let currentUserRole = '';
let currentDoctorId = null;

// Relative, because Spring serves this page, this script and the API from one origin: the browser
// resolves it against wherever the page was loaded from, so no host needs writing down here.
const API_BASE = '/api';

// Global reference lists for dropdowns and safe lookup
let doctorsList = [];
let patientsList = [];
let visitsList = [];
let specializationsList = [];

document.addEventListener('DOMContentLoaded', () => {
    initKeycloak();
});

function initKeycloak() {
    // Filled in by WebController from app.keycloak.* - the same property the backend's issuer-uri is built from.
    const config = document.getElementById('app').dataset;
    keycloak = new Keycloak({
        url: config.keycloakUrl,
        realm: config.keycloakRealm,
        clientId: config.keycloakClientId
    });

    // PKCE: frontend-client is public (no secret), so this binds the auth code to this browser - a leaked code is useless.
    // The realm enforces it (pkce.code.challenge.method=S256) and refuses an exchange without a verifier.
    keycloak.init({ onLoad: 'check-sso', checkLoginIframe: false, pkceMethod: 'S256' }).then(async authenticated => {
        const usernameEl = document.getElementById('username');
        const userInfo = document.getElementById('userInfo');
        const heroSection = document.getElementById('hero');
        const dashboardSection = document.getElementById('dashboard');

        if (authenticated) {
            if (keycloak.hasRealmRole('ADMIN')) currentUserRole = 'ADMIN';
            else if (keycloak.hasRealmRole('DOCTOR')) currentUserRole = 'DOCTOR';
            else if (keycloak.hasRealmRole('PATIENT')) currentUserRole = 'PATIENT';

            usernameEl.textContent = `${keycloak.tokenParsed.preferred_username} ${currentUserRole ? '('+currentUserRole+')' : '(New User)'}`;
            userInfo.style.display = 'flex';
            heroSection.style.display = 'none';
            dashboardSection.style.display = 'block';

            if (!currentUserRole) {
                renderNewUserRoleSelection();
            } else if (await isOrphanedAccount()) {
                // Holds a role but the record behind it is gone - see renderNewUserRoleSelection.
                renderNewUserRoleSelection();
            } else {
                renderTabs();
                loadDashboard();
            }
        } else {
            // Signed out the header is just the wordmark: the landing page carries the call to
            // action, and a "Not logged in" badge tells the reader nothing that page does not.
            userInfo.style.display = 'none';
            heroSection.style.display = 'block';
            dashboardSection.style.display = 'none';
        }
    }).catch(error => {
        console.error('Failed to initialize Keycloak', error);
    });
}

function login() { keycloak.login(); }
function register() { keycloak.register(); }
function logout() { keycloak.logout(); }

// Escapes quotes too, so a value inside an attribute (value="${esc(x)}") can't break out of it.
const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
function esc(str) {
    if (str == null) return '';
    return String(str).replace(/[&<>"']/g, ch => ESC_MAP[ch]);
}

// onclick handlers carry ids only - esc() is NOT safe there, the parser turns an escaped quote back
// into a real one before the JS runs. Names are looked up from the id instead.
function patientNameById(patientId) {
    const p = patientsList.find(x => x.id === patientId);
    return p ? `${p.firstName} ${p.lastName}` : `ID ${patientId}`;
}

function doctorNameById(doctorId) {
    const d = doctorsList.find(x => x.id === doctorId);
    return d ? `${d.firstName} ${d.lastName}` : `ID ${doctorId}`;
}

// ================= DATE FORMATTING =================
// Every date shown goes through these, in the Bulgarian dd.mm.yyyy - the API speaks ISO (yyyy-mm-dd).
// Never use them to fill an <input type="date">; that control requires ISO.

/** "2026-07-01" (or an ISO datetime) -> "01.07.2026" */
function fmtDate(value) {
    if (!value) return '';
    const [y, m, d] = String(value).slice(0, 10).split('-');
    return (y && m && d) ? `${d}.${m}.${y}` : String(value);
}

/** "09:00:00" -> "09:00" */
function fmtTime(value) {
    return value ? String(value).slice(0, 5) : '';
}

/** ISO datetime -> "01.07.2026 14:30" */
function fmtDateTime(value) {
    if (!value) return '';
    const dt = new Date(value);
    if (isNaN(dt.getTime())) return String(value);
    const p = n => String(n).padStart(2, '0');
    return `${p(dt.getDate())}.${p(dt.getMonth() + 1)}.${dt.getFullYear()} ${p(dt.getHours())}:${p(dt.getMinutes())}`;
}

/** Year and month numbers -> "07.2026" */
function fmtMonth(year, month) {
    return `${String(month).padStart(2, '0')}.${year}`;
}

// yyyy-mm-dd in local time - toISOString() is UTC, which is still yesterday until 03:00 in Sofia.
function localIsoDate(d = new Date()) {
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

// The current month is listed as unpaid but is not overdue until it ends.
function fmtUnpaidMonth(m) {
    const now = new Date();
    const isCurrent = m.year === now.getFullYear() && m.month === now.getMonth() + 1;
    return fmtMonth(m.year, m.month) + (isCurrent ? ' (due by month end)' : '');
}

async function fetchWithAuth(url, options = {}) {
    try {
        await keycloak.updateToken(30);
    } catch (err) {
        console.error('Token refresh failed, redirecting to login', err);
        keycloak.login();
        return;
    }
    const headers = {
        'Authorization': 'Bearer ' + keycloak.token,
        'Content-Type': 'application/json'
    };
    return fetch(url, { ...options, headers });
}

function setActiveTab(clickedBtn) {
    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
    clickedBtn.classList.add('active');
}

// Hidden on screens with no .data-table (registration, awaiting approval), where it can't filter anything.
function setSearchVisible(visible) {
    const bar = document.querySelector('.filter-bar');
    if (!bar) return;
    bar.style.display = visible ? '' : 'none';
    if (!visible) {
        const input = document.getElementById('searchInput');
        if (input) input.value = '';
    }
}

// Every role's tabs in one order: own view, shared directories, visits, reports, admin log last.
// landing = the opening screen. Doctors get the All tabs because spec §2 lets them see every patient and doctor.
const TABS_BY_ROLE = {
    ADMIN: [
        { label: 'Patients',   load: loadAllPatients },
        { label: 'Doctors',    load: loadDoctorsDirectory },
        { label: 'Visits',     load: loadDoctorVisits },
        { label: 'Reports',    load: loadFullReportsDashboard, landing: true },
        { label: 'Audit Logs', load: loadAuditLogs }
    ],
    DOCTOR: [
        { label: 'My Patients',  load: loadMyPatients, landing: true },
        { label: 'All Patients', load: loadAllPatients },
        { label: 'All Doctors',  load: loadDoctorsDirectory },
        { label: 'Visits',       load: loadDoctorVisits },
        { label: 'Reports',      load: loadFullReportsDashboard }
    ],
    PATIENT: [
        { label: 'My Medical History', load: loadPatientHistory, landing: true },
        { label: 'Doctors Directory',  load: loadDoctorsDirectory }
    ]
};

// Each screen's loader -> its tab button, so a screen reached without a click still lights its tab.
const tabButtons = new Map();

function renderTabs() {
    setSearchVisible(true);
    const navTabs = document.getElementById('nav-tabs');
    navTabs.innerHTML = '';
    tabButtons.clear();

    (TABS_BY_ROLE[currentUserRole] || []).forEach(tab => {
        const button = document.createElement('button');
        button.className = tab.landing ? 'tab-btn active' : 'tab-btn';
        button.textContent = tab.label;
        button.addEventListener('click', () => tab.load());
        tabButtons.set(tab.load, button);
        navTabs.appendChild(button);
    });
}

// Called at the top of every tab's loader.
function markActiveTab(loader) {
    const button = tabButtons.get(loader);
    if (button) setActiveTab(button);
}

// Re-runs the screen of the highlighted tab, for actions that are offered on more than one screen.
async function reloadActiveTab() {
    const active = [...tabButtons].find(([, button]) => button.classList.contains('active'));
    if (active) await active[0]();
}

/** Opens whichever screen the role's tab strip marks as its landing, so the two stay in step. */
async function loadDashboard() {
    const landing = (TABS_BY_ROLE[currentUserRole] || []).find(tab => tab.landing);
    if (landing) {
        await landing.load();
    }
}

// ================= NEW USER ROLE SELECTION =================

// Holds PATIENT/DOCTOR but the record is gone (the account survives a delete, and revoking the role is best-effort).
// Treated as unregistered so they can make a new profile instead of a dead end. Admins are exempt.
async function isOrphanedAccount() {
    if (currentUserRole !== 'PATIENT' && currentUserRole !== 'DOCTOR') return false;
    try {
        const res = await fetchWithAuth(`${API_BASE}/registration-status`);
        if (!res.ok) return false;   // never strand a user because this check failed
        const status = await res.json();
        return currentUserRole === 'PATIENT' ? !status.hasPatientProfile : !status.hasDoctorProfile;
    } catch (err) {
        console.error('Could not read registration status', err);
        return false;
    }
}

// No role = not registered yet OR a doctor applicant waiting for approval. Telling them apart stops
// an applicant getting the role picker on every sign-in.
async function renderNewUserRoleSelection() {
    setSearchVisible(false);
    const contentArea = document.getElementById('content-area');
    try {
        const res = await fetchWithAuth(`${API_BASE}/registration-status`);
        if (res.ok) {
            const status = await res.json();
            if (status.hasDoctorProfile) {
                contentArea.innerHTML = `
                    <div style="max-width: 550px; margin: 2rem auto; text-align: center; background: rgba(245, 158, 11, 0.1); border: 1px solid #F59E0B; padding: 2rem; border-radius: 12px;">
                        <h3 style="color: #F59E0B;">Application under review</h3>
                        <p style="color: #94A3B8; margin-top: 0.75rem;">An administrator still needs to approve your application.</p>
                        <p style="color: #64748B; font-size: 0.85rem; margin-top: 1rem;">Once approved, sign out and back in to pick up your new role.</p>
                        <button class="btn btn-secondary" style="margin-top: 1.25rem;" onclick="logout()">Sign out</button>
                    </div>`;
                return;
            }
            if (status.hasPatientProfile) {
                contentArea.innerHTML = `
                    <div style="max-width: 550px; margin: 2rem auto; text-align: center; background: rgba(56, 189, 248, 0.1); border: 1px solid #38BDF8; padding: 2rem; border-radius: 12px;">
                        <h3 style="color: #38BDF8;">Profile already created</h3>
                        <p style="color: #94A3B8; margin-top: 0.75rem;">Your patient profile exists but your account has not picked up the PATIENT role yet.</p>
                        <p style="color: #64748B; font-size: 0.85rem; margin-top: 0.75rem;">Reloading cannot grant the role - re-submitting your registration can.</p>
                        <button class="btn btn-primary" style="margin-top: 1.25rem;" onclick="retryPatientActivation()">Retry activation</button>
                    </div>`;
                return;
            }
        }
    } catch (err) {
        // Fall through to the role picker - a status check failure should not block registration.
        console.error('Could not read registration status', err);
    }

    renderRolePicker();
}

function renderRolePicker() {
    document.getElementById('content-area').innerHTML = `
        <div style="max-width: 550px; margin: 2rem auto; text-align: center;">
            <h3>Welcome, ${esc(keycloak.tokenParsed.given_name || keycloak.tokenParsed.preferred_username)}!</h3>

            <div id="rolePicker">
                <p class="muted-label" style="margin-bottom: 2rem;">Please select your role to complete your profile setup.</p>
                <div class="role-choice-grid">
                    <div class="report-card role-choice" onclick="showPatientRegistrationForm()">
                        <h4>I am a Patient</h4>
                        <p>Register your patient profile to view medical visits, diagnoses, and prescriptions.</p>
                    </div>
                    <div class="report-card role-choice" onclick="showDoctorRegistrationForm()">
                        <h4>I am a Doctor</h4>
                        <p>Submit your doctor credentials for administrator review and activation.</p>
                    </div>
                </div>
            </div>

            <div id="selfRegContainer" style="margin-top: 2rem; text-align: left;"></div>
        </div>
    `;
}

// Finishes a registration whose PATIENT grant failed. Re-opens the form because the EGN isn't known here;
// self-registration is idempotent, so resubmitting only grants the role.
async function retryPatientActivation() {
    renderRolePicker();
    await showPatientRegistrationForm();
    const container = document.getElementById('selfRegContainer');
    container.insertAdjacentHTML('afterbegin', `
        <div style="background: rgba(56, 189, 248, 0.1); border: 1px solid #38BDF8; color: #94A3B8; padding: 0.9rem 1rem; border-radius: 8px; margin-bottom: 1rem;">
            Your profile already exists - re-enter your details to finish activating your account.
            No second record will be created.
        </div>`);
}

// No GP dropdown: with no role yet the doctor list is a 403. The GP is chosen from their dashboard right after.
async function showPatientRegistrationForm() {
    const container = document.getElementById('selfRegContainer');
    document.getElementById('rolePicker').hidden = true;
    container.innerHTML = `
        <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.5rem; border-radius: 12px;">
            <h4>Complete Patient Profile</h4>
            <form onsubmit="submitSelfPatientRegistration(event)">
                <div class="form-group">
                    <label for="selfFirstName">First Name:</label>
                    <input type="text" id="selfFirstName" value="${esc(keycloak.tokenParsed.given_name || '')}" required>
                </div>
                <div class="form-group">
                    <label for="selfLastName">Last Name:</label>
                    <input type="text" id="selfLastName" value="${esc(keycloak.tokenParsed.family_name || '')}" required>
                </div>
                <div class="form-group">
                    <label for="selfEgn">EGN (10 Digits):</label>
                    <input type="text" id="selfEgn" required pattern="\\d{10}" title="Must be exactly 10 digits">
                </div>
                <p style="color: #94A3B8; font-size: 0.85rem; margin: 0.75rem 0 0;">
                    You will choose your GP from your dashboard afterwards.
                </p>
                <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 1rem;">Complete Registration</button>
            </form>
            <button type="button" class="btn-back" onclick="backToRoleChoice()">&larr; Choose a different role</button>
        </div>
    `;
}

function showDoctorRegistrationForm() {
    const container = document.getElementById('selfRegContainer');
    document.getElementById('rolePicker').hidden = true;
    container.innerHTML = `
        <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.5rem; border-radius: 12px;">
            <h4>Submit Doctor Application</h4>
            <form onsubmit="submitSelfDoctorRegistration(event)">
                <div class="form-group">
                    <label for="selfDocFirstName">First Name:</label>
                    <input type="text" id="selfDocFirstName" value="${esc(keycloak.tokenParsed.given_name || '')}" required>
                </div>
                <div class="form-group">
                    <label for="selfDocLastName">Last Name:</label>
                    <input type="text" id="selfDocLastName" value="${esc(keycloak.tokenParsed.family_name || '')}" required>
                </div>
                <p style="color: #94A3B8; font-size: 0.85rem; margin: 0.75rem 0 0;">
                    An administrator reviews every application before your account is activated.
                </p>
                <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 1rem;">Submit Application</button>
            </form>
            <button type="button" class="btn-back" onclick="backToRoleChoice()">&larr; Choose a different role</button>
        </div>
    `;
}

/** Restores the role picker after a mis-click, discarding whatever was typed into the open form. */
function backToRoleChoice() {
    document.getElementById('selfRegContainer').innerHTML = '';
    document.getElementById('rolePicker').hidden = false;
}

async function submitSelfPatientRegistration(e) {
    e.preventDefault();
    // No keycloakId: the server binds the profile to our verified token subject.
    // No gpDoctorId: chosen from the dashboard once the PATIENT role has been granted.
    const patientData = {
        firstName: document.getElementById('selfFirstName').value.trim(),
        lastName: document.getElementById('selfLastName').value.trim(),
        egn: document.getElementById('selfEgn').value.trim(),
        gpDoctorId: null
    };

    try {
        const res = await fetchWithAuth(`${API_BASE}/patients/self-register`, { method: 'POST', body: JSON.stringify(patientData) });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, `HTTP ${res.status}`));
        }
        // Backend auto-assigns PATIENT role in Keycloak.
        // Force token refresh to pick up the new role, then reload the app.
        try {
            await keycloak.updateToken(-1); // Force refresh by passing -1 (always refresh)
        } catch (refreshErr) {
            console.warn('Token refresh failed, reloading page...', refreshErr);
        }
        // Check if the refreshed token now has the PATIENT role
        if (keycloak.hasRealmRole('PATIENT')) {
            currentUserRole = 'PATIENT';
            document.getElementById('username').textContent = `${keycloak.tokenParsed.preferred_username} (PATIENT)`;
            renderTabs();
            loadPatientHistory();
        } else {
            // Fallback: role propagation may take a moment, reload the page
            const container = document.getElementById('selfRegContainer');
            container.innerHTML = `
                <div style="background: rgba(16, 185, 129, 0.15); border: 1px solid #10B981; color: #10B981; padding: 1.5rem; border-radius: 12px; text-align: center;">
                    <h4>Registration saved</h4>
                    <p>Your patient profile has been created. Your account has not picked up the PATIENT role
                       yet - reload to try again. If this screen comes back, press <em>Retry activation</em>
                       there; patients need no approval, so the only thing outstanding is the role itself.</p>
                    <button class="btn btn-primary" style="margin-top: 1rem;" onclick="location.reload()">Reload Now</button>
                </div>
            `;
        }
    } catch (err) { alert("Registration Error: " + err.message); }
}

async function submitSelfDoctorRegistration(e) {
    e.preventDefault();
    // No keycloakId and no isGP: the server derives the identity from our token, and only an
    // administrator may grant GP status.
    const doctorData = {
        firstName: document.getElementById('selfDocFirstName').value.trim(),
        lastName: document.getElementById('selfDocLastName').value.trim()
    };

    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/self-register`, { method: 'POST', body: JSON.stringify(doctorData) });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, `HTTP ${res.status}`));
        }
        const container = document.getElementById('selfRegContainer');
        container.innerHTML = `
            <div style="background: rgba(16, 185, 129, 0.15); border: 1px solid #10B981; color: #10B981; padding: 1.5rem; border-radius: 12px; text-align: center;">
                <h4>Registration Submitted!</h4>
                <p>Your application has been submitted. An administrator reviews it before your account is activated.</p>
            </div>
        `;
    } catch (err) { alert("Registration Error: " + err.message); }
}

// ================= REFERENCE DATA HELPERS =================

async function fetchDoctorsList() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors`);
        if (res.ok) doctorsList = await res.json();
    } catch (e) { console.error("Error fetching doctors", e); }
}

async function fetchPatientsList() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/patients`);
        if (res.ok) patientsList = await res.json();
    } catch (e) { console.error("Error fetching patients", e); }
}

async function fetchSpecializations() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/specializations`);
        if (res.ok) specializationsList = await res.json();
    } catch (e) { console.error("Error fetching specializations", e); }
}

// ================= MODAL LOGIC =================

function openModal(modalId) { document.getElementById(modalId).showModal(); }
function closeModal(modalId) {
    document.getElementById(modalId).close();
    // Reset visit modal state when closing
    if (modalId === 'visitModal') {
        document.getElementById('visitBasicFields').style.display = 'block';
        document.getElementById('clinicalDataSection').style.display = 'none';
        document.getElementById('visitSummary').style.display = 'none';
        document.getElementById('diagnosisList').innerHTML = '';
        document.getElementById('prescriptionList').innerHTML = '';
        document.getElementById('sickLeaveDisplay').innerHTML = '';
        document.getElementById('sickLeaveForm').style.display = 'flex';
        currentEditVisitId = null;
        currentVisitClinicalData = null;
        editingSickLeaveId = null;
        editingClinicalItem = null;
        renderSickLeaveFormMode();
        (visitModalReturn || loadDoctorVisits)();
        visitModalReturn = null;
    }
}

// ================= SPECIALIZATION CREATION (ADMIN) =================

function openAddSpecModal() {
    document.getElementById('specForm').reset();
    const msgEl = document.getElementById('specMsg');
    if (msgEl) msgEl.innerHTML = '';
    openModal('specModal');
}

async function submitSpecForm(e) {
    e.preventDefault();
    const name = document.getElementById('specName').value.trim();
    const msgEl = document.getElementById('specMsg');
    if (msgEl) msgEl.innerHTML = '';

    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/specializations`, {
            method: 'POST',
            body: JSON.stringify({ name })
        });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, `HTTP ${res.status}`));
        }
        if (msgEl) {
            msgEl.innerHTML = `<p style="color: #10B981; margin: 0.5rem 0; font-weight: 500;">✓ Specialization "${esc(name)}" created successfully!</p>`;
        }
        await fetchSpecializations();
        setTimeout(() => {
            closeModal('specModal');
            if (msgEl) msgEl.innerHTML = '';
            // Refresh the management screen if that is what opened this form.
            if (document.querySelector('#content-area h3')?.textContent === 'Specializations') {
                manageSpecializations();
            }
        }, 1200);
    } catch (err) {
        if (msgEl) {
            msgEl.innerHTML = `<p style="color: #EF4444; margin: 0.5rem 0;">Error: ${esc(err.message)}</p>`;
        }
    }
}

// Rename and delete for specializations - otherwise a misspelt one stays on every doctor holding it.
async function manageSpecializations() {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading specializations...</p>';
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/specializations`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        specializationsList = await res.json();

        let html = '<h3>Specializations</h3>';
        html += `<button class="btn btn-primary btn-add-new" onclick="openAddSpecModal()">+ Add Specialization</button> `;
        html += `<button class="btn btn-secondary btn-add-new" onclick="loadDoctorsDirectory()">← Back to Doctors</button>`;

        renamingSpecializationId = null;
        contentArea.innerHTML = html + '<div id="specializationsTable"></div>';
        renderSpecializationsTable();
    } catch (err) {
        contentArea.innerHTML = `<p class="error-text">Error loading specializations: ${esc(err.message)}</p>`;
    }
}

// The specialization whose name is open for editing, if any.
let renamingSpecializationId = null;

function renderSpecializationsTable() {
    const container = document.getElementById('specializationsTable');
    if (specializationsList.length === 0) {
        container.innerHTML = '<p class="muted-label">No specializations recorded.</p>';
        return;
    }
    let html = '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">ID</th>'
             + '<th onclick="sortTable(1, this)">Name</th><th>Actions</th></tr></thead><tbody>';
    specializationsList.forEach(s => {
        html += s.id === renamingSpecializationId
            ? `<tr><td>${s.id}</td><td><input type="text" id="specRenameInput" class="inline-input" value="${esc(s.name)}" onkeydown="onSpecRenameKey(event)"></td>`
              + `<td><button class="action-btn btn-save" onclick="saveSpecializationRename()">Save</button> `
              + `<button class="action-btn btn-cancel" onclick="cancelSpecializationRename()">Cancel</button></td></tr>`
            : `<tr><td>${s.id}</td><td>${esc(s.name)}</td>`
              + `<td><button class="action-btn btn-edit" onclick="renameSpecialization(${s.id})">Rename</button> `
              + `<button class="action-btn btn-delete" onclick="deleteSpecialization(${s.id})">Delete</button></td></tr>`;
    });
    container.innerHTML = html + '</tbody></table>';
}

function renameSpecialization(id) {
    renamingSpecializationId = id;
    renderSpecializationsTable();
    const input = document.getElementById('specRenameInput');
    input.focus();
    input.select();
}

function cancelSpecializationRename() {
    renamingSpecializationId = null;
    renderSpecializationsTable();
}

function onSpecRenameKey(event) {
    if (event.key === 'Enter') saveSpecializationRename();
    else if (event.key === 'Escape') cancelSpecializationRename();
}

async function saveSpecializationRename() {
    const current = specializationsList.find(s => s.id === renamingSpecializationId);
    if (!current) return;
    const name = document.getElementById('specRenameInput').value.trim();
    if (!name) { alert('Please enter a name.'); return; }
    if (name === current.name) { cancelSpecializationRename(); return; }
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/specializations/${current.id}`, {
            method: 'PUT', body: JSON.stringify({ name })
        });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to rename specialization'));
        await manageSpecializations();
    } catch (err) { alert(err.message); }
}

// Takes it off every doctor holding it. The doctors stay - one with no specialization is valid.
async function deleteSpecialization(id) {
    const current = specializationsList.find(s => s.id === id);
    const name = current ? current.name : `#${id}`;
    if (!confirm(`Delete "${name}"? It will be removed from every doctor who holds it. The doctors themselves are kept.`)) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/specializations/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to delete specialization'));
        await manageSpecializations();
    } catch (err) { alert(err.message); }
}

// Insurance for every patient, keyed by id, in one request. Empty map on failure so the list still renders.
async function fetchInsuranceStatuses() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/reports/patients/insurance`);
        if (!res.ok) return {};
        const rows = await res.json();
        return Object.fromEntries(rows.map(r => [r.patientId, r]));
    } catch (e) {
        console.error('Could not load insurance status', e);
        return {};
    }
}

// Shows months owed, not the unbroken run - a gap in the middle makes the run ("1/6") look far worse than it is.
function insuranceCell(status) {
    if (!status) return '<span class="muted">-</span>';
    const owed = (status.unpaidMonths || []).length;
    const suffix = owed === 0
        ? ''
        : ` <span class="muted" style="font-size:0.85em;">${owed} month${owed === 1 ? '' : 's'} owed</span>`;
    return status.insured
        ? `<span class="status-ok">Insured</span>${suffix}`
        : `<span class="status-bad">Not insured</span>${suffix}`;
}

// ================= PATIENT CRUD =================

async function openAddPatientModal() {
    await fetchDoctorsList();
    document.getElementById('patientForm').reset();
    document.getElementById('patientId').value = '';
    document.getElementById('patientKeycloakId').value = '';
    document.getElementById('patientUsername').value = '';
    document.getElementById('patientEgn').readOnly = false;
    // Nothing to re-issue for a record that does not exist yet.
    document.getElementById('patientAccountActions').style.display = 'none';
    syncAccountFields('patient');
    document.getElementById('patientModalTitle').innerText = 'Add New Patient';

    populateGpDropdown();
    openModal('patientModal');
}

async function editPatient(id) {
    await fetchDoctorsList();
    const patient = patientsList.find(p => p.id === id);
    if (!patient) return;

    document.getElementById('patientId').value = patient.id;
    document.getElementById('patientKeycloakId').value = patient.keycloakId || '';
    document.getElementById('patientFirstName').value = patient.firstName;
    document.getElementById('patientLastName').value = patient.lastName;
    document.getElementById('patientEgn').value = patient.egn;
    // The EGN identifies the person, so it is fixed once the record exists.
    document.getElementById('patientEgn').readOnly = true;
    // Blank on open: a username here means "create a new account", never "the current one".
    document.getElementById('patientUsername').value = '';
    document.getElementById('patientAccountActions').style.display = patient.keycloakId ? 'block' : 'none';
    syncAccountFields('patient');
    document.getElementById('patientModalTitle').innerText = 'Edit Patient';

    populateGpDropdown(patient.gpDoctorId);
    openModal('patientModal');
}

// GPs only - the server rejects anyone else. Same filter as openChangeGpModal.
function populateGpDropdown(selectedGpId = null) {
    const gpSelect = document.getElementById('patientGpId');
    gpSelect.innerHTML = '<option value="">-- No GP Assigned --</option>';

    doctorsList.filter(doc => doc.isGP).forEach(doc => {
        const selected = (selectedGpId && doc.id === selectedGpId) ? 'selected' : '';
        gpSelect.innerHTML += `<option value="${doc.id}" ${selected}>Dr. ${esc(doc.firstName)} ${esc(doc.lastName)} (GP)</option>`;
    });
}

async function submitPatientForm(e) {
    e.preventDefault();
    const id = document.getElementById('patientId').value;
    const gpVal = document.getElementById('patientGpId').value;

    const patientData = {
        // Sent as-is, not `|| null`: absent = leave the link alone, "" = unlink. Null would make clearing the box do nothing.
        keycloakId: document.getElementById('patientKeycloakId').value.trim(),
        firstName: document.getElementById('patientFirstName').value.trim(),
        lastName: document.getElementById('patientLastName').value.trim(),
        egn: document.getElementById('patientEgn').value.trim(),
        gpDoctorId: gpVal ? Number(gpVal) : null,
        username: document.getElementById('patientUsername').value.trim()
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/patients/${id}` : `${API_BASE}/patients`;

    try {
        const response = await fetchWithAuth(url, { method, body: JSON.stringify(patientData) });
        if (!response.ok) {
            throw new Error(await apiErrorMessage(response, `HTTP ${response.status}`));
        }
        const saved = await response.json();
        closeModal('patientModal');
        loadAllPatients();
        showCredentialsIfIssued(patientData.username, saved);
    } catch (err) { alert("Error saving patient: " + err.message); }
}

async function deletePatient(id) {
    // Their visits are deleted with them, so say how many before asking.
    let warning = 'This also deletes their whole medical history.';
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/patient/${id}`);
        if (res.ok) {
            const count = (await res.json()).length;
            warning = count === 0
                ? 'They have no recorded visits.'
                : `This also deletes their ${count} recorded visit${count === 1 ? '' : 's'}, with every diagnosis, prescription and sick leave.`;
        }
    } catch (e) { /* keep the generic warning */ }
    if (!confirm(`Delete ${patientNameById(id)}? ${warning}`)) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/patients/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(await apiErrorMessage(response, 'Failed to delete patient'));
        loadAllPatients();
    } catch (err) { alert(err.message); }
}

// Which patient list we came from, so "← Back to Patients" returns to the one whose tab is highlighted.
let patientListOrigin = 'all';

function backToPatientList() {
    return patientListOrigin === 'mine' ? loadMyPatients() : loadAllPatients();
}

async function loadAllPatients() {
    markActiveTab(loadAllPatients);
    const contentArea = document.getElementById('content-area');
    managingInsuranceForPatientId = null;   // this screen replaces the insurance one
    patientListOrigin = 'all';
    contentArea.innerHTML = '<p>Loading patients...</p>';
    try {
        const response = await fetchWithAuth(`${API_BASE}/patients`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        patientsList = await response.json();
        const insurance = await fetchInsuranceStatuses();

        let html = `<h3>All Patients</h3>`;
        if (currentUserRole === 'ADMIN') {
            html += `<button class="btn btn-primary btn-add-new" onclick="openAddPatientModal()">+ Add New Patient</button>`;
        }

        html += `<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">EGN</th><th onclick="sortTable(2, this)">GP Doctor</th><th onclick="sortTable(3, this)">Insurance</th><th>Actions</th></tr></thead><tbody>`;

        patientsList.forEach(p => {
            const safeName = esc(`${p.firstName} ${p.lastName}`);
            html += `<tr><td>${safeName}</td><td>${esc(p.egn)}</td><td>${esc(p.gpDoctorName || 'Not assigned')}</td><td>${insuranceCell(insurance[p.id])}</td>`;
            html += `<td>`;
            html += `<button class="action-btn btn-view" onclick="viewPatientVisitsDirect(${p.id})">Visits</button> `;
            if (currentUserRole === 'ADMIN') {
                html += `<button class="action-btn btn-insurance" onclick="manageInsurance(${p.id})">Insurance</button> `;
                html += `<button class="action-btn btn-edit" onclick="editPatient(${p.id})">Edit</button> `;
                html += `<button class="action-btn btn-delete" onclick="deletePatient(${p.id})">Delete</button>`;
            }
            html += `</td></tr>`;
        });
        html += '</tbody></table>';
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p class="error-text">Error loading patients: ${esc(error.message)}</p>`;
    }
}

// ================= DOCTOR CRUD =================

async function openAddDoctorModal() {
    await fetchSpecializations();
    document.getElementById('doctorForm').reset();
    document.getElementById('doctorId').value = '';
    document.getElementById('doctorKeycloakId').value = '';
    document.getElementById('doctorUsername').value = '';
    document.getElementById('doctorAccountActions').style.display = 'none';
    syncAccountFields('doctor');
    document.getElementById('doctorModalTitle').innerText = 'Add New Doctor';

    populateSpecsCheckboxes();
    openModal('doctorModal');
}

async function editDoctor(id) {
    await fetchSpecializations();
    const doctor = doctorsList.find(d => d.id === id);
    if (!doctor) return;

    document.getElementById('doctorId').value = doctor.id;
    document.getElementById('doctorKeycloakId').value = doctor.keycloakId || '';
    document.getElementById('doctorFirstName').value = doctor.firstName;
    document.getElementById('doctorLastName').value = doctor.lastName;
    document.getElementById('doctorIsGp').checked = doctor.isGP;
    // Blank on open: a username here means "create a new account", never "the current one".
    document.getElementById('doctorUsername').value = '';
    document.getElementById('doctorAccountActions').style.display = doctor.keycloakId ? 'block' : 'none';
    syncAccountFields('doctor');
    document.getElementById('doctorModalTitle').innerText = 'Edit Doctor';

    populateSpecsCheckboxes(doctor.specializations);
    openModal('doctorModal');
}

function populateSpecsCheckboxes(assignedSpecs = []) {
    const container = document.getElementById('doctorSpecsContainer');
    container.innerHTML = '';
    if (specializationsList.length === 0) {
        container.innerHTML = '<span style="font-size:0.85rem; color:#94a3b8;">No specializations available.</span>';
        return;
    }

    specializationsList.forEach(spec => {
        const isChecked = assignedSpecs && assignedSpecs.some(s => (s === spec.name || s.name === spec.name || s.id === spec.id));
        container.innerHTML += `
            <label class="checkbox-item">
                <input type="checkbox" class="spec-cb" value="${spec.id}" ${isChecked ? 'checked' : ''}>
                ${esc(spec.name)}
            </label>
        `;
    });
}

async function submitDoctorForm(e) {
    e.preventDefault();
    const id = document.getElementById('doctorId').value;
    const checkedBoxes = Array.from(document.querySelectorAll('.spec-cb:checked'));
    const specIds = checkedBoxes.map(cb => Number(cb.value));

    const doctorData = {
        // See submitPatientForm: empty means unlink, absent means leave alone.
        keycloakId: document.getElementById('doctorKeycloakId').value.trim(),
        firstName: document.getElementById('doctorFirstName').value.trim(),
        lastName: document.getElementById('doctorLastName').value.trim(),
        isGP: document.getElementById('doctorIsGp').checked,
        specializationIds: specIds,
        username: document.getElementById('doctorUsername').value.trim()
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/doctors/${id}` : `${API_BASE}/doctors`;

    try {
        const response = await fetchWithAuth(url, { method, body: JSON.stringify(doctorData) });
        if (!response.ok) {
            throw new Error(await apiErrorMessage(response, `HTTP ${response.status}`));
        }
        const saved = await response.json();
        closeModal('doctorModal');
        loadDoctorsDirectory();
        showCredentialsIfIssued(doctorData.username, saved);
    } catch (err) { alert("Error saving doctor: " + err.message); }
}

// ================= ACCOUNT PROVISIONING =================

// Shows the one-time password only if the response has one - the server decides whether an account was created.
// A linked record can't get a second account, so the username box is disabled instead of ending in a 400.
function syncAccountFields(prefix) {
    const usernameInput = document.getElementById(prefix + 'Username');
    const linked = document.getElementById(prefix + 'KeycloakId').value.trim().length > 0;

    usernameInput.disabled = linked;
    if (linked) usernameInput.value = '';
    document.getElementById(prefix + 'AccountNote').style.display = linked ? 'block' : 'none';
}

function showCredentialsIfIssued(username, saved) {
    if (!saved || !saved.temporaryPassword) return;
    showCredentials(`Username: ${username}`, saved.temporaryPassword);
}

// accountLine is always "Username: x" - the value they sign in with.
function showCredentials(accountLine, password) {
    document.getElementById('credentialsText').value =
        `${accountLine}\nTemporary password: ${password}`;
    openModal('credentialsModal');
}

function copyCredentials() {
    const field = document.getElementById('credentialsText');
    field.select();
    navigator.clipboard.writeText(field.value).catch(() => {
        // Clipboard access can be refused; the text is selected either way, so Ctrl+C still works.
        console.warn('Clipboard write refused; text is selected for manual copying.');
    });
}

// New one-time password. resource: 'patients' or 'doctors'; idFieldId: the hidden id input in the open modal.
async function reissuePassword(resource, idFieldId) {
    const id = document.getElementById(idFieldId).value;
    if (!id) return;
    if (!confirm('Issue a new temporary password? The current one stops working immediately.')) return;

    try {
        const res = await fetchWithAuth(`${API_BASE}/${resource}/${id}/reissue-password`, { method: 'POST' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to re-issue password'));
        const saved = await res.json();
        // The username only exists in Keycloak - the record stores just the account UUID.
        showCredentials(`Username: ${saved.username}`, saved.temporaryPassword);
    } catch (err) { alert(err.message); }
}

// Also turns down an application from the Reports banner. Their sign-in account stays, so they can apply again.
async function deleteDoctor(id) {
    await fetchDoctorsList();
    const doctor = doctorsList.find(d => d.id === id);
    const question = doctor && doctor.pending
        ? `Delete the application from Dr. ${doctorNameById(id)}? Their sign-in account stays, so they can apply again.`
        : `Delete Dr. ${doctorNameById(id)}?`;
    if (!confirm(question)) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/doctors/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(await apiErrorMessage(response, 'Failed to delete doctor'));
        await reloadActiveTab();
    } catch (err) { alert(err.message); }
}

async function loadDoctorsDirectory() {
    markActiveTab(loadDoctorsDirectory);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading doctors...</p>';
    try {
        const response = await fetchWithAuth(`${API_BASE}/doctors`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        doctorsList = await response.json();

        let html = `<h3>Doctors Directory</h3>`;
        if (currentUserRole === 'ADMIN') {
            html += `<button class="btn btn-primary btn-add-new" onclick="openAddDoctorModal()">+ Add New Doctor</button> `;
            html += `<button class="btn btn-secondary btn-add-new" onclick="manageSpecializations()">Specializations</button>`;
        }

        // Clinical staff get an Actions column; only admins get the editing controls inside it.
        const showActions = currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR';

        html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">ID</th><th onclick="sortTable(1, this)">Name</th><th onclick="sortTable(2, this)">GP Status</th><th onclick="sortTable(3, this)">Specializations</th>';
        if (showActions) html += `<th>Actions</th>`;
        html += `</tr></thead><tbody>`;

        // Patients don't see unapproved applicants - they can't sign in yet. Staff see every row, applications marked.
        const visible = currentUserRole === 'PATIENT'
            ? doctorsList.filter(doc => !doc.pending)
            : doctorsList;

        visible.forEach(doc => {
            const specs = doc.specializations && doc.specializations.length > 0
                ? doc.specializations.map(s => esc(s.name || s)).join(', ')
                : '-';
            const name = `Dr. ${esc(doc.firstName)} ${esc(doc.lastName)}`
                + (doc.pending ? ' <span class="pending-badge">Pending approval</span>' : '');
            html += `<tr><td>${doc.id}</td><td>${name}</td><td>${doc.isGP ? 'Yes' : 'No'}</td><td>${specs}</td>`;
            if (showActions) {
                html += `<td>`;
                // Requirement 3c: list of patients registered with a given GP. Only general
                // practitioners have a patient roster, so the button belongs on those rows.
                if (doc.isGP) {
                    html += `<button class="action-btn btn-view" onclick="viewGpPatients(${doc.id})">Patients</button> `;
                }
                if (currentUserRole === 'ADMIN' && doc.pending) {
                    html += `<button class="action-btn btn-approve" onclick="approveDoctor(${doc.id})">Approve</button> `;
                }
                if (currentUserRole === 'ADMIN') {
                    html += `<button class="action-btn btn-edit" onclick="editDoctor(${doc.id})">Edit</button>
                    <button class="action-btn btn-delete" onclick="deleteDoctor(${doc.id})">Delete</button>`;
                }
                html += `</td>`;
            }
            html += `</tr>`;
        });
        html += '</tbody></table>';
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p class="error-text">Error loading doctors: ${esc(error.message)}</p>`;
    }
}

// ================= VISIT CRUD =================

// Where closing the visit modal leads; null means the All Visits list.
let visitModalReturn = null;

// Opened from a patient's screen, the patient is preselected and closing leads back to their visits.
async function openAddVisitModal(patientId = null) {
    visitModalReturn = patientId ? () => viewPatientVisitsDirect(patientId) : null;
    await Promise.all([fetchDoctorsList(), fetchPatientsList()]);
    document.getElementById('visitForm').reset();
    document.getElementById('visitId').value = '';
    document.getElementById('visitModalTitle').innerText = 'Add New Visit';
    document.getElementById('visitBasicFields').style.display = 'block';
    document.getElementById('clinicalDataSection').style.display = 'none';
    document.getElementById('visitSummary').style.display = 'none';
    currentEditVisitId = null;

    const now = new Date();
    const today = localIsoDate(now);
    const timeStr = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');

    document.getElementById('visitDate').value = today;
    document.getElementById('visitTime').value = timeStr;

    populateVisitSelects(patientId);
    openModal('visitModal');
}

async function editVisit(id) {
    visitModalReturn = null;
    await Promise.all([fetchDoctorsList(), fetchPatientsList()]);
    // Fetch the full visit with clinical data from the server
    let visit;
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/${id}`);
        if (res.ok) visit = await res.json();
        else visit = visitsList.find(v => v.id === id);
    } catch (e) {
        visit = visitsList.find(v => v.id === id);
    }
    if (!visit) return;

    currentEditVisitId = visit.id;
    document.getElementById('visitId').value = visit.id;
    document.getElementById('visitDate').value = visit.visitDate;
    document.getElementById('visitTime').value = visit.visitTime;
    document.getElementById('visitModalTitle').innerText = 'Edit Visit';
    document.getElementById('visitBasicFields').style.display = 'block';
    // Fields and clinical data show together here, so the recap must not linger from a previous create.
    document.getElementById('visitSummary').style.display = 'none';

    populateVisitSelects(visit.patientId, visit.doctorId);
    // Also show clinical data for editing
    showClinicalDataSection(visit);
    openModal('visitModal');
}

function populateVisitSelects(selectedPatientId = null, selectedDoctorId = null) {
    const patSelect = document.getElementById('visitPatientId');
    const docSelect = document.getElementById('visitDoctorId');
    const docGroup = document.getElementById('visitDoctorGroup');

    patSelect.innerHTML = '<option value="">-- Select Patient --</option>';
    patientsList.forEach(p => {
        const sel = (selectedPatientId && p.id === selectedPatientId) ? 'selected' : '';
        patSelect.innerHTML += `<option value="${p.id}" ${sel}>${esc(p.firstName)} ${esc(p.lastName)} (EGN: ${esc(p.egn)})</option>`;
    });

    docSelect.innerHTML = '<option value="">-- Select Doctor --</option>';
    // Unapproved applicants are left out (no DOCTOR role yet). The visit's current doctor stays selectable,
    // so editing doesn't silently repoint it.
    doctorsList
        .filter(d => !d.pending || d.id === selectedDoctorId)
        .forEach(d => {
            const sel = (selectedDoctorId && d.id === selectedDoctorId) ? 'selected' : '';
            docSelect.innerHTML += `<option value="${d.id}" ${sel}>Dr. ${esc(d.firstName)} ${esc(d.lastName)}</option>`;
        });

    if (currentUserRole === 'DOCTOR') {
        if (currentDoctorId) {
            docSelect.value = currentDoctorId;
        }
        if (docGroup) {
            docGroup.style.display = 'none';
        }
    } else {
        if (docGroup) {
            docGroup.style.display = 'block';
        }
        docSelect.disabled = false;
    }
}

let currentEditVisitId = null;

// The open visit's clinical data, so handlers can look text up by id - text in an onclick breaks on an apostrophe.
let currentVisitClinicalData = null;
let editingSickLeaveId = null;

async function submitVisitForm(e) {
    e.preventDefault();
    const id = document.getElementById('visitId').value;
    const docVal = (currentUserRole === 'DOCTOR' && currentDoctorId) ? currentDoctorId : document.getElementById('visitDoctorId').value;
    const patVal = document.getElementById('visitPatientId').value;

    if (!patVal || !docVal) {
        alert('Please select a Patient.');
        return;
    }

    const visitData = {
        patientId: Number(patVal),
        doctorId: Number(docVal),
        visitDate: document.getElementById('visitDate').value,
        visitTime: document.getElementById('visitTime').value + (document.getElementById('visitTime').value.length === 5 ? ":00" : "")
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/visits/${id}` : `${API_BASE}/visits`;

    try {
        const response = await fetchWithAuth(url, { method, body: JSON.stringify(visitData) });
        if (!response.ok) {
            throw new Error(await apiErrorMessage(response, `HTTP ${response.status}`));
        }
        const savedVisit = await response.json();
        currentEditVisitId = savedVisit.id;
        // So "Edit details" after a create updates this visit instead of POSTing a duplicate.
        document.getElementById('visitId').value = savedVisit.id;

        // Swap the form for a read-only recap, then open the clinical-data step.
        document.getElementById('visitBasicFields').style.display = 'none';
        document.getElementById('visitModalTitle').innerText = 'Clinical Data';
        showVisitSummary(savedVisit);
        showClinicalDataSection(savedVisit);
    } catch (err) { alert("Error saving visit: " + err.message); }
}

/** Keeps the saved visit's who/when on screen while its clinical data is being entered. */
function showVisitSummary(visit) {
    const summary = document.getElementById('visitSummary');
    document.getElementById('visitSummaryText').innerHTML =
        `<strong>${esc(visit.patientName)}</strong> &nbsp;seen by&nbsp; <strong>Dr. ${esc(visit.doctorName)}</strong>`
        + `<br><span style="color:#94A3B8;">${esc(fmtDate(visit.visitDate))} at ${esc(fmtTime(visit.visitTime))}`
        + ` &middot; visit #${visit.id}</span>`;
    summary.style.display = 'block';
}

/** Returns to the first step so the patient, doctor, date or time can be corrected. */
function editVisitDetailsAgain() {
    document.getElementById('visitSummary').style.display = 'none';
    document.getElementById('clinicalDataSection').style.display = 'none';
    document.getElementById('visitBasicFields').style.display = 'block';
    document.getElementById('visitModalTitle').innerText = 'Edit Visit';
}

function showClinicalDataSection(visit) {
    const section = document.getElementById('clinicalDataSection');
    section.style.display = 'block';

    // Pre-fill sick leave date with visit date
    const slFrom = document.getElementById('newSickLeaveFrom');
    if (slFrom && visit.visitDate) slFrom.value = visit.visitDate;

    renderExistingClinicalData(visit);
    loadDiagnosisSuggestions();
}

// Every diagnosis name already recorded, most used first - offered so one condition isn't split across spellings.
let diagnosisNames = [];

async function loadDiagnosisSuggestions() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/reports/diagnoses/common`);
        if (res.ok) diagnosisNames = (await res.json()).map(c => c.diagnosisName);
    } catch (e) { console.error('Error loading diagnosis suggestions', e); }
}

// A themed stand-in for <datalist>, whose native popup can't be styled.
function suggestionBox(input) {
    return input.parentNode.querySelector('.suggestions');
}

function showSuggestions(input) {
    const box = suggestionBox(input);
    const typed = input.value.trim().toLowerCase();
    const matches = typed
        ? diagnosisNames.filter(n => n.toLowerCase().includes(typed) && n.toLowerCase() !== typed).slice(0, 8)
        : [];
    box.innerHTML = matches.map(n => `<div class="suggestion">${esc(n)}</div>`).join('');
    box.style.display = matches.length ? 'block' : 'none';
    box.dataset.active = '-1';
}

function hideSuggestions(input) {
    const box = suggestionBox(input);
    if (box) box.style.display = 'none';
}

// mousedown rather than click, so the input keeps focus and its blur doesn't close the list first.
function pickSuggestion(event) {
    const item = event.target.closest('.suggestion');
    if (!item) return;
    event.preventDefault();
    const input = event.currentTarget.parentNode.querySelector('input');
    input.value = item.textContent;
    hideSuggestions(input);
}

// Arrows move through the open list, Enter takes the highlighted name, Escape closes it.
// Returns true when the key was used, so the caller doesn't also add, save or cancel.
function handleSuggestionKey(event) {
    const box = suggestionBox(event.target);
    const items = box && box.style.display === 'block' ? [...box.querySelectorAll('.suggestion')] : [];
    if (items.length === 0) return false;
    let active = Number(box.dataset.active);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        active = event.key === 'ArrowDown' ? (active + 1) % items.length : (active <= 0 ? items.length - 1 : active - 1);
        items.forEach((el, i) => el.classList.toggle('active', i === active));
        box.dataset.active = String(active);
        return true;
    }
    if (event.key === 'Enter' && active >= 0) {
        event.preventDefault();
        event.target.value = items[active].textContent;
        hideSuggestions(event.target);
        return true;
    }
    if (event.key === 'Escape') {
        event.preventDefault();
        hideSuggestions(event.target);
        return true;
    }
    return false;
}

function renderExistingClinicalData(visit) {
    currentVisitClinicalData = visit;

    // Diagnoses
    const diagList = document.getElementById('diagnosisList');
    if (visit.diagnoses && visit.diagnoses.length > 0) {
        diagList.innerHTML = visit.diagnoses.map(d =>
            clinicalItemRow('diagnosis', d.id, d.name, 'rgba(56, 189, 248, 0.1)', '#1E3A5F')
        ).join('');
    } else {
        diagList.innerHTML = '<p style="color: #64748B; font-size: 0.85rem; margin: 0;">No diagnoses yet.</p>';
    }

    // Prescriptions
    const prescList = document.getElementById('prescriptionList');
    if (visit.prescriptions && visit.prescriptions.length > 0) {
        prescList.innerHTML = visit.prescriptions.map(p =>
            clinicalItemRow('prescription', p.id, p.note, 'rgba(16, 185, 129, 0.1)', '#065F46')
        ).join('');
    } else {
        prescList.innerHTML = '<p style="color: #64748B; font-size: 0.85rem; margin: 0;">No prescriptions yet.</p>';
    }

    // Sick Leave
    const slDisplay = document.getElementById('sickLeaveDisplay');
    const slForm = document.getElementById('sickLeaveForm');
    if (visit.sickLeave) {
        slDisplay.innerHTML = `
            <div style="display: flex; justify-content: space-between; align-items: center; background: rgba(245, 158, 11, 0.1); border: 1px solid #92400E; padding: 0.4rem 0.75rem; border-radius: 8px;">
                <span style="color: #E2E8F0;">From ${fmtDate(visit.sickLeave.validFrom)} for ${visit.sickLeave.validForDays} days</span>
                <span style="white-space: nowrap;">
                    <button type="button" onclick="editSickLeave()" class="btn-inline-edit" title="Edit sick leave">Edit</button>
                    <button type="button" onclick="removeSickLeave(${visit.sickLeave.id})" class="btn-remove" title="Remove sick leave">✕</button>
                </span>
            </div>`;
        // A visit carries at most one sick leave, so the form below is the issue form only while
        // none exists. editSickLeave() reopens it prefilled to amend the one that does.
        slForm.style.display = editingSickLeaveId === visit.sickLeave.id ? 'flex' : 'none';
    } else {
        slDisplay.innerHTML = '';
        editingSickLeaveId = null;
        slForm.style.display = 'flex';
    }
    renderSickLeaveFormMode();
}

// One diagnosis or prescription; the one being edited turns into a text box in place.
function clinicalItemRow(kind, id, text, background, border) {
    const editing = editingClinicalItem && editingClinicalItem.kind === kind && editingClinicalItem.id === id;
    const verb = kind === 'diagnosis' ? 'Diagnosis' : 'Prescription';
    const input = `<input type="text" id="clinicalEditInput" value="${esc(text)}" autocomplete="off" onkeydown="onClinicalEditKey(event)"`;
    const field = kind === 'diagnosis'
        ? `<div class="suggest-wrap" style="margin-right: 0.5rem;">
               ${input} oninput="showSuggestions(this)" onblur="hideSuggestions(this)">
               <div class="suggestions" onmousedown="pickSuggestion(event)"></div>
           </div>`
        : `${input} style="flex: 1; margin-right: 0.5rem;">`;
    const body = editing
        ? `${field}
           <span style="white-space: nowrap;">
               <button type="button" onclick="saveClinicalEdit()" class="btn-inline-edit">Save</button>
               <button type="button" onclick="cancelClinicalEdit()" class="btn-inline-edit">Cancel</button>
           </span>`
        : `<span style="color: #E2E8F0;">${esc(text)}</span>
           <span style="white-space: nowrap;">
               <button type="button" onclick="edit${verb}(${id})" class="btn-inline-edit" title="Edit ${kind}">Edit</button>
               <button type="button" onclick="remove${verb}(${id})" class="btn-remove" title="Remove ${kind}">✕</button>
           </span>`;
    return `<div style="display: flex; justify-content: space-between; align-items: center; background: ${background}; border: 1px solid ${border}; padding: 0.4rem 0.75rem; border-radius: 8px; margin-bottom: 0.3rem;">${body}</div>`;
}

/** Keeps the sick-leave form's button saying what pressing it will do. */
function renderSickLeaveFormMode() {
    const button = document.getElementById('sickLeaveSubmitBtn');
    const cancel = document.getElementById('sickLeaveCancelBtn');
    if (!button) return;
    const editing = editingSickLeaveId !== null;
    button.textContent = editing ? 'Save' : '+ Issue';
    if (cancel) cancel.style.display = editing ? 'inline-block' : 'none';
}

// Warns, doesn't block, on a visit with no diagnosis - the spec makes treatment and sick leave optional
// ("евентуално") but treats the diagnosis as the point of the visit.
function finishVisit() {
    const noDiagnosis = currentVisitClinicalData
        && (!currentVisitClinicalData.diagnoses || currentVisitClinicalData.diagnoses.length === 0);
    if (noDiagnosis && !confirm('This visit has no diagnosis recorded. Close it anyway?')) {
        return;
    }
    closeModal('visitModal');
}

async function refreshVisitAndRender() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/${currentEditVisitId}`);
        if (res.ok) {
            const visit = await res.json();
            renderExistingClinicalData(visit);
        }
    } catch (e) { console.error('Error refreshing visit', e); }
}

async function addDiagnosisToVisit() {
    const input = document.getElementById('newDiagnosisName');
    const name = input.value.trim();
    if (!name) { alert('Please enter a diagnosis name.'); return; }
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/diagnosis`, {
            method: 'POST', body: JSON.stringify({ visitId: currentEditVisitId, name })
        });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to add diagnosis'));
        input.value = '';
        hideSuggestions(input);
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function addPrescriptionToVisit() {
    const input = document.getElementById('newPrescriptionNote');
    const note = input.value.trim();
    if (!note) { alert('Please enter a prescription.'); return; }
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/prescription`, {
            method: 'POST', body: JSON.stringify({ visitId: currentEditVisitId, note })
        });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to add prescription'));
        input.value = '';
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

// Issues a sick leave or saves the one being edited - POST and PUT take the same SickLeaveRequest, so one form.
async function addSickLeaveToVisit() {
    const validFrom = document.getElementById('newSickLeaveFrom').value;
    const validForDays = document.getElementById('newSickLeaveDays').value;
    if (!validFrom || !validForDays) { alert('Please fill in both date and number of days.'); return; }
    const days = Number(validForDays);
    if (!Number.isInteger(days) || days < 1 || days > 365) {
        alert('A sick leave runs for 1 to 365 days.');
        return;
    }
    const editing = editingSickLeaveId !== null;
    try {
        const res = await fetchWithAuth(
            editing ? `${API_BASE}/visits/sickleave/${editingSickLeaveId}` : `${API_BASE}/visits/sickleave`, {
                method: editing ? 'PUT' : 'POST',
                body: JSON.stringify({ visitId: currentEditVisitId, validFrom, validForDays: days })
            });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, editing ? 'Failed to update sick leave' : 'Failed to issue sick leave'));
        }
        editingSickLeaveId = null;
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

/** Reopens the sick-leave form over the existing certificate so its dates can be corrected. */
function editSickLeave() {
    const sickLeave = currentVisitClinicalData && currentVisitClinicalData.sickLeave;
    if (!sickLeave) return;
    editingSickLeaveId = sickLeave.id;
    document.getElementById('newSickLeaveFrom').value = sickLeave.validFrom;
    document.getElementById('newSickLeaveDays').value = sickLeave.validForDays;
    document.getElementById('sickLeaveForm').style.display = 'flex';
    renderSickLeaveFormMode();
}

function cancelEditSickLeave() {
    editingSickLeaveId = null;
    document.getElementById('newSickLeaveFrom').value = '';
    document.getElementById('newSickLeaveDays').value = '';
    if (currentVisitClinicalData) renderExistingClinicalData(currentVisitClinicalData);
}

// Edited in place, not deleted and re-added - the spec names editing, and re-adding would log DELETE + CREATE.
function editDiagnosis(id) { startClinicalEdit('diagnosis', id); }

function editPrescription(id) { startClinicalEdit('prescription', id); }

// The diagnosis or prescription currently open for editing, as { kind, id }.
let editingClinicalItem = null;

function startClinicalEdit(kind, id) {
    editingClinicalItem = { kind, id };
    renderExistingClinicalData(currentVisitClinicalData);
    const input = document.getElementById('clinicalEditInput');
    if (input) { input.focus(); input.select(); }
}

function cancelClinicalEdit() {
    editingClinicalItem = null;
    if (currentVisitClinicalData) renderExistingClinicalData(currentVisitClinicalData);
}

function onClinicalEditKey(event) {
    if (handleSuggestionKey(event)) return;
    if (event.key === 'Enter') saveClinicalEdit();
    else if (event.key === 'Escape') { event.preventDefault(); cancelClinicalEdit(); }
}

async function saveClinicalEdit() {
    if (!editingClinicalItem) return;
    const { kind, id } = editingClinicalItem;
    const isDiagnosis = kind === 'diagnosis';
    const items = (isDiagnosis ? currentVisitClinicalData.diagnoses : currentVisitClinicalData.prescriptions) || [];
    const current = items.find(x => x.id === id);
    const value = document.getElementById('clinicalEditInput').value.trim();
    if (!value) { alert(isDiagnosis ? 'Please enter a diagnosis name.' : 'Please enter a prescription.'); return; }
    if (!current || value === (isDiagnosis ? current.name : current.note)) { cancelClinicalEdit(); return; }
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/${kind}/${id}`, {
            method: 'PUT',
            body: JSON.stringify(isDiagnosis
                ? { visitId: currentEditVisitId, name: value }
                : { visitId: currentEditVisitId, note: value })
        });
        if (!res.ok) throw new Error(await apiErrorMessage(res, `Failed to update ${kind}`));
        editingClinicalItem = null;
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removeDiagnosis(id) {
    const d = (currentVisitClinicalData?.diagnoses || []).find(x => x.id === id);
    if (!confirm(`Remove the diagnosis${d ? ` "${d.name}"` : ''} from this visit?`)) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/diagnosis/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to remove diagnosis'));
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removePrescription(id) {
    const p = (currentVisitClinicalData?.prescriptions || []).find(x => x.id === id);
    if (!confirm(`Remove the prescription${p ? ` "${p.note}"` : ''} from this visit?`)) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/prescription/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to remove prescription'));
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removeSickLeave(id) {
    if (!confirm('Remove the sick leave from this visit?')) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/sickleave/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to remove sick leave'));
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function deleteVisit(id) {
    if (!confirm('Are you sure you want to delete this visit?')) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/visits/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(await apiErrorMessage(response, 'Failed to delete visit'));
        loadDoctorVisits();
    } catch (err) { alert(err.message); }
}

async function loadDoctorVisits() {
    markActiveTab(loadDoctorVisits);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading visits...</p>';
    try {
        if (currentUserRole === 'DOCTOR' && !currentDoctorId) {
            const docResponse = await fetchWithAuth(`${API_BASE}/doctors/keycloak/${keycloak.tokenParsed.sub}`);
            if (docResponse.ok) {
                const doc = await docResponse.json();
                currentDoctorId = doc.id;
            }
        }

        const response = await fetchWithAuth(`${API_BASE}/visits`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        visitsList = await response.json();

        let html = '<h3>All Visits</h3>';
        if (currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR') {
            html += `<button class="btn btn-primary btn-add-new" onclick="openAddVisitModal()">+ Add New Visit</button>`;
        }

        html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Patient</th><th onclick="sortTable(2, this)">Doctor</th><th onclick="sortTable(3, this)">Diagnosis</th><th onclick="sortTable(4, this)">Prescription</th><th onclick="sortTable(5, this)">Sick Leave</th>';
        if (currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR') html += `<th>Actions</th>`;
        html += `</tr></thead><tbody>`;

        visitsList.forEach(v => {
            // "No diagnosis", not a dash - a visit can end without one, so it's a fact about the visit, not a gap.
            const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => d.name).join(', ') : 'No diagnosis';
            const rxNotes = v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p => p.note).join('; ') : '-';
            const slInfo = v.sickLeave ? `${fmtDate(v.sickLeave.validFrom)} - ${v.sickLeave.validForDays} days` : '-';
            html += `<tr><td>${esc(fmtDate(v.visitDate))} ${esc(fmtTime(v.visitTime))}</td><td>${esc(v.patientName)}</td><td>Dr. ${esc(v.doctorName)}</td><td>${esc(diagNames)}</td><td>${esc(rxNotes)}</td><td>${esc(slInfo)}</td>`;

            if (currentUserRole === 'ADMIN' || (currentUserRole === 'DOCTOR' && v.doctorId === currentDoctorId)) {
                html += `<td>
                    <button class="action-btn btn-edit" onclick="editVisit(${v.id})">Edit</button>
                    <button class="action-btn btn-delete" onclick="deleteVisit(${v.id})">Delete</button>
                </td>`;
            } else if (currentUserRole === 'DOCTOR') {
                html += `<td></td>`;
            }
            html += `</tr>`;
        });
        html += '</tbody></table>';
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p class="error-text">Error loading visits: ${esc(error.message)}</p>`;
    }
}

// ================= OTHERS =================

/** The patients registered with this doctor as their GP. */
async function fetchGpRoster(doctorId) {
    const res = await fetchWithAuth(`${API_BASE}/doctors/${doctorId}/patients`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

// Distinct patients this doctor has examined, built from the visit list - no endpoint answers it directly.
async function fetchPatientsSeenByDoctor(doctorId) {
    const [visitsRes, patientsRes] = await Promise.all([
        fetchWithAuth(`${API_BASE}/visits`),
        fetchWithAuth(`${API_BASE}/patients`)
    ]);
    if (!visitsRes.ok) throw new Error(`HTTP ${visitsRes.status}`);
    if (!patientsRes.ok) throw new Error(`HTTP ${patientsRes.status}`);

    const visits = await visitsRes.json();
    const seen = new Set(visits.filter(v => v.doctorId === doctorId).map(v => v.patientId));
    const patients = await patientsRes.json();
    return patients.filter(p => seen.has(p.id));
}

async function loadMyPatients() {
    markActiveTab(loadMyPatients);
    const contentArea = document.getElementById('content-area');
    patientListOrigin = 'mine';
    contentArea.innerHTML = '<p>Loading your patients...</p>';
    try {
        const docResponse = await fetchWithAuth(`${API_BASE}/doctors/keycloak/${keycloak.tokenParsed.sub}`);
        if (!docResponse.ok) {
            contentArea.innerHTML = `<p class="error-text">Could not find your doctor profile in the database.</p>`;
            return;
        }
        const doctor = await docResponse.json();
        currentDoctorId = doctor.id;

        // Only a GP has a roster - other doctors get the patients they've examined, or their landing screen is empty.
        const patients = doctor.isGP
            ? await fetchGpRoster(doctor.id)
            : await fetchPatientsSeenByDoctor(doctor.id);
        const insurance = await fetchInsuranceStatuses();

        // Same heading as the tab; a non-GP is told why the list is who they examined.
        let html = `<h3>My Patients (Dr. ${esc(doctor.firstName)} ${esc(doctor.lastName)})</h3>`;
        if (!doctor.isGP) {
            html += '<p class="muted-label">Patients you have examined. Only a GP has patients registered with them.</p>';
        }
        if (patients.length === 0) {
            html += doctor.isGP
                ? '<p>No patients assigned to you.</p>'
                : '<p>You have not examined any patients yet.</p>';
        } else {
            // All Patients' columns minus GP (it would be this doctor on every row). Insurance is read-only -
            // recording a payment is an admin's job.
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">EGN</th><th onclick="sortTable(2, this)">Insurance</th><th>Actions</th></tr></thead><tbody>';
            patients.forEach(p => {
                html += `<tr><td>${esc(p.firstName)} ${esc(p.lastName)}</td><td>${esc(p.egn)}</td>`
                     + `<td>${insuranceCell(insurance[p.id])}</td>`
                     + `<td><button class="action-btn btn-view" onclick="viewPatientVisitsDirect(${p.id})">Visits</button> `
                     + `<button class="action-btn btn-edit" onclick="openAddVisitModal(${p.id})">+ Visit</button></td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p class="error-text">Error loading patients: ${esc(error.message)}</p>`;
    }
}

async function loadPatientHistory() {
    markActiveTab(loadPatientHistory);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading patient history...</p>';
    try {
        const pResponse = await fetchWithAuth(`${API_BASE}/patients/keycloak/${keycloak.tokenParsed.sub}`);
        if (!pResponse.ok) {
            contentArea.innerHTML = `<p class="error-text">Could not find your patient profile in the database.</p>`;
            return;
        }
        const patient = await pResponse.json();

        // Check health insurance status
        let insuranceStatus = null;
        try {
            const insRes = await fetchWithAuth(`${API_BASE}/patients/${patient.id}/insurance`);
            if (insRes.ok) insuranceStatus = await insRes.json();
        } catch (e) {}

        const vResponse = await fetchWithAuth(`${API_BASE}/visits/patient/${patient.id}`);
        if (!vResponse.ok) throw new Error(`HTTP ${vResponse.status}`);
        const visits = await vResponse.json();

        // Three states: null means the check failed, which must not read as "unpaid".
        const badge = (bg, border, colour, text) =>
            `<span style="display:inline-block; padding: 0.25rem 0.75rem; background: ${bg}; border: 1px solid ${border}; color: ${colour}; border-radius: 20px; font-size: 0.85rem; font-weight: 600;">${text}</span>`;

        let insBadge;
        if (insuranceStatus === null || typeof insuranceStatus !== 'object') {
            insBadge = badge('rgba(100, 116, 139, 0.2)', '#64748B', '#94A3B8', '? Insurance status unavailable');
        } else {
            // No run count - with a gap it reads "1 of 6" next to only two unpaid months. The verdict and the months say it all.
            const owed = (insuranceStatus.unpaidMonths || [])
                .map(fmtUnpaidMonth).join(', ');
            insBadge = insuranceStatus.insured
                ? badge('rgba(16, 185, 129, 0.2)', '#10B981', '#10B981', '✓ Health Insurance Active')
                : badge('rgba(239, 68, 68, 0.2)', '#EF4444', '#EF4444', '✕ Not Insured');
            // Naming the months turns "you are short" into something the patient can act on.
            if (owed) {
                insBadge += `<div class="muted" style="font-size: 0.8rem; margin-top: 0.35rem;">Not yet paid: ${esc(owed)}</div>`;
            }
        }

        let html = `<div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:1rem; margin-bottom:1rem;">
            <div>
                <h3 style="margin-bottom: 0.25rem;">Welcome, ${esc(patient.firstName)} ${esc(patient.lastName)}</h3>
                <h4 style="color: #94A3B8; margin: 0;">EGN: ${esc(patient.egn)} | GP: ${patient.gpDoctorName
                    ? esc(patient.gpDoctorName)
                    : '<span style="color:#F59E0B; font-weight:600;">not chosen yet</span>'}</h4>
            </div>
            <div style="display:flex; gap:0.5rem; align-items:center;">
                ${insBadge}
                <button class="btn ${patient.gpDoctorId ? 'btn-secondary' : 'btn-primary'}" style="padding: 0.35rem 0.8rem; font-size: 0.85rem;" onclick="openChangeGpModal(${patient.id}, ${patient.gpDoctorId || 'null'})">${patient.gpDoctorId ? 'Change GP' : 'Choose GP'}</button>
            </div>
        </div>`;

        if (visits.length === 0) {
            html += '<p>No medical visits found.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Doctor</th><th onclick="sortTable(2, this)">Diagnosis</th><th onclick="sortTable(3, this)">Prescription</th><th onclick="sortTable(4, this)">Sick Leave</th></tr></thead><tbody>';
            visits.forEach(v => {
                const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => esc(d.name)).join(', ') : 'No diagnosis';
                const rxNotes = v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p => esc(p.note)).join('; ') : '-';
                const slInfo = v.sickLeave ? `${esc(fmtDate(v.sickLeave.validFrom))} (${esc(String(v.sickLeave.validForDays))} days)` : '-';
                html += `<tr><td>${esc(fmtDate(v.visitDate))}</td><td>Dr. ${esc(v.doctorName)}</td><td>${diagNames}</td><td>${rxNotes}</td><td>${slInfo}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p class="error-text">Error loading patient history: ${esc(error.message)}</p>`;
    }
}

// ================= FULL REPORTS DASHBOARD & INLINE FILTER PANELS =================

/** Builds the "doctors awaiting approval" banner, or an empty string when nothing is pending. */
async function renderPendingApprovals() {
    try {
        const res = await fetchWithAuth(`${API_BASE}/reports/pending-registrations`);
        if (!res.ok) return '';
        const pending = await res.json();
        if (pending.length === 0) return '';   // banner disappears once everyone is approved

        const rows = pending.map(d =>
            `<tr>
                <td>Dr. ${esc(d.doctorName)}</td>
                <td>${d.registeredAt ? esc(fmtDate(d.registeredAt)) : '-'}</td>
                <td><button class="action-btn btn-approve" onclick="approveDoctor(${d.doctorId})">Approve</button>
                    <button class="action-btn btn-delete" onclick="deleteDoctor(${d.doctorId})">Delete</button></td>
            </tr>`
        ).join('');

        return `
            <div style="background: rgba(245, 158, 11, 0.1); border: 1px solid #F59E0B; padding: 1.25rem; border-radius: 12px; margin-bottom: 1.5rem;">
                <h4 style="color: #F59E0B; margin-bottom: 0.75rem;">Doctors Awaiting Approval (${pending.length})</h4>
                <p style="color: #94A3B8; font-size: 0.88rem; margin-bottom: 0.75rem;">Approving grants the <strong>DOCTOR</strong> role and offers the edit form, where specializations and GP status are set - an applicant can state neither. Until then they are marked <em>Pending approval</em> in the Doctors directory and are left out of the visit form, the patients' directory and report e.</p>
                <table class="data-table" style="margin-top: 0.5rem;">
                    <thead><tr><th>Name</th><th>Registered</th><th>Action</th></tr></thead>
                    <tbody>${rows}</tbody>
                </table>
            </div>`;
    } catch (e) {
        console.error('Error loading pending registrations', e);
        return '';
    }
}

async function loadFullReportsDashboard() {
    markActiveTab(loadFullReportsDashboard);
    const contentArea = document.getElementById('content-area');

    // Pending approvals are an administrator concern; the endpoint refuses doctors, so don't ask.
    let pendingHtml = '';
    if (currentUserRole === 'ADMIN') {
        pendingHtml = await renderPendingApprovals();
    }

    // Cards in the spec's order a-j, which also groups them by subject: a,b diagnoses; c-e doctors; f-h visits; i,j sick leave.
    let html = `
        <h3>Reports</h3>
        <p style="color: #94A3B8; margin-bottom: 1.5rem;">Select a report to run it against the current records.</p>

        ${pendingHtml}

        <div class="reports-grid">
            <div class="report-card" data-group="diagnoses" onclick="renderReportFilterPanel('diagnosis')">
                <span class="report-group">a · Diagnoses</span>
                <h4>Patients with a Given Diagnosis</h4>
                <p>Everyone who has received a particular diagnosis.</p>
            </div>
            <div class="report-card" data-group="diagnoses" onclick="fetchReport('/reports/diagnoses/common', 'Times Diagnosed per Condition', ['Diagnosis', 'Times Diagnosed'], r => [r.diagnosisName, r.count], false, null, { unit: 'diagnoses', rank: { valueColumn: 1, labelColumn: 0, lead: 'Most frequently diagnosed' } })">
                <span class="report-group">b · Diagnoses</span>
                <h4>Times Diagnosed per Condition</h4>
                <p>How many times each condition has been diagnosed.</p>
            </div>
            <div class="report-card" data-group="doctors" onclick="renderReportFilterPanel('gp_patients')">
                <span class="report-group">c · Doctors</span>
                <h4>Patients Registered with a Given GP</h4>
                <p>The roster of one general practitioner.</p>
            </div>
            <div class="report-card" data-group="doctors" onclick="fetchReport('/reports/doctors/gp-patients', 'Patients Registered per General Practitioner', ['Doctor ID', 'Doctor Name', 'Patients Registered'], r => [r.doctorId, 'Dr. '+r.doctorName, r.patientCount])">
                <span class="report-group">d · Doctors</span>
                <h4>Patients Registered per GP</h4>
                <p>How many patients each general practitioner looks after.</p>
            </div>
            <div class="report-card" data-group="doctors" onclick="fetchReport('/reports/doctors/visits', 'Visits Recorded per Doctor', ['Doctor ID', 'Doctor Name', 'Visits'], r => [r.doctorId, 'Dr. '+r.doctorName, r.visitCount])">
                <span class="report-group">e · Doctors</span>
                <h4>Visits Recorded per Doctor</h4>
                <p>How many examinations each doctor has carried out.</p>
            </div>
            <div class="report-card" data-group="visits" onclick="renderReportFilterPanel('patient_visits')">
                <span class="report-group">f · Visits</span>
                <h4>Visits for a Given Patient</h4>
                <p>Every visit for one patient, with diagnosis, treatment and sick leave.</p>
            </div>
            <div class="report-card" data-group="visits" onclick="renderReportFilterPanel('dates')">
                <span class="report-group">g · Visits</span>
                <h4>Visits in a Given Date Range</h4>
                <p>Every visit across the practice between two dates.</p>
            </div>
            <div class="report-card" data-group="visits" onclick="renderReportFilterPanel('doctor_dates')">
                <span class="report-group">h · Visits</span>
                <h4>Visits for a Given Doctor in a Date Range</h4>
                <p>Visits carried out by a chosen doctor between two dates.</p>
            </div>
            <div class="report-card" data-group="sickleaves" onclick="fetchReport('/reports/sickleaves/months', 'Sick Leaves Issued per Month', ['Month', 'Sick Leaves Issued'], r => [fmtMonth(r.year, r.month), r.count], false, null, { unit: 'months', rank: { valueColumn: 1, labelColumn: 0, lead: 'Month with the most sick leaves' } })">
                <span class="report-group">i · Sick leaves</span>
                <h4>Sick Leaves Issued per Month</h4>
                <p>How many sick leaves were issued in each month.</p>
            </div>
            <div class="report-card" data-group="sickleaves" onclick="fetchReport('/reports/sickleaves/doctors', 'Sick Leaves Issued per Doctor', ['Doctor ID', 'Doctor Name', 'Sick Leaves Issued'], r => [r.doctorId, 'Dr. '+r.doctorName, r.sickLeaveCount], false, null, { unit: 'doctors', rank: { valueColumn: 2, labelColumn: 1, lead: 'Issued the most sick leaves' } })">
                <span class="report-group">j · Sick leaves</span>
                <h4>Sick Leaves Issued per Doctor</h4>
                <p>How many sick leaves each doctor has issued.</p>
            </div>
            <div class="report-card" data-group="insurance" onclick="fetchReport('/reports/patients/insurance', 'Unpaid Months per Patient', ['Patient ID', 'Patient', 'EGN', 'Unpaid Months', 'Status'], r => [r.patientId, r.patientName, r.egn, r.unpaidMonths.map(fmtUnpaidMonth).join(', '), r.insured ? 'Insured' : 'Not insured'], false, r => r.unpaidMonths.length > 0)">
                <span class="report-group">Insurance</span>
                <h4>Unpaid Months per Patient</h4>
                <p>Which months each patient still owes for the six-month check. Anyone fully paid is left out.</p>
            </div>
        </div>

        <div id="report-filter-panel"></div>
        <div id="report-results" style="margin-top: 1.5rem;"></div>
    `;
    contentArea.innerHTML = html;
}

async function renderReportFilterPanel(type) {
    const filterPanel = document.getElementById('report-filter-panel');
    const resultsArea = document.getElementById('report-results');
    resultsArea.innerHTML = '';

    const todayStr = localIsoDate();
    // Defaults to the last 12 months, computed from today so it never goes stale.
    const yearAgo = new Date();
    yearAgo.setFullYear(yearAgo.getFullYear() - 1);
    const yearAgoStr = localIsoDate(yearAgo);

    if (type === 'diagnosis') {
        // Fetch actual diagnosis names from the database
        let diagOptions = '<option value="">-- Loading... --</option>';
        try {
            const diagRes = await fetchWithAuth(`${API_BASE}/reports/diagnoses/names`);
            if (diagRes.ok) {
                const names = await diagRes.json();
                diagOptions = names.map(n => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
                if (names.length === 0) diagOptions = '<option value="">No diagnoses found</option>';
            }
        } catch (e) { diagOptions = '<option value="">Error loading diagnoses</option>'; }

        filterPanel.innerHTML = `
            <div class="filter-panel">
                <h4>Filter Report: Patients by Diagnosis</h4>
                <div class="filter-row">
                    <div class="filter-field">
                        <label for="inlineDiagSelect">Select Diagnosis:</label>
                        <select id="inlineDiagSelect" class="filter-input">
                            ${diagOptions}
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="runDiagnosisReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'dates') {
        filterPanel.innerHTML = `
            <div class="filter-panel">
                <h4>Filter Report: Visits in Date Range</h4>
                <div class="filter-row">
                    <div>
                        <label for="inlineStartDate">Start Date:</label>
                        <input type="date" id="inlineStartDate" value="${yearAgoStr}" class="filter-input">
                    </div>
                    <div>
                        <label for="inlineEndDate">End Date:</label>
                        <input type="date" id="inlineEndDate" value="${todayStr}" class="filter-input">
                    </div>
                    <button class="btn btn-primary" onclick="runDatesReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'doctor_dates') {
        await fetchDoctorsList();
        filterPanel.innerHTML = `
            <div class="filter-panel">
                <h4>Filter Report: Visits by Doctor in Date Range</h4>
                <div class="filter-row">
                    <div class="filter-field">
                        <label for="inlineDocSelect">Select Doctor:</label>
                        <select id="inlineDocSelect" class="filter-input">
                            ${doctorsList.map(d => `<option value="${d.id}">Dr. ${esc(d.firstName)} ${esc(d.lastName)}</option>`).join('')}
                        </select>
                    </div>
                    <div>
                        <label for="inlineStartDate">Start Date:</label>
                        <input type="date" id="inlineStartDate" value="${yearAgoStr}" class="filter-input">
                    </div>
                    <div>
                        <label for="inlineEndDate">End Date:</label>
                        <input type="date" id="inlineEndDate" value="${todayStr}" class="filter-input">
                    </div>
                    <button class="btn btn-primary" onclick="runDoctorDatesReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'patient_visits') {
        await fetchPatientsList();
        filterPanel.innerHTML = `
            <div class="filter-panel">
                <h4>Filter Report: Visits for Patient</h4>
                <div class="filter-row">
                    <div class="filter-field">
                        <label for="inlinePatSelect">Select Patient:</label>
                        <select id="inlinePatSelect" class="filter-input">
                            ${patientsList.map(p => `<option value="${p.id}">${esc(p.firstName)} ${esc(p.lastName)} (EGN: ${esc(p.egn)})</option>`).join('')}
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="runPatientVisitsReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'gp_patients') {
        await fetchDoctorsList();
        // Only general practitioners have a roster, so listing anyone else would offer a choice
        // that can only ever come back empty.
        const gps = doctorsList.filter(d => d.isGP);
        filterPanel.innerHTML = `
            <div class="filter-panel">
                <h4>Filter Report: Patients Registered with a GP</h4>
                <div class="filter-row">
                    <div class="filter-field">
                        <label for="inlineGpSelect">Select General Practitioner:</label>
                        <select id="inlineGpSelect" class="filter-input">
                            ${gps.length === 0
                                ? '<option value="">No general practitioners registered</option>'
                                : gps.map(d => `<option value="${d.id}">Dr. ${esc(d.firstName)} ${esc(d.lastName)}</option>`).join('')}
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="runGpPatientsReport()">Generate Report</button>
                </div>
            </div>
        `;
    }
    // The panel renders below all the cards, out of view on a normal screen.
    filterPanel.scrollIntoView({ block: 'start' });
}

function runDiagnosisReport() {
    const diag = document.getElementById('inlineDiagSelect').value;
    // §3a asks for the NUMBER of patients with a diagnosis ("броя на пациентите"), which a list of names never states.
    fetchReport(`/reports/diagnoses/patients?diagnosis=${encodeURIComponent(diag)}`, `Patients diagnosed with: ${diag}`, ['ID', 'Patient Name', 'EGN', 'GP'], p => [p.id, p.firstName + ' ' + p.lastName, p.egn, p.gpDoctorName || '-'], true, null, { unit: 'patients' });
}

// Report c - the patients registered with one general practitioner.
function runGpPatientsReport() {
    const gpId = document.getElementById('inlineGpSelect').value;
    if (!gpId) { alert('Please select a general practitioner.'); return; }

    const gp = doctorsList.find(d => String(d.id) === String(gpId));
    const gpName = gp ? `${gp.firstName} ${gp.lastName}` : `ID ${gpId}`;

    fetchReport(`/doctors/${gpId}/patients`, `Patients registered with Dr. ${gpName}`,
        ['Patient ID', 'Patient Name', 'EGN'],
        p => [p.id, p.firstName + ' ' + p.lastName, p.egn], true);
}

function runDatesReport() {
    const start = document.getElementById('inlineStartDate').value;
    const end = document.getElementById('inlineEndDate').value;
    if (!start || !end) { alert('Please select both start and end dates.'); return; }
    fetchReport(`/reports/visits/period?start=${start}&end=${end}`, `Visits from ${fmtDate(start)} to ${fmtDate(end)}`, ['Visit ID', 'Date', 'Doctor', 'Patient'], v => [v.id, fmtDate(v.visitDate) + ' ' + fmtTime(v.visitTime), 'Dr. ' + v.doctorName, v.patientName], true);
}

function runDoctorDatesReport() {
    const docId = document.getElementById('inlineDocSelect').value;
    const start = document.getElementById('inlineStartDate').value;
    const end = document.getElementById('inlineEndDate').value;
    if (!docId) { alert('Please select a Doctor.'); return; }
    if (!start || !end) { alert('Please select both start and end dates.'); return; }

    const docObj = doctorsList.find(d => String(d.id) === String(docId));
    const docName = docObj ? `${docObj.firstName} ${docObj.lastName}` : `ID ${docId}`;

    fetchReport(`/reports/visits/doctor/period?doctorId=${docId}&start=${start}&end=${end}`, `Visits by Dr. ${docName} (${fmtDate(start)} to ${fmtDate(end)})`, ['Visit ID', 'Date', 'Patient'], v => [v.id, fmtDate(v.visitDate) + ' ' + fmtTime(v.visitTime), v.patientName], true);
}

function runPatientVisitsReport() {
    const patId = document.getElementById('inlinePatSelect').value;
    if (!patId) { alert('Please select a Patient.'); return; }

    const patObj = patientsList.find(p => String(p.id) === String(patId));
    const patName = patObj ? `${patObj.firstName} ${patObj.lastName}` : `ID ${patId}`;

    // Sick leave is part of the history the spec tracks ("излизал ли е в болнични ... и за колко време"), so it's here too.
    fetchReport(`/visits/patient/${patId}`, `Visits for Patient: ${patName}`,
        ['Visit ID', 'Date', 'Doctor', 'Diagnosis', 'Prescription', 'Sick Leave'], v => [
        v.id,
        fmtDate(v.visitDate) + ' ' + fmtTime(v.visitTime),
        'Dr. ' + v.doctorName,
        (v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d=>d.name).join(', ') : 'No diagnosis'),
        (v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p=>p.note).join('; ') : '-'),
        (v.sickLeave ? `${fmtDate(v.sickLeave.validFrom)} - ${v.sickLeave.validForDays} days` : '-')
    ], true);
}

// Prefers the per-field messages in `details`, so a rejected form names the field instead of "Request validation failed".
async function apiErrorMessage(response, fallback) {
    const body = await response.json().catch(() => null);
    if (body && body.details && Object.keys(body.details).length > 0) {
        return Object.values(body.details).join('\n');
    }
    if (body && body.message) return body.message;
    return fallback || `HTTP ${response.status}`;
}

// The most recently rendered report, so Export CSV writes exactly what is on screen.
let lastReport = null;

// Quotes one CSV field. The leading apostrophe on = + - @ stops Excel running a value as a formula.
function csvCell(value) {
    let s = value === null || value === undefined ? '' : String(value);
    if (/^[=+\-@]/.test(s)) s = "'" + s;
    return '"' + s.replace(/"/g, '""') + '"';
}

// Built from the rendered rows, so every report can export and the file always matches the screen, filters included.
function exportCurrentReport() {
    if (!lastReport || lastReport.rows.length === 0) return;

    const csv = [lastReport.headers, ...lastReport.rows]
        .map(row => row.map(csvCell).join(','))
        .join('\r\n');

    // BOM so Excel reads the file as UTF-8 and renders Cyrillic names correctly.
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' });
    const filename = lastReport.title.toLowerCase()
        .replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'report';

    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${filename}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

// Rows tied for the top value in the ranked column - ALL of them. Empty when unranked, so it falls back to a plain table.
function topRowIndexes(rows, rank) {
    const leaders = new Set();
    if (!rank) return leaders;
    const values = rows.map(r => Number(r[rank.valueColumn]));
    const best = Math.max(...values.filter(v => Number.isFinite(v)), -Infinity);
    if (!Number.isFinite(best)) return leaders;
    values.forEach((v, i) => { if (v === best) leaders.add(i); });
    return leaders;
}

// options.unit: plural noun for the row count above the table - card a asks for the NUMBER of patients.
// options.rank: { valueColumn, labelColumn, lead } - names and highlights every row tied for top (b, i, j allow ties).
async function fetchReport(endpoint, title, headers, rowMapper, keepFilterPanel = false, rowFilter = null, options = {}) {
    const filterPanel = document.getElementById('report-filter-panel');
    if (!keepFilterPanel && filterPanel) {
        filterPanel.innerHTML = '';
    }

    const resultsArea = document.getElementById('report-results');
    resultsArea.innerHTML = '<p class="muted-label">Generating report...</p>';

    try {
        const response = await fetchWithAuth(`${API_BASE}${endpoint}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();

        // A report may show a subset of an endpoint - "who owes something" is the insurance list minus those who owe nothing.
        const source = rowFilter ? data.filter(rowFilter) : data;
        const rows = source.map(rowMapper);
        // Remember what is on screen so the export button below writes exactly that.
        lastReport = { title, headers, rows };

        // Escape centrally: every report title and cell here is server data (patient and doctor
        // names, diagnosis text, prescription notes), and no row mapper produces markup.
        let html = `<h4>${esc(title)}</h4>`;
        if (rows.length === 0) {
            html += '<p class="muted-label">No records found for this report.</p>';
        } else {
            const leaders = topRowIndexes(rows, options.rank);
            html += `<p class="muted-label">${rows.length} ${esc(options.unit || 'rows')}</p>`;
            if (leaders.size > 0) {
                const { valueColumn, labelColumn, lead } = options.rank;
                const names = [...leaders].map(i => String(rows[i][labelColumn])).join(', ');
                const value = rows[[...leaders][0]][valueColumn];
                html += `<p class="report-answer">${esc(lead)}: <strong>${esc(names)}</strong> (${esc(String(value))})</p>`;
            }
            html += `<div style="display:flex; justify-content:flex-end; margin-bottom:0.5rem;">
                        <button class="btn btn-secondary" style="padding:0.3rem 0.9rem; font-size:0.8rem;"
                                onclick="exportCurrentReport()">Export CSV</button>
                     </div>`;
            html += '<table class="data-table"><thead><tr>';
            headers.forEach((h, i) => html += `<th onclick="sortTable(${i}, this)">${esc(h)}</th>`);
            html += '</tr></thead><tbody>';

            rows.forEach((rowData, i) => {
                html += leaders.has(i) ? '<tr class="report-top">' : '<tr>';
                rowData.forEach(cell => html += `<td>${esc(cell)}</td>`);
                html += '</tr>';
            });
            html += '</tbody></table>';
        }
        resultsArea.innerHTML = html;
    } catch (err) {
        resultsArea.innerHTML = `<p class="error-text">Error generating report: ${esc(err.message)}</p>`;
    }
    // Results render below all the cards; scroll once they are in, when the page is tall enough.
    resultsArea.scrollIntoView({ block: 'start' });
}

// ================= UTILS =================

function filterTable() {
    const input = document.getElementById('searchInput');
    const filter = input.value.toLowerCase();
    const tables = document.querySelectorAll('.data-table');

    tables.forEach(table => {
        const tr = table.getElementsByTagName('tr');
        for (let i = 1; i < tr.length; i++) {
            let visible = false;
            const td = tr[i].getElementsByTagName('td');
            for (let j = 0; j < td.length; j++) {
                if (td[j] && td[j].innerText.toLowerCase().includes(filter)) {
                    visible = true;
                    break;
                }
            }
            tr[i].style.display = visible ? '' : 'none';
        }
    });
}

let sortDirections = {};
let sortTableSeq = 0;

// dd.mm.yyyy [hh:mm] and mm.yyyy as yyyymmddhhmm / yyyymm - parseFloat would read "27.01.2026" as 27.01.
function dateSortKey(text) {
    let m = text.match(/^(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2}))?/);
    if (m) return m[3] + m[2] + m[1] + (m[4] || '00') + (m[5] || '00');
    m = text.match(/^(\d{2})\.(\d{4})\b/);
    return m ? m[2] + m[1] : null;
}

// Sorts by a column, toggling direction. Keyed by table AND column - some screens show more than one table.
function sortTable(columnIndex, thElement) {
    const table = thElement.closest('table');
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));

    if (!table.dataset.sortKey) {
        table.dataset.sortKey = `t${++sortTableSeq}`;
    }
    const key = `${table.dataset.sortKey}:${columnIndex}`;
    const ascending = !sortDirections[key];
    sortDirections[key] = ascending;

    rows.sort((rowA, rowB) => {
        const cellA = rowA.children[columnIndex].innerText.trim();
        const cellB = rowB.children[columnIndex].innerText.trim();
        const dateA = dateSortKey(cellA);
        const dateB = dateSortKey(cellB);
        if (dateA && dateB) {
            return ascending ? dateA.localeCompare(dateB) : dateB.localeCompare(dateA);
        }
        const numA = parseFloat(cellA);
        const numB = parseFloat(cellB);
        if (!isNaN(numA) && !isNaN(numB)) {
            return ascending ? numA - numB : numB - numA;
        }
        return ascending ? cellA.localeCompare(cellB) : cellB.localeCompare(cellA);
    });

    tbody.innerHTML = '';
    rows.forEach(row => tbody.appendChild(row));
}

// ================= CSV EXPORT HELPER =================
// ================= AUDIT LOGS =================
const AUDIT_PAGE_SIZE = 50;

// Paged at the database - unlike the reports, the log has no natural end. The header search
// filters only the 50 rows on screen; Prev / Next reach the rest.
async function loadAuditLogs(page = 0) {
    markActiveTab(loadAuditLogs);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading audit logs...</p>';
    try {
        const res = await fetchWithAuth(`${API_BASE}/audit-logs?page=${page}&size=${AUDIT_PAGE_SIZE}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const pageData = await res.json();
        const logs = pageData.content || [];
        // VIA_DTO page metadata (see WebConfig). Defaulted so a shape change degrades to one page
        // rather than to a broken screen.
        const meta = pageData.page || {};
        const pageNumber = meta.number ?? page;
        const totalPages = meta.totalPages ?? 1;
        const totalElements = meta.totalElements ?? logs.length;

        let html = '<h3>System Audit Logs</h3>';

        if (logs.length === 0) {
            html += '<p class="muted-label">No audit logs found.</p>';
        } else {
            html += '<table class="data-table"><thead><tr>';
            html += '<th onclick="sortTable(0, this)">Timestamp</th><th onclick="sortTable(1, this)">Action</th><th onclick="sortTable(2, this)">Entity</th><th onclick="sortTable(3, this)">Record</th><th onclick="sortTable(4, this)">Actor</th>';
            html += '</tr></thead><tbody>';
            logs.forEach(l => {
                const dateStr = l.createdAt ? fmtDateTime(l.createdAt) : '-';
                // Older entries predate entity_label, so fall back to the id alone.
                const record = l.entityLabel
                    ? `${esc(l.entityLabel)} <span class="muted">#${esc(String(l.entityId))}</span>`
                    : `<span class="muted">#${esc(String(l.entityId))}</span>`;
                html += `<tr><td>${esc(dateStr)}</td><td><span class="role-badge">${esc(l.action)}</span></td><td>${esc(l.entityType)}</td><td>${record}</td><td>${esc(l.actor)}</td></tr>`;
            });
            html += '</tbody></table>';
            html += renderAuditPager(pageNumber, totalPages, totalElements);
        }
        contentArea.innerHTML = html;
    } catch (e) {
        contentArea.innerHTML = `<p class="error-text">Error loading audit logs: ${esc(e.message)}</p>`;
    }
}

/** Prev / Next plus where in the feed the reader is. Sorting only reorders the page on screen. */
function renderAuditPager(pageNumber, totalPages, totalElements) {
    const first = pageNumber <= 0;
    const last = pageNumber >= totalPages - 1;
    return `<div class="pager">
        <button type="button" class="btn btn-secondary" onclick="loadAuditLogs(${pageNumber - 1})" ${first ? 'disabled' : ''}>← Prev</button>
        <span class="muted-label">Page ${pageNumber + 1} of ${totalPages} · ${totalElements} entries</span>
        <button type="button" class="btn btn-secondary" onclick="loadAuditLogs(${pageNumber + 1})" ${last ? 'disabled' : ''}>Next →</button>
    </div>`;
}

// ================= GP ASSIGNMENT =================
async function openChangeGpModal(patientId, currentGpId) {
    await fetchDoctorsList();
    document.getElementById('changeGpPatientId').value = patientId;
    const gpSelect = document.getElementById('changeGpDoctorId');
    gpSelect.innerHTML = '<option value="">-- Select GP --</option>';
    doctorsList.filter(d => d.isGP).forEach(doc => {
        const selected = (currentGpId && doc.id === currentGpId) ? 'selected' : '';
        gpSelect.innerHTML += `<option value="${doc.id}" ${selected}>Dr. ${esc(doc.firstName)} ${esc(doc.lastName)} (GP)</option>`;
    });
    openModal('gpModal');
}

async function submitGpChangeForm(e) {
    e.preventDefault();
    const patientId = document.getElementById('changeGpPatientId').value;
    const gpDoctorId = document.getElementById('changeGpDoctorId').value;
    if (!gpDoctorId) {
        alert('Please select a GP Doctor');
        return;
    }
    try {
        const res = await fetchWithAuth(`${API_BASE}/patients/${patientId}/gp?gpDoctorId=${gpDoctorId}`, {
            method: 'PATCH'
        });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, 'Failed to update GP'));
        }
        closeModal('gpModal');
        if (currentUserRole === 'PATIENT') {
            loadPatientHistory();
        } else {
            loadAllPatients();
        }
    } catch (err) {
        alert('Failed to update GP: ' + err.message);
    }
}

// ================= HEALTH INSURANCE =================

// Snaps to the 1st of the month, which is what the server stores anyway, so the field shows what gets saved.
// (A month input would fit better, but Firefox and Safari fall back to a plain text box.)
function snapCoverageToFirstOfMonth(input) {
    if (input.value) {
        input.value = input.value.slice(0, 8) + '01';
    }
}

function openRecordInsuranceModal(patientId) {
    document.getElementById('insurancePatientId').value = patientId;
    document.getElementById('insurancePaymentId').value = '';
    document.getElementById('insurancePatientName').value = patientNameById(patientId);
    document.getElementById('insuranceModalTitle').textContent = 'Record Health Insurance Payment';
    document.getElementById('insuranceSubmitBtn').textContent = 'Record Payment';
    const today = localIsoDate();
    // Coverage defaults to the 1st (only the month counts); paidOn is a real date and stays on today.
    document.getElementById('insuranceDate').value = today.slice(0, 8) + '01';
    document.getElementById('insurancePaidOn').value = today;
    openModal('insuranceModal');
}

// Corrects a recorded payment - one entered against the wrong month blocks that month and shows them covered for it.
function openEditInsuranceModal(patientId, paymentId) {
    const payment = insurancePaymentsOnScreen.find(p => p.id === paymentId);
    if (!payment) return;
    document.getElementById('insurancePatientId').value = patientId;
    document.getElementById('insurancePaymentId').value = paymentId;
    document.getElementById('insurancePatientName').value = patientNameById(patientId);
    document.getElementById('insuranceModalTitle').textContent = 'Correct Recorded Payment';
    document.getElementById('insuranceSubmitBtn').textContent = 'Save Correction';
    document.getElementById('insuranceDate').value = payment.insuranceDate;
    document.getElementById('insurancePaidOn').value = payment.paidOn;
    openModal('insuranceModal');
}

async function submitInsurancePayment(e) {
    e.preventDefault();
    const patientId = Number(document.getElementById('insurancePatientId').value);
    const paymentId = document.getElementById('insurancePaymentId').value;
    const insuranceDate = document.getElementById('insuranceDate').value;
    const paidOn = document.getElementById('insurancePaidOn').value;
    const correcting = paymentId !== '';

    try {
        const res = await fetchWithAuth(
            correcting
                ? `${API_BASE}/patients/${patientId}/insurance/payments/${paymentId}`
                : `${API_BASE}/patients/${patientId}/insurance`, {
                method: correcting ? 'PUT' : 'POST',
                // patientId travels in the path, not the body.
                body: JSON.stringify({ insuranceDate, paidOn })
            });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, correcting ? 'Failed to correct payment' : 'Failed to record payment'));
        }
        closeModal('insuranceModal');
        alert(correcting ? 'Payment corrected.' : 'Insurance payment recorded successfully!');
        // Stay where the user was: the management screen if that is what opened the form.
        if (managingInsuranceForPatientId === patientId) {
            await manageInsurance(patientId);
        } else {
            loadAllPatients();
        }
    } catch (err) {
        alert((correcting ? 'Failed to correct payment: ' : 'Failed to record payment: ') + err.message);
    }
}

// ================= INSURANCE MANAGEMENT =================

/** The payments currently rendered, so the edit form can prefill without a second request. */
let insurancePaymentsOnScreen = [];
let managingInsuranceForPatientId = null;

// Every insurance payment for one patient, with edit controls - the verdict alone can't show which month is wrong.
async function manageInsurance(patientId) {
    const contentArea = document.getElementById('content-area');
    managingInsuranceForPatientId = patientId;
    contentArea.innerHTML = '<p>Loading insurance record...</p>';
    try {
        const [paymentsRes, statusRes] = await Promise.all([
            fetchWithAuth(`${API_BASE}/patients/${patientId}/insurance/payments`),
            fetchWithAuth(`${API_BASE}/patients/${patientId}/insurance`)
        ]);
        if (!paymentsRes.ok) throw new Error(`HTTP ${paymentsRes.status}`);
        insurancePaymentsOnScreen = await paymentsRes.json();
        const status = statusRes.ok ? await statusRes.json() : null;

        const name = patientNameById(patientId);
        let html = `<h3>Health Insurance - ${esc(name)}</h3>`;
        if (status) {
            html += `<p>${insuranceCell(status)}</p>`;
            if (status.unpaidMonths && status.unpaidMonths.length > 0) {
                html += `<p class="muted-label">Unpaid months: `
                     + status.unpaidMonths.map(m => esc(fmtUnpaidMonth(m))).join(', ') + '</p>';
            }
        }
        html += `<button class="btn btn-primary btn-add-new" onclick="openRecordInsuranceModal(${patientId})">+ Record Payment</button> `;
        html += `<button class="btn btn-secondary btn-add-new" onclick="loadAllPatients()">← Back to Patients</button>`;

        if (insurancePaymentsOnScreen.length === 0) {
            html += '<p class="muted-label">No contributions recorded for this patient.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Covered Month</th>'
                 + '<th onclick="sortTable(1, this)">Paid On</th><th>Actions</th></tr></thead><tbody>';
            insurancePaymentsOnScreen.forEach(p => {
                const [year, month] = p.insuranceDate.split('-');
                html += `<tr><td>${esc(fmtMonth(Number(year), Number(month)))}</td>`
                     + `<td>${esc(fmtDate(p.paidOn))}</td>`
                     + `<td><button class="action-btn btn-edit" onclick="openEditInsuranceModal(${patientId}, ${p.id})">Edit</button> `
                     + `<button class="action-btn btn-delete" onclick="deleteInsurancePayment(${patientId}, ${p.id})">Delete</button></td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (err) {
        contentArea.innerHTML = `<p class="error-text">Error loading insurance record: ${esc(err.message)}</p>`;
    }
}

async function deleteInsurancePayment(patientId, paymentId) {
    const payment = insurancePaymentsOnScreen.find(p => p.id === paymentId);
    const [year, month] = payment ? payment.insuranceDate.split('-') : [];
    const label = payment ? fmtMonth(Number(year), Number(month)) : `payment ${paymentId}`;
    if (!confirm(`Remove the contribution covering ${label}? The month becomes unpaid again.`)) return;
    try {
        const res = await fetchWithAuth(
            `${API_BASE}/patients/${patientId}/insurance/payments/${paymentId}`, { method: 'DELETE' });
        if (!res.ok) throw new Error(await apiErrorMessage(res, 'Failed to remove payment'));
        await manageInsurance(patientId);
    } catch (err) {
        alert(err.message);
    }
}

// ================= DIRECT PATIENT VISITS LOOKUP =================
// Grants DOCTOR, refreshes the current screen, then opens the edit form - an applicant can't state specializations
// or GP status. Cancelling there leaves them approved with none, which is valid.
async function approveDoctor(doctorId) {
    if (!confirm('Approve this doctor and grant them the DOCTOR role?')) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/${doctorId}/approve`, { method: 'POST' });
        if (!res.ok) {
            throw new Error(await apiErrorMessage(res, `HTTP ${res.status}`));
        }
        await reloadActiveTab();
        // Straight into the form; its Cancel leaves them approved with no specialization.
        await fetchDoctorsList();
        await editDoctor(doctorId);
        document.getElementById('doctorModalTitle').innerText = 'Approved - set specializations and GP status';
    } catch (err) {
        alert('Could not approve doctor: ' + err.message);
    }
}

// Report c from the Doctors screen. Renders into the content area - fetchReport's elements only exist on Reports.
async function viewGpPatients(doctorId) {
    const doctorName = doctorNameById(doctorId);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = `<p>Loading patients registered with Dr. ${esc(doctorName)}...</p>`;
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/${doctorId}/patients`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const patients = await res.json();

        let html = `<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:1rem;">
            <h3>Patients registered with Dr. ${esc(doctorName)}</h3>
            <button class="btn btn-secondary" onclick="loadDoctorsDirectory()">← Back to Doctors</button>
        </div>`;

        if (patients.length === 0) {
            html += '<p>No patients are registered with this general practitioner.</p>';
        } else {
            html += `<p style="color:#94A3B8;">${patients.length} patient(s) registered.</p>`;
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">EGN</th></tr></thead><tbody>';
            patients.forEach(p => {
                html += `<tr><td>${esc(p.firstName)} ${esc(p.lastName)}</td><td>${esc(p.egn)}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (err) {
        contentArea.innerHTML = `<p class="error-text">Error loading patients: ${esc(err.message)}</p>`;
    }
}

async function viewPatientVisitsDirect(patientId) {
    const patientName = patientNameById(patientId);
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = `<p>Loading visits for ${esc(patientName)}...</p>`;
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/patient/${patientId}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const visits = await res.json();

        let html = `<div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:1rem;">
            <h3>Visits for Patient: ${esc(patientName)}</h3>
            <div style="display:flex; gap:0.5rem;">
                ${currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR'
                    ? `<button class="btn btn-primary" onclick="openAddVisitModal(${patientId})">+ Add Visit</button>` : ''}
                <button class="btn btn-secondary" onclick="backToPatientList()">← Back to Patients</button>
            </div>
        </div>`;

        if (visits.length === 0) {
            html += '<p>No visits recorded for this patient.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Doctor</th><th onclick="sortTable(2, this)">Diagnosis</th><th onclick="sortTable(3, this)">Prescription</th><th onclick="sortTable(4, this)">Sick Leave</th></tr></thead><tbody>';
            visits.forEach(v => {
                const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => esc(d.name)).join(', ') : 'No diagnosis';
                const rxNotes = v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p => esc(p.note)).join('; ') : '-';
                const slInfo = v.sickLeave ? `${esc(fmtDate(v.sickLeave.validFrom))} (${esc(String(v.sickLeave.validForDays))} days)` : '-';
                html += `<tr><td>${esc(fmtDate(v.visitDate))}</td><td>Dr. ${esc(v.doctorName)}</td><td>${diagNames}</td><td>${rxNotes}</td><td>${slInfo}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (err) {
        contentArea.innerHTML = `<p class="error-text">Error loading patient visits: ${esc(err.message)}</p>`;
    }
}
