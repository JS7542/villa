import { test } from "node:test";
import assert from "node:assert/strict";
import { DateRangeSelection, dayCount, orderedRange, plusDays, rangeError } from "../../main/resources/static/assets/date-range.js";

const policy = { today: "2026-08-31", advanceDays: 90, maxDays: 3, maxGuests: 8 };

test("two clicks select an inclusive range, then a new click starts over", () => {
  const selection = new DateRangeSelection();
  selection.click("2026-09-04");
  assert.equal(selection.anchor, "2026-09-04");
  selection.click("2026-09-06");
  assert.equal(selection.start, "2026-09-04");
  assert.equal(selection.end, "2026-09-06");
  assert.equal(selection.anchor, null);
  assert.equal(dayCount(selection.start, selection.end), 3);
  selection.click("2026-09-10");
  assert.equal(selection.start, "2026-09-10");
  assert.equal(selection.end, "2026-09-10");
});

test("reverse selection, same-day stay and a range across months", () => {
  const selection = new DateRangeSelection();
  selection.click("2026-10-01");
  selection.click("2026-09-30");
  assert.equal(selection.start, "2026-09-30");
  assert.equal(dayCount(selection.start, selection.end), 2);
  assert.equal(rangeError(selection, policy), "");
  selection.click("2026-09-03");
  selection.click("2026-09-03");
  assert.equal(dayCount(selection.start, selection.end), 1);
  assert.equal(selection.anchor, null);
});

test("blocked dates inside a range cannot be skipped, including boundary dates", () => {
  for (const kind of ["RESERVATION", "BLOCK"]) {
    const entries = [{ kind, startDate: "2026-09-05", endDate: "2026-09-05" }];
    assert.notEqual(rangeError(orderedRange("2026-09-04", "2026-09-06"), policy, entries), "");
    assert.notEqual(rangeError(orderedRange("2026-09-05", "2026-09-06"), policy, entries), "");
    assert.equal(rangeError(orderedRange("2026-09-06", "2026-09-08"), policy, entries), "");
  }
});

test("invalid candidates do not change the first click; clear and drag reset the anchor", () => {
  const selection = new DateRangeSelection();
  selection.click("2026-09-04");
  assert.notEqual(rangeError(selection.proposed("2026-09-10"), policy), "");
  assert.equal(selection.anchor, "2026-09-04");
  selection.set("2026-09-12", "2026-09-14");
  assert.equal(selection.anchor, null);
  selection.clear();
  assert.equal(selection.start, "");
  assert.equal(selection.end, "");
});

test("booking limits, past dates, manual reversed dates and missing dates are rejected", () => {
  for (const range of [
    { start: "", end: "" },
    { start: "2026-08-31", end: "2026-09-01" },
    { start: "2026-09-04", end: "2026-09-03" },
    { start: "2026-09-04", end: "2026-09-07" },
    { start: plusDays(policy.today, 90), end: plusDays(policy.today, 91) },
  ]) assert.notEqual(rangeError(range, policy), "");
  assert.equal(rangeError(orderedRange(plusDays(policy.today, 90), plusDays(policy.today, 90)), policy), "");
  assert.notEqual(rangeError(orderedRange("2026-09-04", "2026-09-05"), { ...policy, maxGuests: 0 }), "");
});

test("inclusive date arithmetic survives leap years and timezone changes", () => {
  assert.equal(plusDays("2028-02-28", 1), "2028-02-29");
  assert.equal(dayCount("2028-02-28", "2028-03-01"), 3);
  assert.equal(plusDays("2026-12-31", 1), "2027-01-01");
  assert.equal(dayCount("2026-03-07", "2026-03-09"), 3);
});
