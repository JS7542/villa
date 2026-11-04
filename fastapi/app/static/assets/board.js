export async function initBoard({ api, message, el, button, bindForm, confirmAction }) {
  const $ = (selector) => document.querySelector(selector);
  const editor = $("#editor-dialog"), detail = $("#post-dialog"), form = $("#post-form");
  const cursors = [null];
  let page = 0, lastId = null, listRequest = 0;
  const dateFormat = new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul", year: "numeric", month: "long", day: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
  const date = (value) => dateFormat.format(new Date(value + "Z"));

  async function load() {
    const request = ++listRequest;
    $("#board-prev").disabled = true;
    $("#board-next").disabled = true;
    $("#post-list").setAttribute("aria-busy", "true");
    try {
      const result = await api("/board/posts" + (cursors[page] ? "?before=" + cursors[page] : ""));
      if (request !== listRequest) return;
      const list = $("#post-list");
      list.replaceChildren();
      if (!result.items.length) list.append(el("p", "아직 게시글이 없습니다.", "empty-state"));
      for (const post of result.items) {
        const row = el("article", null, "post-row");
        row.append(button(post.title, () => open(post.id), "post-link"),
          el("p", post.authorName + " · " + date(post.createdAt), "quiet"));
        list.append(row);
      }
      lastId = result.items.at(-1)?.id;
      $("#board-page-number").textContent = (page + 1) + "페이지";
      $("#board-next").disabled = !result.hasNext;
    } catch (e) {
      if (request === listRequest) {
        $("#post-list").replaceChildren(
          el("p", "글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.", "empty-state"),
          button("다시 불러오기", load));
      }
      throw e;
    } finally {
      if (request === listRequest) {
        $("#board-prev").disabled = page === 0;
        $("#post-list").setAttribute("aria-busy", "false");
      }
    }
  }

  function edit(post = null) {
    form.reset();
    form.elements.id.value = post?.id || "";
    form.elements.expectedVersion.value = post?.version ?? "";
    form.elements.title.value = post?.title || "";
    form.elements.body.value = post?.body || "";
    $("#editor-title").textContent = post ? "글 수정" : "글쓰기";
    form.querySelector('[type="submit"]').textContent = post ? "수정 저장" : "등록";
    message("#editor-message", "");
    detail.close();
    editor.showModal();
    form.elements.title.focus();
  }

  async function open(id) {
    const post = await api("/board/posts/" + id);
    $("#post-title").textContent = post.title;
    $("#post-meta").textContent = post.authorName + " · " + date(post.createdAt)
      + (post.version > 0 ? " · 수정 " + date(post.updatedAt) : "");
    // Posts are plain text. Never interpret member-supplied content as HTML.
    $("#post-body").textContent = post.body;
    message("#post-message", "");
    const actions = $("#post-actions");
    actions.replaceChildren(button("닫기", () => detail.close()));
    if (post.editable) actions.append(button("수정", () => edit(post)));
    if (post.deletable) actions.append(button("삭제", async () => {
      if (!(await confirmAction("이 글을 삭제할까요? 삭제한 글은 되돌릴 수 없습니다."))) return;
      try {
        await api("/board/posts/" + post.id + "?expectedVersion=" + post.version, { method: "DELETE" });
        detail.close();
        page = 0;
        cursors.splice(1);
        await load();
        message("#global-message", "글을 삭제했습니다.", true);
      } catch (e) {
        message("#post-message", e.message);
      }
    }, "danger"));
    detail.showModal();
  }

  $("#new-post").onclick = () => edit();
  $("#close-editor").onclick = () => editor.close();
  $("#board-prev").onclick = () => {
    if (page > 0) page--;
    load().catch((e) => message("#global-message", e.message));
  };
  $("#board-next").onclick = () => {
    if (!lastId) return;
    cursors[++page] = lastId;
    load().catch((e) => message("#global-message", e.message));
  };
  bindForm("#post-form", async (data) => {
    const body = { title: data.title, body: data.body };
    if (data.id) body.expectedVersion = Number(data.expectedVersion);
    const post = await api("/board/posts" + (data.id ? "/" + data.id : ""), {
      method: data.id ? "PATCH" : "POST", body: JSON.stringify(body),
    });
    editor.close();
    page = 0;
    cursors.splice(1);
    message("#global-message", data.id ? "글을 수정했습니다." : "글을 등록했습니다.", true);
    // Saving succeeded even if refreshing the list later fails.
    try { await load(); await open(post.id); }
    catch (e) { message("#global-message", "글은 저장됐습니다. 목록을 다시 불러와 주세요. " + e.message); }
  }, "#editor-message");
  await load();
}
