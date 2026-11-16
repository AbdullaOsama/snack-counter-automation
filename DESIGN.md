# Snack Counter Automation: Design

## 1. What "automate" means here

The problems are about coordination (who buys, when), not about buying. So I automate the
**coordination and the paperwork**, and leave the shopping to people:

| Problem | Root cause | What the system does |
|---|---|---|
| Box sits empty for days | Nobody owns it | One tap reports "empty"; the system **names one person** and follows up until someone has bought |
| Two people buy the same day | No shared state | **One active refill per office**; only one person can hold it; the channel is told "Bjørn is buying" |
| Same few people refill and wait weeks for money | No rotation; manual claims | **Fair rotation**; the HR claim is **filed automatically** from the receipt; slow claims are chased |
| Nobody knows spend or what's eaten | No data | Every refill records amount, items and timings → **monthly report** across both offices |

Everything happens in the company chat tool, which everyone already uses. No new app, no new login.

## 2. How a refill moves from "empty" to "reimbursed"

```
 "Box empty" ─▶ OPEN ─offer─▶ OFFERED ─accept─▶ COMMITTED ─receipt─▶ PURCHASED ─▶ CLAIM_SUBMITTED ─▶ REIMBURSED
                 ▲  │            │ 2h no answer / decline │ 24h no receipt / back out          │
                 │  │            └──────── next person ◀──┘                                    └─▶ CLAIM_REJECTED
                 │  └─▶ ON_HOLD_BUDGET (not enough budget left; reopens on the 1st)
                 └── nobody eligible: OPEN CALL in office channel, first to accept wins; admins pinged after 4h
                                       receipt above limit ─▶ NEEDS_APPROVAL ─admin─▶ PURCHASED
```

1. **Report.** Anyone presses *Box is empty* (optionally: what ran out). If a refill is already in
   progress for that office, the reporter is told who is on it. No second refill is created.
2. **Budget check.** Spending limit = min(typical max, budget left this month). If that is below a
   typical minimum purchase, the refill goes **on hold** and admins are told, instead of someone buying
   snacks that cannot be reimbursed.
3. **Choose who buys.** Among volunteers **physically in that office today** (from the presence calendar,
   so remote, holiday and *visitors from the other office* are handled the same way), pick by:
   fewest refills in last 90 days → not already waiting for a reimbursement → longest since last refill.
4. **Offer.** A DM with the limit and *Accept / Decline*. No answer in 2h, or a decline, moves it to the next person.
   If nobody is eligible, an **open call** goes to the office channel; first to accept wins.
5. **Commit.** On accept, the channel is told "X is buying, nobody else needs to". If no receipt arrives
   within 24h, it is reassigned and X is told explicitly **not** to buy.
6. **Receipt.** Photo + total + items. Must come from the committed buyer, in the office's currency.
   Over the limit → admin approval (money is already spent, so we escalate rather than refuse).
7. **Claim.** Filed to HR automatically (amount, currency, description, image, employee ID) with the refill
   ID as idempotency key, so retries after an outage never duplicate it.
8. **Reimbursed.** A scheduler polls HR. Approved → volunteer is thanked. Rejected → volunteer + admin told.
   Pending > 10 days → admin asked to chase.

## 3. Main parts and the data each keeps

| Part | Responsibility | Data |
|---|---|---|
| **Chat bot** (adapter) | Buttons/DMs → commands; messages out | none (stateless) |
| **Refill service** (core) | State machine, rules, timeouts (`tick`) | **Refill**: id, office, state, reporter, assignee, offer/commit times, excluded people, spending limit, receipt (amount, date, items, image ref), purchaser, HR claim id, audit history |
| **Volunteer picker** | Fairness rule (pure function) | none; derived from refills |
| **Budget** | Spent / remaining per office per month | none; **derived from refills**, so it can't drift |
| **Presence** | Who is in which office today | calendar / desk booking / "I'm in" check-in |
| **HR adapter** | Submit claim, read status | claim id stored on the refill |
| **Report** | Monthly totals, FX conversion | FX rate per month (from finance) |
| **Config** | Offices, budgets, limits, timeouts, volunteers | Office: currency, timezone, budget, typical min/max, channels. Volunteer: employee id, home office, payroll currency |

## 4. Rules that make the problems go away (all covered by tests)

- **No double buying:** at most one "box still empty" refill per office; accept is an atomic check-and-set
  (`synchronized` here; a conditional `UPDATE … WHERE state IN (…)` with a DB). Tested with 5 threads racing.
- **Never stuck:** every waiting state has a timeout or escalation; `tick()` is idempotent.
- **Fairness:** rotation by recent refills; someone still owed money is asked last.
- **Money:** `BigDecimal` with currency; NOK and INR can never be added together. Budgets are checked in local
  currency; FX is used **only** for the management total, at a stated rate.
- **Visitors:** spend is booked to the office where snacks were bought (receipt currency); the claim is in that
  currency and HR pays the person in their payroll currency. Our budgets never depend on payroll currency.

## 5. Trade-offs

- **Presence-based assignment instead of a fixed rota.** Hybrid work makes rotas wrong half the time. Cost: we
  need a presence source; fallback is the open call, so it still works without one.
- **Escalate, don't block, on overspend.** The volunteer already paid; refusing would just shift the problem to HR.
- **Polling HR instead of webhooks.** We don't control the HR vendor. Polling every few minutes is plenty for a 1–10 day process.
- **Derived budget, no ledger.** Simpler and always consistent; fine at ~50 refills/month. Would add a ledger if
  refunds/adjustments become common.
- **Single process + lock.** Correct and simple at this scale (two offices). The rule stays the same when moved to a DB.
- **Timeouts (2h / 24h / 4h / 10d) are guesses** to tune with volunteers; they live in one `Policy` object.

## 6. Deliberately not built

- **Inventory / stock tracking or sensors.** Expensive and fiddly; "what ran out" tags on empty reports + receipt
  items answer "what gets eaten" well enough.
- **Company card / paying volunteers directly.** Would remove reimbursement entirely, but it's a finance-policy
  decision, not software. I'd recommend it as the next step if the pilot shows reimbursement is still the pain.
- **Receipt OCR.** The volunteer types the total (one field); OCR adds cost and errors for little gain.
- **Our own FX/currency conversion for claims.** HR already pays in payroll currency; doing it twice risks mismatch.
- **A web UI / admin dashboard.** Chat + one monthly report covers the need; admins act from chat messages.
- **Gamification, leaderboards, shopping lists, supplier ordering.** Nice-to-haves, not the stated problems.
