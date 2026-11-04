"use strict";
import { DateRangeSelection, dayCount, orderedRange, plusDays, rangeError } from "./date-range.js";
const $ = (s, root = document) => root.querySelector(s),
  mode = document.body.dataset.mode;
let policy,
  viewMonth,
  calendarRequest = 0,
  bookingKey = crypto.randomUUID();
const selection = new DateRangeSelection(), calendarMonths = new Map();
let preview = null, gesture = null, ignoreClick = false, selectionRevision = 0;
const labels = {
  CONFIRMED: "예약 확정",
  CANCELLED: "취소",
  PENDING: "승인 대기",
  ACTIVE: "활성",
  REJECTED: "거절",
  SUSPENDED: "정지",
  WITHDRAWN: "탈퇴",
  CONTACTED: "연락 완료",
  ACKNOWLEDGED: "본인 확인",
  REVOKED: "폐기",
  EXPIRED: "만료",
  USED: "사용 완료",
  RELEASED: "해제",
};
async function api(path, options = {}) {
  const headers = { Accept: "application/json", ...options.headers };
  if (options.body) headers["Content-Type"] = "application/json";
  if (options.method && options.method !== "GET")
    headers[$('meta[name="_csrf_header"]').content] =
      $('meta[name="_csrf"]').content;
  const response = await fetch(path, {
    ...options,
    headers,
    credentials: "same-origin",
  });
  const text = await response.text();
  let data;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    throw Error("서버 응답을 확인하지 못했습니다. 새로고침 후 다시 확인해 주세요.");
  }
  if (!response.ok) {
    if (response.status === 401 && !["login", "signup", "reset"].includes(mode))
      location.href = "/login";
    const fields = data?.fieldErrors
      ? Object.values(data.fieldErrors).join("\n")
      : "";
    const e = Error(fields || data?.message || "요청을 처리하지 못했습니다.");
    e.status = response.status;
    throw e;
  }
  return data;
}
function message(target, text, success = false) {
  const n = $(target);
  n.textContent = text;
  n.classList.toggle("success", success);
}
function el(tag, text, cls) {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function button(text, action, cls) {
  const b = el("button", text, cls);
  b.type = "button";
  b.onclick = async () => {
    b.disabled = true;
    try {
      await action();
    } catch (e) {
      message("#global-message", e.message);
    } finally {
      b.disabled = false;
    }
  };
  return b;
}
function empty(t, text = "아직 내역이 없습니다.") {
  t.replaceChildren(el("p", text, "empty-state"));
}
const json = (method, body) => ({ method, body: JSON.stringify(body) });
function ask(question, needsReason = false) {
  return new Promise((resolve) => {
    const dialog = el("dialog");
    const form = el("form");
    const title = el("h2", needsReason ? "처리 사유" : "내용 확인");
    title.id = "action-dialog-title";
    dialog.setAttribute("aria-labelledby", title.id);
    const copy = el("p", question, "dialog-copy");
    form.append(title, copy);
    let input;
    if (needsReason) {
      const label = el("label", "사유");
      input = el("textarea");
      input.required = true;
      input.maxLength = 500;
      label.append(input);
      form.append(label);
    }
    const actions = el("div", null, "actions");
    const cancel = el("button", "돌아가기");
    cancel.type = "button";
    cancel.onclick = () => dialog.close();
    const submit = el("button", "확인", "primary");
    submit.type = "submit";
    actions.append(cancel, submit);
    form.append(actions);
    form.onsubmit = (event) => {
      event.preventDefault();
      if (needsReason && !input.value.trim()) {
        input.setCustomValidity("처리 사유를 입력해 주세요.");
        input.reportValidity();
        return;
      }
      dialog.close("confirmed");
    };
    if (input) input.oninput = () => input.setCustomValidity("");
    dialog.append(form);
    dialog.onclose = () => {
      const result =
        dialog.returnValue === "confirmed"
          ? needsReason
            ? input.value.trim()
            : true
          : null;
      dialog.remove();
      resolve(result);
    };
    document.body.append(dialog);
    dialog.showModal();
  });
}
const reason = (label) => ask(label, true);
const confirmAction = (label) => ask(label);
function bindForm(selector, handler, target = "#global-message") {
  const f = $(selector);
  if (!f) return;
  f.addEventListener("submit", async (e) => {
    e.preventDefault();
    const b = $('button[type="submit"]', f);
    b.disabled = true;
    message(target, "");
    try {
      await handler(Object.fromEntries(new FormData(f)), f);
    } catch (x) {
      message(target, x.message);
    } finally {
      b.disabled = false;
    }
  });
}
function iso(y, m, d) {
  return (
    y + "-" + String(m + 1).padStart(2, "0") + "-" + String(d).padStart(2, "0")
  );
}
function monthKeys(range) {
  const keys = new Set();
  if (!rangeError(range, policy))
    for (let date = range.start; date <= range.end; date = plusDays(date, 1))
      keys.add(date.slice(0, 7));
  return [...keys];
}
function selectionError(range) {
  return rangeError(range, policy, [...calendarMonths.values()].flat());
}
async function loadSelectionMonths(range, refresh = false) {
  await Promise.all(monthKeys(range).map(async (key) => {
    if (refresh || !calendarMonths.has(key)) {
      const [year, month] = key.split("-").map(Number);
      calendarMonths.set(key, await api("/reservations/calendar?year=" + year + "&month=" + month));
    }
  }));
}
function paintSelection() {
  const range = preview || selection;
  const error = selectionError(range);
  for (const b of $("#calendar").querySelectorAll("[data-date]")) {
    const date = b.dataset.date;
    const selected = !!range.start && date >= range.start && date <= range.end;
    b.classList.toggle("selected", selected);
    b.classList.toggle("selection-invalid", selected && !!error);
    b.classList.toggle("range-edge", selected && (date === range.start || date === range.end));
    b.setAttribute("aria-pressed", String(selected));
    const marker = $(".selection-label", b);
    marker.textContent = !selected ? "" : date === range.start && date === range.end
      ? (selection.anchor && !preview ? "시작" : "당일")
      : date === range.start ? "시작" : date === range.end ? "종료" : "";
    b.setAttribute("aria-label", b.dataset.label + (selected ? " · 선택됨 " + marker.textContent : ""));
  }
  const count = dayCount(range.start, range.end);
  const summary = Number.isFinite(count) && count > 0
    ? range.start + " ~ " + range.end + " · " + count + "일"
    : "이용할 날짜를 선택해 주세요.";
  $("#selection-summary").textContent = summary + (preview ? " (드래그 중)" : selection.anchor ? " · 종료일을 골라 주세요." : "");
  $("#clear-selection").disabled = !selection.start && !selection.end;
  $("#duration").textContent = selection.start && selection.end && dayCount(selection.start, selection.end) > 0
    ? selection.start + " ~ " + selection.end + " · " + dayCount(selection.start, selection.end) + "일 이용"
    : "날짜를 선택해 주세요.";
}
function syncSelection() {
  const f = $("#booking-form");
  f.elements.startDate.value = selection.start;
  f.elements.endDate.value = selection.end;
  const error = selectionError(selection);
  f.elements.endDate.setCustomValidity(error);
  message("#selection-message", selection.start && selection.end ? error : "");
  paintSelection();
}
function chooseDate(date) {
  const error = selectionError(selection.proposed(date));
  if (error) {
    message("#selection-message", error + " 다른 종료일을 골라 주세요.");
    return;
  }
  selectionRevision++;
  selection.click(date);
  preview = null;
  syncSelection();
}
function setupCalendarSelection() {
  const c = $("#calendar"), f = $("#booking-form");
  const dayAt = (event) => document.elementFromPoint(event.clientX, event.clientY)?.closest("#calendar [data-date]");
  c.addEventListener("click", (event) => {
    const b = event.target.closest("[data-date]");
    if (!ignoreClick && b && !b.disabled) chooseDate(b.dataset.date);
  });
  c.addEventListener("pointerdown", (event) => {
    // Touch keeps native scrolling; tapping twice works on phones and tablets.
    if (event.pointerType === "touch" || event.button !== 0 || !event.isPrimary) return;
    const b = event.target.closest("[data-date]");
    if (!b || b.disabled) return;
    gesture = { id: event.pointerId, start: b.dataset.date, dragged: false };
    c.setPointerCapture(event.pointerId);
    b.focus({ preventScroll: true });
  });
  c.addEventListener("pointermove", (event) => {
    if (!gesture || gesture.id !== event.pointerId) return;
    const b = dayAt(event);
    if (!b) return;
    if (b.dataset.date !== gesture.start) gesture.dragged = true;
    if (gesture.dragged) {
      preview = orderedRange(gesture.start, b.dataset.date);
      message("#selection-message", selectionError(preview));
      paintSelection();
    }
  });
  function cancelGesture() {
    gesture = null;
    preview = null;
    syncSelection();
  }
  c.addEventListener("pointerup", (event) => {
    if (!gesture || gesture.id !== event.pointerId) return;
    const current = gesture, b = dayAt(event);
    gesture = null;
    preview = null;
    ignoreClick = true;
    setTimeout(() => { ignoreClick = false; }, 0);
    if (c.hasPointerCapture(event.pointerId)) c.releasePointerCapture(event.pointerId);
    if (!b) return syncSelection();
    if (!current.dragged) return chooseDate(current.start);
    const range = orderedRange(current.start, b.dataset.date), error = selectionError(range);
    if (error) {
      syncSelection();
      message("#selection-message", error + " 날짜를 다시 골라 주세요.");
    } else {
      selectionRevision++;
      selection.set(range.start, range.end);
      syncSelection();
    }
  });
  c.addEventListener("pointercancel", cancelGesture);
  c.addEventListener("lostpointercapture", () => { if (gesture) cancelGesture(); });
  $("#clear-selection").onclick = () => {
    selectionRevision++;
    selection.clear();
    preview = null;
    syncSelection();
  };
  for (const name of ["startDate", "endDate"]) {
    f.elements[name].addEventListener("change", async () => {
      const revision = ++selectionRevision;
      selection.set(f.elements.startDate.value, f.elements.endDate.value);
      preview = null;
      syncSelection();
      if (rangeError(selection, policy)) return;
      f.elements.endDate.setCustomValidity("선택한 날짜를 확인 중입니다.");
      try {
        await loadSelectionMonths({ start: selection.start, end: selection.end });
        if (revision === selectionRevision) syncSelection();
      } catch (e) {
        if (revision !== selectionRevision) return;
        f.elements.endDate.setCustomValidity("날짜를 확인하지 못했습니다. 잠시 후 다시 선택해 주세요.");
        message("#selection-message", e.message);
      }
    });
  }
}
async function renderCalendar() {
  const request = ++calendarRequest,
    [y, m] = viewMonth;
  $("#calendar").setAttribute("aria-busy", "true");
  $("#calendar").replaceChildren(el("p", "일정을 불러오는 중입니다.", "calendar-loading"));
  let items;
  try {
    items = await api("/reservations/calendar?year=" + y + "&month=" + (m + 1));
  } catch (e) {
    if (request === calendarRequest) {
      $("#calendar").setAttribute("aria-busy", "false");
      $("#calendar").replaceChildren(el("p", "일정을 불러오지 못했습니다. 새로고침해 주세요.", "calendar-loading"));
    }
    throw e;
  }
  if (request !== calendarRequest) return;
  calendarMonths.set(iso(y, m, 1).slice(0, 7), items);
  $("#month-title").textContent = y + "년 " + (m + 1) + "월";
  const c = $("#calendar");
  c.setAttribute("aria-busy", "false");
  c.replaceChildren();
  const start = new Date(y, m, 1).getDay(),
    days = new Date(y, m + 1, 0).getDate();
  for (let i = 0; i < start; i++) c.append(el("span", null, "day empty"));
  for (let d = 1; d <= days; d++) {
    const date = iso(y, m, d),
      entry = items.find((x) => x.startDate <= date && x.endDate >= date),
      b = el("button", null, "day");
    b.type = "button";
    b.dataset.date = date;
    b.append(el("span", String(d), "date-number"));
    if (date === policy.today) b.classList.add("today");
    if (entry) {
      b.classList.add(
        entry.kind === "BLOCK" ? "block" : entry.mine ? "mine" : "family",
      );
      b.append(el("span", entry.userName, "day-label"));
    }
    if (date <= policy.today) b.classList.add("past");
    b.disabled =
      !!entry ||
      date <= policy.today ||
      date > plusDays(policy.today, policy.advanceDays) ||
      policy.maxGuests <= 0;
    b.setAttribute(
      "aria-label",
      date +
        " " +
        (entry
          ? entry.userName + " 사용 중"
          : b.disabled
            ? "예약 불가"
            : "예약 가능"),
    );
    b.dataset.label = b.getAttribute("aria-label");
    b.append(el("span", "", "selection-label"));
    c.append(b);
  }
  syncSelection();
}
async function bookings(admin = false) {
  const target = $(admin ? "#admin-bookings" : "#my-bookings"),
    rows = await api(admin ? "/admin/reservations" : "/reservations/me");
  target.replaceChildren();
  if (!rows.length) return empty(target, "예약 내역이 없습니다.");
  for (const r of rows) {
    const card = el("article", null, "booking-card");
    card.append(
      el("span", labels[r.status], "pill"),
      el("h3", r.startDate + " ~ " + r.endDate),
      el(
        "p",
        (admin ? r.userName + " · " : "") + r.guestCount + "명 · 예약 #" + r.id,
      ),
    );
    if (r.memo) card.append(el("p", r.memo));
    const actions = el("div", null, "actions");
    actions.append(
      button("변경 이력", async () => {
        const old = $(".history", card);
        if (old) {
          old.remove();
          return;
        }
        const hs = await api("/reservations/" + r.id + "/history"),
          list = el("div", null, "history");
        for (const h of hs)
          list.append(
            el(
              "p",
              h.action +
                " · " +
                h.reason +
                "\n" +
                (h.beforeValue || "") +
                " → " +
                h.afterValue,
            ),
          );
        card.append(list);
      }),
    );
    if (r.status === "CONFIRMED") {
      if (admin) {
        actions.append(
          button("일정 변경", () => {
            const f = $("#change-form");
            for (const k of ["id", "startDate", "endDate", "guestCount"])
              f.elements[k].value = r[k];
            f.elements.expectedVersion.value = r.version;
            f.elements.reason.value = "";
            message("#change-message", "");
            $("#change-dialog").showModal();
          }),
        );
        actions.append(
          button(
            "강제 취소",
            async () => {
              const why = await reason("예약자에게 안내할 취소 사유");
              if (!why) return;
              await api(
                "/admin/reservations/" + r.id + "/cancel",
                json("PATCH", { expectedVersion: r.version, reason: why }),
              );
              await refreshManage();
            },
            "danger",
          ),
        );
      } else if (r.startDate > policy.today)
        actions.append(
          button(
            "예약 취소",
            async () => {
              if (
                !(await confirmAction(
                  r.startDate + " ~ " + r.endDate + " 예약을 취소할까요?",
                ))
              )
                return;
              await api("/reservations/" + r.id, { method: "DELETE" });
              await refreshHome();
            },
            "danger",
          ),
        );
    }
    card.append(actions);
    target.append(card);
  }
}
async function notices() {
  const rows = await api("/users/me/notices"),
    t = $("#notices");
  t.replaceChildren();
  for (const n of rows.filter((r) => r.status !== "ACKNOWLEDGED")) {
    const card = el("article", null, "notice");
    card.append(
      el("p", "예약 #" + n.reservationId + " 변경 안내 · " + n.reason),
      button("변경 내용을 확인했습니다", async () => {
        await api("/users/me/notices/" + n.id + "/acknowledge", {
          method: "PATCH",
        });
        await notices();
      }),
    );
    t.append(card);
  }
}
async function refreshHome() {
  calendarMonths.clear();
  await Promise.all([renderCalendar(), bookings(), notices()]);
  await loadSelectionMonths(selection);
  syncSelection();
}
function roleButton(u) {
  return button(
    u.role === "ADMIN" ? "관리자 권한 해제" : "관리자로 지정",
    async () => {
      const why = await reason(
        u.name + "님 권한 변경 사유 (변경 후 다시 로그인해야 합니다)",
      );
      if (!why) return;
      await api(
        "/admin/users/" + u.id + "/role",
        json("PATCH", {
          role: u.role === "ADMIN" ? "USER" : "ADMIN",
          reason: why,
        }),
      );
      await manageLists();
    },
  );
}
async function manageLists() {
  const [pending, invites, blocks, communications, users] = await Promise.all([
    api("/admin/users/pending"),
    api("/admin/invite-codes"),
    api("/admin/calendar-blocks"),
    api("/admin/communications"),
    api("/admin/users"),
  ]);
  for (const [selector, rows] of [
    ["#pending-users", pending],
    ["#invites", invites],
    ["#blocks", blocks],
    ["#communications", communications],
    ["#users", users],
  ]) {
    const t = $(selector);
    t.replaceChildren();
    if (!rows.length) empty(t);
  }
  for (const u of pending) {
    const row = el("div", null, "compact-row");
    row.append(
      el(
        "p",
        u.name + " (" + u.loginId + ") · " + (u.signupNote || "가입 메모 없음"),
      ),
    );
    for (const [label, status] of [
      ["승인", "ACTIVE"],
      ["거절", "REJECTED"],
    ])
      row.append(
        button(label, async () => {
          const why = await reason(u.name + "님 " + label + " 사유");
          if (!why) return;
          await api(
            "/admin/users/" + u.id + "/status",
            json("PATCH", { status, reason: why }),
          );
          await refreshManage();
        }),
      );
    $("#pending-users").append(row);
  }
  for (const i of invites) {
    const row = el("div", null, "compact-row");
    row.append(
      el(
        "p",
        "초대 #" +
          i.id +
          " · " +
          labels[i.status] +
          " · 만료 " +
          i.expiresAt +
          " UTC",
      ),
    );
    if (i.status === "ACTIVE")
      row.append(
        button("폐기", async () => {
          await api("/admin/invite-codes/" + i.id + "/revoke", {
            method: "PATCH",
          });
          await manageLists();
        }),
      );
    $("#invites").append(row);
  }
  for (const b of blocks) {
    const row = el("div", null, "compact-row");
    row.append(
      el(
        "p",
        b.startDate +
          " ~ " +
          b.endDate +
          " · " +
          b.reason +
          " · " +
          labels[b.status],
      ),
    );
    if (b.status === "ACTIVE")
      row.append(
        button("기간 해제", async () => {
          const why = await reason("해제 사유");
          if (!why) return;
          await api(
            "/admin/calendar-blocks/" + b.id + "/release",
            json("PATCH", { expectedVersion: b.version, reason: why }),
          );
          await manageLists();
        }),
      );
    $("#blocks").append(row);
  }
  for (const n of communications) {
    const row = el("div", null, "compact-row");
    row.append(
      el(
        "p",
        n.userName +
          " · 예약 #" +
          n.reservationId +
          " · " +
          n.reason +
          " · " +
          (n.status === "PENDING" ? "연락 필요" : labels[n.status]),
      ),
    );
    if (n.status === "PENDING")
      row.append(
        button("직접 연락 완료", async () => {
          if (!(await confirmAction("예약자에게 실제 연락을 마쳤나요?")))
            return;
          await api("/admin/communications/" + n.id + "/contact", {
            method: "PATCH",
          });
          await manageLists();
        }),
      );
    $("#communications").append(row);
  }
  for (const u of users) {
    const row = el("div", null, "compact-row");
    row.append(
      el(
        "p",
        u.name + " · " + u.loginId + " · " + u.role + " · " + labels[u.status],
      ),
    );
    if (["ACTIVE", "SUSPENDED"].includes(u.status)) {
      const next = u.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
      row.append(
        button(next === "ACTIVE" ? "정지 해제" : "계정 정지", async () => {
          const why = await reason("계정 상태 변경 사유");
          if (!why) return;
          await api(
            "/admin/users/" + u.id + "/status",
            json("PATCH", { status: next, reason: why }),
          );
          await manageLists();
        }),
      );
      row.append(
        button("탈퇴 처리", async () => {
          const why = await reason(
            "본인 요청을 확인한 뒤 탈퇴 사유를 입력하세요. 미래 예약은 따로 정리해야 합니다.",
          );
          if (!why) return;
          await api(
            "/admin/users/" + u.id + "/status",
            json("PATCH", { status: "WITHDRAWN", reason: why }),
          );
          await manageLists();
        }),
      );
    }
    if (u.status === "ACTIVE") row.append(roleButton(u));
    if (u.status === "ACTIVE")
      row.append(
        button("비밀번호 복구 코드", async () => {
          const why = await reason("가족 연락 경로로 본인을 확인한 방법");
          if (!why) return;
          const r = await api(
            "/admin/users/" + u.id + "/password-reset",
            json("POST", { reason: why }),
          );
          $("#reset-code").textContent =
            u.name +
            "님 15분 유효·1회용 코드: " +
            r.token +
            " (안전한 연락 경로로 직접 전달하세요)";
        }),
      );
    $("#users").append(row);
  }
}
async function refreshManage() {
  await Promise.all([manageLists(), bookings(true), notices()]);
}
async function init() {
  if (["login", "signup", "reset"].includes(mode)) {
    bindForm(
      "#auth-form",
      async (data, form) => {
        await api(
          mode === "reset" ? "/auth/password/reset" : "/auth/" + mode,
          json("POST", data),
        );
        if (mode === "login") location.href = "/";
        else {
          form.reset();
          message(
            "#form-message",
            mode === "signup"
              ? "가입 신청을 완료했습니다. 관리자 승인 후 로그인해 주세요."
              : "비밀번호를 변경했습니다. 다시 로그인해 주세요.",
            true,
          );
        }
      },
      "#form-message",
    );
    return;
  }
  $("#logout").onclick = async () => {
    try {
      await api("/auth/logout", { method: "POST" });
      location.href = "/login";
    } catch (e) {
      message("#global-message", e.message);
    }
  };
  if (mode === "board") {
    const { initBoard } = await import("./board.js");
    await initBoard({ api, message, el, button, bindForm, confirmAction });
    return;
  }
  bindForm("#password-form", async (data) => {
    await api("/users/me/password", json("POST", data));
    location.href = "/login";
  });
  if (mode === "home") {
    policy = await api("/reservations/policy");
    const [y, m] = policy.today.split("-").map(Number);
    viewMonth = [y, m - 1];
    const f = $("#booking-form");
    for (const name of ["startDate", "endDate"]) {
      f.elements[name].min = plusDays(policy.today, 1);
      f.elements[name].max = plusDays(policy.today, policy.advanceDays);
    }
    f.elements.guestCount.max = policy.maxGuests || 1;
    $("#policy-summary").textContent =
      "오늘부터 " +
      policy.advanceDays +
      "일 이내, 한 번에 최대 " +
      policy.maxDays +
      "일까지 예약할 수 있습니다. 이용 중이거나 예정된 예약은 " +
      policy.maxActive +
      "건까지 가능합니다.";
    if (policy.maxGuests <= 0) {
      $('button[type="submit"]', f).disabled = true;
      message(
        "#booking-message",
        "예약 준비 중입니다. 관리자에게 문의해 주세요.",
      );
    }
    setupCalendarSelection();
    function move(offset) {
      const d = new Date(viewMonth[0], viewMonth[1] + offset, 1);
      viewMonth = [d.getFullYear(), d.getMonth()];
      renderCalendar().catch((e) => message("#global-message", e.message));
    }
    $("#prev-month").onclick = () => move(-1);
    $("#next-month").onclick = () => move(1);
    $("#today-month").onclick = () => {
      viewMonth = [y, m - 1];
      move(0);
    };
    $("#refresh").onclick = () =>
      refreshHome().catch((e) => message("#global-message", e.message));
    bindForm(
      "#booking-form",
      async (data) => {
        const range = { start: data.startDate, end: data.endDate };
        await loadSelectionMonths(range);
        const error = selectionError(range);
        if (error) throw Error(error);
        data.guestCount = Number(data.guestCount);
        if (
          !(await confirmAction(
            data.startDate +
              " ~ " +
              data.endDate +
              "\n시작일·종료일 모두 포함, " +
              data.guestCount +
              "명으로 예약할까요?",
          ))
        )
          return;
        try {
          const r = await api("/reservations", {
            ...json("POST", data),
            headers: { "Idempotency-Key": bookingKey },
          });
          bookingKey = crypto.randomUUID();
          selectionRevision++;
          selection.clear();
          preview = null;
          syncSelection();
          message(
            "#booking-message",
            "예약이 완료됐습니다. (예약번호 " + r.id + ")",
            true,
          );
          await refreshHome();
        } catch (e) {
          if (e.status && e.status < 500) bookingKey = crypto.randomUUID();
          throw e;
        }
      },
      "#booking-message",
    );
    await refreshHome();
  } else {
    $("#issue-invite").onclick = async () => {
      const b = $("#issue-invite");
      b.disabled = true;
      try {
        const r = await api("/admin/invite-codes", { method: "POST" });
        $("#issued-code").textContent =
          "초대코드: " + r.code + " (발급 때만 표시됩니다)";
        await manageLists();
      } catch (e) {
        message("#global-message", e.message);
      } finally {
        b.disabled = false;
      }
    };
    bindForm("#block-form", async (data, form) => {
      await api("/admin/calendar-blocks", json("POST", data));
      form.reset();
      await manageLists();
    });
    bindForm(
      "#change-form",
      async (data) => {
        const id = data.id;
        delete data.id;
        data.expectedVersion = Number(data.expectedVersion);
        data.guestCount = Number(data.guestCount);
        await api("/admin/reservations/" + id, json("PATCH", data));
        $("#change-dialog").close();
        await refreshManage();
      },
      "#change-message",
    );
    $("#close-change").onclick = () => $("#change-dialog").close();
    await refreshManage();
  }
}
init().catch((e) =>
  message(
    $("#global-message") ? "#global-message" : "#form-message",
    e.message,
  ),
);
