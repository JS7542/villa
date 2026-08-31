"use strict";
const $ = (s, root = document) => root.querySelector(s),
  mode = document.body.dataset.mode;
let policy,
  viewMonth,
  calendarRequest = 0,
  bookingKey = crypto.randomUUID();
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
    throw Error("서버 응답을 확인할 수 없습니다. 내 예약을 확인해 주세요.");
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
const reason = (label) => prompt(label)?.trim() || null;
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
function plusDays(date, n) {
  const d = new Date(date + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}
function duration() {
  const f = $("#booking-form"),
    a = f.elements.startDate.value,
    b = f.elements.endDate.value,
    count = (Date.parse(b) - Date.parse(a)) / 86400000 + 1;
  $("#duration").textContent =
    Number.isFinite(count) && count > 0
      ? a + " ~ " + b + " · 총 " + count + "일 사용"
      : "날짜를 선택해 주세요.";
}
async function renderCalendar() {
  const request = ++calendarRequest,
    [y, m] = viewMonth,
    items = await api("/reservations/calendar?year=" + y + "&month=" + (m + 1));
  if (request !== calendarRequest) return;
  $("#month-title").textContent = y + "년 " + (m + 1) + "월";
  const c = $("#calendar");
  c.replaceChildren();
  const start = new Date(y, m, 1).getDay(),
    days = new Date(y, m + 1, 0).getDate();
  for (let i = 0; i < start; i++) c.append(el("span", null, "day empty"));
  for (let d = 1; d <= days; d++) {
    const date = iso(y, m, d),
      entry = items.find((x) => x.startDate <= date && x.endDate >= date),
      b = el("button", null, "day");
    b.type = "button";
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
    b.onclick = () => {
      const f = $("#booking-form");
      f.elements.startDate.value = date;
      f.elements.endDate.value = date;
      duration();
      f.elements.endDate.focus();
    };
    c.append(b);
  }
}
async function bookings(admin = false) {
  const target = $(admin ? "#admin-bookings" : "#my-bookings"),
    rows = await api(admin ? "/admin/reservations" : "/reservations/me");
  target.replaceChildren();
  if (!rows.length) return empty(target, "새로운 쉼을 계획해 보세요.");
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
              const why = reason("예약자에게 안내할 취소 사유");
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
                !confirm(
                  r.startDate + " ~ " + r.endDate + " 예약을 취소할까요?",
                )
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
  await Promise.all([renderCalendar(), bookings(), notices()]);
}
function roleButton(u) {
  return button(
    u.role === "ADMIN" ? "관리자 권한 해제" : "관리자로 지정",
    async () => {
      const why = reason(
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
          const why = reason(u.name + "님 " + label + " 사유");
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
          const why = reason("해제 사유");
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
          if (!confirm("예약자에게 실제 연락을 마쳤나요?")) return;
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
          const why = reason("계정 상태 변경 사유");
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
          const why = reason(
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
          const why = reason("가족 연락 경로로 본인을 확인한 방법");
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
      f.elements[name].addEventListener("change", duration);
    }
    f.elements.guestCount.max = policy.maxGuests || 1;
    $("#policy-summary").textContent =
      "앞으로 " +
      policy.advanceDays +
      "일 · 최대 " +
      policy.maxDays +
      "일 연속 · 진행 중·미래 예약 " +
      policy.maxActive +
      "건까지.";
    if (policy.maxGuests <= 0) {
      $('button[type="submit"]', f).disabled = true;
      message(
        "#booking-message",
        "실제 정원이 아직 설정되지 않았습니다. 관리자에게 문의해 주세요.",
      );
    }
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
        data.guestCount = Number(data.guestCount);
        if (
          !confirm(
            data.startDate +
              " ~ " +
              data.endDate +
              "\n시작일·종료일 모두 포함, " +
              data.guestCount +
              "명으로 예약할까요?",
          )
        )
          return;
        try {
          const r = await api("/reservations", {
            ...json("POST", data),
            headers: { "Idempotency-Key": bookingKey },
          });
          bookingKey = crypto.randomUUID();
          message(
            "#booking-message",
            "예약 #" + r.id + "을 확정했습니다.",
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
