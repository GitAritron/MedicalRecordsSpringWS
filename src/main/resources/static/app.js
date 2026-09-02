let keycloak;
let currentUserRole = '';
let currentDoctorId = null;

const API_BASE = 'http://localhost:8080/api';

// Global reference lists for dropdowns and safe lookup
let doctorsList = [];
let patientsList = [];
let visitsList = [];
let specializationsList = [];

document.addEventListener('DOMContentLoaded', () => {
    initKeycloak();
});

function initKeycloak() {
    keycloak = new Keycloak({
        url: 'http://localhost:8081',
        realm: 'medical-records',
        clientId: 'frontend-client'
    });

    keycloak.init({ onLoad: 'check-sso', checkLoginIframe: false }).then(async authenticated => {
        const usernameEl = document.getElementById('username');
        const loginBtn = document.getElementById('loginBtn');
        const registerBtn = document.getElementById('registerBtn');
        const logoutBtn = document.getElementById('logoutBtn');
        const heroSection = document.getElementById('hero');
        const dashboardSection = document.getElementById('dashboard');

        if (authenticated) {
            if (keycloak.hasRealmRole('ADMIN')) currentUserRole = 'ADMIN';
            else if (keycloak.hasRealmRole('DOCTOR')) currentUserRole = 'DOCTOR';
            else if (keycloak.hasRealmRole('PATIENT')) currentUserRole = 'PATIENT';

            usernameEl.textContent = `${keycloak.tokenParsed.preferred_username} ${currentUserRole ? '('+currentUserRole+')' : '(New User)'}`;
            loginBtn.style.display = 'none';
            registerBtn.style.display = 'none';
            logoutBtn.style.display = 'inline-block';
            heroSection.style.display = 'none';
            dashboardSection.style.display = 'block';

            if (!currentUserRole) {
                renderNewUserRoleSelection();
            } else {
                renderTabs();
                loadDashboard();
            }
        } else {
            usernameEl.textContent = 'Not logged in';
            loginBtn.style.display = 'inline-block';
            registerBtn.style.display = 'inline-block';
            logoutBtn.style.display = 'none';
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

// Escapes text for interpolation into HTML. The previous implementation round-tripped through
// div.textContent -> div.innerHTML, which escapes & < > but leaves quotes untouched — so any value
// placed inside a quoted attribute (value="${esc(x)}") could still break out of it.
const ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
function esc(str) {
    if (str == null) return '';
    return String(str).replace(/[&<>"']/g, ch => ESC_MAP[ch]);
}

// Resolves a display name from an id we already hold, so inline onclick handlers only ever carry
// numbers. esc() is NOT safe inside an onclick attribute: the HTML parser decodes entities before
// the JavaScript is compiled, so an escaped quote turns back into a real one and can close the
// string literal. Keeping names out of generated code sidesteps that entirely.
function patientNameById(patientId) {
    const p = patientsList.find(x => x.id === patientId);
    return p ? `${p.firstName} ${p.lastName}` : `ID ${patientId}`;
}

function doctorNameById(doctorId) {
    const d = doctorsList.find(x => x.id === doctorId);
    return d ? `${d.firstName} ${d.lastName}` : `ID ${doctorId}`;
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

function renderTabs() {
    const navTabs = document.getElementById('nav-tabs');
    let tabsHtml = '';
    if (currentUserRole === 'ADMIN') {
        tabsHtml += `<button class="tab-btn active" onclick="setActiveTab(this); loadFullReportsDashboard()">All Reports</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadDoctorsDirectory()">Doctors</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadAllPatients()">Patients</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadDoctorVisits()">Visits</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadAuditLogs()">Audit Logs</button>`;
    } else if (currentUserRole === 'DOCTOR') {
        // Specification §2: "Лекарите трябва да могат да виждат информацията за всички пациенти
        // и лекари." The API already granted doctors the patient directory and the reports, but
        // neither had a tab, so the permission was unreachable from the interface.
        tabsHtml += `<button class="tab-btn active" onclick="setActiveTab(this); loadMyPatients()">My Patients</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadAllPatients()">All Patients</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadDoctorVisits()">Visits</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadDoctorsDirectory()">All Doctors</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadFullReportsDashboard()">Reports</button>`;
    } else if (currentUserRole === 'PATIENT') {
        tabsHtml += `<button class="tab-btn active" onclick="setActiveTab(this); loadPatientHistory()">My Medical History</button>`;
        tabsHtml += `<button class="tab-btn" onclick="setActiveTab(this); loadDoctorsDirectory()">Doctors Directory</button>`;
    }
    navTabs.innerHTML = tabsHtml;
}

async function loadDashboard() {
    if (currentUserRole === 'ADMIN') await loadFullReportsDashboard();
    else if (currentUserRole === 'DOCTOR') await loadMyPatients();
    else if (currentUserRole === 'PATIENT') await loadPatientHistory();
}

// ================= NEW USER ROLE SELECTION =================

/**
 * A user with no realm role has either not registered yet, or has applied as a doctor and is
 * waiting for approval. Distinguishing the two stops an applicant being shown the role picker
 * again on every sign-in, only to hit the duplicate-profile guard if they re-apply.
 */
async function renderNewUserRoleSelection() {
    const contentArea = document.getElementById('content-area');
    try {
        const res = await fetchWithAuth(`${API_BASE}/registration-status`);
        if (res.ok) {
            const status = await res.json();
            if (status.hasDoctorProfile) {
                contentArea.innerHTML = `
                    <div style="max-width: 550px; margin: 2rem auto; text-align: center; background: rgba(245, 158, 11, 0.1); border: 1px solid #F59E0B; padding: 2rem; border-radius: 12px;">
                        <h3 style="color: #F59E0B;">Application under review</h3>
                        <p style="color: #94A3B8; margin-top: 0.75rem;">Your doctor profile has been submitted. An administrator still needs to approve it before you can sign in as a doctor.</p>
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
                        <button class="btn btn-primary" style="margin-top: 1.25rem;" onclick="location.reload()">Reload</button>
                    </div>`;
                return;
            }
        }
    } catch (err) {
        // Fall through to the role picker — a status check failure should not block registration.
        console.error('Could not read registration status', err);
    }

    contentArea.innerHTML = `
        <div style="max-width: 550px; margin: 2rem auto; text-align: center;">
            <h3>Welcome, ${keycloak.tokenParsed.preferred_username}!</h3>
            <p style="color: #94A3B8; margin-bottom: 2rem;">Please select your role to complete your profile setup.</p>

            <div style="display: flex; gap: 1.5rem; justify-content: center;">
                <div class="report-card" style="flex: 1; text-align: center; cursor: pointer;" onclick="showPatientRegistrationForm()">
                    <div style="font-size: 2.5rem; margin-bottom: 0.5rem;">👤</div>
                    <h4>I am a Patient</h4>
                    <p>Register your patient profile to view medical visits, diagnoses, and prescriptions.</p>
                </div>
                <div class="report-card" style="flex: 1; text-align: center; cursor: pointer;" onclick="showDoctorRegistrationForm()">
                    <div style="font-size: 2.5rem; margin-bottom: 0.5rem;">🩺</div>
                    <h4>I am a Doctor</h4>
                    <p>Submit your doctor credentials for administrator review and activation.</p>
                </div>
            </div>

            <div id="selfRegContainer" style="margin-top: 2rem; text-align: left;"></div>
        </div>
    `;
}

async function showPatientRegistrationForm() {
    await fetchDoctorsList();
    const gpOptions = doctorsList
        .filter(d => d.isGP)
        .map(d => `<option value="${d.id}">Dr. ${esc(d.firstName)} ${esc(d.lastName)} (GP)</option>`)
        .join('');

    const container = document.getElementById('selfRegContainer');
    container.innerHTML = `
        <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.5rem; border-radius: 12px;">
            <h4>Complete Patient Profile</h4>
            <form onsubmit="submitSelfPatientRegistration(event)">
                <div class="form-group">
                    <label>First Name:</label>
                    <input type="text" id="selfFirstName" value="${esc(keycloak.tokenParsed.given_name || '')}" required>
                </div>
                <div class="form-group">
                    <label>Last Name:</label>
                    <input type="text" id="selfLastName" value="${esc(keycloak.tokenParsed.family_name || '')}" required>
                </div>
                <div class="form-group">
                    <label>EGN (10 Digits):</label>
                    <input type="text" id="selfEgn" required pattern="\\d{10}" title="Must be exactly 10 digits">
                </div>
                <div class="form-group">
                    <label>General Practitioner (GP Doctor):</label>
                    <select id="selfGpId" style="width: 100%; padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                        <option value="">-- Select GP (Optional) --</option>
                        ${gpOptions}
                    </select>
                </div>
                <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 1rem;">Complete Registration</button>
            </form>
        </div>
    `;
}

function showDoctorRegistrationForm() {
    const container = document.getElementById('selfRegContainer');
    container.innerHTML = `
        <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.5rem; border-radius: 12px;">
            <h4>Submit Doctor Application</h4>
            <form onsubmit="submitSelfDoctorRegistration(event)">
                <div class="form-group">
                    <label>First Name:</label>
                    <input type="text" id="selfDocFirstName" value="${esc(keycloak.tokenParsed.given_name || '')}" required>
                </div>
                <div class="form-group">
                    <label>Last Name:</label>
                    <input type="text" id="selfDocLastName" value="${esc(keycloak.tokenParsed.family_name || '')}" required>
                </div>
                <p style="color: #94A3B8; font-size: 0.85rem; margin: 0.75rem 0 0;">
                    An administrator reviews every application. They grant your Doctor role and, if
                    applicable, register you as a General Practitioner.
                </p>
                <button type="submit" class="btn btn-primary" style="width: 100%; margin-top: 1rem;">Submit Application</button>
            </form>
        </div>
    `;
}

async function submitSelfPatientRegistration(e) {
    e.preventDefault();
    const gpVal = document.getElementById('selfGpId') ? document.getElementById('selfGpId').value : '';
    // No keycloakId: the server binds the profile to our verified token subject.
    const patientData = {
        firstName: document.getElementById('selfFirstName').value.trim(),
        lastName: document.getElementById('selfLastName').value.trim(),
        egn: document.getElementById('selfEgn').value.trim(),
        gpDoctorId: gpVal ? Number(gpVal) : null
    };

    try {
        const res = await fetchWithAuth(`${API_BASE}/patients/self-register`, { method: 'POST', body: JSON.stringify(patientData) });
        if (!res.ok) {
            const errJson = await res.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${res.status}`);
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
                    <h4>✅ Registration Complete!</h4>
                    <p>Your patient profile has been created and approved. Please reload the page to access your dashboard.</p>
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
        lastName: document.getElementById('selfDocLastName').value.trim(),
        specializationIds: []
    };

    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/self-register`, { method: 'POST', body: JSON.stringify(doctorData) });
        if (!res.ok) {
            const errJson = await res.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${res.status}`);
        }
        const container = document.getElementById('selfRegContainer');
        container.innerHTML = `
            <div style="background: rgba(16, 185, 129, 0.15); border: 1px solid #10B981; color: #10B981; padding: 1.5rem; border-radius: 12px; text-align: center;">
                <h4>Registration Submitted!</h4>
                <p>Your doctor profile has been created and is awaiting approval. An administrator will grant your Doctor security role in Keycloak, and register you as a General Practitioner if that applies. You will not be able to sign in as a doctor until then.</p>
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
        document.getElementById('diagnosisList').innerHTML = '';
        document.getElementById('prescriptionList').innerHTML = '';
        document.getElementById('sickLeaveDisplay').innerHTML = '';
        document.getElementById('sickLeaveForm').style.display = 'flex';
        currentEditVisitId = null;
        loadDoctorVisits();
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
            const errJson = await res.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${res.status}`);
        }
        if (msgEl) {
            msgEl.innerHTML = `<p style="color: #10B981; margin: 0.5rem 0; font-weight: 500;">✓ Specialization "${esc(name)}" created successfully!</p>`;
        }
        await fetchSpecializations();
        setTimeout(() => {
            closeModal('specModal');
            if (msgEl) msgEl.innerHTML = '';
        }, 1200);
    } catch (err) {
        if (msgEl) {
            msgEl.innerHTML = `<p style="color: #EF4444; margin: 0.5rem 0;">Error: ${esc(err.message)}</p>`;
        }
    }
}

// ================= PATIENT CRUD =================

async function openAddPatientModal() {
    await fetchDoctorsList();
    document.getElementById('patientForm').reset();
    document.getElementById('patientId').value = '';
    document.getElementById('patientKeycloakId').value = '';
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
    document.getElementById('patientModalTitle').innerText = 'Edit Patient';

    populateGpDropdown(patient.gpDoctorId);
    openModal('patientModal');
}

function populateGpDropdown(selectedGpId = null) {
    const gpSelect = document.getElementById('patientGpId');
    gpSelect.innerHTML = '<option value="">-- No GP Assigned --</option>';
    
    doctorsList.forEach(doc => {
        const selected = (selectedGpId && doc.id === selectedGpId) ? 'selected' : '';
        gpSelect.innerHTML += `<option value="${doc.id}" ${selected}>Dr. ${esc(doc.firstName)} ${esc(doc.lastName)} ${doc.isGP ? '(GP)' : ''}</option>`;
    });
}

async function submitPatientForm(e) {
    e.preventDefault();
    const id = document.getElementById('patientId').value;
    const gpVal = document.getElementById('patientGpId').value;

    const patientData = {
        keycloakId: document.getElementById('patientKeycloakId').value.trim() || null,
        firstName: document.getElementById('patientFirstName').value.trim(),
        lastName: document.getElementById('patientLastName').value.trim(),
        egn: document.getElementById('patientEgn').value.trim(),
        gpDoctorId: gpVal ? Number(gpVal) : null
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/patients/${id}` : `${API_BASE}/patients`;

    try {
        const response = await fetchWithAuth(url, { method, body: JSON.stringify(patientData) });
        if (!response.ok) {
            const errJson = await response.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${response.status}`);
        }
        closeModal('patientModal');
        loadAllPatients();
    } catch (err) { alert("Error saving patient: " + err.message); }
}

async function deletePatient(id) {
    if (!confirm('Are you sure you want to delete this patient?')) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/patients/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error('Failed to delete patient');
        loadAllPatients();
    } catch (err) { alert(err.message); }
}

async function loadAllPatients() {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading patients...</p>';
    try {
        const response = await fetchWithAuth(`${API_BASE}/patients`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        patientsList = await response.json();
        
        let html = `<h3>All Patients</h3>`;
        if (currentUserRole === 'ADMIN') {
            html += `<button class="btn btn-primary btn-add-new" onclick="openAddPatientModal()">+ Add New Patient</button>`;
        }
        
        html += `<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">EGN</th><th onclick="sortTable(2, this)">GP Doctor</th><th>Actions</th></tr></thead><tbody>`;
        
        patientsList.forEach(p => {
            const safeName = esc(`${p.firstName} ${p.lastName}`);
            html += `<tr><td>${safeName}</td><td>${esc(p.egn)}</td><td>${esc(p.gpDoctorName || 'Not assigned')}</td>`;
            html += `<td>`;
            html += `<button class="action-btn" style="background:#0284C7;color:white;" onclick="viewPatientVisitsDirect(${p.id})">Visits</button> `;
            if (currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR') {
                html += `<button class="action-btn" style="background:#059669;color:white;" onclick="openRecordInsuranceModal(${p.id})">+ Insurance</button> `;
            }
            if (currentUserRole === 'ADMIN') {
                html += `<button class="action-btn btn-edit" onclick="editPatient(${p.id})">Edit</button> `;
                html += `<button class="action-btn btn-delete" onclick="deletePatient(${p.id})">Delete</button>`;
            }
            html += `</td></tr>`;
        });
        html += '</tbody></table>';
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading patients: ${esc(error.message)}</p>`;
    }
}

// ================= DOCTOR CRUD =================

async function openAddDoctorModal() {
    await fetchSpecializations();
    document.getElementById('doctorForm').reset();
    document.getElementById('doctorId').value = '';
    document.getElementById('doctorKeycloakId').value = '';
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
        keycloakId: document.getElementById('doctorKeycloakId').value.trim() || null,
        firstName: document.getElementById('doctorFirstName').value.trim(),
        lastName: document.getElementById('doctorLastName').value.trim(),
        isGP: document.getElementById('doctorIsGp').checked,
        specializationIds: specIds
    };

    const method = id ? 'PUT' : 'POST';
    const url = id ? `${API_BASE}/doctors/${id}` : `${API_BASE}/doctors`;

    try {
        const response = await fetchWithAuth(url, { method, body: JSON.stringify(doctorData) });
        if (!response.ok) {
            const errJson = await response.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${response.status}`);
        }
        closeModal('doctorModal');
        loadDoctorsDirectory();
    } catch (err) { alert("Error saving doctor: " + err.message); }
}

async function deleteDoctor(id) {
    if (!confirm('Are you sure you want to delete this doctor?')) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/doctors/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error('Failed to delete doctor');
        loadDoctorsDirectory();
    } catch (err) { alert(err.message); }
}

async function loadDoctorsDirectory() {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading doctors...</p>';
    try {
        const response = await fetchWithAuth(`${API_BASE}/doctors`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        doctorsList = await response.json();
        
        let html = `<h3>Doctors Directory</h3>`;
        if (currentUserRole === 'ADMIN') {
            html += `<button class="btn btn-primary btn-add-new" onclick="openAddDoctorModal()">+ Add New Doctor</button> `;
            html += `<button class="btn btn-secondary btn-add-new" onclick="openAddSpecModal()">+ Add Specialization</button>`;
        }

        // Clinical staff get an Actions column; only admins get the editing controls inside it.
        const showActions = currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR';

        html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">GP Status</th><th onclick="sortTable(2, this)">Specializations</th>';
        if (showActions) html += `<th>Actions</th>`;
        html += `</tr></thead><tbody>`;

        doctorsList.forEach(doc => {
            const specs = doc.specializations && doc.specializations.length > 0
                ? doc.specializations.map(s => esc(s.name || s)).join(', ')
                : '—';
            html += `<tr><td>Dr. ${esc(doc.firstName)} ${esc(doc.lastName)}</td><td>${doc.isGP ? 'Yes' : 'No'}</td><td>${specs}</td>`;
            if (showActions) {
                html += `<td>`;
                // Requirement 3c: list of patients registered with a given GP. Only general
                // practitioners have a patient roster, so the button belongs on those rows.
                if (doc.isGP) {
                    html += `<button class="action-btn" style="background:#0284C7;color:white;" onclick="viewGpPatients(${doc.id})">Patients</button> `;
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
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading doctors: ${esc(error.message)}</p>`;
    }
}

// ================= VISIT CRUD =================

async function openAddVisitModal() {
    await Promise.all([fetchDoctorsList(), fetchPatientsList()]);
    document.getElementById('visitForm').reset();
    document.getElementById('visitId').value = '';
    document.getElementById('visitModalTitle').innerText = 'Add New Visit';
    document.getElementById('visitBasicFields').style.display = 'block';
    document.getElementById('clinicalDataSection').style.display = 'none';
    currentEditVisitId = null;

    const now = new Date();
    const today = now.toISOString().split('T')[0];
    const timeStr = String(now.getHours()).padStart(2, '0') + ':' + String(now.getMinutes()).padStart(2, '0');
    
    document.getElementById('visitDate').value = today;
    document.getElementById('visitTime').value = timeStr;

    populateVisitSelects();
    openModal('visitModal');
}

async function editVisit(id) {
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
    doctorsList.forEach(d => {
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
            const errJson = await response.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${response.status}`);
        }
        const savedVisit = await response.json();
        currentEditVisitId = savedVisit.id;

        // Show clinical data section for adding diagnoses, prescriptions, sick leave
        document.getElementById('visitBasicFields').style.display = 'none';
        document.getElementById('visitModalTitle').innerText = `Visit #${savedVisit.id} — Add Clinical Data`;
        showClinicalDataSection(savedVisit);
    } catch (err) { alert("Error saving visit: " + err.message); }
}

function showClinicalDataSection(visit) {
    const section = document.getElementById('clinicalDataSection');
    section.style.display = 'block';

    // Pre-fill sick leave date with visit date
    const slFrom = document.getElementById('newSickLeaveFrom');
    if (slFrom && visit.visitDate) slFrom.value = visit.visitDate;

    renderExistingClinicalData(visit);
}

function renderExistingClinicalData(visit) {
    // Diagnoses
    const diagList = document.getElementById('diagnosisList');
    if (visit.diagnoses && visit.diagnoses.length > 0) {
        diagList.innerHTML = visit.diagnoses.map(d =>
            `<div style="display: flex; justify-content: space-between; align-items: center; background: rgba(56, 189, 248, 0.1); border: 1px solid #1E3A5F; padding: 0.4rem 0.75rem; border-radius: 8px; margin-bottom: 0.3rem;">
                <span style="color: #E2E8F0;">${esc(d.name)}</span>
                <button type="button" onclick="removeDiagnosis(${d.id})" style="background: none; border: none; color: #EF4444; cursor: pointer; font-size: 1rem;">✕</button>
            </div>`
        ).join('');
    } else {
        diagList.innerHTML = '<p style="color: #64748B; font-size: 0.85rem; margin: 0;">No diagnoses yet.</p>';
    }

    // Prescriptions
    const prescList = document.getElementById('prescriptionList');
    if (visit.prescriptions && visit.prescriptions.length > 0) {
        prescList.innerHTML = visit.prescriptions.map(p =>
            `<div style="display: flex; justify-content: space-between; align-items: center; background: rgba(16, 185, 129, 0.1); border: 1px solid #065F46; padding: 0.4rem 0.75rem; border-radius: 8px; margin-bottom: 0.3rem;">
                <span style="color: #E2E8F0;">${esc(p.note)}</span>
                <button type="button" onclick="removePrescription(${p.id})" style="background: none; border: none; color: #EF4444; cursor: pointer; font-size: 1rem;">✕</button>
            </div>`
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
                <span style="color: #E2E8F0;">From ${visit.sickLeave.validFrom} for ${visit.sickLeave.validForDays} days</span>
                <button type="button" onclick="removeSickLeave(${visit.sickLeave.id})" style="background: none; border: none; color: #EF4444; cursor: pointer; font-size: 1rem;">✕</button>
            </div>`;
        slForm.style.display = 'none';
    } else {
        slDisplay.innerHTML = '';
        slForm.style.display = 'flex';
    }
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
        if (!res.ok) throw new Error('Failed to add diagnosis');
        input.value = '';
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
        if (!res.ok) throw new Error('Failed to add prescription');
        input.value = '';
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function addSickLeaveToVisit() {
    const validFrom = document.getElementById('newSickLeaveFrom').value;
    const validForDays = document.getElementById('newSickLeaveDays').value;
    if (!validFrom || !validForDays) { alert('Please fill in both date and number of days.'); return; }
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/sickleave`, {
            method: 'POST', body: JSON.stringify({ visitId: currentEditVisitId, validFrom, validForDays: Number(validForDays) })
        });
        if (!res.ok) {
            const errJson = await res.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : 'Failed to issue sick leave');
        }
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removeDiagnosis(id) {
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/diagnosis/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error('Failed to remove diagnosis');
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removePrescription(id) {
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/prescription/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error('Failed to remove prescription');
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function removeSickLeave(id) {
    try {
        const res = await fetchWithAuth(`${API_BASE}/visits/sickleave/${id}`, { method: 'DELETE' });
        if (!res.ok) throw new Error('Failed to remove sick leave');
        await refreshVisitAndRender();
    } catch (err) { alert(err.message); }
}

async function deleteVisit(id) {
    if (!confirm('Are you sure you want to delete this visit?')) return;
    try {
        const response = await fetchWithAuth(`${API_BASE}/visits/${id}`, { method: 'DELETE' });
        if (!response.ok) throw new Error('Failed to delete visit');
        loadDoctorVisits();
    } catch (err) { alert(err.message); }
}

async function loadDoctorVisits() {
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
        
        html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Patient</th><th onclick="sortTable(2, this)">Doctor</th><th onclick="sortTable(3, this)">Diagnosis</th>';
        if (currentUserRole === 'ADMIN' || currentUserRole === 'DOCTOR') html += `<th>Actions</th>`;
        html += `</tr></thead><tbody>`;
        
        visitsList.forEach(v => {
            const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => d.name).join(', ') : '—';
            html += `<tr><td>${esc(v.visitDate)} ${esc(v.visitTime)}</td><td>${esc(v.patientName)}</td><td>Dr. ${esc(v.doctorName)}</td><td>${esc(diagNames)}</td>`;
            
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
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading visits: ${esc(error.message)}</p>`;
    }
}

// ================= OTHERS =================

async function loadMyPatients() {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading your patients...</p>';
    try {
        const docResponse = await fetchWithAuth(`${API_BASE}/doctors/keycloak/${keycloak.tokenParsed.sub}`);
        if (!docResponse.ok) {
            contentArea.innerHTML = `<p style="color: #EF4444;">Could not find your doctor profile in the database.</p>`;
            return;
        }
        const doctor = await docResponse.json();
        currentDoctorId = doctor.id;

        const patResponse = await fetchWithAuth(`${API_BASE}/doctors/${doctor.id}/patients`);
        if (!patResponse.ok) throw new Error(`HTTP ${patResponse.status}`);
        const patients = await patResponse.json();

        let html = `<h3>My Patients (Dr. ${esc(doctor.firstName)} ${esc(doctor.lastName)})</h3>`;
        if (patients.length === 0) {
            html += '<p>No patients assigned to you.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Name</th><th onclick="sortTable(1, this)">EGN</th></tr></thead><tbody>';
            patients.forEach(p => {
                html += `<tr><td>${esc(p.firstName)} ${esc(p.lastName)}</td><td>${esc(p.egn)}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading patients: ${esc(error.message)}</p>`;
    }
}

async function loadPatientHistory() {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading patient history...</p>';
    try {
        const pResponse = await fetchWithAuth(`${API_BASE}/patients/keycloak/${keycloak.tokenParsed.sub}`);
        if (!pResponse.ok) {
            contentArea.innerHTML = `<p style="color: #EF4444;">Could not find your patient profile in the database.</p>`;
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

        const insBadge = insuranceStatus === true
            ? `<span style="display:inline-block; padding: 0.25rem 0.75rem; background: rgba(16, 185, 129, 0.2); border: 1px solid #10B981; color: #10B981; border-radius: 20px; font-size: 0.85rem; font-weight: 600;">✓ Health Insurance Active</span>`
            : `<span style="display:inline-block; padding: 0.25rem 0.75rem; background: rgba(239, 68, 68, 0.2); border: 1px solid #EF4444; color: #EF4444; border-radius: 20px; font-size: 0.85rem; font-weight: 600;">✕ Health Insurance Lapsed / Unpaid</span>`;

        let html = `<div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:1rem; margin-bottom:1rem;">
            <div>
                <h3 style="margin-bottom: 0.25rem;">Welcome, ${esc(patient.firstName)} ${esc(patient.lastName)}</h3>
                <h4 style="color: #94A3B8; margin: 0;">EGN: ${esc(patient.egn)} | GP: ${esc(patient.gpDoctorName || 'Not assigned')}</h4>
            </div>
            <div style="display:flex; gap:0.5rem; align-items:center;">
                ${insBadge}
                <button class="btn btn-secondary" style="padding: 0.35rem 0.8rem; font-size: 0.85rem;" onclick="openChangeGpModal(${patient.id}, ${patient.gpDoctorId || 'null'})">Change GP</button>
            </div>
        </div>`;

        if (visits.length === 0) {
            html += '<p>No medical visits found.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Doctor</th><th onclick="sortTable(2, this)">Diagnosis</th><th onclick="sortTable(3, this)">Prescription</th><th onclick="sortTable(4, this)">Sick Leave</th></tr></thead><tbody>';
            visits.forEach(v => {
                const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => esc(d.name)).join(', ') : '—';
                const rxNotes = v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p => esc(p.note)).join('; ') : '—';
                const slInfo = v.sickLeave ? `${esc(v.sickLeave.validFrom)} (${esc(String(v.sickLeave.validForDays))} days)` : '—';
                html += `<tr><td>${esc(v.visitDate)}</td><td>Dr. ${esc(v.doctorName)}</td><td>${diagNames}</td><td>${rxNotes}</td><td>${slInfo}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (error) {
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading patient history: ${esc(error.message)}</p>`;
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
                <td>${d.registeredAt ? esc(new Date(d.registeredAt).toLocaleDateString()) : '—'}</td>
                <td><button class="action-btn" style="background:#10B981;color:white;" onclick="approveDoctor(${d.doctorId})">Approve</button></td>
            </tr>`
        ).join('');

        return `
            <div style="background: rgba(245, 158, 11, 0.1); border: 1px solid #F59E0B; padding: 1.25rem; border-radius: 12px; margin-bottom: 1.5rem;">
                <h4 style="color: #F59E0B; margin-bottom: 0.75rem;">🔔 Doctors Awaiting Approval (${pending.length})</h4>
                <p style="color: #94A3B8; font-size: 0.88rem; margin-bottom: 0.75rem;">These people registered themselves as doctors. Approving grants them the <strong>DOCTOR</strong> role so they can sign in. Set GP status separately from the Doctors tab.</p>
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
    const contentArea = document.getElementById('content-area');

    // Pending approvals are an administrator concern; the endpoint refuses doctors, so don't ask.
    let pendingHtml = '';
    if (currentUserRole === 'ADMIN') {
        pendingHtml = await renderPendingApprovals();
    }
    
    let html = `
        <h3>Full Analytical Reports Dashboard</h3>
        <p style="color: #94A3B8; margin-bottom: 1.5rem;">Generate live analytical insights directly from system records.</p>
        
        ${pendingHtml}

        <div class="reports-grid">
            <div class="report-card" onclick="fetchReport('/reports/diagnoses/common', 'Most Common Diagnoses', ['Diagnosis', 'Occurrences'], r => [r.diagnosisName, r.count])">
                <div style="display:flex; justify-content:space-between; align-items:flex-start;">
                    <h4>📊 1. Most Common Diagnoses</h4>
                    <button class="btn btn-secondary" style="padding: 0.2rem 0.5rem; font-size: 0.75rem;" onclick="event.stopPropagation(); downloadCsv('/reports/export/diagnoses/common', 'most-common-diagnoses.csv')">CSV</button>
                </div>
                <p>List of diagnoses ordered by frequency.</p>
            </div>
            <div class="report-card" onclick="renderReportFilterPanel('diagnosis')">
                <h4>🔍 2. Patients by Diagnosis</h4>
                <p>Find all patients who have received a specific diagnosis.</p>
            </div>
            <div class="report-card" onclick="fetchReport('/reports/doctors/gp-patients', 'Patients per GP', ['Doctor ID', 'Doctor Name', 'Patients'], r => [r.doctorId, 'Dr. '+r.doctorName, r.patientCount])">
                <h4>👥 3. GP Patient Counts</h4>
                <p>Number of registered patients for each General Practitioner.</p>
            </div>
            <div class="report-card" onclick="fetchReport('/reports/doctors/visits', 'Visits per Doctor', ['Doctor ID', 'Doctor Name', 'Visit Count'], r => [r.doctorId, 'Dr. '+r.doctorName, r.visitCount])">
                <div style="display:flex; justify-content:space-between; align-items:flex-start;">
                    <h4>🩺 4. Visit Count per Doctor</h4>
                    <button class="btn btn-secondary" style="padding: 0.2rem 0.5rem; font-size: 0.75rem;" onclick="event.stopPropagation(); downloadCsv('/reports/export/doctors/visits', 'doctor-visits.csv')">CSV</button>
                </div>
                <p>Total number of medical visits handled by each doctor.</p>
            </div>
            <div class="report-card" onclick="fetchReport('/reports/sickleaves/doctors', 'Sick Leaves per Doctor', ['Doctor ID', 'Doctor Name', 'Sick Leaves Issued'], r => [r.doctorId, 'Dr. '+r.doctorName, r.sickLeaveCount])">
                <div style="display:flex; justify-content:space-between; align-items:flex-start;">
                    <h4>📜 5. Doctors Issuing Most Sick Leaves</h4>
                    <button class="btn btn-secondary" style="padding: 0.2rem 0.5rem; font-size: 0.75rem;" onclick="event.stopPropagation(); downloadCsv('/reports/export/sickleaves/doctors', 'doctor-sickleaves.csv')">CSV</button>
                </div>
                <p>Doctors sorted by the number of sick leaves issued.</p>
            </div>
            <div class="report-card" onclick="fetchReport('/reports/sickleaves/months', 'Sick Leaves per Month', ['Month', 'Sick Leaves Issued'], r => [r.month, r.sickLeaveCount])">
                <h4>📅 6. Sick Leaves by Month</h4>
                <p>Months with the highest number of sick leaves issued.</p>
            </div>
            <div class="report-card" onclick="renderReportFilterPanel('dates')">
                <h4>🗓️ 7. All Visits in Period</h4>
                <p>Filter all medical visits across the system by a date range.</p>
            </div>
            <div class="report-card" onclick="renderReportFilterPanel('doctor_dates')">
                <h4>👨‍⚕️ 8. Visits per Doctor in Period</h4>
                <p>Filter medical visits for a specific doctor by a date range.</p>
            </div>
            <div class="report-card" onclick="renderReportFilterPanel('patient_visits')">
                <h4>📋 9. Visits for a Specific Patient</h4>
                <p>Lookup medical visit history for a specific patient by name.</p>
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

    const todayStr = new Date().toISOString().split('T')[0];

    if (type === 'diagnosis') {
        // Fetch actual diagnosis names from the database
        let diagOptions = '<option value="">-- Loading... --</option>';
        try {
            const diagRes = await fetchWithAuth(`${API_BASE}/reports/diagnoses/names`);
            if (diagRes.ok) {
                const names = await diagRes.json();
                diagOptions = names.map(n => `<option value="${n}">${n}</option>`).join('');
                if (names.length === 0) diagOptions = '<option value="">No diagnoses found</option>';
            }
        } catch (e) { diagOptions = '<option value="">Error loading diagnoses</option>'; }

        filterPanel.innerHTML = `
            <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.25rem; border-radius: 12px; margin-top: 1rem;">
                <h4 style="color: #F8FAFC; margin-bottom: 1rem;">Filter Report: Patients by Diagnosis</h4>
                <div style="display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap;">
                    <div style="flex: 1; min-width: 220px;">
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">Select Diagnosis:</label>
                        <select id="inlineDiagSelect" style="width: 100%; padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                            ${diagOptions}
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="runDiagnosisReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'dates') {
        filterPanel.innerHTML = `
            <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.25rem; border-radius: 12px; margin-top: 1rem;">
                <h4 style="color: #F8FAFC; margin-bottom: 1rem;">Filter Report: Visits in Date Range</h4>
                <div style="display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap;">
                    <div>
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">Start Date:</label>
                        <input type="date" id="inlineStartDate" value="2026-01-01" style="padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                    </div>
                    <div>
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">End Date:</label>
                        <input type="date" id="inlineEndDate" value="${todayStr}" style="padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                    </div>
                    <button class="btn btn-primary" onclick="runDatesReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'doctor_dates') {
        await fetchDoctorsList();
        filterPanel.innerHTML = `
            <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.25rem; border-radius: 12px; margin-top: 1rem;">
                <h4 style="color: #F8FAFC; margin-bottom: 1rem;">Filter Report: Visits by Doctor in Date Range</h4>
                <div style="display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap;">
                    <div style="flex: 1; min-width: 200px;">
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">Select Doctor:</label>
                        <select id="inlineDocSelect" style="width: 100%; padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                            ${doctorsList.map(d => `<option value="${d.id}">Dr. ${esc(d.firstName)} ${esc(d.lastName)}</option>`).join('')}
                        </select>
                    </div>
                    <div>
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">Start Date:</label>
                        <input type="date" id="inlineStartDate" value="2026-01-01" style="padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                    </div>
                    <div>
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">End Date:</label>
                        <input type="date" id="inlineEndDate" value="${todayStr}" style="padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                    </div>
                    <button class="btn btn-primary" onclick="runDoctorDatesReport()">Generate Report</button>
                </div>
            </div>
        `;
    } else if (type === 'patient_visits') {
        await fetchPatientsList();
        filterPanel.innerHTML = `
            <div style="background: rgba(30, 41, 59, 0.8); border: 1px solid var(--glass-border); padding: 1.25rem; border-radius: 12px; margin-top: 1rem;">
                <h4 style="color: #F8FAFC; margin-bottom: 1rem;">Filter Report: Visits for Patient</h4>
                <div style="display: flex; gap: 1rem; align-items: flex-end; flex-wrap: wrap;">
                    <div style="flex: 1; min-width: 240px;">
                        <label style="display: block; color: #94A3B8; margin-bottom: 0.4rem; font-size: 0.88rem;">Select Patient:</label>
                        <select id="inlinePatSelect" style="width: 100%; padding: 0.5rem; border-radius: 6px; border: 1px solid #475569; background: #0F172A; color: white;">
                            ${patientsList.map(p => `<option value="${p.id}">${esc(p.firstName)} ${esc(p.lastName)} (EGN: ${esc(p.egn)})</option>`).join('')}
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="runPatientVisitsReport()">Generate Report</button>
                </div>
            </div>
        `;
    }
}

function runDiagnosisReport() {
    const diag = document.getElementById('inlineDiagSelect').value;
    fetchReport(`/reports/diagnoses/patients?diagnosis=${encodeURIComponent(diag)}`, `Patients diagnosed with: ${diag}`, ['ID', 'Patient Name', 'EGN'], p => [p.id, p.firstName + ' ' + p.lastName, p.egn], true);
}

function runDatesReport() {
    const start = document.getElementById('inlineStartDate').value;
    const end = document.getElementById('inlineEndDate').value;
    if (!start || !end) { alert('Please select both start and end dates.'); return; }
    fetchReport(`/reports/visits/period?start=${start}&end=${end}`, `Visits from ${start} to ${end}`, ['Visit ID', 'Date', 'Doctor', 'Patient'], v => [v.id, v.visitDate + ' ' + (v.visitTime || ''), 'Dr. ' + v.doctorName, v.patientName], true);
}

function runDoctorDatesReport() {
    const docId = document.getElementById('inlineDocSelect').value;
    const start = document.getElementById('inlineStartDate').value;
    const end = document.getElementById('inlineEndDate').value;
    if (!docId) { alert('Please select a Doctor.'); return; }
    if (!start || !end) { alert('Please select both start and end dates.'); return; }

    const docObj = doctorsList.find(d => String(d.id) === String(docId));
    const docName = docObj ? `${docObj.firstName} ${docObj.lastName}` : `ID ${docId}`;

    fetchReport(`/reports/visits/doctor/period?doctorId=${docId}&start=${start}&end=${end}`, `Visits by Dr. ${docName} (${start} to ${end})`, ['Visit ID', 'Date', 'Patient'], v => [v.id, v.visitDate + ' ' + (v.visitTime || ''), v.patientName], true);
}

function runPatientVisitsReport() {
    const patId = document.getElementById('inlinePatSelect').value;
    if (!patId) { alert('Please select a Patient.'); return; }

    const patObj = patientsList.find(p => String(p.id) === String(patId));
    const patName = patObj ? `${patObj.firstName} ${patObj.lastName}` : `ID ${patId}`;

    fetchReport(`/visits/patient/${patId}`, `Visits for Patient: ${patName}`, ['Visit ID', 'Date', 'Doctor', 'Diagnosis', 'Prescription'], v => [
        v.id,
        v.visitDate + ' ' + (v.visitTime || ''),
        'Dr. ' + v.doctorName,
        (v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d=>d.name).join(', ') : '—'),
        (v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p=>p.note).join('; ') : '—')
    ], true);
}

async function fetchReport(endpoint, title, headers, rowMapper, keepFilterPanel = false) {
    const filterPanel = document.getElementById('report-filter-panel');
    if (!keepFilterPanel && filterPanel) {
        filterPanel.innerHTML = '';
    }

    const resultsArea = document.getElementById('report-results');
    resultsArea.innerHTML = '<p style="color: #94A3B8;">Generating report...</p>';
    
    try {
        const response = await fetchWithAuth(`${API_BASE}${endpoint}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        
        // Escape centrally: every report title and cell here is server data (patient and doctor
        // names, diagnosis text, prescription notes), and no row mapper produces markup.
        let html = `<h4 style="color: #F8FAFC; margin-bottom: 1rem;">${esc(title)}</h4>`;
        if (data.length === 0) {
            html += '<p style="color: #94A3B8;">No records found for this report.</p>';
        } else {
            html += '<table class="data-table"><thead><tr>';
            headers.forEach((h, i) => html += `<th onclick="sortTable(${i}, this)">${esc(h)}</th>`);
            html += '</tr></thead><tbody>';

            data.forEach(item => {
                const rowData = rowMapper(item);
                html += '<tr>';
                rowData.forEach(cell => html += `<td>${esc(cell)}</td>`);
                html += '</tr>';
            });
            html += '</tbody></table>';
        }
        resultsArea.innerHTML = html;
    } catch (err) {
        resultsArea.innerHTML = `<p style="color: #EF4444;">Error generating report: ${esc(err.message)}</p>`;
    }
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
function sortTable(columnIndex, thElement) {
    const table = thElement.closest('table');
    const tbody = table.querySelector('tbody');
    const rows = Array.from(tbody.querySelectorAll('tr'));
    
    const key = `${columnIndex}`;
    const ascending = !sortDirections[key];
    sortDirections[key] = ascending;

    rows.sort((rowA, rowB) => {
        const cellA = rowA.children[columnIndex].innerText.trim();
        const cellB = rowB.children[columnIndex].innerText.trim();
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
async function downloadCsv(endpoint, filename) {
    try {
        const res = await fetchWithAuth(`${API_BASE}${endpoint}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
    } catch (e) {
        alert('Failed to download CSV: ' + e.message);
    }
}

// ================= AUDIT LOGS =================
async function loadAuditLogs(page = 0) {
    const contentArea = document.getElementById('content-area');
    contentArea.innerHTML = '<p>Loading audit logs...</p>';
    try {
        const res = await fetchWithAuth(`${API_BASE}/audit-logs?page=${page}&size=50`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const pageData = await res.json();
        const logs = pageData.content || [];

        let html = '<h3>System Audit Logs</h3>';
        html += '<p style="color: #94A3B8; margin-bottom: 1rem;">Track all entity creation, modification, and deletion events across the system.</p>';

        if (logs.length === 0) {
            html += '<p style="color: #94A3B8;">No audit logs found.</p>';
        } else {
            html += '<table class="data-table"><thead><tr>';
            html += '<th onclick="sortTable(0, this)">Timestamp</th><th onclick="sortTable(1, this)">Action</th><th onclick="sortTable(2, this)">Entity</th><th onclick="sortTable(3, this)">ID</th><th onclick="sortTable(4, this)">Actor</th>';
            html += '</tr></thead><tbody>';
            logs.forEach(l => {
                const dateStr = l.createdAt ? new Date(l.createdAt).toLocaleString() : '—';
                html += `<tr><td>${esc(dateStr)}</td><td><span class="role-badge">${esc(l.action)}</span></td><td>${esc(l.entityType)}</td><td>#${esc(String(l.entityId))}</td><td>${esc(l.actor)}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (e) {
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading audit logs: ${esc(e.message)}</p>`;
    }
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
            const err = await res.json().catch(() => null);
            throw new Error((err && err.message) ? err.message : `HTTP ${res.status}`);
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
function openRecordInsuranceModal(patientId) {
    document.getElementById('insurancePatientId').value = patientId;
    document.getElementById('insurancePatientName').value = patientNameById(patientId);
    const today = new Date().toISOString().split('T')[0];
    document.getElementById('insuranceDate').value = today;
    document.getElementById('insurancePaidOn').value = today;
    openModal('insuranceModal');
}

async function submitInsurancePayment(e) {
    e.preventDefault();
    const patientId = Number(document.getElementById('insurancePatientId').value);
    const insuranceDate = document.getElementById('insuranceDate').value;
    const paidOn = document.getElementById('insurancePaidOn').value;

    try {
        const res = await fetchWithAuth(`${API_BASE}/patients/${patientId}/insurance`, {
            method: 'POST',
            // patientId travels in the path, not the body.
            body: JSON.stringify({ insuranceDate, paidOn })
        });
        if (!res.ok) {
            const err = await res.json().catch(() => null);
            throw new Error((err && err.message) ? err.message : `HTTP ${res.status}`);
        }
        closeModal('insuranceModal');
        alert('Insurance payment recorded successfully!');
        loadAllPatients();
    } catch (err) {
        alert('Failed to record payment: ' + err.message);
    }
}

// ================= DIRECT PATIENT VISITS LOOKUP =================
/**
 * Grants a self-registered doctor the DOCTOR role, then refreshes the dashboard so the
 * approved entry disappears from the pending banner.
 */
async function approveDoctor(doctorId) {
    if (!confirm('Approve this doctor and grant them the DOCTOR role?')) return;
    try {
        const res = await fetchWithAuth(`${API_BASE}/doctors/${doctorId}/approve`, { method: 'POST' });
        if (!res.ok) {
            const errJson = await res.json().catch(() => null);
            throw new Error((errJson && errJson.message) ? errJson.message : `HTTP ${res.status}`);
        }
        await loadFullReportsDashboard();
    } catch (err) {
        alert('Could not approve doctor: ' + err.message);
    }
}

/**
 * Requirement 3c — "Списък с пациенти, които имат даден личен лекар".
 * Renders into the main content area rather than through fetchReport, because the reports
 * dashboard elements that helper writes into do not exist on the Doctors Directory screen.
 */
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
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading patients: ${esc(err.message)}</p>`;
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
            <button class="btn btn-secondary" onclick="loadAllPatients()">← Back to Patients</button>
        </div>`;

        if (visits.length === 0) {
            html += '<p>No visits recorded for this patient.</p>';
        } else {
            html += '<table class="data-table"><thead><tr><th onclick="sortTable(0, this)">Date</th><th onclick="sortTable(1, this)">Doctor</th><th onclick="sortTable(2, this)">Diagnosis</th><th onclick="sortTable(3, this)">Prescription</th><th onclick="sortTable(4, this)">Sick Leave</th></tr></thead><tbody>';
            visits.forEach(v => {
                const diagNames = v.diagnoses && v.diagnoses.length > 0 ? v.diagnoses.map(d => esc(d.name)).join(', ') : '—';
                const rxNotes = v.prescriptions && v.prescriptions.length > 0 ? v.prescriptions.map(p => esc(p.note)).join('; ') : '—';
                const slInfo = v.sickLeave ? `${esc(v.sickLeave.validFrom)} (${esc(String(v.sickLeave.validForDays))} days)` : '—';
                html += `<tr><td>${esc(v.visitDate)}</td><td>Dr. ${esc(v.doctorName)}</td><td>${diagNames}</td><td>${rxNotes}</td><td>${slInfo}</td></tr>`;
            });
            html += '</tbody></table>';
        }
        contentArea.innerHTML = html;
    } catch (err) {
        contentArea.innerHTML = `<p style="color: #EF4444;">Error loading patient visits: ${esc(err.message)}</p>`;
    }
}
