"use strict";

const byId = id => document.getElementById(id);
const auth = byId("auth");
const editor = byId("editor");
const filters = byId("filters");
const workspace = byId("workspace");
const entries = byId("entries");
const pageSize = 10;

let authorization = "";
let editingId = null;
let inTrash = false;
let page = 0;
let total = 0;
let query = new URLSearchParams();

function showMessage(text, error = false) {
  byId("message").textContent = text;
  byId("message").classList.toggle("error", error);
}

async function api(path, method = "GET", body, credentials = authorization) {
  const headers = { "X-Requested-With": "XMLHttpRequest" };
  if (credentials) headers.Authorization = credentials;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: "omit",
    cache: "no-store"
  });
  if (!response.ok) {
    if (response.status === 401 && authorization) signOut();
    const messages = {
      400: "Check the fields and try again.",
      401: "Sign in with a valid username and password.",
      404: "That note is no longer available in this view.",
      409: "The request conflicts with an existing account or change."
    };
    throw new Error(messages[response.status] || "The server could not complete the request.");
  }
  return response;
}

async function run(action) {
  byId("auth-controls").disabled = true;
  byId("workspace-controls").disabled = true;
  showMessage("");
  try {
    await action();
  } catch (error) {
    showMessage(error instanceof TypeError ? "Could not reach the server." : error.message, true);
  } finally {
    byId("auth-controls").disabled = false;
    byId("workspace-controls").disabled = false;
  }
}

function signOut() {
  authorization = "";
  resetEditor();
  filters.reset();
  query = new URLSearchParams();
  entries.replaceChildren();
  byId("account").textContent = "Your notes";
  workspace.hidden = true;
  auth.hidden = false;
  auth.reset();
}

function resetEditor() {
  editingId = null;
  editor.reset();
  byId("editor-title").textContent = "New note";
  byId("cancel-edit").hidden = true;
}

function edit(note) {
  editingId = note.id;
  byId("editor-title").textContent = "Edit note";
  byId("cancel-edit").hidden = false;
  editor.elements.title.value = note.title;
  editor.elements.content.value = note.content ?? "";
  editor.elements.tags.value = note.tags.join(", ");
  editor.elements.title.focus();
}

function noteButton(label, action, style = "secondary") {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = label;
  button.className = style;
  button.addEventListener("click", action);
  return button;
}

async function changeTrash(note, method, path, message) {
  await api(path, method);
  if (editingId === note.id) resetEditor();
  await loadNotes();
  showMessage(message);
}

function renderNote(note) {
  const card = document.createElement("article");
  const title = document.createElement("h3");
  title.textContent = note.title;
  const content = document.createElement("p");
  content.className = "note-content";
  content.textContent = note.content ?? "";
  const tags = document.createElement("p");
  tags.className = "note-tags";
  tags.textContent = note.tags.map(tag => "#" + tag).join("  ");
  tags.hidden = note.tags.length === 0;
  const actions = document.createElement("div");
  actions.className = "actions";
  const id = encodeURIComponent(note.id);
  const trashPath = "/demo/trash/" + id;
  if (inTrash) {
    actions.append(
      noteButton("Restore", () => run(() => changeTrash(note, "PATCH", trashPath, "Note restored."))),
      noteButton("Delete permanently", () => {
        if (window.confirm("Permanently delete this note?")) {
          run(() => changeTrash(note, "DELETE", trashPath, "Note permanently deleted."));
        }
      }, "danger")
    );
  } else {
    actions.append(
      noteButton("Edit", () => edit(note)),
      noteButton("Move to trash", () => run(() => changeTrash(note, "DELETE", "/demo/id/" + id, "Note moved to trash.")))
    );
  }
  card.append(title, content, tags, actions);
  return card;
}

async function loadNotes() {
  const params = new URLSearchParams(inTrash ? undefined : query);
  if (!inTrash) params.set("sort", "updated");
  params.set("page", page);
  params.set("size", pageSize);
  const response = await api((inTrash ? "/demo/trash?" : "/demo?") + params);
  total = Number(response.headers.get("X-Total-Count"));
  const lastPage = Math.max(0, Math.ceil(total / pageSize) - 1);
  if (page > lastPage) {
    page = lastPage;
    return loadNotes();
  }
  const notes = await response.json();
  entries.replaceChildren(...notes.map(renderNote));
  if (!notes.length) entries.textContent = inTrash ? "Trash is empty." : "No notes found.";
  byId("previous").disabled = page === 0;
  byId("next").disabled = page === lastPage;
  byId("page-label").textContent = "Page " + (page + 1) + " of " + (lastPage + 1);
}

async function switchView(showTrash) {
  inTrash = showTrash;
  page = 0;
  byId("show-notes").setAttribute("aria-pressed", String(!inTrash));
  byId("show-trash").setAttribute("aria-pressed", String(inTrash));
  byId("show-notes").classList.toggle("secondary", inTrash);
  byId("show-trash").classList.toggle("secondary", !inTrash);
  byId("list-title").textContent = inTrash ? "Trash" : "Notes";
  editor.hidden = inTrash;
  filters.hidden = inTrash;
  entries.replaceChildren();
  await loadNotes();
}

auth.addEventListener("submit", event => {
  event.preventDefault();
  const { username, password } = Object.fromEntries(new FormData(auth));
  const register = event.submitter?.value === "register";
  run(async () => {
    if (register) await api("/user", "POST", { username, password }, "");
    const encoded = new TextEncoder().encode(username + ":" + password);
    const credentials = "Basic " + btoa(Array.from(encoded, byte => String.fromCharCode(byte)).join(""));
    const response = await api("/user", "GET", undefined, credentials);
    const account = await response.json();
    authorization = credentials;
    auth.reset();
    auth.hidden = true;
    workspace.hidden = false;
    byId("account").textContent = "Signed in as " + account.username;
    await switchView(false);
  });
});

editor.addEventListener("submit", event => {
  event.preventDefault();
  const { title, content, tags } = Object.fromEntries(new FormData(editor));
  const body = { title, content, tags: tags.split(",").map(tag => tag.trim()).filter(Boolean) };
  run(async () => {
    const path = editingId ? "/demo/id/" + encodeURIComponent(editingId) : "/demo";
    await api(path, editingId ? "PATCH" : "POST", body);
    resetEditor();
    page = 0;
    await loadNotes();
    showMessage("Note saved.");
  });
});

filters.addEventListener("submit", event => {
  event.preventDefault();
  query = new URLSearchParams(new FormData(filters));
  page = 0;
  run(loadNotes);
});

byId("show-notes").addEventListener("click", () => run(() => switchView(false)));
byId("show-trash").addEventListener("click", () => run(() => switchView(true)));
byId("signout").addEventListener("click", () => {
  signOut();
  showMessage("Signed out.");
  auth.elements.username.focus();
});
byId("cancel-edit").addEventListener("click", resetEditor);
byId("previous").addEventListener("click", () => {
  page -= 1;
  run(loadNotes);
});
byId("next").addEventListener("click", () => {
  page += 1;
  run(loadNotes);
});
