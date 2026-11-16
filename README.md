# Snack Counter Automation

Take-home assignment: automating snack refills for the Oslo and Bengaluru offices.

| Deliverable | Where |
|---|---|
| 1. Design document (≤ 2 pages) | [DESIGN.md](DESIGN.md) |
| 2. Core logic + simulated chat & HR | [`src/main/java/snackcounter`](src/main/java/snackcounter) |
| 3. Automated tests | [`src/test/java/snackcounter`](src/test/java/snackcounter) |
| Open questions and assumptions | [QUESTIONS.md](QUESTIONS.md) |

## Run it

Requirements: **JDK 21+** and **Maven 3.9+**. No other dependencies (JUnit is fetched by Maven).

```bash
mvn test                      # run all tests
mvn -q compile exec:java      # narrated end-to-end demo (chat messages printed to the console)
```

Verified with JDK 27 and Maven 3.9.16: 56 test runs, all green.

## Code map

```
src/main/java/snackcounter/
  domain/   Money (BigDecimal + currency), OfficeConfig, Volunteer, Presence, Receipt
  core/     RefillState (state machine), Refill, RefillService (all commands + tick),
            VolunteerPicker (fairness), Budget, MonthlyReportService, Policy (timeouts)
  ports/    ChatGateway, HrSystem, PresenceCalendar, FxRates   <- interfaces to the outside world
  fakes/    FakeChat, FakeHrSystem, InMemoryPresenceCalendar, FixedFxRates, MutableClock
  Demo.java
```

Dependencies point inward: `core` knows only `ports` and `domain`; the fakes (or real Slack/Teams/HR
adapters later) plug in from outside. Time comes from an injected `Clock`, so tests can jump "2 hours"
or "10 days" instantly.

## Tests: which rule each proves

| Rule | Test |
|---|---|
| Two people cannot both commit to the same refill | `RefillLifecycleTest.onlyTheOfferedPersonCanCommit`, `ConcurrencyTest` (5 threads, ×20) |
| A refill nobody completes is handed to someone else | `unansweredOfferMovesOn`, `committedButNotCompletedIsReassigned`, `declineMovesOn`, `backOutMovesOn` |
| A second "empty" report doesn't start a second purchase | `duplicateReportReusesActiveRefill` |
| Only people in the office today are asked; visitors count | `onlyPeopleInTheOfficeAreAsked`, `visitorRefillsOtherOffice` |
| Nobody available → open call → admin escalation | `everyoneDeclinesThenOpenCall`, `openCallLifecycle` |
| Fair rotation; people owed money asked last | `VolunteerPickerTest` |
| Budget limit, hold when exhausted, monthly reset | `BudgetTest` |
| Budgets and totals correct across NOK and INR | `BudgetTest.budgetsAreSeparatePerOfficeAndCurrency`, `MonthlyReportTest.totalsAcrossCurrencies`, `MoneyTest` |
| HR outage retried without duplicate claims | `hrOutageIsRetried` |
| Slow / rejected claims are escalated | `slowClaimEscalation`, `hrRejection` |

## Path to production

The core has no framework dependencies, so going live means adding adapters, not rewriting logic:

- **Chat:** a Slack/Teams bot whose buttons call `RefillService` commands (`reportEmpty`, `accept`, `decline`, `submitReceipt`).
- **Scheduler:** call `tick()` every few minutes (timeouts, HR retries, claim polling). It is idempotent.
- **Storage:** one `refill` table (≈50 rows/month). `accept` becomes a conditional
  `UPDATE … WHERE state IN ('OPEN','OFFERED')`, so "exactly one buyer" holds across multiple instances.
- **HR / presence / FX:** real implementations of the existing `ports` interfaces.
