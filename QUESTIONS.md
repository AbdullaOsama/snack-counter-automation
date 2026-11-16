# Open questions and assumptions

These are the questions whose answers would change the design. For each one, the last column shows
the assumption the current implementation is built on, so it's clear what would change if the answer differs.

| # | Question | Why it matters | Assumption used |
|---|---|---|---|
| 1 | Can the HR system accept an idempotency key / external reference, and can we read claim status (API or webhook)? | Prevents duplicate claims on retry; lets us tell volunteers when they're paid | Yes to both; we poll status |
| 2 | Is there a source of who is in which office today (desk booking, calendar, badge system)? | Drives "who is asked to buy" for hybrid workers and visitors | A presence calendar exists; fallback is an open call in chat |
| 3 | Who owns the budget per office, and may they approve over-limit purchases? | Escalation target for overspend, exhausted budget, rejected claims | One admin channel; an office manager approves |
| 4 | Is the monthly budget a hard cap, or a guideline that can be exceeded with approval? | Decides block vs. escalate | Soft cap: hold new refills, approve overspends |
| 5 | When a visitor buys in the other office, which budget pays? | Visitors are explicitly mentioned | The office where the snacks are bought |
| 6 | Which currency should management's combined report use, and which FX rate (monthly average, month-end)? | Report correctness | NOK, one rate per month from finance |
| 7 | Does a budget month follow the receipt date or the claim/approval date? | Month-end edge cases | Receipt date, in office local time |
| 8 | Which chat tool (Slack, Teams, Google Chat)? Can a bot DM people and post buttons? | Integration effort | Yes, a bot with buttons and DMs |
| 9 | Is a company card or prepaid card an option at all? | Would remove reimbursement waits entirely | Not now; listed as future option |
| 10 | Does HR need anything else on claims (cost centre, project code, VAT)? | Claim rejections | Only the five fields in the brief |
| 11 | Is volunteering opt-in, and can people pause (e.g. parental leave)? | Who is in the pool | Opt-in list; holiday via presence |
| 12 | How should "which snacks actually get eaten" be measured: is "what ran out first" good enough? | Avoids building inventory tracking | Yes: tags on empty reports + receipt items |
