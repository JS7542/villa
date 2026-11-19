from fastapi import APIRouter, Query
from sqlalchemy import delete, insert, select, update

from . import schemas as s
from .db import now, posts, users
from .security import DB, Actor, audit, fail, forbidden, missing

router = APIRouter(prefix="/board/posts")
POSTS = select(posts, users.c.name.label("author_name")).join(users, users.c.id == posts.c.user_id)


def detail(connection, user, post_id):
    row = connection.execute(POSTS.where(posts.c.id == post_id)).mappings().first()
    if not row:
        missing()
    own = row["user_id"] == user["id"]
    return {
        "id": row["id"],
        "title": row["title"],
        "body": row["body"],
        "authorName": row["author_name"],
        "createdAt": row["created_at"],
        "updatedAt": row["updated_at"],
        "version": row["version"],
        "editable": own,
        "deletable": own or user["role"] == "ADMIN",
    }


def version(row, expected):
    if row["version"] != expected:
        fail("STALE_VERSION", "내용이 변경되었습니다. 새로고침해 주세요.")


@router.get("")
def list_posts(connection: DB, user: Actor, before: int | None = Query(None, ge=1)):
    query = POSTS.order_by(posts.c.id.desc()).limit(21)
    if before is not None:
        query = query.where(posts.c.id < before)
    rows = list(connection.execute(query).mappings())
    return {
        "items": [
            {
                "id": row["id"],
                "title": row["title"],
                "authorName": row["author_name"],
                "createdAt": row["created_at"],
            }
            for row in rows[:20]
        ],
        "hasNext": len(rows) > 20,
    }


@router.get("/{post_id}")
def get_post(post_id: int, connection: DB, user: Actor):
    return detail(connection, user, post_id)


@router.post("", status_code=201)
def create(data: s.PostCreate, connection: DB, user: Actor):
    post_id = connection.execute(
        insert(posts).values(user_id=user["id"], title=data.title, body=data.body)
    ).inserted_primary_key[0]
    return detail(connection, user, post_id)


@router.patch("/{post_id}")
def edit(post_id: int, data: s.PostUpdate, connection: DB, user: Actor):
    row = detail(connection, user, post_id)
    if not row["editable"]:
        forbidden()
    version(row, data.expectedVersion)
    connection.execute(
        update(posts)
        .where(posts.c.id == post_id)
        .values(
            title=data.title,
            body=data.body,
            version=posts.c.version + 1,
            updated_at=now(),
        )
    )
    return detail(connection, user, post_id)


@router.delete("/{post_id}", status_code=204)
def remove(post_id: int, connection: DB, user: Actor, expectedVersion: int = Query(ge=0)):
    row = detail(connection, user, post_id)
    if not row["deletable"]:
        forbidden()
    version(row, expectedVersion)
    connection.execute(delete(posts).where(posts.c.id == post_id))
    if not row["editable"]:
        audit(connection, user["id"], "BOARD_DELETE", post_id, "관리자가 게시글 삭제")
