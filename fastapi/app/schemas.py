from datetime import date
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

ReasonText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Login(Input):
    loginId: str = Field(min_length=1, max_length=50)
    password: str = Field(min_length=1, max_length=255)


class Signup(Login):
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=30)]
    inviteCode: str = Field(min_length=1, max_length=100)
    signupNote: str | None = Field(default=None, max_length=500)


class Reason(Input):
    reason: ReasonText


class UserStatus(Reason):
    status: Literal["PENDING", "ACTIVE", "REJECTED", "SUSPENDED", "WITHDRAWN"]


class UserRole(Reason):
    role: Literal["ADMIN", "USER"]


class Reset(Input):
    token: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=72)


class PasswordChange(Input):
    currentPassword: str = Field(min_length=1, max_length=255)
    password: str = Field(min_length=1, max_length=72)


class BookingCreate(Input):
    startDate: date
    endDate: date
    guestCount: int = Field(ge=1, strict=True)
    memo: str | None = Field(default=None, max_length=500)


class Cancel(Reason):
    expectedVersion: int = Field(ge=0, strict=True)


class BookingChange(Cancel):
    startDate: date
    endDate: date
    guestCount: int = Field(ge=1, strict=True)


class BlockCreate(Reason):
    startDate: date
    endDate: date


class PostCreate(Input):
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
    body: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=5000)]


class PostUpdate(PostCreate):
    expectedVersion: int = Field(ge=0, strict=True)
