export function plusDays(date, count) {
  const value = new Date(date + "T12:00:00Z");
  value.setUTCDate(value.getUTCDate() + count);
  return value.toISOString().slice(0, 10);
}

export function dayCount(start, end) {
  return (Date.parse(end) - Date.parse(start)) / 86400000 + 1;
}

export function orderedRange(first, last) {
  return first <= last ? { start: first, end: last } : { start: last, end: first };
}

export function rangeError({ start, end }, policy, items = []) {
  if (!start || !end) return "시작일과 종료일을 선택해 주세요.";
  if (!Number.isFinite(dayCount(start, end))) return "날짜를 다시 확인해 주세요.";
  if (end < start) return "종료일은 시작일보다 빠를 수 없습니다.";
  if (policy.maxGuests <= 0) return "예약 준비 중입니다. 관리자에게 문의해 주세요.";
  if (start <= policy.today) return "내일부터 예약할 수 있습니다.";
  if (end > plusDays(policy.today, policy.advanceDays))
    return "오늘부터 " + policy.advanceDays + "일 이내의 날짜를 선택해 주세요.";
  if (dayCount(start, end) > policy.maxDays)
    return "한 번에 최대 " + policy.maxDays + "일까지 예약할 수 있습니다.";
  if (items.some((item) => item.startDate <= end && item.endDate >= start))
    return "선택한 기간에 다른 예약이나 점검·행사가 있습니다.";
  return "";
}

// An anchor means the next click chooses the end date. A completed range starts
// over on the next click; dragging and manual inputs always complete the range.
export class DateRangeSelection {
  constructor() {
    this.clear();
  }

  proposed(date) {
    return orderedRange(this.anchor || date, date);
  }

  click(date) {
    const range = this.proposed(date);
    const nextAnchor = this.anchor ? null : date;
    this.set(range.start, range.end);
    this.anchor = nextAnchor;
  }

  set(start, end) {
    this.start = start;
    this.end = end;
    this.anchor = null;
  }

  clear() {
    this.set("", "");
  }
}
